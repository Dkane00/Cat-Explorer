# Third-party notices

Cat Explorer is Copyright (C) 2026 David Kane (W6MZT) and is free software under the GNU General Public License,
version 3 or (at your option) any later version — see [LICENSE](LICENSE). It is distributed WITHOUT ANY WARRANTY.

It includes or is built from the work of others, listed here with their licences. Every licence below is compatible
with GPL version 3, so the app (APK) and the PC Panadapter as a whole are distributed under GPL-3.0-or-later, with
complete source code in this repository. Each library's own licence file is kept beside its source.

## Built into the app (native code in `app/src/main/cpp`)

| Part | Source | Licence | Changes |
|---|---|---|---|
| librtlsdr (RTL-SDR Blog fork) | https://github.com/rtlsdrblog/rtl-sdr-blog (commit aed0ea1) | GPL-2.0-or-later | Added `rtlsdr_open_fd()` to open a device from an Android USB file descriptor (marked "Cat Explorer" in the source) |
| libusb 1.0.27 | https://github.com/libusb/libusb | LGPL-2.1-or-later | None |
| libairspy 1.0.12 | https://github.com/airspy/airspyone_host (commit fc61ab6) | BSD-3-Clause | None |
| libairspyhf 1.8.1 | https://github.com/airspy/airspyhf (commit 24fe8ff) | BSD-3-Clause | None |
| libhackrf | https://github.com/greatscottgadgets/hackrf (commit 8540a76) | BSD-3-Clause | Added `hackrf_open_fd()` (marked "Cat Explorer" in the source) |

librtlsdr: Copyright (C) 2012-2014 Steve Markgraf, Copyright (C) 2012 Dimitri Stolnikov, Copyright (C) 2013 Mauro Carvalho
Chehab, and the other authors named in its source files; RTL-SDR Blog's changes by RTL-SDR Blog. Licensed under the GNU
General Public License version 2 or later (`app/src/main/cpp/rtlsdr/COPYING`).

libusb: Copyright (C) 2001 Johannes Erdfelt, Copyright (C) 2007-2008 Daniel Drake, Copyright (C) 2012-2023 Nathan Hjelm,
and the other authors named in its source files. Licensed under the GNU Lesser General Public License version 2.1 or later
(`app/src/main/cpp/libusb/COPYING`).

### libairspy

```
Copyright (c) 2013, Michael Ossmann <mike@ossmann.com>
Copyright (c) 2012, Jared Boone <jared@sharebrained.com>
Copyright (c) 2014, Youssef Touil <youssef@airspy.com>
Copyright (c) 2014, Benjamin Vernoux <bvernoux@airspy.com>
Copyright (c) 2015, Ian Gilmour <ian@sdrsharp.com>

All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.
Neither the name of AirSpy nor the names of its contributors may be used to endorse or promote products derived from this software
without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### libairspyhf

```
Copyright (c) 2013-2023, Youssef Touil <youssef@airspy.com>
Copyright (c) 2013-2017, Ian Gilmour <ian@sdrsharp.com>
Copyright (c) 2013-2017, Benjamin Vernoux <bvernoux@airspy.com>
Copyright (c) 2013, Michael Ossmann <mike@ossmann.com>
Copyright (c) 2012, Jared Boone <jared@sharebrained.com>

All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.
Neither the name of Airspy HF+ nor the names of its contributors may be used to endorse or promote products derived from this software
without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### libhackrf

```
Copyright (c) 2012-2026 Great Scott Gadgets <info@greatscottgadgets.com>
Copyright (c) 2012, Jared Boone <jared@sharebrained.com>
Copyright (c) 2013, Benjamin Vernoux <titanmkd@gmail.com>

All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.
Neither the name of Great Scott Gadgets nor the names of its contributors may be used to endorse or promote products derived from this software
without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Waterfall colour palettes from JS8Call

28 of the waterfall colour themes (`app/src/main/java/net/kanenet/catexplorer/audio/WaterfallThemes.kt`) are the colour
stops of the palettes that come with JS8Call, which inherited them from WSJT-X. JS8Call is licensed under the GNU General
Public License version 3 (https://github.com/js8call/js8call); WSJT-X is by the WSJT Development Group
(https://wsjt.sourceforge.io/). As JS8Call asks of works that use any part of it under the GPL:

"The algorithms, source code, look-and-feel of WSJT-X and related programs, and protocol specifications for the modes FSK441, FT8, JT4, JT6M, JT9, JT65, JTMS, QRA64, ISCAT, MSK144 are Copyright (C) 2001-2018 by one or more of the following authors: Joseph Taylor, K1JT; Bill Somerville, G4WJS; Steven Franke, K9AN; Nico Palermo, IV3NWV; Greg Beam, KI7MT; Michael Black, W9MDB; Edson Pereira, PY2SDR; Philip Karn, KA9Q; and other members of the WSJT Development Group.
Further, the source code of JS8Call contains material Copyright (C) 2018-2019 by Jordan Sherer, KN4CRD."

Cat Explorer is not affiliated with, supported by or endorsed by the JS8Call or WSJT-X developers.

## Android libraries used by the app

| Library | Source | Licence |
|---|---|---|
| usb-serial-for-android 3.8.1 | https://github.com/mik3y/usb-serial-for-android | MIT (Copyright (c) 2011-2013 Google Inc., Copyright (c) 2013 Mike Wakerly, and contributors) |
| AndroidX / Jetpack Compose (ui, foundation, material3, activity, lifecycle, graphics-path) | https://developer.android.com/jetpack/androidx | Apache-2.0 |
| Kotlin standard library and kotlinx.coroutines | https://github.com/JetBrains/kotlin, https://github.com/Kotlin/kotlinx.coroutines | Apache-2.0 |

The Gradle wrapper (`gradlew`, `gradle/wrapper`) is Apache-2.0 (Gradle Inc.).

## Used by the PC Panadapter (installed separately, not included here)

Python 3 (PSF licence), PyGObject / GTK 3 (LGPL), numpy (BSD-3-Clause), SoapySDR and its modules (Boost Software License
1.0), RTL-SDR Blog's `rtl_tcp` (GPL-2.0-or-later), SoapySDRPlay3 (MIT). SDRplay's API is SDRplay Ltd's own closed driver,
downloaded by the user from https://www.sdrplay.com/downloads/ under SDRplay's licence; it is not part of Cat Explorer.

## The user guide

The PDF user guide is built with ReportLab (BSD) and embeds subsets of the Liberation fonts (SIL Open Font License 1.1).

## Band plan

The US band edges and license-class privileges are facts from the FCC rules (47 CFR Part 97, section 97.301 and
following), presented as a guide only.
