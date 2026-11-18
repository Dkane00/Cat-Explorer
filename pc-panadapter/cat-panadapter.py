#!/usr/bin/env python3
"""
Window for the Cat Explorer Panadapter.

Pick the sound card the radio is wired to, then Start. The panadapter (cat-panadapter-server.py) runs as its own
background program, so this window can be closed and the audio keeps flowing to the tablet; open the window
again any time to see its status or to stop it.

    python3 cat-panadapter.py     # open the window
    ./install.sh                        # install it, with a Ham Radio start-menu entry and the app's icon

Needs GTK 3 for Python (python3-gi), which Linux Mint, Ubuntu and Pop!_OS include.
"""
import importlib.util
import json
import os
import signal
import subprocess
import sys
import time

import gi
gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
from gi.repository import Gdk, GLib, Gtk  # noqa: E402

ICON_NAME = "cat-explorer-panadapter"
# the window's class name, so the taskbar matches it to the menu entry (StartupWMClass) and shows its icon
GLib.set_prgname(ICON_NAME)
Gdk.set_program_class(ICON_NAME)

HERE = os.path.dirname(os.path.abspath(__file__))
SERVER = os.path.join(HERE, "cat-panadapter-server.py")
CONFIG = os.path.join(os.path.expanduser("~/.config/cat-explorer"), "panadapter.json")
LOG = os.path.join(os.path.expanduser("~/.cache"), "cat-panadapter.log")

# share the file locations and the sound-card listing with the panadapter itself
_spec = importlib.util.spec_from_file_location("cat_audio_server", SERVER)
server = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(server)


# ---------------------------------------------------------------- background process control

def server_pid():
    """PID of the running panadapter, or None. Checks the process really is the panadapter, not a reused PID."""
    try:
        pid = int(open(server.PID_FILE).read().strip())
        cmd = open(f"/proc/{pid}/cmdline", "rb").read().decode(errors="replace")
    except (OSError, ValueError):
        return None
    return pid if "cat-panadapter-server" in cmd else None


def read_status():
    try:
        return json.load(open(server.STATUS_FILE))
    except (OSError, ValueError):
        return None


def start_server(device, port):
    os.makedirs(os.path.dirname(LOG), exist_ok=True)
    log = open(LOG, "w")
    # a new session: the panadapter doesn't belong to this window, so closing the window leaves it running
    subprocess.Popen(
        [sys.executable, SERVER, "--device", device, "--port", str(port)],
        stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, start_new_session=True,
    )


def stop_server():
    pid = server_pid()
    if pid is None:
        return
    os.kill(pid, signal.SIGTERM)
    for _ in range(20):
        time.sleep(0.1)
        if server_pid() is None:
            return
    try:
        os.kill(pid, signal.SIGKILL)
    except OSError:
        pass


def log_tail(lines=4):
    try:
        return "".join(open(LOG).readlines()[-lines:]).strip()
    except OSError:
        return ""


def this_pc_addresses():
    out = subprocess.run(["hostname", "-I"], capture_output=True, text=True).stdout.split()
    return [a for a in out if ":" not in a and not a.startswith("192.168.122.")]   # skip IPv6 and the libvirt bridge


def load_config():
    # falls back to the file saved under the program's earlier name, "audio helper"
    for path in (CONFIG, os.path.join(os.path.dirname(CONFIG), "audio-helper.json")):
        try:
            return json.load(open(path))
        except (OSError, ValueError):
            pass
    return {}


def save_config(cfg):
    os.makedirs(os.path.dirname(CONFIG), exist_ok=True)
    json.dump(cfg, open(CONFIG, "w"))


# ---------------------------------------------------------------- window

class PanadapterWindow(Gtk.Window):
    def __init__(self):
        super().__init__(title="Cat Explorer Panadapter")
        self.set_default_size(560, -1)
        self.set_border_width(16)
        if Gtk.IconTheme.get_default().has_icon(ICON_NAME):
            self.set_icon_name(ICON_NAME)
        else:   # not installed yet: use the copy next to this script
            svg = os.path.join(HERE, "icon", ICON_NAME + ".svg")
            if os.path.exists(svg):
                self.set_icon_from_file(svg)
        self.cfg = load_config()
        self.starting_since = 0.0

        grid = Gtk.Grid(column_spacing=12, row_spacing=10)
        self.add(grid)
        row = 0

        intro = Gtk.Label(xalign=0, wrap=True, max_width_chars=60)
        intro.set_markup("Sends the radio's receive audio to <b>Cat Explorer</b> on the tablet for the waterfall and Listen.")
        grid.attach(intro, 0, row, 3, 1); row += 1

        grid.attach(Gtk.Label(label="Sound input", xalign=1), 0, row, 1, 1)
        self.device = Gtk.ComboBoxText(hexpand=True)
        grid.attach(self.device, 1, row, 1, 1)
        self.refresh_btn = Gtk.Button(label="Refresh")
        self.refresh_btn.set_tooltip_text("Look for sound cards again (after plugging one in)")
        self.refresh_btn.connect("clicked", lambda *_: self.fill_devices())
        grid.attach(self.refresh_btn, 2, row, 1, 1); row += 1

        grid.attach(Gtk.Label(label="Port", xalign=1), 0, row, 1, 1)
        self.port = Gtk.SpinButton.new_with_range(1024, 65535, 1)
        self.port.set_value(self.cfg.get("port", server.DEFAULT_PORT))
        self.port.set_halign(Gtk.Align.START)
        grid.attach(self.port, 1, row, 1, 1)
        port_note = Gtk.Label(label="must match Settings → Waterfall in the app", xalign=0)
        port_note.get_style_context().add_class("dim-label")
        grid.attach(port_note, 2, row, 1, 1); row += 1

        buttons = Gtk.Box(spacing=8)
        self.start_btn = Gtk.Button(label="Start")
        self.start_btn.get_style_context().add_class("suggested-action")
        self.start_btn.connect("clicked", self.on_start)
        self.stop_btn = Gtk.Button(label="Stop")
        self.stop_btn.get_style_context().add_class("destructive-action")
        self.stop_btn.connect("clicked", self.on_stop)
        buttons.pack_start(self.start_btn, False, False, 0)
        buttons.pack_start(self.stop_btn, False, False, 0)
        grid.attach(buttons, 1, row, 2, 1); row += 1

        grid.attach(Gtk.Separator(), 0, row, 3, 1); row += 1

        self.state = Gtk.Label(xalign=0)
        grid.attach(self.state, 0, row, 3, 1); row += 1

        self.details = Gtk.Label(xalign=0, wrap=True, max_width_chars=70, selectable=True)
        grid.attach(self.details, 0, row, 3, 1); row += 1

        grid.attach(Gtk.Label(label="Audio level", xalign=1), 0, row, 1, 1)
        self.level = Gtk.LevelBar(min_value=0, max_value=1, hexpand=True)
        self.level.add_offset_value(Gtk.LEVEL_BAR_OFFSET_LOW, 0.5)
        self.level.add_offset_value(Gtk.LEVEL_BAR_OFFSET_HIGH, 0.9)
        self.level.add_offset_value(Gtk.LEVEL_BAR_OFFSET_FULL, 0.99)
        grid.attach(self.level, 1, row, 2, 1); row += 1

        self.level_note = Gtk.Label(xalign=0, wrap=True, max_width_chars=70)
        grid.attach(self.level_note, 0, row, 3, 1); row += 1

        foot = Gtk.Label(xalign=0, wrap=True, max_width_chars=70,
                         label="You can close this window: the panadapter keeps running in the background until you press Stop.")
        foot.get_style_context().add_class("dim-label")
        grid.attach(foot, 0, row, 3, 1)

        self.fill_devices()
        self.refresh()
        GLib.timeout_add(500, self.refresh)

    def fill_devices(self):
        status = read_status()
        want = (status or {}).get("device") or self.device.get_active_id() or self.cfg.get("device")
        self.device.remove_all()
        inputs = server.sound_inputs()
        for name, desc in inputs:
            self.device.append(name, desc)
        if want and want in [n for n, _ in inputs]:
            self.device.set_active_id(want)
        elif inputs:
            usb = [n for n, _ in inputs if "usb" in n.lower()]
            self.device.set_active_id(usb[0] if usb else inputs[0][0])

    def on_start(self, *_):
        device = self.device.get_active_id()
        if not device:
            self.details.set_text("Choose a sound input first.")
            return
        port = int(self.port.get_value())
        self.cfg.update(device=device, port=port)
        save_config(self.cfg)
        start_server(device, port)
        self.starting_since = time.time()
        self.refresh()

    def on_stop(self, *_):
        stop_server()
        self.refresh()

    def refresh(self):
        pid = server_pid()
        status = read_status() if pid else None
        running = pid is not None
        starting = not running and time.time() - self.starting_since < 3

        self.start_btn.set_sensitive(not running and not starting)
        self.stop_btn.set_sensitive(running)
        for w in (self.device, self.port, self.refresh_btn):
            w.set_sensitive(not running and not starting)

        if starting:
            self.state.set_markup("<b>Starting…</b>")
            self.details.set_text("")
        elif not running:
            self.state.set_markup("<span foreground='#888'>●</span>  <b>Stopped</b>")
            tail = log_tail()
            failed = self.starting_since and tail and "Streaming" not in tail
            self.details.set_text(("It stopped straight away:\n" + tail) if failed else
                                  "Press Start to begin sending audio to the tablet.")
        else:
            stale = status is None or time.time() - status.get("updated", 0) > 3
            if status and status.get("device") and self.device.get_active_id() != status["device"]:
                self.device.set_active_id(status["device"])
            if status:
                self.port.set_value(status.get("port", self.port.get_value()))
            if stale:
                self.state.set_markup("<span foreground='#e0a000'>●</span>  <b>Running, but not answering</b>")
            elif status.get("error"):
                self.state.set_markup("<span foreground='#e0a000'>●</span>  <b>Running — problem with the sound card</b>")
            else:
                self.state.set_markup("<span foreground='#2ec27e'>●</span>  <b>Running</b>")
            lines = []
            if status:
                addrs = ", ".join(this_pc_addresses()) or "this PC's address"
                tablets = status.get("clients") or []
                up = int(time.time() - status.get("started", time.time()))
                lines.append(f"Port {status['port']} on {addrs}   ·   running {up // 3600}h {up // 60 % 60}m   ·   PID {pid}")
                lines.append("Tablets connected: " + (", ".join(c.split(":")[0] for c in tablets) if tablets else "none yet"))
                if status.get("error"):
                    lines.append(status["error"])
            self.details.set_text("\n".join(lines))

        peak = (status or {}).get("peak", 0.0) if running else 0.0
        clip = (status or {}).get("clip", 0.0) if running else 0.0
        self.level.set_value(min(peak, 1.0))
        if not running:
            self.level_note.set_text("")
        elif clip > 0.001:
            self.level_note.set_markup("<span foreground='#e01b24'><b>Too loud</b> — the audio is clipping. Turn the "
                                       "sound card's input level down (Sound settings → Input) until this goes away.</span>")
        elif peak < 0.02:
            self.level_note.set_text("Very quiet — check the radio is on and the cable is in the DATA jack.")
        else:
            self.level_note.set_text("Level OK.")
        return True   # keep the timer going


def main():
    win = PanadapterWindow()
    win.connect("destroy", Gtk.main_quit)
    win.show_all()
    Gtk.main()


if __name__ == "__main__":
    main()
