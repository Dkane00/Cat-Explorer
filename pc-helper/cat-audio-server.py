#!/usr/bin/env python3
"""
Cat Explorer audio helper.

Runs on the PC that runs FLRig. It records the radio's receive audio from the USB sound card wired to the
radio's DATA jack and streams it, raw, to Cat Explorer on the tablet. The tablet draws the waterfall from it
and can also play it (the "Listen" button).

Only the Python standard library is needed. Recording uses `parec` (PulseAudio / PipeWire) or, if that is
missing, `arecord` (ALSA).

    python3 cat-audio-server.py --list          # show the sound inputs on this PC
    python3 cat-audio-server.py                 # pick the USB sound card automatically
    python3 cat-audio-server.py --device NAME   # or name it (a parec source name, or an ALSA device for arecord)

The stream is receive audio only; nothing comes back from the tablet. It has no password, so keep the port
(default 7366) on your home network / VPN, the same way as FLRig's XML-RPC port.

Protocol: on connect the server sends one text line
    CATAUDIO 1 rate=12000 ch=1 fmt=s16le\n
followed by raw 16-bit little-endian mono samples until the tablet disconnects.
"""
import argparse
import shutil
import socket
import subprocess
import sys
import threading

DEFAULT_PORT = 7366
RATE = 12000   # 0..6 kHz of audio; the FT-891's widest receive filter is about 3.2 kHz


def pulse_sources():
    """(name, description-ish) for every PulseAudio/PipeWire input that isn't a speaker 'monitor'."""
    if not shutil.which("pactl"):
        return []
    out = subprocess.run(["pactl", "list", "short", "sources"], capture_output=True, text=True).stdout
    names = [line.split("\t")[1] for line in out.splitlines() if "\t" in line]
    return [n for n in names if not n.endswith(".monitor")]


def pick_device():
    srcs = pulse_sources()
    usb = [s for s in srcs if "usb" in s.lower()]
    if len(usb) == 1:
        return usb[0]
    if len(usb) > 1:
        print("More than one USB sound input — choose one with --device:", *usb, sep="\n  ", file=sys.stderr)
        sys.exit(2)
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


def serve_client(conn, addr, cmd):
    print(f"{addr[0]} connected", flush=True)
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    try:
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        conn.sendall(f"CATAUDIO 1 rate={RATE} ch=1 fmt=s16le\n".encode())
        while True:
            chunk = proc.stdout.read(480)     # 20 ms of audio
            if not chunk:
                print("the recorder stopped (is the sound card still plugged in?)", file=sys.stderr, flush=True)
                break
            conn.sendall(chunk)
    except (BrokenPipeError, ConnectionResetError, OSError):
        pass
    finally:
        proc.kill()
        proc.wait()
        conn.close()
        print(f"{addr[0]} disconnected", flush=True)


def main():
    ap = argparse.ArgumentParser(description="Streams the radio's receive audio to Cat Explorer.")
    ap.add_argument("--device", help="sound input to record (see --list)")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"TCP port (default {DEFAULT_PORT})")
    ap.add_argument("--list", action="store_true", help="list sound inputs and exit")
    a = ap.parse_args()

    if a.list:
        srcs = pulse_sources()
        print("\n".join(srcs) if srcs else "No PulseAudio/PipeWire inputs found; try `arecord -l` for ALSA devices.")
        return

    device = a.device or pick_device()
    cmd = recorder(device)
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", a.port))
    srv.listen(4)
    print(f"Streaming {device} at {RATE} Hz on port {a.port}. Ctrl+C to stop.", flush=True)
    try:
        while True:
            conn, addr = srv.accept()
            threading.Thread(target=serve_client, args=(conn, addr, cmd), daemon=True).start()
    except KeyboardInterrupt:
        print()


if __name__ == "__main__":
    main()
