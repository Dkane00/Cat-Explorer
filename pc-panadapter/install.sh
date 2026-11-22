#!/usr/bin/env bash
# Installs the Cat Explorer Panadapter (the PC side of the app's waterfall).
#
#   ./install.sh              install for you only (no password needed)
#   sudo ./install.sh         install for every user on this PC
#   ./install.sh --uninstall  remove it again (use sudo if it was installed with sudo)
#
#   --sdr-software            also install the SDR software (numpy, SoapySDR and its modules) without asking
#   --no-sdr-software         don't offer it
#
# The SDR software is optional: a sound card needs none of it. It's installed with apt (your password is asked for),
# and if SDRplay's own API is already installed, SoapySDR's SDRplay module is built too.
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
    # A personal icon folder normally has no cache, but another program may have left one there. A stale cache hides
    # the new icon from the panel, so rebuild any cache that exists, and always rebuild the system one.
    if [[ -f "$ICONS/index.theme" || -f "$ICONS/icon-theme.cache" ]] && command -v gtk-update-icon-cache >/dev/null; then
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

SDR_SOFTWARE=ask
UNINSTALL=
for arg in "$@"; do
    case $arg in
        --uninstall) UNINSTALL=1 ;;
        --sdr-software) SDR_SOFTWARE=yes ;;
        --no-sdr-software) SDR_SOFTWARE=no ;;
        *) echo "Unknown option: $arg (see the top of this script)"; exit 2 ;;
    esac
done

# SDRplay's API (their closed driver) installs this library; SoapySDR's SDRplay module needs it to build and run
sdrplay_api_present() { [[ -e /usr/local/lib/libsdrplay_api.so || -e /usr/lib/libsdrplay_api.so ]] || ldconfig -p 2>/dev/null | grep -q libsdrplay_api; }
soapy_has_sdrplay() { command -v SoapySDRUtil >/dev/null && SoapySDRUtil --info 2>/dev/null | grep -qi sdrplay; }

as_root() { if [[ $EUID -eq 0 ]]; then "$@"; else sudo "$@"; fi; }

install_sdr_software() {
    if ! command -v apt-get >/dev/null; then
        echo "This system has no apt; install numpy, SoapySDR and its modules with your package manager."
        return 1
    fi
    echo "Installing the SDR software (numpy, SoapySDR and its modules)…"
    as_root apt-get install -y python3-numpy python3-soapysdr soapysdr-tools soapysdr-module-all || return 1
    if sdrplay_api_present && ! soapy_has_sdrplay; then
        echo "SDRplay's API is installed: building SoapySDR's SDRplay module (SoapySDRPlay3)…"
        as_root apt-get install -y build-essential cmake git libsoapysdr-dev || return 1
        local src="$HOME/.local/src/SoapySDRPlay3"
        if [[ -d $src/.git ]]; then git -C "$src" pull -q || true; else git clone -q https://github.com/pothosware/SoapySDRPlay3 "$src"; fi
        mkdir -p "$src/build"
        (cd "$src/build" && cmake .. >/dev/null && make -j"$(nproc)" >/dev/null) || return 1
        (cd "$src/build" && as_root make install >/dev/null && as_root ldconfig) || return 1
        echo "SoapySDR's SDRplay module is installed."
    fi
}

if [[ -n $UNINSTALL ]]; then
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

# ---- optional SDR software: numpy (every SDR), SoapySDR and its modules (SDRplay, Airspy, HackRF, LimeSDR…) ----
need_sdr=
python3 -c 'import numpy' 2>/dev/null || need_sdr=1
python3 -c 'import SoapySDR' 2>/dev/null || need_sdr=1
command -v SoapySDRUtil >/dev/null || need_sdr=1
if command -v dpkg >/dev/null; then dpkg -s soapysdr-module-all >/dev/null 2>&1 || need_sdr=1; fi
sdrplay_api_present && ! soapy_has_sdrplay && need_sdr=1
if [[ -n $need_sdr && $SDR_SOFTWARE != no ]]; then
    if [[ $SDR_SOFTWARE == ask ]]; then
        if [[ -t 0 ]]; then
            echo
            echo "Using an SDR (RTL-SDR, SDRplay, Airspy, HackRF, LimeSDR…) for the wide panadapter needs some more software:"
            echo "numpy, SoapySDR and its SDR modules, from apt. A sound card needs none of it."
            read -r -p "Install the SDR software now? It asks for your password. [y/N] " reply
            [[ $reply == [yY]* ]] && SDR_SOFTWARE=yes
        fi
    fi
    if [[ $SDR_SOFTWARE == yes ]]; then
        install_sdr_software || echo "The SDR software didn't install completely; see the messages above. The panadapter itself is installed."
    fi
fi

# notes for whatever is still missing
sdr_notes=()
python3 -c 'import numpy' 2>/dev/null || sdr_notes+=("numpy:  sudo apt install python3-numpy")
if [[ ! -x $HOME/.local/opt/rtl-sdr-blog/bin/rtl_tcp && ! -x /opt/rtl-sdr-blog/bin/rtl_tcp && ! -x /usr/local/bin/rtl_tcp ]]; then
    sdr_notes+=("RTL-SDR: build RTL-SDR Blog's driver for HF (Ubuntu's rtl-sdr package can't do HF) — see \"RTL-SDR\" in the README")
fi
python3 -c 'import SoapySDR' 2>/dev/null || sdr_notes+=("Other SDRs:  sudo apt install python3-soapysdr soapysdr-module-all   (or run this again with --sdr-software)")
if ! sdrplay_api_present; then
    sdr_notes+=("SDRplay: install SDRplay's API from https://www.sdrplay.com/downloads/ (Linux, \"API\"), then run this again with --sdr-software")
elif ! soapy_has_sdrplay; then
    sdr_notes+=("SDRplay: its API is installed but SoapySDR's SDRplay module isn't — run this again with --sdr-software")
fi

echo
echo "Installed:"
echo "  program     $APP_DIR"
echo "  menu entry  Ham Radio → Cat Explorer Panadapter"
echo "  command     cat-panadapter"
[[ :$PATH: == *":$BIN:"* ]] || echo "  ($BIN isn't on your PATH yet; it will be after you log out and back in)"
echo "Open it from the menu, choose the radio's sound card (or an SDR) and press Start."
if (( ${#sdr_notes[@]} )); then
    echo
    echo "Only needed if you use an SDR:"
    printf '  %s\n' "${sdr_notes[@]}"
fi
