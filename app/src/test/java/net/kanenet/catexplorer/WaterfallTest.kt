// Copyright (C) 2026 David Kane (W6MZT)
// SPDX-License-Identifier: GPL-3.0-or-later
package net.kanenet.catexplorer

import net.kanenet.catexplorer.audio.Spectrum
import net.kanenet.catexplorer.audio.TuneMath
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class WaterfallTest {
    @Test fun usbTapMakesTheTappedPointTheCarrier() {
        assertEquals(14_201_000L, TuneMath.retune(1000.0, 14_200_000, "USB", 700))
        assertEquals(14_201_000L, TuneMath.rfOf(1000.0, 14_200_000, "USB", 700))
    }

    @Test fun lsbAudioRunsBackwards() {
        assertEquals(7_199_000L, TuneMath.retune(1000.0, 7_200_000, "LSB", 700))
    }

    @Test fun cwTapMovesTheToneOntoThePitch() {
        // a tone at 900 Hz with a 700 Hz pitch is 200 Hz above the dial in CW-U, below it in CW-L
        assertEquals(7_030_200L, TuneMath.retune(900.0, 7_030_000, "CW-U", 700))
        assertEquals(7_029_800L, TuneMath.retune(900.0, 7_030_000, "CW-L", 700))
        assertEquals(7_030_000L, TuneMath.rfOf(700.0, 7_030_000, "CW-U", 700))
    }

    @Test fun dataTapMovesTheSignalTo1500() {
        assertEquals(14_074_500L, TuneMath.retune(2000.0, 14_074_000, "DATA-U", 700))
        assertEquals(null, TuneMath.retune(2000.0, 146_520_000, "FM", 700))
    }

    @Test fun fftFindsAToneInTheRightBin() {
        val n = 2048; val rate = 12000
        val ring = FloatArray(n) { (0.5 * sin(2 * PI * 1500.0 * it / rate)).toFloat() }
        val out = FloatArray(682)
        Spectrum(n).compute(ring, 0, out)
        val peak = out.indices.maxBy { out[it] }
        assertEquals(1500.0, peak * rate.toDouble() / n, 6.0)
    }

    @Test fun ft857ModeNamesTune() {
        assertEquals(net.kanenet.catexplorer.audio.Sense.UPPER, TuneMath.sense("CW"))
        assertEquals(net.kanenet.catexplorer.audio.Sense.LOWER, TuneMath.sense("CW-R"))
        assertEquals(net.kanenet.catexplorer.audio.Sense.NONE, TuneMath.sense("DIG"))
        // CW: a tone at the 700 Hz pitch is on the dial; a tone 300 Hz higher is a signal 300 Hz up (300 Hz down in CW-R)
        assertEquals(7_030_300L, TuneMath.retune(1000.0, 7_030_000, "CW", 700))
        assertEquals(7_029_700L, TuneMath.retune(1000.0, 7_030_000, "CW-R", 700))
    }
}
