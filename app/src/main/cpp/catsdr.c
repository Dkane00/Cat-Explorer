/*
 * Cat Explorer — bridge between Kotlin (net.kanenet.catexplorer.audio.RtlSdr) and the RTL-SDR driver.
 *
 * The tablet opens the dongle with Android's USB host API and hands its file descriptor here. Samples are read
 * synchronously and turned into an averaged power spectrum (Hann window, FFT, |X|^2, centre frequency in the middle),
 * the same processing the PC Panadapter does with numpy.
 */
#include <jni.h>
#include <math.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>
#include "rtl-sdr.h"

#define TAG "CatSdr"

typedef struct {
    rtlsdr_dev_t *dev;
    int n;              /* FFT size, a power of two */
    float *win, *cosT, *sinT, *re, *im, *acc;
    int *rev;
    unsigned char *buf;
    int bufLen;
} sdr_t;

/* ---- the driver prints to stderr; send that to logcat so problems are visible with `adb logcat -s CatSdr` ---- */
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

static void free_sdr(sdr_t *s) {
    if (!s) return;
    free(s->win); free(s->cosT); free(s->sinT); free(s->re); free(s->im); free(s->acc); free(s->rev); free(s->buf);
    free(s);
}

static int last_error;

JNIEXPORT jint JNICALL
Java_net_kanenet_catexplorer_audio_RtlSdr_nativeLastError(JNIEnv *env, jclass cls) { (void)env; (void)cls; return last_error; }

/* Opens the dongle behind [fd] at [rate] samples a second, tuned to [hz]. [gainTenths] < 0 means automatic gain.
 * Returns a handle, or 0 with the driver's error in nativeLastError(). (A handle is a pointer; on Android it can carry a
 * tag in its top bits and look negative as a number, so only 0 means failure.) */
JNIEXPORT jlong JNICALL
Java_net_kanenet_catexplorer_audio_RtlSdr_nativeOpen(JNIEnv *env, jclass cls, jint fd, jint rate, jint hz, jint gainTenths,
                                                     jint ppm, jint fftSize) {
    (void)env; (void)cls;
    last_error = 0;
    sdr_t *s = calloc(1, sizeof(sdr_t));
    if (!s) { last_error = -12; return 0; }
    int r = rtlsdr_open_fd(&s->dev, fd);
    if (r < 0 || !s->dev) { free(s); last_error = r < 0 ? r : -1; return 0; }
    int n = fftSize;
    s->n = n;
    s->win = malloc(sizeof(float) * n); s->re = malloc(sizeof(float) * n); s->im = malloc(sizeof(float) * n);
    s->acc = malloc(sizeof(float) * n); s->rev = malloc(sizeof(int) * n);
    s->cosT = malloc(sizeof(float) * n / 2); s->sinT = malloc(sizeof(float) * n / 2);
    int bits = 0; while ((1 << bits) < n) bits++;
    for (int i = 0; i < n; i++) {
        s->win[i] = (float)(0.5 - 0.5 * cos(2 * M_PI * i / (n - 1)));
        int x = i, y = 0;
        for (int b = 0; b < bits; b++) { y = (y << 1) | (x & 1); x >>= 1; }
        s->rev[i] = y;
    }
    for (int i = 0; i < n / 2; i++) { s->cosT[i] = (float)cos(2 * M_PI * i / n); s->sinT[i] = (float)sin(2 * M_PI * i / n); }

    rtlsdr_set_sample_rate(s->dev, (uint32_t)rate);
    if (ppm) rtlsdr_set_freq_correction(s->dev, ppm);
    if (gainTenths < 0) rtlsdr_set_tuner_gain_mode(s->dev, 0);
    else { rtlsdr_set_tuner_gain_mode(s->dev, 1); rtlsdr_set_tuner_gain(s->dev, gainTenths); }
    rtlsdr_set_center_freq(s->dev, (uint32_t)hz);   /* RTL-SDR Blog's driver picks direct sampling below 24 MHz */
    rtlsdr_reset_buffer(s->dev);
    return (jlong)(intptr_t)s;
}

JNIEXPORT jint JNICALL
Java_net_kanenet_catexplorer_audio_RtlSdr_nativeTune(JNIEnv *env, jclass cls, jlong h, jint hz) {
    (void)env; (void)cls;
    sdr_t *s = (sdr_t *)(intptr_t)h;
    int r = rtlsdr_set_center_freq(s->dev, (uint32_t)hz);
    rtlsdr_reset_buffer(s->dev);
    return r;
}

JNIEXPORT jint JNICALL
Java_net_kanenet_catexplorer_audio_RtlSdr_nativeSetGain(JNIEnv *env, jclass cls, jlong h, jint gainTenths) {
    (void)env; (void)cls;
    sdr_t *s = (sdr_t *)(intptr_t)h;
    if (gainTenths < 0) return rtlsdr_set_tuner_gain_mode(s->dev, 0);
    rtlsdr_set_tuner_gain_mode(s->dev, 1);
    return rtlsdr_set_tuner_gain(s->dev, gainTenths);
}

/* 1 or 2 while the dongle samples HF directly (no tuner, so no gain control), 0 when the tuner is in use. */
JNIEXPORT jint JNICALL
Java_net_kanenet_catexplorer_audio_RtlSdr_nativeDirectSampling(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    sdr_t *s = (sdr_t *)(intptr_t)h;
    return rtlsdr_get_direct_sampling(s->dev);
}

/* The tuner's gain steps in tenths of a dB. */
JNIEXPORT jintArray JNICALL
Java_net_kanenet_catexplorer_audio_RtlSdr_nativeGains(JNIEnv *env, jclass cls, jlong h) {
    (void)cls;
    sdr_t *s = (sdr_t *)(intptr_t)h;
    int n = rtlsdr_get_tuner_gains(s->dev, NULL);
    if (n < 0) n = 0;
    int *g = calloc(n > 0 ? n : 1, sizeof(int));
    if (n > 0) rtlsdr_get_tuner_gains(s->dev, g);
    jintArray out = (*env)->NewIntArray(env, n);
    (*env)->SetIntArrayRegion(env, out, 0, n, g);
    free(g);
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

/*
 * Reads [ffts] FFT-sized blocks and writes their average power into [out] (size n, lowest frequency first, the tuned
 * frequency at n/2). [stats] receives {samples at full scale, samples counted, peak deviation 0..128}.
 * Returns 0, or a negative driver error (the dongle was unplugged, for example).
 */
JNIEXPORT jint JNICALL
Java_net_kanenet_catexplorer_audio_RtlSdr_nativeReadSpectrum(JNIEnv *env, jclass cls, jlong h, jfloatArray out, jint ffts,
                                                             jintArray stats) {
    (void)cls;
    sdr_t *s = (sdr_t *)(intptr_t)h;
    int n = s->n;
    int need = n * 2 * ffts;
    if (s->bufLen < need) { free(s->buf); s->buf = malloc(need); s->bufLen = need; }
    int got = 0;
    while (got < need) {
        int nread = 0;
        int chunk = need - got;
        if (chunk > 262144) chunk = 262144;
        int r = rtlsdr_read_sync(s->dev, s->buf + got, chunk, &nread);
        if (r < 0) return r;
        if (nread <= 0) return -5;
        got += nread;
    }
    memset(s->acc, 0, sizeof(float) * n);
    int over = 0, peak = 0;
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
    /* average, and swap halves so the tuned frequency sits in the middle */
    float *o = (*env)->GetFloatArrayElements(env, out, NULL);
    for (int i = 0; i < n; i++) o[i] = s->acc[(i + n / 2) % n] / ffts;
    (*env)->ReleaseFloatArrayElements(env, out, o, 0);
    jint st[3] = { over, need, peak };
    (*env)->SetIntArrayRegion(env, stats, 0, 3, st);
    return 0;
}

JNIEXPORT void JNICALL
Java_net_kanenet_catexplorer_audio_RtlSdr_nativeClose(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    sdr_t *s = (sdr_t *)(intptr_t)h;
    if (!s) return;
    if (s->dev) rtlsdr_close(s->dev);
    free_sdr(s);
}
