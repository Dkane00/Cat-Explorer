/*
 * Cat Explorer — bridge between Kotlin (net.kanenet.catexplorer.audio.UsbSdr) and the SDR drivers built into the app.
 *
 * The tablet opens the SDR with Android's USB host API and hands its file descriptor here, with the kind of SDR:
 *   0  RTL-SDR (RTL-SDR Blog's librtlsdr)  — samples read synchronously, unsigned 8-bit I/Q
 *   1  Airspy R2 / Mini (libairspy)        — streamed by the driver as float I/Q
 *   2  Airspy HF+ / Discovery (libairspyhf) — streamed as float I/Q
 *   3  HackRF One (libhackrf)              — streamed as signed 8-bit I/Q
 * Streamed samples go into a ring buffer; readSpectrum() takes the newest block from it. Either way the samples become an
 * averaged power spectrum (Hann window, FFT, |X|^2, tuned frequency in the middle), the same processing the PC Panadapter
 * does with numpy.
 *
 * Only the RTL-SDR has been tried on real hardware; the other three follow their drivers' documented use.
 */
#include <jni.h>
#include <math.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <android/log.h>
#include "libusb.h"
#include "rtl-sdr.h"
#include "airspy.h"
#include "airspyhf.h"
#include "hackrf.h"

#define TAG "CatSdr"
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

enum { KIND_RTL = 0, KIND_AIRSPY = 1, KIND_AIRSPYHF = 2, KIND_HACKRF = 3 };

typedef struct {
    int kind;
    rtlsdr_dev_t *rtl;
    struct airspy_device *airspy;
    airspyhf_device_t *airspyhf;
    hackrf_device *hackrf;
    uint32_t rate;
    int n;              /* FFT size, a power of two */
    float *win, *cosT, *sinT, *re, *im, *acc;
    int *rev;
    unsigned char *buf; /* RTL-SDR read buffer */
    int bufLen;
    /* streamed SDRs: a ring of complex samples, newest written at ringPos */
    float *ring;        /* I,Q pairs */
    int ringLen;        /* complex samples */
    long long written;  /* complex samples ever written */
    long long over;     /* samples at (or near) full scale */
    int peak;           /* 0..128 */
    pthread_mutex_t lock;
    pthread_cond_t more;
    int streaming;
} sdr_t;

/* ---- the drivers print to stderr; send that to logcat so problems are visible with `adb logcat -s CatSdr` ---- */
static int log_pipe[2];
static void *log_thread(void *arg) {
    (void)arg;
    char line[512];
    FILE *f = fdopen(log_pipe[0], "r");
    while (f && fgets(line, sizeof line, f)) __android_log_write(ANDROID_LOG_INFO, TAG, line);
    return NULL;
}
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm; (void)reserved;
    if (pipe(log_pipe) == 0) {
        setvbuf(stderr, NULL, _IOLBF, 0);
        dup2(log_pipe[1], 2);
        pthread_t t;
        pthread_create(&t, NULL, log_thread, NULL);
        pthread_detach(t);
    }
    return JNI_VERSION_1_6;
}

static int last_error;

JNIEXPORT jint JNICALL
Java_net_kanenet_catexplorer_audio_UsbSdr_nativeLastError(JNIEnv *env, jclass cls) { (void)env; (void)cls; return last_error; }

static void free_sdr(sdr_t *s) {
    if (!s) return;
    free(s->win); free(s->cosT); free(s->sinT); free(s->re); free(s->im); free(s->acc); free(s->rev); free(s->buf);
    free(s->ring);
    pthread_mutex_destroy(&s->lock);
    pthread_cond_destroy(&s->more);
    free(s);
}

/* FFT tables for size n (a power of two). */
static int setup_fft(sdr_t *s, int n) {
    s->n = n;
    s->win = malloc(sizeof(float) * n); s->re = malloc(sizeof(float) * n); s->im = malloc(sizeof(float) * n);
    s->acc = malloc(sizeof(float) * n); s->rev = malloc(sizeof(int) * n);
    s->cosT = malloc(sizeof(float) * n / 2); s->sinT = malloc(sizeof(float) * n / 2);
    if (!s->win || !s->re || !s->im || !s->acc || !s->rev || !s->cosT || !s->sinT) return -12;
    int bits = 0; while ((1 << bits) < n) bits++;
    for (int i = 0; i < n; i++) {
        s->win[i] = (float)(0.5 - 0.5 * cos(2 * M_PI * i / (n - 1)));
        int x = i, y = 0;
        for (int b = 0; b < bits; b++) { y = (y << 1) | (x & 1); x >>= 1; }
        s->rev[i] = y;
    }
    for (int i = 0; i < n / 2; i++) { s->cosT[i] = (float)cos(2 * M_PI * i / n); s->sinT[i] = (float)sin(2 * M_PI * i / n); }
    return 0;
}

/* The power of two nearest to rate / 125 Hz, so every SDR gives about the same detail. */
static int fft_for(uint32_t rate) {
    int n = 1024;
    while (n < 65536 && fabs(log2((double)rate / 125.0 / (n * 2))) < fabs(log2((double)rate / 125.0 / n))) n *= 2;
    return n;
}

/* ---- ring buffer for the streamed SDRs (called on the driver's USB thread) ---- */
static void ring_put_float(sdr_t *s, const float *iq, int count) {
    pthread_mutex_lock(&s->lock);
    int pos = (int)(s->written % s->ringLen);
    for (int i = 0; i < count; i++) {
        float I = iq[2 * i], Q = iq[2 * i + 1];
        if (fabsf(I) > 0.99f || fabsf(Q) > 0.99f) s->over++;
        int pk = (int)(fmaxf(fabsf(I), fabsf(Q)) * 128);
        if (pk > s->peak) s->peak = pk > 128 ? 128 : pk;
        s->ring[2 * pos] = I; s->ring[2 * pos + 1] = Q;
        if (++pos == s->ringLen) pos = 0;
    }
    s->written += count;
    pthread_cond_broadcast(&s->more);
    pthread_mutex_unlock(&s->lock);
}

static int airspy_cb(airspy_transfer *t) {
    ring_put_float((sdr_t *)t->ctx, (const float *)t->samples, t->sample_count);
    return 0;
}

static int airspyhf_cb(airspyhf_transfer_t *t) {
    ring_put_float((sdr_t *)t->ctx, (const float *)t->samples, t->sample_count);
    return 0;
}

static int hackrf_cb(hackrf_transfer *t) {
    sdr_t *s = (sdr_t *)t->rx_ctx;
    int count = t->valid_length / 2;
    const int8_t *b = (const int8_t *)t->buffer;
    pthread_mutex_lock(&s->lock);
    int pos = (int)(s->written % s->ringLen);
    for (int i = 0; i < count; i++) {
        int I = b[2 * i], Q = b[2 * i + 1];
        if (I >= 127 || I <= -127 || Q >= 127 || Q <= -127) s->over++;
        int pk = abs(I) > abs(Q) ? abs(I) : abs(Q);
        if (pk > s->peak) s->peak = pk;
        s->ring[2 * pos] = I / 128.0f; s->ring[2 * pos + 1] = Q / 128.0f;
        if (++pos == s->ringLen) pos = 0;
    }
    s->written += count;
    pthread_cond_broadcast(&s->more);
    pthread_mutex_unlock(&s->lock);
    return 0;
}

/* ---- gains ---- */

/* HackRF has no automatic gain: [tenths] < 0 picks a middling LNA 16 dB + VGA 20 dB. Otherwise [tenths] is the total,
 * split with the LNA (0..40 dB in 8 dB steps) taking about half and the VGA (0..62 dB in 2 dB steps) the rest. */
static void hackrf_gain(hackrf_device *d, int tenths) {
    int lna = 16, vga = 20;
    if (tenths >= 0) {
        int total = tenths / 10;
        lna = (total / 2) / 8 * 8; if (lna > 40) lna = 40;
        vga = (total - lna) / 2 * 2; if (vga > 62) vga = 62; if (vga < 0) vga = 0;
    }
    hackrf_set_lna_gain(d, lna);
    hackrf_set_vga_gain(d, vga);
}

static int set_gain(sdr_t *s, int tenths) {
    switch (s->kind) {
    case KIND_RTL:
        if (tenths < 0) return rtlsdr_set_tuner_gain_mode(s->rtl, 0);
        rtlsdr_set_tuner_gain_mode(s->rtl, 1);
        return rtlsdr_set_tuner_gain(s->rtl, tenths);
    case KIND_AIRSPY:
        /* Auto: the Airspy's own LNA and mixer AGC. Manual: its "linearity" gain preset, 0..21 (given as 0..210). */
        if (tenths < 0) { airspy_set_lna_agc(s->airspy, 1); return airspy_set_mixer_agc(s->airspy, 1); }
        return airspy_set_linearity_gain(s->airspy, (uint8_t)(tenths / 10 > 21 ? 21 : tenths / 10));
    case KIND_AIRSPYHF:
        /* Auto: the HF+'s AGC. Manual: gain 0..48 dB means attenuation 48..0 dB in 6 dB steps, AGC off. */
        if (tenths < 0) return airspyhf_set_hf_agc(s->airspyhf, 1);
        airspyhf_set_hf_agc(s->airspyhf, 0);
        {
            int idx = (480 - tenths) / 60; if (idx < 0) idx = 0; if (idx > 8) idx = 8;
            return airspyhf_set_hf_att(s->airspyhf, (uint8_t)idx);
        }
    case KIND_HACKRF:
        hackrf_gain(s->hackrf, tenths);
        return 0;
    }
    return -1;
}

/* ---- open / close ---- */

static uint32_t pick_rate(uint32_t *rates, int count, uint32_t want_min, uint32_t prefer) {
    uint32_t best = 0;
    for (int i = 0; i < count; i++) if (rates[i] == prefer) return prefer;
    for (int i = 0; i < count; i++) if (rates[i] >= want_min && (best == 0 || rates[i] < best)) best = rates[i];
    if (!best) for (int i = 0; i < count; i++) if (rates[i] > best) best = rates[i];
    return best;
}

static int open_streamed(sdr_t *s, int fd, uint32_t hz, int gainTenths) {
    int r;
    s->ringLen = 1 << 21;   /* about 0.7 s at 3 MS/s */
    s->ring = calloc((size_t)s->ringLen * 2, sizeof(float));
    if (!s->ring) return -12;
    switch (s->kind) {
    case KIND_AIRSPY: {
        if ((r = airspy_open_fd(&s->airspy, fd)) != AIRSPY_SUCCESS) return r < 0 ? r : -r;
        uint32_t count = 0, rates[16];
        airspy_get_samplerates(s->airspy, &count, 0);
        if (count > 16) count = 16;
        airspy_get_samplerates(s->airspy, rates, count);
        s->rate = pick_rate(rates, (int)count, 2000000, 0);   /* the lowest rate: 2.5 MS/s (R2) or 3 MS/s (Mini) */
        airspy_set_sample_type(s->airspy, AIRSPY_SAMPLE_FLOAT32_IQ);
        airspy_set_samplerate(s->airspy, s->rate);
        airspy_set_freq(s->airspy, hz);
        set_gain(s, gainTenths);
        r = airspy_start_rx(s->airspy, airspy_cb, s);
        break;
    }
    case KIND_AIRSPYHF: {
        if ((r = airspyhf_open_fd(&s->airspyhf, fd)) != AIRSPYHF_SUCCESS) return r < 0 ? r : -1;
        uint32_t count = 0, rates[32];
        airspyhf_get_samplerates(s->airspyhf, &count, 0);
        if (count > 32) count = 32;
        airspyhf_get_samplerates(s->airspyhf, rates, count);
        s->rate = pick_rate(rates, (int)count, 768000, 912000);   /* wide enough for ±100 kHz plus the 250 kHz offset */
        airspyhf_set_samplerate(s->airspyhf, s->rate);
        airspyhf_set_lib_dsp(s->airspyhf, 1);
        airspyhf_set_freq(s->airspyhf, hz);
        set_gain(s, gainTenths);
        r = airspyhf_start(s->airspyhf, airspyhf_cb, s);
        break;
    }
    case KIND_HACKRF: {
        if ((r = hackrf_init()) != HACKRF_SUCCESS) return r < 0 ? r : -r;
        if ((r = hackrf_open_fd(fd, &s->hackrf)) != HACKRF_SUCCESS) return r < 0 ? r : -r;
        s->rate = 2000000;   /* the HackRF's lowest recommended rate */
        hackrf_set_sample_rate(s->hackrf, s->rate);
        hackrf_set_baseband_filter_bandwidth(s->hackrf, hackrf_compute_baseband_filter_bw(1750000));
        hackrf_set_amp_enable(s->hackrf, 0);   /* the front-end amplifier stays off: it overloads easily */
        hackrf_set_freq(s->hackrf, hz);
        set_gain(s, gainTenths);
        r = hackrf_start_rx(s->hackrf, hackrf_cb, s);
        break;
    }
    default:
        return -1;
    }
    if (r != 0) return r < 0 ? r : -r;
    s->streaming = 1;
    return 0;
}

static void close_sdr(sdr_t *s) {
    switch (s->kind) {
    case KIND_RTL:
        if (s->rtl) rtlsdr_close(s->rtl);
        break;
    case KIND_AIRSPY:
        if (s->airspy) { if (s->streaming) airspy_stop_rx(s->airspy); airspy_close(s->airspy); }
        break;
    case KIND_AIRSPYHF:
        if (s->airspyhf) { if (s->streaming) airspyhf_stop(s->airspyhf); airspyhf_close(s->airspyhf); }
        break;
    case KIND_HACKRF:
        if (s->hackrf) { if (s->streaming) hackrf_stop_rx(s->hackrf); hackrf_close(s->hackrf); }
        hackrf_exit();
        break;
    }
}

/* Opens an SDR of [kind] behind [fd], tuned to [hz]. [rate] is used by the RTL-SDR only (the others pick their own;
 * see nativeRate). [gainTenths] < 0 means automatic gain. [fftSize] 0 picks one giving bins of about 125 Hz.
 * Returns a handle, or 0 with the driver's error in nativeLastError(). (A handle is a pointer; on Android it can carry a
 * tag in its top bits and look negative as a number, so only 0 means failure.) */
JNIEXPORT jlong JNICALL
Java_net_kanenet_catexplorer_audio_UsbSdr_nativeOpen(JNIEnv *env, jclass cls, jint kind, jint fd, jint rate, jint hz,
                                                     jint gainTenths, jint ppm, jint fftSize) {
    (void)env; (void)cls;
    last_error = 0;
    /* apps can't search the USB bus; every driver opens the device Android already opened for us */
    libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    sdr_t *s = calloc(1, sizeof(sdr_t));
    if (!s) { last_error = -12; return 0; }
    pthread_mutex_init(&s->lock, NULL);
    pthread_cond_init(&s->more, NULL);
    s->kind = kind;
    int r;
    if (kind == KIND_RTL) {
        r = rtlsdr_open_fd(&s->rtl, fd);
        if (r < 0 || !s->rtl) { free_sdr(s); last_error = r < 0 ? r : -1; return 0; }
        s->rate = (uint32_t)rate;
        rtlsdr_set_sample_rate(s->rtl, s->rate);
        if (ppm) rtlsdr_set_freq_correction(s->rtl, ppm);
        set_gain(s, gainTenths);
        rtlsdr_set_center_freq(s->rtl, (uint32_t)hz);   /* RTL-SDR Blog's driver picks direct sampling below 24 MHz */
        rtlsdr_reset_buffer(s->rtl);
    } else {
        r = open_streamed(s, fd, (uint32_t)hz, gainTenths);
        if (r != 0) { LOG("open kind %d failed: %d", kind, r); close_sdr(s); free_sdr(s); last_error = r; return 0; }
        LOG("SDR kind %d streaming at %u samples/s", kind, s->rate);
    }
    if (setup_fft(s, fftSize > 0 ? fftSize : fft_for(s->rate)) != 0) { close_sdr(s); free_sdr(s); last_error = -12; return 0; }
    return (jlong)(intptr_t)s;
}

JNIEXPORT jint JNICALL
Java_net_kanenet_catexplorer_audio_UsbSdr_nativeRate(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    return (jint)((sdr_t *)(intptr_t)h)->rate;
}

JNIEXPORT jint JNICALL
Java_net_kanenet_catexplorer_audio_UsbSdr_nativeFftSize(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    return ((sdr_t *)(intptr_t)h)->n;
}

JNIEXPORT jint JNICALL
Java_net_kanenet_catexplorer_audio_UsbSdr_nativeTune(JNIEnv *env, jclass cls, jlong h, jint hz) {
    (void)env; (void)cls;
    sdr_t *s = (sdr_t *)(intptr_t)h;
    int r = -1;
    switch (s->kind) {
    case KIND_RTL: r = rtlsdr_set_center_freq(s->rtl, (uint32_t)hz); rtlsdr_reset_buffer(s->rtl); break;
    case KIND_AIRSPY: r = airspy_set_freq(s->airspy, (uint32_t)hz); break;
    case KIND_AIRSPYHF: r = airspyhf_set_freq(s->airspyhf, (uint32_t)hz); break;
    case KIND_HACKRF: r = hackrf_set_freq(s->hackrf, (uint64_t)hz); break;
    }
    return r;
}

JNIEXPORT jint JNICALL
Java_net_kanenet_catexplorer_audio_UsbSdr_nativeSetGain(JNIEnv *env, jclass cls, jlong h, jint gainTenths) {
    (void)env; (void)cls;
    return set_gain((sdr_t *)(intptr_t)h, gainTenths);
}

/* 1 or 2 while an RTL-SDR samples HF directly (no tuner, so no gain control); 0 otherwise. */
JNIEXPORT jint JNICALL
Java_net_kanenet_catexplorer_audio_UsbSdr_nativeDirectSampling(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    sdr_t *s = (sdr_t *)(intptr_t)h;
    return s->kind == KIND_RTL ? rtlsdr_get_direct_sampling(s->rtl) : 0;
}

/* The manual gain steps, in tenths (of a dB, or of a level for the Airspy's 0..21 presets). */
JNIEXPORT jintArray JNICALL
Java_net_kanenet_catexplorer_audio_UsbSdr_nativeGains(JNIEnv *env, jclass cls, jlong h) {
    (void)cls;
    sdr_t *s = (sdr_t *)(intptr_t)h;
    int g[128], n = 0;
    switch (s->kind) {
    case KIND_RTL:
        n = rtlsdr_get_tuner_gains(s->rtl, NULL);
        if (n < 0) n = 0;
        if (n > 128) n = 128;
        if (n > 0) {
            int *all = calloc(n, sizeof(int));
            rtlsdr_get_tuner_gains(s->rtl, all);
            memcpy(g, all, n * sizeof(int));
            free(all);
        }
        break;
    case KIND_AIRSPY: for (int i = 0; i <= 21; i++) g[n++] = i * 10; break;
    case KIND_AIRSPYHF: for (int i = 0; i <= 8; i++) g[n++] = i * 60; break;
    case KIND_HACKRF: for (int t = 0; t <= 100; t += 4) g[n++] = t * 10; break;
    }
    jintArray out = (*env)->NewIntArray(env, n);
    (*env)->SetIntArrayRegion(env, out, 0, n, g);
    return out;
}

static void fft(sdr_t *s) {
    int n = s->n;
    float *re = s->re, *im = s->im;
    for (int size = 2; size <= n; size <<= 1) {
        int half = size >> 1, step = n / size;
        for (int k = 0; k < n; k += size) {
            for (int m = 0; m < half; m++) {
                float c = s->cosT[m * step], sn = -s->sinT[m * step];
                int a = k + m, b = a + half;
                float tr = re[b] * c - im[b] * sn, ti = re[b] * sn + im[b] * c;
                re[b] = re[a] - tr; im[b] = im[a] - ti;
                re[a] += tr; im[a] += ti;
            }
        }
    }
}

/* Waits (up to 3 s) until a streamed SDR has delivered [need] complex samples since [from]. */
static int wait_for(sdr_t *s, long long from, int need) {
    struct timespec until;
    clock_gettime(CLOCK_REALTIME, &until);
    until.tv_sec += 3;
    int r = 0;
    pthread_mutex_lock(&s->lock);
    while (s->written - from < need) {
        if (pthread_cond_timedwait(&s->more, &s->lock, &until) != 0) { r = -5; break; }
    }
    pthread_mutex_unlock(&s->lock);
    return r;
}

/*
 * Waits for [ffts] FFTs' worth of fresh samples and writes their average power into [out] (size n, lowest frequency
 * first, the tuned frequency at n/2). [stats] receives {samples at full scale, samples counted, peak deviation 0..128}.
 * Returns 0, or a negative driver error (the SDR was unplugged, for example).
 */
JNIEXPORT jint JNICALL
Java_net_kanenet_catexplorer_audio_UsbSdr_nativeReadSpectrum(JNIEnv *env, jclass cls, jlong h, jfloatArray out, jint ffts,
                                                             jintArray stats) {
    (void)cls;
    sdr_t *s = (sdr_t *)(intptr_t)h;
    int n = s->n;
    int over = 0, peak = 0, counted = 0;
    memset(s->acc, 0, sizeof(float) * n);
    if (s->kind == KIND_RTL) {
        int need = n * 2 * ffts;
        if (s->bufLen < need) { free(s->buf); s->buf = malloc(need); s->bufLen = need; }
        int got = 0;
        while (got < need) {
            int nread = 0;
            int chunk = need - got;
            if (chunk > 262144) chunk = 262144;
            int r = rtlsdr_read_sync(s->rtl, s->buf + got, chunk, &nread);
            if (r < 0) return r;
            if (nread <= 0) return -5;
            got += nread;
        }
        for (int f = 0; f < ffts; f++) {
            const unsigned char *p = s->buf + f * n * 2;
            for (int i = 0; i < n; i++) {
                int I = p[2 * i], Q = p[2 * i + 1];
                if (I == 0 || I == 255) over++;
                if (Q == 0 || Q == 255) over++;
                int dI = abs(I - 127), dQ = abs(Q - 127);
                if (dI > peak) peak = dI;
                if (dQ > peak) peak = dQ;
                int j = s->rev[i];
                s->re[j] = ((float)I - 127.5f) * s->win[i];
                s->im[j] = ((float)Q - 127.5f) * s->win[i];
            }
            fft(s);
            for (int i = 0; i < n; i++) s->acc[i] += s->re[i] * s->re[i] + s->im[i] * s->im[i];
        }
        counted = need;
    } else {
        int need = n * ffts;
        if (need > s->ringLen) need = s->ringLen;
        /* fresh samples only: wait for a whole new block after this call starts */
        pthread_mutex_lock(&s->lock);
        long long start = s->written;
        s->over = 0; s->peak = 0;
        pthread_mutex_unlock(&s->lock);
        int r = wait_for(s, start, need);
        if (r < 0) return r;
        pthread_mutex_lock(&s->lock);
        long long end = s->written;
        over = (int)s->over; peak = s->peak;
        /* copy the newest [need] samples out before the driver overwrites them */
        float *copy = malloc(sizeof(float) * 2 * (size_t)need);
        if (!copy) { pthread_mutex_unlock(&s->lock); return -12; }
        for (int i = 0; i < need; i++) {
            int pos = (int)((end - need + i) % s->ringLen);
            copy[2 * i] = s->ring[2 * pos]; copy[2 * i + 1] = s->ring[2 * pos + 1];
        }
        pthread_mutex_unlock(&s->lock);
        int blocks = need / n;
        for (int f = 0; f < blocks; f++) {
            const float *p = copy + (size_t)f * n * 2;
            for (int i = 0; i < n; i++) {
                int j = s->rev[i];
                s->re[j] = p[2 * i] * s->win[i];
                s->im[j] = p[2 * i + 1] * s->win[i];
            }
            fft(s);
            for (int i = 0; i < n; i++) s->acc[i] += s->re[i] * s->re[i] + s->im[i] * s->im[i];
        }
        free(copy);
        ffts = blocks > 0 ? blocks : 1;
        counted = need * 2;
        over *= 2;
    }
    /* average, and swap halves so the tuned frequency sits in the middle */
    float *o = (*env)->GetFloatArrayElements(env, out, NULL);
    for (int i = 0; i < n; i++) o[i] = s->acc[(i + n / 2) % n] / ffts;
    (*env)->ReleaseFloatArrayElements(env, out, o, 0);
    jint st[3] = { over, counted, peak };
    (*env)->SetIntArrayRegion(env, stats, 0, 3, st);
    return 0;
}

JNIEXPORT void JNICALL
Java_net_kanenet_catexplorer_audio_UsbSdr_nativeClose(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    sdr_t *s = (sdr_t *)(intptr_t)h;
    if (!s) return;
    close_sdr(s);
    free_sdr(s);
}
