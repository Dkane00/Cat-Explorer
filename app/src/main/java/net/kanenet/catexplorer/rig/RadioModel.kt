// Copyright (C) 2026 David Kane (W6MZT)
// SPDX-License-Identifier: GPL-3.0-or-later
package net.kanenet.catexplorer.rig

/**
 * Which radio the app is talking to, from the name the connection reports: FLRig's transceiver name, or what the USB probe
 * recognised. It decides which controls the screen offers.
 */
enum class RadioModel {
    /** Text CAT with full read-back: everything the app has. */
    FT891,
    /** Five-byte binary CAT (FT-857D, and the FT-897D which shares it): frequency, mode, VFO, split, PTT, meters, lock,
     *  clarifier, repeater and tones; no volume, gain, power, filters, DSP, memories or tuner over CAT. */
    FT857,
    /** Anything else FLRig is set up for: the general controls FLRig offers. */
    OTHER;

    companion object {
        fun of(xcvr: String): RadioModel = when {
            xcvr == "FT-891" -> FT891
            "857" in xcvr || "897" in xcvr -> FT857
            else -> OTHER
        }
    }
}
