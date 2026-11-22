package net.kanenet.catexplorer

import net.kanenet.catexplorer.audio.SdrKind
import net.kanenet.catexplorer.audio.TabletSdr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabletSdrTest {
    private val rtlBin = 1_024_000f / 8192   // 125 Hz

    @Test fun theSdrSitsBelowTheDialAndRetunesOnlyWhenItWanders() {
        assertEquals(6_860_000, TabletSdr.centreFor(7_110_000))
        assertTrue(TabletSdr.inRange(7_110_000, 6_860_000))
        assertTrue(TabletSdr.inRange(7_200_000, 6_860_000))       // 340 kHz above: still fine
        assertFalse(TabletSdr.inRange(7_250_000, 6_860_000))      // 390 kHz: retune
        assertFalse(TabletSdr.inRange(14_074_000, 6_860_000))     // band change
        assertEquals(500_000, TabletSdr.centreFor(600_000))       // never below 500 kHz
        assertTrue(TabletSdr.inRange(600_000, 500_000))           // and no endless retuning there
    }

    @Test fun anSdrThatStartsAt24MHzStaysInsideItsRange() {
        val low = SdrKind.AIRSPY.coverage.first().first
        assertEquals(24_000_000, TabletSdr.centreFor(24_100_000, low))
        assertTrue(TabletSdr.inRange(24_100_000, 24_000_000, low))
    }

    @Test fun aFrameIsTheSpanAroundTheDial() {
        val power = FloatArray(8192) { 1f }
        // a signal 20 kHz above a dial that is 250 kHz above the SDR's centre
        val bin = 8192 / 2 + Math.round((250_000 + 20_000) / rtlBin)
        power[bin] = 1000f
        val f = TabletSdr.frame(power, rtlBin, 6_860_000, 7_110_000, overload = false)!!
        assertEquals(7_110_000L, f.centreHz)
        assertEquals(1600, f.db.size)                                // ±100 kHz in 125 Hz bins
        val peak = f.db.indices.maxBy { f.db[it] }
        assertEquals(20_000.0, (peak - f.db.size / 2) * rtlBin.toDouble(), 125.0)
        assertEquals(30f, f.db[peak], 0.01f)                         // 10·log10(1000)
    }

    @Test fun otherSdrsBinsWork() {
        // an Airspy R2 at 2.5 MS/s with a 16384-point FFT: 152.6 Hz bins
        val bin = 2_500_000f / 16384
        val f = TabletSdr.frame(FloatArray(16384) { 1f }, bin, 50_000_000, 50_250_000, overload = false)!!
        assertEquals(bin, f.binHz, 0.01f)
        assertEquals(2 * (100_000 / bin).toInt(), f.db.size)
    }

    @Test fun noFrameWhenTheDialIsOutsideTheSdrsView() {
        assertNull(TabletSdr.frame(FloatArray(8192), rtlBin, 6_860_000, 7_400_000, overload = false))
    }

    @Test fun coverageAndGainLabels() {
        assertTrue(SdrKind.RTL.covers(7_110_000))
        assertFalse(SdrKind.AIRSPY.covers(7_110_000))
        assertTrue(SdrKind.AIRSPY.covers(50_125_000))
        assertTrue(SdrKind.AIRSPY_HF.covers(28_074_000))
        assertFalse(SdrKind.AIRSPY_HF.covers(50_125_000))
        assertTrue(SdrKind.HACKRF.covers(7_110_000))
        assertEquals("Level 15", SdrKind.AIRSPY.formatGain(150))
        assertEquals("25.4 dB", SdrKind.RTL.formatGain(254))
        assertEquals("Auto", SdrKind.HACKRF.formatGain(-1))
        assertEquals("24 MHz–1.8 GHz", SdrKind.AIRSPY.coverageText())
    }
}
