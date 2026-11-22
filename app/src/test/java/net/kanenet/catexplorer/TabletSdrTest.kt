package net.kanenet.catexplorer

import net.kanenet.catexplorer.audio.TabletSdr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabletSdrTest {
    @Test fun theSdrSitsBelowTheDialAndRetunesOnlyWhenItWanders() {
        assertEquals(6_860_000, TabletSdr.centreFor(7_110_000))
        assertTrue(TabletSdr.inRange(7_110_000, 6_860_000))
        assertTrue(TabletSdr.inRange(7_200_000, 6_860_000))       // 340 kHz above: still fine
        assertFalse(TabletSdr.inRange(7_250_000, 6_860_000))      // 390 kHz: retune
        assertFalse(TabletSdr.inRange(14_074_000, 6_860_000))     // band change
        assertEquals(500_000, TabletSdr.centreFor(600_000))       // never below 500 kHz
    }

    @Test fun aFrameIsTheSpanAroundTheDial() {
        val power = FloatArray(TabletSdr.FFT) { 1f }
        // a signal 20 kHz above a dial that is 250 kHz above the SDR's centre
        val bin = TabletSdr.FFT / 2 + Math.round((250_000 + 20_000) / TabletSdr.BIN_HZ)
        power[bin] = 1000f
        val f = TabletSdr.frame(power, 6_860_000, 7_110_000, overload = false)!!
        assertEquals(7_110_000L, f.centreHz)
        assertEquals(1600, f.db.size)                                // ±100 kHz in 125 Hz bins
        val peak = f.db.indices.maxBy { f.db[it] }
        assertEquals(20_000.0, (peak - f.db.size / 2) * TabletSdr.BIN_HZ.toDouble(), 125.0)
        assertEquals(30f, f.db[peak], 0.01f)                         // 10·log10(1000)
    }

    @Test fun noFrameWhenTheDialIsOutsideTheSdrsView() {
        assertNull(TabletSdr.frame(FloatArray(TabletSdr.FFT), 6_860_000, 7_400_000, overload = false))
    }
}
