# Cat Explorer

A touchscreen controller for the **Yaesu FT-891** HF transceiver, for Android tablets and phones. It shows the radio's frequency, mode, filter and meters live and lets you control the radio from the screen, either **through FLRig on a PC** or **directly over USB** with an OTG cable and no PC at all. Inspired by the creater of the CatTouch7USB, Chris Day Zl1CVD.  Use the hardware you already have lying around and put it to good use!

![Cat Explorer main screen, connected to an FT-891 over USB](docs/screenshot-main.png)

**New to the app? Read the [User Guide (PDF)](docs/Cat-Explorer-User-Guide.pdf).** It covers connecting to the radio, every screen and button, and all of the settings, with screenshots.

> **Radio support: FT-891 only, for now.**
> Most of the CAT (Computer Aided Transceiver) commands in this app are written for the FT-891 and its CAT manual: the DSP and filter settings, meter scales, memory channels, filter-width table and the like. Other Yaesu radios use different commands and ranges, and other brands use different protocols altogether. The app has only been tested on an FT-891. Support for other radios may come but I am limited to the radios that I have to test.

## What it does

- **Live readout and control**: frequency (tap to type one in), VFO A/B, split, mode, band, filter width, IPO/ATT and dial lock.
- **Meters**: S, power, SWR, ALC, compression and drain current, as bars or analogue needle gauges, plus a rolling signal-history trace.
- **Sliders**: volume, RF gain, mic gain and TX power, with ◀ ▶ arrows for single steps.
- **DSP and IF popup** (tap the DSP graph): IF shift, width, noise blanker and reduction, contour, notch, APF and AGC. A mic EQ and speech-processor popup is available from the MIC EQ graph.
- **CW, FM and voice-memory panels** for keyer speed, pitch and break-in, repeater shift and tones, and voice-memory playback.
- **Memory channels**: a table of the radio's programmed channels. Tap a row to go to it, or add and edit channels (frequency, mode, tone, shift, name). The channel list is backed up to the app's files folder before the first write of a session.
- **Band button**: jumps to a phone calling frequency on each band and sets its usual mode (30 m goes to its digital frequency in DATA-U). The Bands tab lists the digital-mode calling frequencies.
- **Tune button**: runs the radio's own antenna tuner, or, for an external auto-tuner, sends a short low-power AM carrier and then puts your mode, width and power back.
- **Raw CAT console** for sending any CAT command and seeing the reply.
- **Themes**: dark or light, with every colour changeable.

## Two ways to connect

### Directly over USB (no PC)

Plug the radio's USB port into the tablet with a USB-C OTG cable. In the app, enter the baud rate and tap **Connect over USB**. The baud rate must match the radio's CAT RATE (menu 05-06). Android will ask once for permission to use the radio's USB serial bridge.

- The app speaks CAT straight to the radio. FLRig and the PC are not involved.
- The radio can only be connected to one computer at a time, so with the tablet plugged in the PC cannot use the same USB port (a USB hub does not change this).
- Some things are not available in this mode: typed CW text (use the keyer memories 1 to 5), FLRig's user buttons, and the A and B VFO buttons (the radio has no CAT command to pick a VFO; use A/B to swap them instead).

### Through FLRig on a PC

Run FLRig on the computer the radio is plugged into. In FLRig turn on the XML-RPC server (Config → Setup → UI → Server), make sure it listens on the network and not only on localhost, and allow its port (default 12345) through the PC's firewall. In the app, enter the PC's address and port and tap **Connect**. The tablet and the PC must be on the same network or VPN.

### Waterfall (FLRig connection only)

The radio screen has a **Waterfall** card that shows the radio's receive audio, about 0 to 4 kHz: everything inside your current filter. Tap a signal to tune to it. In CW the signal goes onto your CW pitch, in DATA it goes to 1500 Hz, and in SSB the point you tap becomes the carrier (tap the low edge of a USB voice signal). **Listen** also plays the audio on the tablet.

The FT-891 has no wide band-scope output, so this is a passband waterfall, not a wide panadapter. The audio comes from a small helper on the FLRig PC that records the sound card wired to the radio's DATA jack:

```bash
python3 pc-helper/cat-audio-server.py --list     # show the sound inputs
python3 pc-helper/cat-audio-server.py            # picks the USB sound card by itself; or add --device NAME
```

It needs only Python 3 and `parec` (PulseAudio/PipeWire) or `arecord`. It listens on port 7366 (change it with `--port`, and in the app's Settings). It has no password, so keep it on your home network or VPN like FLRig's port. If the card says "Audio too loud", turn the sound card's input level down on the PC. It is off in USB mode because the tablet can't get the radio's audio over the USB cable.

## Safety

This app can **transmit**. The Tune button, the CW keyer memories and voice-message playback key the radio. Check that your antenna is connected and use low power when trying new things. The Tune carrier is limited to 5 to 10 W and stops by itself after one minute.

## Build

You need JDK 17 and the Android SDK. `env.sh` shows the paths used here (adjust them for your machine).

```bash
source ./env.sh                 # JDK 17 + Gradle + Android SDK paths
./gradlew :app:assembleDebug    # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`CATEXPLORER_LIVE=127.0.0.1 ./gradlew :app:testDebugUnitTest` adds a read-only test against a running FLRig.

## Code layout

- `rig/RigClient.kt`: the interface both connections implement.
- `rig/FlrigClient.kt` and `rig/XmlRpc.kt`: the FLRig connection (a small XML-RPC client).
- `rig/UsbCatClient.kt` and `rig/UsbLink.kt`: the direct USB connection (CAT over the radio's CP2105 serial bridge, using the [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) library).
- `rig/YaesuDsp.kt`: FT-891 CAT command strings and reply parsers.
- `audio/`: the waterfall: `AudioLink.kt` (the stream from the PC helper, and Listen playback) and `Waterfall.kt` (FFT, waterfall rows, tap-to-tune maths).
- `pc-helper/cat-audio-server.py`: the PC side of the waterfall.
- `RigViewModel.kt`: the polling loop and command dispatch.
- `ui/`: connect screen, radio screen, meters, DSP and memory panels, settings.

## Status and limits

- Tested on an FT-891 with a Samsung Galaxy Tab S6 Lite (Android 16). The FLRig path has had the most use. The USB path is newer: reading the radio, width changes, the A/B swap, Tune and the Band button have been tested over USB, while other controls have had less testing there.
- The radio's spectrum scope can't be shown because the FT-891 doesn't send scope data over CAT. The waterfall shows only the receive passband (see above).
- The radio can't store a filter width in a memory channel over CAT.
- Meter scales (especially SWR and S) are approximations.

## License

Cat Explorer is open source under the [MIT License](LICENSE). Copyright (c) 2026 David Kane (W6MZT).
