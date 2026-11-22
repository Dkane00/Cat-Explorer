# Cat Explorer

A touchscreen controller for the **Yaesu FT-891** and **FT-857D** transceivers, for Android tablets and phones. It shows the radio's frequency, mode, filter and meters live and lets you control the radio from the screen, either **through FLRig on a PC** or **directly over USB** with an OTG cable and no PC at all. Inspired by the creater of the CatTouch7USB, Chris Day Zl1CVD.  Use the hardware you already have lying around and put it to good use!

![Cat Explorer main screen, connected through FLRig, with the RTL-SDR waterfall](docs/screenshot-main.png)

**New to the app? Read the [User Guide (PDF)](docs/Cat-Explorer-User-Guide.pdf).** It covers connecting to the radio, every screen and button, the waterfall and all of the settings, with screenshots.

**Download:** the latest APK is on the [Releases](https://github.com/Dkane00/Cat-Explorer/releases) page.

> **Radio support: FT-891 (tested) and FT-857D (untested).**
> The app recognises which radio it's talking to. The **FT-891** gets everything (the app was built and tested on one). The **FT-857D** is supported from Yaesu's manual and tested only against a simulated radio, since I don't own one: it speaks a much older, smaller CAT language, so it gets the controls that language offers (see [FT-857D](#ft-857d)). The FT-897D uses the same CAT language and should work the same way, also untested. Other radios that FLRig supports get FLRig's general controls. Reports from FT-857D owners are very welcome.

## What it does

- **Live readout and control**: frequency (tap to type one in), VFO A/B, split, mode, band, filter width, IPO/ATT and dial lock.
- **Meters**: S, power, SWR, ALC, compression and drain current, as bars or analogue needle gauges, plus a rolling signal-history trace.
- **Sliders**: volume, RF gain, mic gain and TX power, with ◀ ▶ arrows for single steps.
- **DSP and IF popup** (tap the DSP graph): IF shift, width, noise blanker and reduction, contour, notch, APF and AGC. A mic EQ and speech-processor popup is available from the MIC EQ graph.
- **CW, FM and voice-memory panels** for keyer speed, pitch and break-in, repeater shift and tones, and voice-memory playback.
- **Memory channels**: a table of the radio's programmed channels. Tap a row to go to it, or add and edit channels (frequency, mode, tone, shift, name). The channel list is backed up to the app's files folder before the first write of a session.
- **Band button**: jumps to a phone calling frequency on each band and sets its usual mode (30 m goes to its digital frequency in DATA-U). The Bands tab lists the digital-mode calling frequencies.
- **Tune button**: runs the radio's own antenna tuner, or, for an external auto-tuner, sends a short low-power AM carrier and then puts your mode, width and power back.
- **Waterfall and panadapter**: with FLRig, a waterfall of the radio's receive audio, or with an SDR on the PC (RTL-SDR, or SDRplay, Airspy, HackRF and others through SoapySDR) a true wide panadapter of up to 200 kHz, fed by the small **Cat Explorer Panadapter** program on the FLRig PC. Over USB, plug an RTL-SDR, Airspy, Airspy HF+ or HackRF into the tablet beside the radio (through a USB hub) and the app runs it itself, no PC needed. Tap a signal to tune to it. US band and sub-band edges are marked, with license bars showing where Technician, General, Advanced or Extra may transmit (red where only a higher class may), and 29 colour themes including JS8Call's palettes. See [Waterfall](#waterfall).
- **Raw CAT console** for sending any CAT command and seeing the reply.
- **Themes**: dark or light, with every colour changeable.

## Two ways to connect

### Directly over USB (no PC)

Plug the radio's USB port into the tablet with a USB-C OTG cable (an FT-857D: its CAT jack, through a USB CAT cable such as a CT-62-style USB lead). In the app, enter the baud rate and tap **Connect over USB**. The app works out which radio it is (FT-891 or FT-857D); it tries the baud rate you entered first, then the other CAT rates (4800, 9600, 19200, 38400), so it still connects if the radio's CAT RATE menu is set to something else. Android will ask once for permission to use the radio's USB serial bridge.

- The app speaks CAT straight to the radio. FLRig and the PC are not involved.
- The radio can only be connected to one computer at a time, so with the tablet plugged in the PC cannot use the same USB port (a USB hub does not change this).
- Some things are not available in this mode: typed CW text (use the keyer memories 1 to 5), FLRig's user buttons, and the A and B VFO buttons (the radio has no CAT command to pick a VFO; use A/B to swap them instead).

### FT-857D

The FT-857D's CAT language is much smaller than the FT-891's: five-byte commands, and the radio can report only its frequency, mode, S-meter, PO meter, transmit/receive, split, a high-SWR warning and (through an undocumented read Hamlib also uses) which VFO is in use. So with an FT-857D:

- **Works, directly over USB:** frequency (typed, Band button, waterfall taps), mode (LSB, USB, CW, CW-R, AM, FM, FM-N, WFM, DIG, PKT), VFO A/B, split, dial lock, the clarifier (**CLAR**: on/off and ±9.99 kHz) and **RPT**: repeater shift and offset, CTCSS tone or DCS code and tone mode (off, Tone, TSQ, DCS). The S and power meters work, and SWR shows OK or HIGH. The waterfall (an SDR on the tablet, or through FLRig) works the same as with the FT-891.
- **Set-only:** lock, clarifier, repeater and tone settings can't be read back, so the app shows what it last sent. The VFO not in use isn't shown until the app has seen it. Setting it switches to that VFO, sets it and switches back.
- **Not possible over the FT-857D's CAT:** volume, RF gain, mic gain, transmit power, filter widths, DSP, memories, the tuner, CW keying, and copying VFO A to B. The app hides those controls, and the **Tune** button too, because the radio's power can't be lowered over CAT for a tuning carrier. The radio's undocumented memory-write commands are deliberately not used: a wrong guess there could overwrite its calibration.
- **Through FLRig:** set FLRig up for the FT-857D and the app uses FLRig's general controls (frequency, mode, PTT, meters, split, A/B).
- **CAT console:** type five-byte commands in hex, e.g. `00 00 00 00 03` (read frequency and mode); the reply comes back in hex.

### Through FLRig on a PC

Run FLRig on the computer the radio is plugged into. In FLRig turn on the XML-RPC server (Config → Setup → UI → Server), make sure it listens on the network and not only on localhost, and allow its port (default 12345) through the PC's firewall. In the app, enter the PC's address and port and tap **Connect**. The tablet and the PC must be on the same network or VPN.

### Waterfall

The radio screen has a **Waterfall** card that shows the radio's receive audio, about 0 to 4 kHz: everything inside your current filter. Tap a signal to tune to it. In CW the signal goes onto your CW pitch, in DATA it goes to 1500 Hz, and in SSB the point you tap becomes the carrier (tap the low edge of a USB voice signal). **Listen** also plays the audio on the tablet.

The FT-891 has no wide band-scope output, so this is a passband waterfall, not a wide panadapter. The audio comes from a small program on the FLRig PC, **Cat Explorer Panadapter**, that records the sound card wired to the radio's DATA jack.

**The easy way: install the Panadapter window.** From the Cat Explorer folder run

```bash
pc-panadapter/install.sh
```

That installs it just for you (no password). Run it with `sudo` instead to install it for every user on the PC, and with `--uninstall` to remove it. It adds **Cat Explorer Panadapter**, with the app's icon, to the **Ham Radio** folder of the start menu (creating that folder if your system doesn't have one), plus a `cat-panadapter` command. It needs Python 3, GTK 3 for Python (`python3-gi`) and `pulseaudio-utils`, which Mint, Ubuntu and Pop!_OS include; if one is missing, the installer tells you the `apt` command to get it.

Open it from the menu, pick the sound card from **Sound input** and press **Start**. The window shows whether the panadapter is running, the PC's addresses, which tablets are connected, and a live audio level that warns you if the audio is clipping. You can close the window: the panadapter keeps running in the background until you open the window again and press **Stop**. (Without installing, `python3 pc-panadapter/cat-panadapter.py` opens the same window.)

The desktop icon is made from the app's own launcher icon by `pc-panadapter/icon/make-icons.py`; run it again if the app icon changes.

**From a terminal** (no window):

```bash
python3 pc-panadapter/cat-panadapter-server.py --list     # show the sound inputs
python3 pc-panadapter/cat-panadapter-server.py            # picks the USB sound card by itself; or add --device NAME
```

The panadapter needs only Python 3 and `parec` (PulseAudio/PipeWire) or `arecord`. It listens on port 7366 (change it in the window or with `--port`, and in the app's Settings). It has no password, so keep it on your home network or VPN like FLRig's port. If the app or the window says the audio is too loud, turn the sound card's input level down on the PC.

#### RTL-SDR: a real wide panadapter

With an RTL-SDR on the FLRig PC, the Waterfall tab becomes a true panadapter: about 200 kHz of the band around your dial, with real frequencies, a red line at the dial, your receive filter shaded, **Span** from 200 kHz down to 10 kHz, and tap-to-tune (CW and DATA taps snap to the nearest signal; DATA puts the signal at 1500 Hz in your audio). The PC reads the radio's frequency from FLRig, keeps the SDR tuned near it (retuning by itself when you change band), does the FFT and sends the picture to the tablet. It pauses while you transmit.

**Protect the SDR.** It needs its own receive antenna or a T/R switch keyed by the radio (for example from the FT-891's TX GND line on the rear LINEAR jack). Never connect it to the radio's antenna: one transmission would destroy it. With a separate antenna near your HF antenna, add an RF limiter and keep the power down until you know what it picks up.

Setup, once:

1. Install the tools and numpy: `sudo apt install rtl-sdr python3-numpy`, then unplug and replug the dongle.
2. Build RTL-SDR Blog's driver into your home folder. Ubuntu's `rtl-sdr` 2.0.1 returns no data in the direct-sampling mode a V3 uses for HF; RTL-SDR Blog's version works and switches to it by itself below 24 MHz:

   ```bash
   git clone https://github.com/rtlsdrblog/rtl-sdr-blog ~/.local/src/rtl-sdr-blog
   cd ~/.local/src/rtl-sdr-blog && mkdir -p build && cd build
   cmake .. -DCMAKE_INSTALL_PREFIX=$HOME/.local/opt/rtl-sdr-blog -DINSTALL_UDEV_RULES=OFF -DDETACH_KERNEL_DRIVER=ON
   make -j4 && make install
   ```

3. In the Panadapter window choose **RTL-SDR — wide panadapter** as the source and press Start. **SDR gain** only matters above 24 MHz (10 m, 6 m); lower it if the window says the SDR is overloaded. **Correction** (ppm) fixes an SDR that shows signals off their real frequency. **Test on a fixed frequency** points it at, say, FM broadcast (98.0) to check it works without FLRig; taps do nothing then.

Below 24 MHz a V3 has no amplifier in front, so it needs a decent wire (5–10 m or more); a small whip shows little but interference there. On 10 m and 6 m it uses its normal tuner and a small antenna does much better.

#### Other SDRs on the PC (SDRplay, Airspy, HackRF, LimeSDR, PlutoSDR…)

Besides the RTL-SDR, the Panadapter can run any SDR that has a [SoapySDR](https://github.com/pothosware/SoapySDR) module, giving the same wide view on the tablet. `pc-panadapter/install.sh` offers to install SoapySDR and its modules for you (answer **y**; it asks for your password), or run it with `--sdr-software` to install them without the question (`--no-sdr-software` skips it). By hand:

```bash
sudo apt install python3-soapysdr python3-numpy soapysdr-tools soapysdr-module-all
```

Then plug the SDR in, press **Refresh** in the Panadapter window, choose it from **Source** (it shows as "… — wide panadapter (SoapySDR)") and press **Start**. **SDR gain** is the SDR's overall gain (Auto where the SDR has automatic gain; otherwise a middle setting), and **Correction** and **Test on a fixed frequency** work as for the RTL-SDR. From a terminal: `cat-panadapter-server.py --list`, then `--soapy driver=airspy` (or the `--device soapy:…` the list shows).

**SDRplay** (RSP1A, RSP1B, RSPdx, RSPduo and others) also needs SDRplay's own driver, which is free but closed and not in Ubuntu: download and run the Linux **API** installer from [sdrplay.com](https://www.sdrplay.com/downloads/), then build the SoapySDR module for it:

```bash
sudo apt install build-essential cmake libsoapysdr-dev
git clone https://github.com/pothosware/SoapySDRPlay3 ~/.local/src/SoapySDRPlay3
cd ~/.local/src/SoapySDRPlay3 && mkdir -p build && cd build && cmake .. && make && sudo make install && sudo ldconfig
```

If SDRplay's API is already installed when you run `pc-panadapter/install.sh --sdr-software`, the installer builds and installs SoapySDRPlay3 for you. `SoapySDRUtil --find` should then list the SDRplay. Each SDR's frequency range is its own: an Airspy R2 or Mini starts at 24 MHz (10 m and up, no HF without an upconverter), an Airspy HF+ covers 0–31 MHz and 60–260 MHz, and the window says when the dial is outside the SDR's range. The SoapySDR path itself has been tested end to end with an RTL-SDR Blog V3 through SoapySDR's RTL-SDR module (following FLRig across 10 m and 6 m, manual gain, and the same picture as the RTL-SDR source, not mirrored or shifted). The SDRs above are supported from their published drivers but **haven't been tried with Cat Explorer on real hardware**; reports are welcome. Some SDRs' automatic gain runs hot (the RTL-SDR's own AGC overloads through SoapySDR); if the window or the app says the SDR is overloaded, choose a fixed gain.

#### SDR on the tablet (USB connection, no PC)

Connected to the radio over USB, the tablet can run an SDR itself: plug the radio and the SDR into a powered USB-C hub on the tablet, connect with **Connect over USB**, and the **Waterfall** tab appears. The first time, Android asks whether Cat Explorer may use the SDR; tap **OK**. The view is the same wide panadapter as the PC's (up to 200 kHz around the dial, Span, tap-to-tune, band edges), and the app keeps the SDR tuned near the radio's dial, using direct sampling below 24 MHz like the PC. The audio waterfall isn't available this way, because the FT-891's USB port carries no audio.

The same antenna rules apply: the SDR needs its own receive antenna or a T/R switch, never the radio's antenna socket. The **Gain** button on the waterfall bar (also in **Settings → Waterfall → SDR gain**) opens **Auto** or **Manual**, with a slider through the gain steps your dongle reports. On an RTL-SDR Blog V3, gain only applies above 24 MHz (10 m, 6 m), because below that it samples HF directly; the setting says so while that's the case. SDRs with an HF front end use it on every band. Lower it if the waterfall says the SDR is overloaded. A USB hub with an Ethernet adapter or a charger can add steady carriers to the picture; if you see evenly spaced lines that move when the SDR retunes, try another hub or unplug its extras. Tested with an RTL-SDR Blog V3 and an FT-891 on a Galaxy Tab S6 Lite (Android 16).

SDRs the tablet can run, with their drivers built into the app:

| SDR | Covers | Gain | Tested |
|---|---|---|---|
| RTL-SDR (Blog V3/V4 and other RTL2832U dongles) | 500 kHz–1.766 GHz (HF by direct sampling on a V3) | the tuner's dB steps | yes |
| Airspy R2 / Mini | 24 MHz–1.8 GHz (10 m and up) | Auto (its AGC) or levels 0–21 | no |
| Airspy HF+ / HF+ Discovery | 0–31 MHz and 60–260 MHz | Auto (its AGC) or 0–48 dB | no |
| HackRF One | 1 MHz–6 GHz | total LNA+VGA 0–100 dB; Auto is a middle setting | no |

**SDRplay can't be run by the tablet**: its driver is closed and SDRplay doesn't offer it for Android apps. Plug an SDRplay into the FLRig PC and use the Panadapter there instead; if one is plugged into the tablet, the Waterfall tab says so.

The drivers (RTL-SDR Blog's `librtlsdr`, `libairspy`, `libairspyhf`, `libhackrf`, with `libusb`) are built into the app from `app/src/main/cpp` (see [License](#license)); building the app needs the Android NDK and CMake (`sdkmanager "ndk;27.3.13750724" "cmake;3.22.1"`).

#### Band edges and colours

Both waterfalls mark the US (FCC) amateur band plan: band edges as solid white lines with everything outside the band dimmed, and sub-band edges as dashed lines, each labelled with its frequency. License bars along the bottom of the waterfall show where a class may transmit (**Settings → Waterfall → License bars**: Off, Technician, General, Advanced, Extra, or all four, one row each): yellow = CW only, blue = CW & data, green = phone & CW, purple = all modes; red means only a higher license class may transmit there, so don't. It covers 160 m to 6 m plus 2 m and 70 cm (60 m as its five channels). It is a guide: check the current FCC rules before relying on it. Turn the edges off under **Settings → Waterfall → Band edges (US)**. The audio waterfall shows edges only in SSB, CW and DATA modes, where audio maps simply to RF.

**Settings → Waterfall → Colours** picks the waterfall's colour theme: the app's own **Cat Explorer** colours, or any of the 28 palettes that come with JS8Call (the same set WSJT-X has). Changing it recolours the whole picture at once, including what has already scrolled by.

## Safety

This app can **transmit**. The Tune button, the CW keyer memories and voice-message playback key the radio. Check that your antenna is connected and use low power when trying new things. The Tune carrier is limited to 5 to 10 W and stops by itself after one minute.

## Build

You need JDK 17, the Android SDK, and the Android NDK and CMake for the RTL-SDR driver (`sdkmanager "ndk;27.3.13750724" "cmake;3.22.1"`). `env.sh` shows the paths used here (adjust them for your machine).

```bash
source ./env.sh                 # JDK 17 + Gradle + Android SDK paths
./gradlew :app:assembleDebug    # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`CATEXPLORER_LIVE=127.0.0.1 ./gradlew :app:testDebugUnitTest` adds a read-only test against a running FLRig.

The user guide PDF is built from `docs/guide/make-guide.py` and the screenshots in `docs/guide/shots/` (needs Python with `reportlab` and `pillow`): `python3 docs/guide/make-guide.py`.

## Code layout

- `rig/RigClient.kt`: the interface both connections implement.
- `rig/FlrigClient.kt` and `rig/XmlRpc.kt`: the FLRig connection (a small XML-RPC client).
- `rig/UsbCatClient.kt` and `rig/UsbLink.kt`: the direct USB connection (CAT over the radio's CP2105 serial bridge, using the [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) library).
- `rig/YaesuDsp.kt`: FT-891 CAT command strings and reply parsers.
- `rig/Ft857Cat.kt` and `rig/Ft857Client.kt`: the FT-857D's five-byte CAT language and its direct connection; `rig/RadioModel.kt` decides which radio's controls to show.
- `audio/`: the waterfall: `AudioLink.kt` (the stream from the PC Panadapter: audio with Listen playback, or SDR spectrum lines) and `Waterfall.kt` (FFT, waterfall rows, the SDR view's `WideEngine`, tap-to-tune maths).
- `pc-panadapter/cat-panadapter-server.py`: the PC side of the waterfall; `pc-panadapter/cat-panadapter.py` is its window.
- `RigViewModel.kt`: the polling loop and command dispatch.
- `ui/`: connect screen, radio screen, meters, DSP and memory panels, settings.

## Status and limits

- FT-857D support is untested on a real radio: it was written from Yaesu's FT-857D manual (and Hamlib's driver for the VFO read) and tested against a simulated FT-857D.
- Tested on an FT-891 with a Samsung Galaxy Tab S6 Lite (Android 16). The FLRig path has had the most use. The USB path is newer: reading the radio, width changes, the A/B swap, Tune and the Band button have been tested over USB, while other controls have had less testing there.
- The radio's spectrum scope can't be shown because the FT-891 doesn't send scope data over CAT. The waterfall shows only the receive passband (see above).
- The radio can't store a filter width in a memory channel over CAT.
- Meter scales (especially SWR and S) are approximations.

## License

Cat Explorer is free software: you can redistribute it and/or modify it under the terms of the **GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version** — see [LICENSE](LICENSE). It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. Copyright (C) 2026 David Kane (W6MZT).

This covers the app, the PC Panadapter and the user guide. (Releases up to v1.1.0 were published under the MIT License; from v1.2.0 the project is GPL-3.0-or-later.)

It includes the work of others, all under licences compatible with GPL version 3. [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) lists each one with its copyright and licence; the app shows the same under **Settings → About**:

- **librtlsdr** from [RTL-SDR Blog](https://github.com/rtlsdrblog/rtl-sdr-blog) (GPL version 2 or later), with one small addition for Android marked in the source: `rtlsdr_open_fd()`.
- **libusb** 1.0.27 from [libusb.info](https://github.com/libusb/libusb) (LGPL version 2.1 or later), unchanged.
- **libairspy** and **libairspyhf** from [Airspy](https://github.com/airspy) (BSD), unchanged.
- **libhackrf** from [Great Scott Gadgets](https://github.com/greatscottgadgets/hackrf) (BSD), with one small addition for Android marked in the source: `hackrf_open_fd()`.
- **28 waterfall colour palettes** from [JS8Call](https://github.com/js8call/js8call) (GPL version 3), which inherited them from [WSJT-X](https://wsjt.sourceforge.io/). As JS8Call asks of works that use any part of it:

  > "The algorithms, source code, look-and-feel of WSJT-X and related programs, and protocol specifications for the modes FSK441, FT8, JT4, JT6M, JT9, JT65, JTMS, QRA64, ISCAT, MSK144 are Copyright (C) 2001-2018 by one or more of the following authors: Joseph Taylor, K1JT; Bill Somerville, G4WJS; Steven Franke, K9AN; Nico Palermo, IV3NWV; Greg Beam, KI7MT; Michael Black, W9MDB; Edson Pereira, PY2SDR; Philip Karn, KA9Q; and other members of the WSJT Development Group.
  > Further, the source code of JS8Call contains material Copyright (C) 2018-2019 by Jordan Sherer, KN4CRD."

  Cat Explorer is not affiliated with, supported by or endorsed by the JS8Call or WSJT-X developers.
- **usb-serial-for-android** (MIT), **AndroidX / Jetpack Compose** and **kotlinx.coroutines** (Apache 2.0).

The PC Panadapter uses Python, GTK, numpy and SoapySDR, installed separately from your system's packages. SDRplay's API is SDRplay's own closed driver, downloaded from [sdrplay.com](https://www.sdrplay.com/downloads/) under SDRplay's licence; it is not part of Cat Explorer.
