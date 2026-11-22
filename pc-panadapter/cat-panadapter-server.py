#!/usr/bin/env python3
# Copyright (C) 2026 David Kane (W6MZT)
# SPDX-License-Identifier: GPL-3.0-or-later
"""
Cat Explorer Panadapter.

Runs on the PC that runs FLRig and feeds the Waterfall tab in Cat Explorer on the tablet. It has two sources:

* A sound card (default): records the radio's receive audio from the USB sound card wired to the radio's DATA
  jack and streams it, raw, to the tablet. The tablet draws a 0-4 kHz waterfall of the receive passband and
  can also play the audio ("Listen"). Needs only the Python standard library plus `parec` or `arecord`.

* Another SDR through SoapySDR (--soapy driver=NAME, or --device soapy:driver=NAME from --list): the same wide
  panadapter from an SDRplay, Airspy, Airspy HF+, HackRF, LimeSDR, PlutoSDR or anything else with a SoapySDR module.
  Needs python3-soapysdr and the SDR's module (soapysdr-module-all; SDRplay: SDRplay's API plus SoapySDRPlay3).

* An RTL-SDR (--sdr): a wide panadapter. It runs RTL-SDR Blog's `rtl_tcp`, keeps the SDR tuned near the
  radio's frequency (read from FLRig on this PC), does the FFT here and sends the tablet about +/-100 kHz of
  spectrum around the dial ~20 times a second. Needs numpy (python3-numpy). The SDR must have its own receive
  antenna or a T/R switch: it must never see the radio's transmit power.

Most people start it from the window, cat-panadapter.py, which runs this in the background. By hand:

    python3 cat-panadapter-server.py --list          # show the sound inputs on this PC
    python3 cat-panadapter-server.py                 # pick the USB sound card automatically
    python3 cat-panadapter-server.py --device NAME   # or name it (a parec source name, or an ALSA device for arecord)
    python3 cat-panadapter-server.py --sdr           # RTL-SDR, following FLRig's frequency
    python3 cat-panadapter-server.py --sdr --test-freq 98.0   # RTL-SDR on a fixed frequency (MHz), no FLRig needed

The stream goes one way, to the tablet. It has no password, so keep the port (default 7366) on your home
network / VPN, the same way as FLRig's XML-RPC port.

Protocol: on connect the server sends one text line, then data until the tablet disconnects.
  Sound:  "CATAUDIO 1 rate=12000 ch=1 fmt=s16le\\n" then raw 16-bit little-endian mono samples.
  SDR:    "CATSPEC 2\\n" then frames, each: b"Z", int64 centre Hz (the dial), float32 Hz per bin,
          float32 base dB, float32 dB per step, uint16 bin count, uint8 flags (1 = SDR overloaded),
          uint16 length, then that many bytes of zlib-compressed int8 differences between neighbouring bins
          (running sum = steps above base). All numbers big-endian. Bins run low to high, centred on the dial.
          About 7 KB/s at ~10 frames a second.

While running it keeps two small files in $XDG_RUNTIME_DIR/cat-panadapter/ for the window to read:
panadapter.pid and status.json (source, port, connected tablets, level, last error).
"""
import argparse
import array
import json
import os
import queue
import shutil
import signal
import socket
import struct
import subprocess
import sys
import threading
import time
import xmlrpc.client
import zlib

DEFAULT_PORT = 7366
RATE = 12000   # sound: 0..6 kHz of audio; the FT-891's widest receive filter is about 3.2 kHz
CHUNK = 480    # sound: bytes = 20 ms of audio

SDR_RATE = 1_024_000        # samples a second from the RTL-SDR
SDR_FFT = 8192              # -> 125 Hz per bin
SDR_HALF_SPAN = 100_000     # Hz either side of the dial sent to the tablet
SDR_OFFSET = 250_000        # the SDR is tuned this far below the dial, keeping the dial off its centre spike
SDR_RETUNE_BAND = (150_000, 350_000)   # retune when the dial drifts outside this offset from the SDR's centre
SDR_FFTS_PER_FRAME = 12     # ~96 ms of samples averaged into each frame -> ~10 frames a second
SDR_STEP_DB = 1.0           # each bin is sent to the nearest 1 dB (finer than the waterfall's colours show)
RTL_TCP_PORT = 17366        # rtl_tcp listens here, on this PC only
RTL_TCP_LOG = os.path.expanduser("~/.cache/cat-panadapter-rtl_tcp.log")

SDR_ID = "rtlsdr"           # the "device" name the window uses for the RTL-SDR


def runtime_dir():
    base = os.environ.get("XDG_RUNTIME_DIR") or f"/tmp/cat-panadapter-{os.getuid()}"
    d = os.path.join(base, "cat-panadapter")
    os.makedirs(d, exist_ok=True)
    return d


PID_FILE = os.path.join(runtime_dir(), "panadapter.pid")
STATUS_FILE = os.path.join(runtime_dir(), "status.json")


# ---------------------------------------------------------------- finding things

def sound_inputs(include_monitors=False):
    """[(name, description)] for the PulseAudio/PipeWire inputs. Speaker 'monitor' sources are left out unless asked."""
    if not shutil.which("pactl"):
        return []
    out = subprocess.run(["pactl", "-f", "json", "list", "sources"], capture_output=True, text=True)
    result = []
    try:
        for s in json.loads(out.stdout):
            result.append((s["name"], s.get("description") or s["name"]))
    except (ValueError, KeyError, TypeError):
        # older pactl without JSON output
        short = subprocess.run(["pactl", "list", "short", "sources"], capture_output=True, text=True).stdout
        result = [(line.split("\t")[1], line.split("\t")[1]) for line in short.splitlines() if "\t" in line]
    return [r for r in result if include_monitors or not r[0].endswith(".monitor")]


def find_rtl_tcp():
    """(path to rtl_tcp, its lib folder or None). Prefers RTL-SDR Blog's build in ~/.local/opt/rtl-sdr-blog, whose
    HF direct sampling works; Ubuntu's own rtl-sdr 2.0.1 package returns no data in direct-sampling mode."""
    for base in (os.path.expanduser("~/.local/opt/rtl-sdr-blog"), "/opt/rtl-sdr-blog", "/usr/local"):
        exe = os.path.join(base, "bin", "rtl_tcp")
        if os.access(exe, os.X_OK):
            lib = os.path.join(base, "lib")
            return exe, lib if os.path.isdir(lib) else None
    exe = shutil.which("rtl_tcp")
    return (exe, None) if exe else (None, None)


def rtlsdr_present():
    """True when an RTL2832-based dongle is plugged in (read from sysfs, no driver needed)."""
    known = {("0bda", "2838"), ("0bda", "2832")}
    for d in os.listdir("/sys/bus/usb/devices"):
        try:
            v = open(f"/sys/bus/usb/devices/{d}/idVendor").read().strip()
            p = open(f"/sys/bus/usb/devices/{d}/idProduct").read().strip()
        except OSError:
            continue
        if (v, p) in known:
            return True
    return False


def pick_device():
    usb = [n for n, _ in sound_inputs() if "usb" in n.lower()]
    if len(usb) == 1:
        return usb[0]
    if len(usb) > 1:
        print("More than one USB sound input — choose one with --device:", *usb, sep="\n  ", file=sys.stderr)
    else:
        print("No USB sound input found. Use --list, then --device NAME.", file=sys.stderr)
    sys.exit(2)


def recorder(device):
    if shutil.which("parec"):
        return ["parec", f"--device={device}", f"--rate={RATE}", "--channels=1", "--format=s16le",
                "--raw", "--latency-msec=40"]
    if shutil.which("arecord"):
        return ["arecord", "-q", "-D", device, "-f", "S16_LE", "-r", str(RATE), "-c", "1", "-t", "raw"]
    print("Neither parec nor arecord is installed (sudo apt install pulseaudio-utils).", file=sys.stderr)
    sys.exit(2)


# ---------------------------------------------------------------- sources

class SoundSource:
    """The radio's receive audio from a sound card."""
    header = f"CATAUDIO 1 rate={RATE} ch=1 fmt=s16le\n"

    def __init__(self, device):
        self.device = device
        self.cmd = recorder(device)

    def describe(self):
        return self.device

    def close(self):
        pass   # parec/arecord exit by themselves when this program's end of their pipe closes

    def run(self, pan):
        while not pan.stopping:
            proc = subprocess.Popen(self.cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            pan.error = ""
            peak = clipped = counted = 0
            while not pan.stopping:
                chunk = proc.stdout.read(CHUNK)
                if not chunk:
                    break
                samples = array.array("h", chunk[: len(chunk) // 2 * 2])
                if sys.byteorder == "big":
                    samples.byteswap()
                for v in samples:
                    a = -v if v < 0 else v
                    if a > peak:
                        peak = a
                    if a >= 32700:
                        clipped += 1
                counted += len(samples)
                if counted >= RATE:
                    pan.peak, pan.clip = peak / 32768, clipped / counted
                    peak = clipped = counted = 0
                pan.broadcast(chunk)
            proc.kill()
            err = proc.stderr.read().decode(errors="replace").strip()
            proc.wait()
            if pan.stopping:
                return
            pan.peak = pan.clip = 0.0
            pan.error = f"The recorder stopped ({err or 'is the sound card still plugged in?'}) — retrying"
            time.sleep(3)


def spectrum_frame(spec, bin_hz, sdr_centre, dial, overloaded, floor=1e-6):
    """One compressed CATSPEC 2 frame: +/-SDR_HALF_SPAN Hz around the dial from a power spectrum [spec] (numpy, lowest
    frequency first, sdr_centre in the middle, bins of bin_hz). None if that span isn't all inside the spectrum."""
    import numpy as np
    n = len(spec)
    half_bins = int(SDR_HALF_SPAN / bin_hz)
    centre_bin = n // 2 + int(round((dial - sdr_centre) / bin_hz))
    lo, hi = centre_bin - half_bins, centre_bin + half_bins
    if lo < 0 or hi > n:
        return None
    db = 10 * np.log10(spec[lo:hi] + floor)
    base = float(np.floor(db.min()))
    q = np.clip(np.round((db - base) / SDR_STEP_DB), 0, 127).astype(np.int16)   # 0..127 keeps every difference in one byte
    # neighbouring bins are similar, so their differences compress well
    packed = zlib.compress(np.diff(q, prepend=0).astype(np.int8).tobytes(), 6)
    return b"Z" + struct.pack(">qfffHBH", dial, bin_hz, base, SDR_STEP_DB, q.size, 1 if overloaded else 0,
                              len(packed)) + packed


class SdrSource:
    """A wide panadapter from an RTL-SDR, kept tuned near the radio's dial."""
    header = "CATSPEC 2\n"

    def __init__(self, gain, ppm, test_hz, flrig):
        import numpy  # noqa: F401  (fail now, with a clear message, rather than in the thread)
        self.gain, self.ppm, self.test_hz, self.flrig_url = gain, ppm, test_hz, flrig
        self.exe, self.lib = find_rtl_tcp()
        if not self.exe:
            print("rtl_tcp not found. Build RTL-SDR Blog's driver into ~/.local/opt/rtl-sdr-blog "
                  "(see the README) or install rtl-sdr.", file=sys.stderr)
            sys.exit(2)
        self.dial = test_hz or 0
        self.ptt = False
        self.sdr_centre = 0
        self.proc = None

    def describe(self):
        return SDR_ID

    def follow_flrig(self, pan):
        """Keeps self.dial and self.ptt current from FLRig (unless a fixed test frequency was given)."""
        rig = xmlrpc.client.ServerProxy(self.flrig_url)
        while not pan.stopping:
            try:
                self.dial = int(float(rig.rig.get_vfo()))
                self.ptt = bool(int(rig.rig.get_ptt()))
                if pan.error.startswith("FLRig"):
                    pan.error = ""
            except Exception as e:  # FLRig not running yet, or busy
                pan.error = f"FLRig isn't answering at {self.flrig_url} ({e.__class__.__name__}) — retrying"
            time.sleep(0.25)

    def start_rtl_tcp(self):
        env = dict(os.environ)
        if self.lib:
            env["LD_LIBRARY_PATH"] = self.lib + (":" + env["LD_LIBRARY_PATH"] if env.get("LD_LIBRARY_PATH") else "")
        start_hz = max(self.dial - SDR_OFFSET, 500_000) if self.dial else 7_000_000
        cmd = [self.exe, "-a", "127.0.0.1", "-p", str(RTL_TCP_PORT), "-s", str(SDR_RATE), "-f", str(start_hz),
               "-g", "0" if self.gain is None else str(self.gain), "-P", str(self.ppm)]
        # its messages go to a file: a pipe nobody reads would fill up after many retunes and stall it
        os.makedirs(os.path.dirname(RTL_TCP_LOG), exist_ok=True)
        log = open(RTL_TCP_LOG, "w")
        self.proc = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, env=env)
        return self.proc

    def last_rtl_tcp_message(self):
        try:
            lines = [l for l in open(RTL_TCP_LOG).read().splitlines() if l.strip()]
            return lines[-1] if lines else ""
        except OSError:
            return ""

    def close(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.proc.kill()

    def run(self, pan):
        import numpy as np
        if not self.test_hz:
            threading.Thread(target=self.follow_flrig, args=(pan,), daemon=True).start()
        window = np.hanning(SDR_FFT).astype(np.float32)
        bin_hz = SDR_RATE / SDR_FFT
        block = SDR_FFT * 2   # bytes per FFT (I and Q, one byte each)
        while not pan.stopping:
            if not self.dial:
                time.sleep(0.3)
                continue
            proc = self.start_rtl_tcp()
            sock = None
            try:
                for _ in range(40):   # rtl_tcp takes a moment to open the dongle
                    if proc.poll() is not None:
                        break
                    try:
                        sock = socket.create_connection(("127.0.0.1", RTL_TCP_PORT), timeout=3)
                        break
                    except OSError:
                        time.sleep(0.1)
                if sock is None:
                    msg = self.last_rtl_tcp_message() if proc.poll() is not None else ""
                    raise RuntimeError(msg or "rtl_tcp didn't start — is the RTL-SDR plugged in?")
                sock.settimeout(5)
                hdr = b""
                while len(hdr) < 12:
                    hdr += sock.recv(12 - len(hdr))
                self.sdr_centre = max(self.dial - SDR_OFFSET, 500_000)
                self.tune(sock, self.sdr_centre)
                skip_until = time.time() + 0.15
                pan.error = ""
                buf = bytearray()
                acc = np.zeros(SDR_FFT, dtype=np.float32)
                n_acc = over = counted = 0
                peak = 0
                while not pan.stopping:
                    data = sock.recv(262144)
                    if not data:
                        raise RuntimeError("rtl_tcp stopped sending — is the RTL-SDR still plugged in?")
                    if time.time() < skip_until:
                        continue
                    buf += data
                    # keep the SDR near the dial: retune when the dial leaves the comfortable part of the SDR's span
                    off = self.dial - self.sdr_centre
                    if not (SDR_RETUNE_BAND[0] <= off <= SDR_RETUNE_BAND[1]):
                        self.sdr_centre = max(self.dial - SDR_OFFSET, 500_000)
                        self.tune(sock, self.sdr_centre)
                        buf.clear(); acc[:] = 0; n_acc = 0
                        skip_until = time.time() + 0.15   # samples from mid-retune are garbage
                        continue
                    while len(buf) >= block:
                        raw = np.frombuffer(bytes(buf[:block]), dtype=np.uint8)
                        del buf[:block]
                        over += int(np.count_nonzero((raw == 0) | (raw == 255)))
                        counted += raw.size
                        peak = max(peak, int(np.abs(raw.astype(np.int16) - 127).max()))
                        f = raw.astype(np.float32) - 127.5
                        iq = (f[0::2] + 1j * f[1::2]) * window
                        acc += np.abs(np.fft.fft(iq)) ** 2
                        n_acc += 1
                        if n_acc >= SDR_FFTS_PER_FRAME:
                            spec = np.fft.fftshift(acc / n_acc)
                            acc[:] = 0; n_acc = 0
                            frame = spectrum_frame(spec, bin_hz, self.sdr_centre, self.dial,
                                                   bool(counted and over / counted > 0.001))
                            if frame is None:
                                continue
                            pan.broadcast(frame)
                            if counted >= SDR_RATE:
                                pan.peak, pan.clip = peak / 128, over / counted
                                over = counted = peak = 0
                            pan.extra = {"sdr_centre": self.sdr_centre, "dial": self.dial, "ptt": self.ptt}
            except Exception as e:
                pan.error = f"RTL-SDR: {e} — retrying"
                pan.peak = pan.clip = 0.0
            finally:
                if sock:
                    sock.close()
                self.close()
            if not pan.stopping:
                time.sleep(3)

    def tune(self, sock, hz):
        sock.sendall(struct.pack(">BI", 0x01, int(hz)))


# ---------------------------------------------------------------- other SDRs, through SoapySDR

SOAPY_PREFIX = "soapy:"
# Drivers not offered through SoapySDR: the RTL-SDR has its own source above (with RTL-SDR Blog's HF direct sampling),
# and these aren't radios.
SOAPY_SKIP = {"rtlsdr", "audio", "remote", "null"}
SOAPY_NAMES = {"sdrplay": "SDRplay", "airspy": "Airspy", "airspyhf": "Airspy HF+", "hackrf": "HackRF",
               "lime": "LimeSDR", "plutosdr": "PlutoSDR", "uhd": "USRP", "bladerf": "bladeRF", "sdrplay3": "SDRplay"}


def soapy_devices():
    """[(device id, description)] for every SDR SoapySDR can see, except the RTL-SDR. Empty if SoapySDR isn't installed
    (sudo apt install python3-soapysdr soapysdr-module-all; SDRplay also needs SDRplay's API and SoapySDRPlay3)."""
    try:
        import SoapySDR
    except ImportError:
        return []
    out = []
    try:
        found = SoapySDR.Device.enumerate()
    except Exception:
        return []
    for kw in found:
        d = dict(kw)
        driver = d.get("driver", "")
        if driver in SOAPY_SKIP:
            continue
        keep = {k: d[k] for k in ("driver", "serial") if k in d}
        dev_id = SOAPY_PREFIX + ",".join(f"{k}={v}" for k, v in keep.items())
        label = d.get("label") or SOAPY_NAMES.get(driver, driver)
        out.append((dev_id, f"{label} — wide panadapter (SoapySDR)"))
    return out


def is_sdr(device):
    return device == SDR_ID or (device or "").startswith(SOAPY_PREFIX)


class SoapySource(SdrSource):
    """A wide panadapter from any SDR with a SoapySDR module (SDRplay, Airspy, HackRF, LimeSDR, PlutoSDR...): the same
    picture and protocol as the RTL-SDR source. Untested on real hardware except through SoapySDR's own RTL-SDR module."""

    def __init__(self, device_id, gain, ppm, test_hz, flrig):
        import numpy  # noqa: F401
        try:
            import SoapySDR  # noqa: F401
        except ImportError:
            print("This SDR needs SoapySDR: sudo apt install python3-soapysdr soapysdr-module-all", file=sys.stderr)
            sys.exit(2)
        self.device_id = device_id
        self.args = device_id[len(SOAPY_PREFIX):]
        self.gain, self.ppm, self.test_hz, self.flrig_url = gain, ppm, test_hz, flrig
        self.dial = test_hz or 0
        self.ptt = False
        self.sdr_centre = 0
        self.dev = None
        self.stream = None

    def describe(self):
        return self.device_id

    @staticmethod
    def pick_rate(dev, rx):
        """A sample rate of about 2 MS/s: wide enough for +/-100 kHz around a dial 250 kHz from the SDR's centre, small
        enough to keep the PC's load down. Prefers exactly 2 MS/s, else the lowest offered rate of at least 768 kS/s."""
        listed = sorted(float(r) for r in dev.listSampleRates(rx, 0))
        ranges = [(float(r.minimum()), float(r.maximum())) for r in dev.getSampleRateRange(rx, 0)]
        if any(lo <= 2e6 <= hi for lo, hi in ranges) or 2e6 in listed:
            return 2e6
        ok = [r for r in listed if r >= 768e3]
        if ok:
            return ok[0]
        ok = [max(lo, 768e3) for lo, hi in ranges if hi >= 768e3]
        if ok:
            return min(ok)
        return listed[-1] if listed else 2e6

    def close(self):
        try:
            if self.stream is not None:
                self.dev.deactivateStream(self.stream)
                self.dev.closeStream(self.stream)
        except Exception:
            pass
        self.stream = None
        self.dev = None

    def set_gain(self, rx):
        dev = self.dev
        if self.gain is None:
            if dev.hasGainMode(rx, 0):
                dev.setGainMode(rx, 0, True)
            else:   # no automatic gain (HackRF, for one): the middle of the range
                r = dev.getGainRange(rx, 0)
                dev.setGain(rx, 0, (r.minimum() + r.maximum()) / 2)
        else:
            if dev.hasGainMode(rx, 0):
                dev.setGainMode(rx, 0, False)
            r = dev.getGainRange(rx, 0)
            dev.setGain(rx, 0, min(max(self.gain, r.minimum()), r.maximum()))

    def covers(self, rx, hz):
        try:
            return any(r.minimum() <= hz <= r.maximum() for r in self.dev.getFrequencyRange(rx, 0))
        except Exception:
            return True

    def run(self, pan):
        import numpy as np
        import SoapySDR
        from SoapySDR import SOAPY_SDR_RX as RX, SOAPY_SDR_CF32 as CF32
        if not self.test_hz:
            threading.Thread(target=self.follow_flrig, args=(pan,), daemon=True).start()
        while not pan.stopping:
            if not self.dial:
                time.sleep(0.3)
                continue
            try:
                self.dev = SoapySDR.Device(self.args)
                rate = self.pick_rate(self.dev, RX)
                self.dev.setSampleRate(RX, 0, rate)
                rate = float(self.dev.getSampleRate(RX, 0))
                n = 1 << max(10, min(16, int(round(np.log2(rate / 125.0)))))   # bins of about 125 Hz
                bin_hz = rate / n
                ffts_per_frame = max(1, int(round(rate * 0.096 / n)))
                window = np.hanning(n).astype(np.float32)
                try:
                    if self.dev.hasDCOffsetMode(RX, 0):
                        self.dev.setDCOffsetMode(RX, 0, True)
                    if self.ppm and self.dev.hasFrequencyCorrection(RX, 0):
                        self.dev.setFrequencyCorrection(RX, 0, float(self.ppm))
                except Exception:
                    pass
                self.set_gain(RX)
                self.sdr_centre = max(self.dial - SDR_OFFSET, 500_000)
                self.dev.setFrequency(RX, 0, float(self.sdr_centre))
                self.stream = self.dev.setupStream(RX, CF32)
                self.dev.activateStream(self.stream)
                pan.error = ""
                buf = np.zeros(n, dtype=np.complex64)
                acc = np.zeros(n, dtype=np.float32)
                n_acc = over = counted = 0
                peak = 0.0
                skip_until = time.time() + 0.15
                while not pan.stopping:
                    if not self.covers(RX, self.dial):
                        pan.error = f"{self.dial / 1e6:.3f} MHz is outside what this SDR covers (it may need an upconverter)"
                        time.sleep(0.5)
                        continue
                    off = self.dial - self.sdr_centre
                    if not (SDR_RETUNE_BAND[0] <= off <= SDR_RETUNE_BAND[1]) and max(self.dial - SDR_OFFSET, 500_000) != self.sdr_centre:
                        self.sdr_centre = max(self.dial - SDR_OFFSET, 500_000)
                        self.dev.setFrequency(RX, 0, float(self.sdr_centre))
                        acc[:] = 0; n_acc = 0
                        skip_until = time.time() + 0.15
                    got = 0
                    while got < n:
                        sr = self.dev.readStream(self.stream, [buf[got:]], n - got, timeoutUs=1_000_000)
                        if sr.ret < 0:
                            if sr.ret == -4:   # overflow: samples were dropped, carry on
                                continue
                            raise RuntimeError(f"stream error {SoapySDR.errToStr(sr.ret)}")
                        got += sr.ret
                    if time.time() < skip_until:
                        continue
                    if pan.error.startswith(f"{self.dial / 1e6:.3f}") or pan.error.startswith("SDR:"):
                        pan.error = ""
                    mag = np.maximum(np.abs(buf.real), np.abs(buf.imag))
                    over += int(np.count_nonzero(mag > 0.99))
                    counted += n
                    peak = max(peak, float(mag.max()))
                    acc += np.abs(np.fft.fft(buf * window)) ** 2
                    n_acc += 1
                    if n_acc >= ffts_per_frame:
                        spec = np.fft.fftshift(acc / n_acc)
                        acc[:] = 0; n_acc = 0
                        frame = spectrum_frame(spec, bin_hz, self.sdr_centre, self.dial,
                                               bool(counted and over / counted > 0.001), floor=1e-12)
                        if frame is not None:
                            pan.broadcast(frame)
                        if counted >= rate:
                            pan.peak, pan.clip = min(peak, 1.0), over / counted
                            over = counted = 0
                            peak = 0.0
                        pan.extra = {"sdr_centre": self.sdr_centre, "dial": self.dial, "ptt": self.ptt,
                                     "sdr_rate": rate}
            except Exception as e:
                pan.error = f"SDR: {e} — retrying"
                pan.peak = pan.clip = 0.0
            finally:
                self.close()
            if not pan.stopping:
                time.sleep(3)


# ---------------------------------------------------------------- the server

class Panadapter:
    """One source shared by every connected tablet, plus the status file the window reads."""

    def __init__(self, source, port):
        self.source, self.port = source, port
        self.clients = {}            # queue -> "ip:port"
        self.lock = threading.Lock()
        self.error = ""
        self.peak = 0.0              # 0..1, loudest sample in the last second
        self.clip = 0.0              # fraction of samples at full scale in the last second
        self.extra = {}
        self.started = time.time()
        self.stopping = False

    def broadcast(self, data):
        with self.lock:
            for q in self.clients:
                try:
                    q.put_nowait(data)
                except queue.Full:
                    pass   # a slow tablet loses a little rather than holding everyone up

    def serve_client(self, conn, addr):
        name = f"{addr[0]}:{addr[1]}"
        q = queue.Queue(maxsize=50)    # ~1-2 s of data
        with self.lock:
            self.clients[q] = name
        print(f"{addr[0]} connected", flush=True)
        try:
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            conn.sendall(self.source.header.encode())
            while not self.stopping:
                try:
                    conn.sendall(q.get(timeout=2))
                except queue.Empty:
                    continue
        except OSError:
            pass
        finally:
            with self.lock:
                self.clients.pop(q, None)
            conn.close()
            print(f"{addr[0]} disconnected", flush=True)

    def write_status(self):
        while not self.stopping:
            with self.lock:
                clients = sorted(self.clients.values())
            status = {
                "pid": os.getpid(), "device": self.source.describe(), "port": self.port,
                "started": self.started, "updated": time.time(), "clients": clients,
                "peak": round(self.peak, 3), "clip": round(self.clip, 4), "error": self.error, **self.extra,
            }
            tmp = STATUS_FILE + ".tmp"
            with open(tmp, "w") as f:
                json.dump(status, f)
            os.replace(tmp, STATUS_FILE)
            time.sleep(0.5)

    def run(self):
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            srv.bind(("0.0.0.0", self.port))
        except OSError as e:
            print(f"Can't use port {self.port}: {e} (is another panadapter already running?)", file=sys.stderr, flush=True)
            sys.exit(3)
        srv.listen(4)
        with open(PID_FILE, "w") as f:
            f.write(str(os.getpid()))
        threading.Thread(target=self.source.run, args=(self,), daemon=True).start()
        threading.Thread(target=self.write_status, daemon=True).start()
        print(f"Streaming {self.source.describe()} on port {self.port}. Ctrl+C to stop.", flush=True)
        try:
            while True:
                conn, addr = srv.accept()
                threading.Thread(target=self.serve_client, args=(conn, addr), daemon=True).start()
        except KeyboardInterrupt:
            print()
        finally:
            self.stopping = True
            self.source.close()   # e.g. rtl_tcp, which would otherwise keep the dongle busy
            for path in (PID_FILE, STATUS_FILE):
                try:
                    os.remove(path)
                except OSError:
                    pass


def main():
    ap = argparse.ArgumentParser(description="Feeds the Waterfall tab in Cat Explorer.")
    ap.add_argument("--device", help="sound input to record (see --list)")
    ap.add_argument("--sdr", action="store_true", help="use an RTL-SDR instead of a sound card")
    ap.add_argument("--soapy", metavar="ARGS", help="use another SDR through SoapySDR, e.g. driver=sdrplay (see --list)")
    ap.add_argument("--gain", help="SDR gain in dB, or 'auto' (default auto; no effect on an RTL-SDR below 24 MHz)")
    ap.add_argument("--ppm", type=int, default=0, help="RTL-SDR frequency correction in ppm")
    ap.add_argument("--test-freq", type=float, help="RTL-SDR: stay on this frequency in MHz instead of following FLRig")
    ap.add_argument("--flrig", default="http://127.0.0.1:12345", help="FLRig's XML-RPC address (default %(default)s)")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"TCP port (default {DEFAULT_PORT})")
    ap.add_argument("--list", action="store_true", help="list sound inputs and exit")
    a = ap.parse_args()

    if a.list:
        srcs = sound_inputs()
        print("\n".join(f"{n}    ({d})" for n, d in srcs) if srcs else
              "No PulseAudio/PipeWire inputs found; try `arecord -l` for ALSA devices.")
        if rtlsdr_present():
            print(f"{SDR_ID}    (RTL-SDR, use --sdr)")
        for dev_id, desc in soapy_devices():
            print(f"{dev_id}    ({desc}; use --device {dev_id})")
        return

    if a.soapy:
        a.device = SOAPY_PREFIX + a.soapy

    gain = None if a.gain in (None, "auto") else float(a.gain)
    if (a.device or "").startswith(SOAPY_PREFIX):
        try:
            source = SoapySource(a.device, gain, a.ppm, int(a.test_freq * 1e6) if a.test_freq else 0, a.flrig)
        except ImportError:
            print("This SDR needs numpy: sudo apt install python3-numpy", file=sys.stderr)
            sys.exit(2)
    elif a.sdr or a.device == SDR_ID:
        try:
            source = SdrSource(gain, a.ppm, int(a.test_freq * 1e6) if a.test_freq else 0, a.flrig)
        except ImportError:
            print("The RTL-SDR needs numpy: sudo apt install python3-numpy", file=sys.stderr)
            sys.exit(2)
    else:
        source = SoundSource(a.device or pick_device())

    # Stop (from the window, or `kill`) arrives as SIGTERM; treat it like Ctrl+C so the files are cleaned up
    def on_term(*_):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, on_term)
    Panadapter(source, a.port).run()


if __name__ == "__main__":
    main()
