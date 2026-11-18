package net.kanenet.catexplorer

import net.kanenet.catexplorer.audio.AudioLink
import net.kanenet.catexplorer.audio.SpecFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Feeds the app's reader three real compressed spectrum lines captured from the PC Panadapter (catspec2-sample.bin) and
 * checks it decodes them to the same levels Python did (catspec2-expected.txt), so the two sides agree on the format.
 */
class SpecStreamTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    @Test fun readsTheCompressedFormatThePanadapterSends() {
        val blob = resource("catspec2-sample.bin")
        val expected = String(resource("catspec2-expected.txt")).trim().lines().map { line ->
            val (centre, binHz, n, db) = line.split(" ")
            Triple(centre.toLong(), binHz.toFloat() to n.toInt(), db.split(",").map { it.toFloat() })
        }
        val server = ServerSocket(0)
        thread { server.accept().use { it.getOutputStream().write(blob); Thread.sleep(500) } }
        val link = AudioLink("127.0.0.1", server.localPort)
        link.open()
        assertTrue(link.spectrum)
        val got = mutableListOf<SpecFrame>()
        try {
            link.pumpSpectrum({ got.size < expected.size }) { got += it }
        } catch (_: java.io.IOException) { /* the sample ends after the last line */ }
        link.close(); server.close()

        assertEquals(expected.size, got.size)
        for ((f, e) in got.zip(expected)) {
            assertEquals(e.first, f.centreHz)
            assertEquals(e.second.first, f.binHz, 0.001f)
            assertEquals(e.second.second, f.db.size)
            for (i in f.db.indices) assertEquals("bin $i", e.third[i], f.db[i], 0.01f)
        }
    }
}
