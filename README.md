# Cat Explorer

Android app (phone/tablet) that talks to **FLRig** over the network (XML-RPC, default port 12345)
to show radio readouts and send CAT commands — modelled on the CatTouch7USB touchscreen controller.

## Build
```bash
source ./env.sh                 # JDK 17 + Gradle + Android SDK paths (installed under ~/tools and ~/Android)
./gradlew :app:assembleDebug    # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
CATEXPLORER_LIVE=127.0.0.1 ./gradlew :app:testDebugUnitTest   # adds a read-only test against a running FLRig
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## FLRig setup
FLRig's XML-RPC server must be reachable from the tablet: same Wi-Fi/VPN, PC firewall open on 12345.
Enter the PC's IP in the app's connect screen.

## Layout
- `rig/XmlRpc.kt` – tiny XML-RPC client (+ `system.multicall` batching)
- `rig/FlrigClient.kt` – every FLRig call the app uses
- `RigViewModel.kt` – polling loop (~8 Hz) and command dispatch
- `ui/` – connect screen, radio screen, bar meters

## Features
Frequency/VFO/split/mode/bandwidth control, S/PWR/SWR meters, rolling RX/TX signal-history trace,
AF/RF/MIC/power sliders, press-and-hold PTT, Tune, band shortcuts, CW keyer memories (via FLRig CWIO),
FLRig user-button 1–24 launcher, raw CAT console.

## Not yet / limits
No spectrum/waterfall (FLRig exposes no scope data), no memory channels, no SWR sweep.
SWR/S/power scales are approximations of FLRig's 0–100 meter values.
