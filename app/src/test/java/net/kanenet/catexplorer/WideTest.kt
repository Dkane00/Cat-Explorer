package net.kanenet.catexplorer

import net.kanenet.catexplorer.audio.SpecFrame
import net.kanenet.catexplorer.audio.TuneMath
import net.kanenet.catexplorer.audio.WideEngine
import net.kanenet.catexplorer.ui.niceStep
import org.junit.Assert.assertEquals
import org.junit.Test

class WideTest {
    @Test fun wideTapTunesToTheSignal() {
        assertEquals(7_030_120L, TuneMath.wideRetune(7_030_123.0, "CW-U"))
        assertEquals(14_250_000L, TuneMath.wideRetune(14_250_004.0, "USB"))
        // DATA: the signal lands at 1500 Hz in the audio
        assertEquals(14_074_500L, TuneMath.wideRetune(14_076_000.0, "DATA-U"))
        assertEquals(7_075_500L, TuneMath.wideRetune(7_074_000.0, "DATA-L"))
    }

    @Test fun gridStepsAreRound() {
        assertEquals(25_000.0, niceStep(200_000 / 8.0), 0.0)
        assertEquals(2_500.0, niceStep(20_000 / 8.0), 0.0)
        assertEquals(2_000.0, niceStep(1_300.0), 0.0)
    }

    /** 1600 bins of 125 Hz around the dial, flat except one strong signal [offsetHz] from the dial. */
    private fun frame(dial: Long, offsetHz: Int): SpecFrame {
        val db = FloatArray(1600) { 30f }
        db[800 + offsetHz / 125] = 80f
        return SpecFrame(dial, 125f, db, false)
    }

    @Test fun aSignalLandsInItsColumnAndStaysThereWhenTheDialMoves() {
        val w = WideEngine(width = 1000, height = 10)   // 200 kHz span -> 200 Hz per pixel
        w.add(frame(7_100_000, 20_000), frozen = false)
        val col = w.peakNear(500, 500)
        assertEquals(7_120_000.0, w.rfAt(col.toDouble()), 200.0)
        // dial up 10 kHz: the same signal is now 10 kHz from the dial; the old row must have slid with it
        w.add(frame(7_110_000, 10_000), frozen = false)
        val col2 = w.peakNear(500, 500)
        assertEquals(7_120_000.0, w.rfAt(col2.toDouble()), 200.0)
        val row1 = w.levels.copyOfRange(1000, 2000)        // the older row, shifted
        val brightest = row1.indices.maxBy { row1[it].toInt() and 0xFF }
        assertEquals(col2.toDouble(), brightest.toDouble(), 1.0)
    }

    @Test fun refinePeakFindsTheSignalBetweenBins() {
        val w = WideEngine(width = 1000, height = 10)
        // a signal 20,060 Hz above the dial: between bins 160 (20,000) and 161 (20,125), nearer 160
        val db = FloatArray(1600) { 30f }
        db[960] = 80f; db[961] = 74f; db[959] = 66f
        w.add(SpecFrame(7_100_000, 125f, db, false), frozen = false)
        val rf = w.refinePeak(7_120_250.0, 300.0)   // tapped 250 Hz off
        assertEquals(7_120_030.0, rf, 40.0)
    }
}
