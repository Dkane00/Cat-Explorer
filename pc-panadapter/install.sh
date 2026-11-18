#!/usr/bin/env bash
# Installs the Cat Explorer Panadapter (the PC side of the app's waterfall).
#
#   ./install.sh              install for you only (no password needed)
#   sudo ./install.sh         install for every user on this PC
#   ./install.sh --uninstall  remove it again (use sudo if it was installed with sudo)
#
# It copies the panadapter and its window to an install folder, puts the Cat Explorer icon into the desktop icon
# theme, adds "Cat Explorer Panadapter" to the Ham Radio folder of the start menu (creating that folder if
# your system doesn't have one), and adds a `cat-panadapter` command that opens the window.
set -euo pipefail

NAME=cat-explorer-panadapter
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ $EUID -eq 0 ]]; then
    SCOPE="every user"
    APP_DIR=/opt/$NAME
    SHARE=/usr/share
    BIN=/usr/local/bin
    MENUS=/etc/xdg/menus/applications-merged
else
    SCOPE="you ($USER)"
    APP_DIR="$HOME/.local/share/$NAME"
    SHARE="$HOME/.local/share"
    BIN="$HOME/.local/bin"
    MENUS="$HOME/.config/menus/applications-merged"
fi
ICONS="$SHARE/icons/hicolor"
DESKTOP="$SHARE/applications/$NAME.desktop"
DIRECTORY="$SHARE/desktop-directories/$NAME-hamradio.directory"
MENU_FILE="$MENUS/$NAME-hamradio.menu"
SIZES="16 22 24 32 48 64 128 256"

refresh_menus() {
    command -v update-desktop-database >/dev/null && update-desktop-database -q "$SHARE/applications" 2>/dev/null || true
    # the icon cache only exists for a theme folder with an index.theme (the system one); a personal folder needs none
    if [[ -f "$ICONS/index.theme" ]] && command -v gtk-update-icon-cache >/dev/null; then
        gtk-update-icon-cache -q -f -t "$ICONS" || true
    fi
    touch "$SHARE/applications" 2>/dev/null || true   # nudges the start menu to reload
}

stop_running_panadapter() {
    local pidfile="${XDG_RUNTIME_DIR:-/tmp}/cat-panadapter/panadapter.pid"
    if [[ -f $pidfile ]] && grep -q cat-panadapter-server "/proc/$(cat "$pidfile")/cmdline" 2>/dev/null; then
        echo "Stopping the running panadapter (start it again from the window afterwards)."
        kill "$(cat "$pidfile")" 2>/dev/null || true
        sleep 1
    fi
}

if [[ ${1:-} == --uninstall ]]; then
    echo "Removing the Cat Explorer Panadapter for $SCOPE…"
    stop_running_panadapter
    rm -rf "$APP_DIR"
    rm -f "$DESKTOP" "$DIRECTORY" "$MENU_FILE" "$BIN/cat-panadapter"
    for s in $SIZES; do rm -f "$ICONS/${s}x${s}/apps/$NAME.png"; done
    rm -f "$ICONS/scalable/apps/$NAME.svg"
    refresh_menus
    echo "Done. (Your saved settings in ~/.config/cat-explorer/ were left in place.)"
    exit 0
fi

echo "Installing the Cat Explorer Panadapter for $SCOPE…"

# ---- what it needs ----
missing=()
command -v python3 >/dev/null || missing+=(python3)
python3 -c 'import gi; gi.require_version("Gtk", "3.0"); from gi.repository import Gtk' 2>/dev/null || missing+=(python3-gi gir1.2-gtk-3.0)
command -v parec >/dev/null || command -v arecord >/dev/null || missing+=(pulseaudio-utils)
command -v pactl >/dev/null || missing+=(pulseaudio-utils)
if (( ${#missing[@]} )); then
    echo "Missing: ${missing[*]}"
    echo "Install them with:  sudo apt install $(printf '%s\n' "${missing[@]}" | sort -u | tr '\n' ' ')"
    exit 1
fi

# optional: the RTL-SDR wide panadapter needs numpy and RTL-SDR Blog's rtl_tcp
sdr_notes=()
python3 -c 'import numpy' 2>/dev/null || sdr_notes+=("numpy is missing:  sudo apt install python3-numpy")
if [[ ! -x $HOME/.local/opt/rtl-sdr-blog/bin/rtl_tcp && ! -x /opt/rtl-sdr-blog/bin/rtl_tcp && ! -x /usr/local/bin/rtl_tcp ]]; then
    sdr_notes+=("RTL-SDR Blog's driver isn't built yet (Ubuntu's rtl-sdr package can't do HF) — see \"RTL-SDR\" in the README")
fi

# ---- the earlier "Cat Explorer Audio Helper" name: stop it and remove it, so the two don't fight over the port ----
OLD=cat-explorer-audio-helper
old_pidfile="${XDG_RUNTIME_DIR:-/tmp}/cat-audio-helper/helper.pid"
if [[ -f $old_pidfile ]] && grep -q cat-audio-server "/proc/$(cat "$old_pidfile")/cmdline" 2>/dev/null; then
    echo "Stopping the old Cat Explorer Audio Helper (start the Panadapter from its window afterwards)."
    kill "$(cat "$old_pidfile")" 2>/dev/null || true
    sleep 1
fi
if [[ $EUID -eq 0 ]]; then OLD_DIR=/opt/$OLD; else OLD_DIR="$HOME/.local/share/$OLD"; fi
if [[ -d $OLD_DIR || -f $SHARE/applications/$OLD.desktop ]]; then
    echo "Removing the old Cat Explorer Audio Helper."
    rm -rf "$OLD_DIR"
    rm -f "$SHARE/applications/$OLD.desktop" "$SHARE/desktop-directories/$OLD-hamradio.directory" \
          "$MENUS/$OLD-hamradio.menu" "$BIN/cat-audio-helper" "$ICONS/scalable/apps/$OLD.svg"
    for s in $SIZES; do rm -f "$ICONS/${s}x${s}/apps/$OLD.png"; done
fi
rm -f "$HOME/.local/share/applications/cat-audio-helper.desktop"   # a launcher from the very first test version

# ---- the program ----
mkdir -p "$APP_DIR/icon"
install -m 755 "$SRC/cat-panadapter-server.py" "$SRC/cat-panadapter.py" "$APP_DIR/"
install -m 644 "$SRC/icon/$NAME.svg" "$APP_DIR/icon/"

mkdir -p "$BIN"
cat > "$BIN/cat-panadapter" <<EOF
#!/bin/sh
exec python3 "$APP_DIR/cat-panadapter.py" "\$@"
EOF
chmod 755 "$BIN/cat-panadapter"

# ---- the icon, in every size the desktop asks for ----
for s in $SIZES; do
    install -D -m 644 "$SRC/icon/png/$s/$NAME.png" "$ICONS/${s}x${s}/apps/$NAME.png"
done
install -D -m 644 "$SRC/icon/$NAME.svg" "$ICONS/scalable/apps/$NAME.svg"

# ---- the start-menu entry, in the Ham Radio folder ----
mkdir -p "$(dirname "$DESKTOP")"
cat > "$DESKTOP" <<EOF
[Desktop Entry]
Type=Application
Name=Cat Explorer Panadapter
GenericName=Receive-audio panadapter for Cat Explorer
Comment=Send the radio's receive audio to Cat Explorer on the tablet
Exec=python3 "$APP_DIR/cat-panadapter.py"
Icon=$NAME
Terminal=false
Categories=HamRadio;
Keywords=ham;radio;FT-891;waterfall;FLRig;audio;
StartupWMClass=$NAME
EOF

# Many ham setups already have a Ham Radio folder (Debian's extra-xdg-menus package, used by WSJT-X, JS8Call,
# fldigi…). Only add our own when no installed menu file sets one up.
if grep -qs '<Category>HamRadio</Category>' /etc/xdg/menus/applications-merged/*.menu "$HOME/.config/menus/applications-merged/"*.menu \
        && [[ ! -f $MENU_FILE ]]; then
    echo "Using your existing Ham Radio menu folder."
else
    mkdir -p "$(dirname "$DIRECTORY")" "$MENUS"
    cat > "$DIRECTORY" <<EOF
[Desktop Entry]
Type=Directory
Name=Ham Radio
Icon=$NAME
EOF
    cat > "$MENU_FILE" <<EOF
<!DOCTYPE Menu PUBLIC "-//freedesktop//DTD Menu 1.0//EN"
 "http://www.freedesktop.org/standards/menu-spec/1.0/menu.dtd">
<Menu>
  <Name>Applications</Name>
  <Menu>
    <Name>Hamradio</Name>
    <Directory>$(basename "$DIRECTORY")</Directory>
    <Include>
      <Category>HamRadio</Category>
    </Include>
  </Menu>
</Menu>
EOF
    echo "Added a Ham Radio folder to the start menu."
fi


refresh_menus

echo
echo "Installed:"
echo "  program     $APP_DIR"
echo "  menu entry  Ham Radio → Cat Explorer Panadapter"
echo "  command     cat-panadapter"
[[ :$PATH: == *":$BIN:"* ]] || echo "  ($BIN isn't on your PATH yet; it will be after you log out and back in)"
echo "Open it from the menu, choose the radio's sound card (or the RTL-SDR) and press Start."
if (( ${#sdr_notes[@]} )); then
    echo
    echo "Only needed for an RTL-SDR:"
    printf '  %s\n' "${sdr_notes[@]}"
fi
