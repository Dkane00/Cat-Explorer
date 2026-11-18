package net.kanenet.catexplorer

import net.kanenet.catexplorer.audio.BandPlan
import net.kanenet.catexplorer.audio.BandPlan.License
import net.kanenet.catexplorer.audio.BandPlan.Use
import net.kanenet.catexplorer.audio.WaterfallThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BandPlanTest {
    @Test fun fortyMetrePrivileges() {
        assertEquals(Use.DATA, BandPlan.useAt(7_010_000, License.EXTRA))
        assertNull(BandPlan.useAt(7_010_000, License.GENERAL))           // Extra-only CW/data
        assertEquals(Use.DATA, BandPlan.useAt(7_074_000, License.GENERAL))
        assertEquals(Use.CW, BandPlan.useAt(7_030_000, License.TECHNICIAN))
        assertNull(BandPlan.useAt(7_150_000, License.GENERAL))           // Advanced/Extra phone
        assertEquals(Use.PHONE, BandPlan.useAt(7_150_000, License.ADVANCED))
        assertEquals(Use.PHONE, BandPlan.useAt(7_200_000, License.GENERAL))
        assertNull(BandPlan.useAt(7_350_000, License.EXTRA))              // out of band
    }

    @Test fun tenMetresTechnicianPhone() {
        assertEquals(Use.PHONE, BandPlan.useAt(28_400_000, License.TECHNICIAN))
        assertNull(BandPlan.useAt(28_600_000, License.TECHNICIAN))
        assertEquals(Use.PHONE, BandPlan.useAt(28_600_000, License.GENERAL))
    }

    @Test fun edgesAroundTwentyMetrePhone() {
        val edges = BandPlan.edgesIn(14_100_000.0, 14_300_000.0).map { it.hz }
        assertEquals(listOf(14_150_000L, 14_175_000L, 14_225_000L), edges)
        val band = BandPlan.edgesIn(13_950_000.0, 14_030_000.0)
        assertTrue(band.first { it.hz == 14_000_000L }.bandEdge)
        assertTrue(!band.first { it.hz == 14_025_000L }.bandEdge)
    }

    @Test fun themesHave256OpaqueColours() {
        assertEquals(29, WaterfallThemes.all.size)
        for (t in WaterfallThemes.all) {
            assertEquals(t.name, 256, t.colors.size)
            assertTrue(t.name, t.colors.all { (it ushr 24) == 0xFF })
        }
        // the app's own theme keeps the old colours: black at the bottom, white at the top
        val c = WaterfallThemes.named(WaterfallThemes.DEFAULT).colors
        assertEquals(0xFF000000.toInt(), c[0]); assertEquals(0xFFFFFFFF.toInt(), c[255])
        assertEquals(WaterfallThemes.DEFAULT, WaterfallThemes.named("no such theme").name)
    }
}
