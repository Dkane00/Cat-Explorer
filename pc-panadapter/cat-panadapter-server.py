#!/usr/bin/env python3
"""
Cat Explorer Panadapter.

Runs on the PC that runs FLRig. It records the radio's receive audio from the USB sound card wired to the
radio's DATA jack and streams it, raw, to Cat Explorer on the tablet. The tablet draws the waterfall from it
and can also play it (the "Listen" button).

Most people start it from the window, cat-panadapter.py, which runs this in the background. It can also
be run by hand:

    python3 cat-panadapter-server.py --list          # show the sound inputs on this PC
    python3 cat-panadapter-server.py                 # pick the USB sound card automatically
    python3 cat-panadapter-server.py --device NAME   # or name it (a parec source name, or an ALSA device for arecord)

Only the Python standard library is needed. Recording uses `parec` (PulseAudio / PipeWire) or, if that is
missing, `arecord` (ALSA).

The stream is receive audio only; nothing comes back from the tablet. It has no password, so keep the port
(default 7366) on your home network / VPN, the same way as FLRig's XML-RPC port.

Protocol: on connect the server sends one text line
    CATAUDIO 1 rate=12000 ch=1 fmt=s16le\n
followed by raw 16-bit little-endian mono samples until the tablet disconnects.

While running it keeps two small files in $XDG_RUNTIME_DIR/cat-panadapter/ for the window to read:
panadapter.pid and status.json (device, port, connected tablets, audio level, last error).
"""
import argparse
import array
import json
import os
import queue
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time

DEFAULT_PORT = 7366
RATE = 12000   # 0..6 kHz of audio; the FT-891's widest receive filter is about 3.2 kHz
CHUNK = 480    # bytes = 20 ms of audio


def runtime_dir():
    base = os.environ.get("XDG_RUNTIME_DIR") or f"/tmp/cat-panadapter-{os.getuid()}"
    d = os.path.join(base, "cat-panadapter")
    os.makedirs(d, exist_ok=True)
    return d


PID_FILE = os.path.join(runtime_dir(), "panadapter.pid")
STATUS_FILE = os.path.join(runtime_dir(), "status.json")


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


class Panadapter:
    """One recorder shared by every connected tablet, plus the status file the window reads."""

    def __init__(self, device, port):
        self.device, self.port = device, port
        self.cmd = recorder(device)
        self.clients = {}            # queue -> "ip:port"
        self.lock = threading.Lock()
        self.error = ""
        self.peak = 0.0              # 0..1, loudest sample in the last second
        self.clip = 0.0              # fraction of samples at full scale in the last second
        self.started = time.time()
        self.stopping = False

    # ---- recording ----
    def record_forever(self):
        while not self.stopping:
            proc = subprocess.Popen(self.cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.error = ""
            peak = clipped = counted = 0
            while not self.stopping:
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
                    self.peak, self.clip = peak / 32768, clipped / counted
                    peak = clipped = counted = 0
                with self.lock:
                    for q in self.clients:
                        try:
                            q.put_nowait(chunk)
                        except queue.Full:
                            pass   # a slow tablet loses a little audio rather than holding everyone up
            proc.kill()
            err = proc.stderr.read().decode(errors="replace").strip()
            proc.wait()
            if self.stopping:
                return
            self.peak = self.clip = 0.0
            self.error = f"The recorder stopped ({err or 'is the sound card still plugged in?'}) — retrying"
            time.sleep(3)

    # ---- tablets ----
    def serve_client(self, conn, addr):
        name = f"{addr[0]}:{addr[1]}"
        q = queue.Queue(maxsize=50)    # 1 s of audio
        with self.lock:
            self.clients[q] = name
        print(f"{addr[0]} connected", flush=True)
        try:
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            conn.sendall(f"CATAUDIO 1 rate={RATE} ch=1 fmt=s16le\n".encode())
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

    # ---- status for the window ----
    def write_status(self):
        while not self.stopping:
            with self.lock:
                clients = sorted(self.clients.values())
            status = {
                "pid": os.getpid(), "device": self.device, "port": self.port, "rate": RATE,
                "started": self.started, "updated": time.time(), "clients": clients,
                "peak": round(self.peak, 3), "clip": round(self.clip, 4), "error": self.error,
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
        threading.Thread(target=self.record_forever, daemon=True).start()
        threading.Thread(target=self.write_status, daemon=True).start()
        print(f"Streaming {self.device} at {RATE} Hz on port {self.port}. Ctrl+C to stop.", flush=True)
        try:
            while True:
                conn, addr = srv.accept()
                threading.Thread(target=self.serve_client, args=(conn, addr), daemon=True).start()
        except KeyboardInterrupt:
            print()
        finally:
            self.stopping = True
            for path in (PID_FILE, STATUS_FILE):
                try:
                    os.remove(path)
                except OSError:
                    pass


def main():
    ap = argparse.ArgumentParser(description="Streams the radio's receive audio to Cat Explorer.")
    ap.add_argument("--device", help="sound input to record (see --list)")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"TCP port (default {DEFAULT_PORT})")
    ap.add_argument("--list", action="store_true", help="list sound inputs and exit")
    a = ap.parse_args()

    if a.list:
        srcs = sound_inputs()
        print("\n".join(f"{n}    ({d})" for n, d in srcs) if srcs else
              "No PulseAudio/PipeWire inputs found; try `arecord -l` for ALSA devices.")
        return

    # Stop (from the window, or `kill`) arrives as SIGTERM; treat it like Ctrl+C so the files are cleaned up
    def on_term(*_):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, on_term)
    Panadapter(a.device or pick_device(), a.port).run()


if __name__ == "__main__":
    main()
