// Copyright (C) 2026 David Kane (W6MZT)
// SPDX-License-Identifier: GPL-3.0-or-later
package net.kanenet.catexplorer

import net.kanenet.catexplorer.rig.ByteLink
import net.kanenet.catexplorer.rig.Ft857Cat
import net.kanenet.catexplorer.rig.Ft857Client
import net.kanenet.catexplorer.rig.RigState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pretend FT-857D: takes bytes in fives like the real radio (with no way to fall back into step on its own), carries out
 * the commands Yaesu's manual lists and answers the reads. Unknown command codes do nothing.
 */
class FakeFt857 : ByteLink {
    var freqA = 14_074_000L
    var freqB = 7_074_000L
    var b = false
    var mode = 0x01
    var ptt = false
    var split = false
    var lock = false
    var clar = false
    var clarHz = 0
    var shift = 0x89
    var offsetHz = 0L
    var toneMode = 0x8A
    var ctcss = 0
    var dcs = 0
    var sMeter = 7
    var po = 0
    var executed = mutableListOf<Int>()
    private val pending = ArrayList<Byte>()
    private val out = ArrayDeque<Byte>()

    private fun bcd(vararg x: Byte) = Ft857Cat.fromBcd(x)!!

    override fun write(bytes: ByteArray) {
        for (x in bytes) {
            pending += x
            if (pending.size == 5) { run(pending.toByteArray()); pending.clear() }
        }
    }

    private fun run(f: ByteArray) {
        val cmd = f[4].toInt() and 0xFF
        executed += cmd
        when (cmd) {
            0x00 -> lock = true
            0x80 -> lock = false
            0x08 -> { ptt = true; po = 12 }
            0x88 -> { ptt = false; po = 0 }
            0x01 -> { val hz = bcd(f[0], f[1], f[2], f[3]) * 10; if (b) freqB = hz else freqA = hz }
            0x07 -> mode = f[0].toInt() and 0xFF
            0x05 -> clar = true
            0x85 -> clar = false
            0xF5 -> clarHz = (bcd(f[2], f[3]) * 10).toInt() * (if (f[0].toInt() != 0) -1 else 1)
            0x81 -> b = !b
            0x02 -> split = true
            0x82 -> split = false
            0x09 -> shift = f[0].toInt() and 0xFF
            0xF9 -> offsetHz = bcd(f[0], f[1], f[2], f[3]) * 10
            0x0A -> toneMode = f[0].toInt() and 0xFF
            0x0B -> ctcss = bcd(f[0], f[1]).toInt()
            0x0C -> dcs = bcd(f[0], f[1]).toInt()
            0x03 -> { Ft857Cat.bcd((if (b) freqB else freqA) / 10, 8).forEach { out += it }; out += mode.toByte() }
            0xE7 -> out += (sMeter or 0x00).toByte()
            0xF7 -> out += ((if (ptt) 0 else 0x80) or (if (split) 0 else 0x20) or po).toByte()
            0xBB -> { out += (if (b) 1 else 0).toByte(); out += 0 }
        }
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        var n = 0
        while (out.isNotEmpty() && n < buf.size) buf[n++] = out.removeFirst()
        return n
    }

    override fun close() {}
}

class Ft857Test {
    @Test fun bcdAndFrames() {
        assertArrayEquals(byteArrayOf(0x43, 0x97.toByte(), 0x00, 0x00, 0x01), Ft857Cat.setFrequency(439_700_000))
        assertArrayEquals(byteArrayOf(0x01, 0x40, 0x74, 0x00, 0x01), Ft857Cat.setFrequency(14_074_000))
        assertEquals(14_074_000L / 10, Ft857Cat.fromBcd(byteArrayOf(0x01, 0x40, 0x74, 0x00)))
        assertNull(Ft857Cat.fromBcd(byteArrayOf(0x1A)))
        assertArrayEquals(byteArrayOf(0x08, 0x85.toByte(), 0x08, 0x85.toByte(), 0x0B), Ft857Cat.ctcssTone(88.5))
        assertArrayEquals(byteArrayOf(0x00, 0x23, 0x00, 0x23, 0x0C), Ft857Cat.dcsCode(23))
        assertArrayEquals(byteArrayOf(0x00, 0x06, 0x00, 0x00, 0xF9.toByte()), Ft857Cat.repeaterOffset(600_000))
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0x01, 0x25, 0xF5.toByte()), Ft857Cat.clarifierOffset(-1_250))   // -1.25 kHz
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x09, 0x99.toByte(), 0xF5.toByte()), Ft857Cat.clarifierOffset(20_000))   // capped at 9.99 kHz
        assertEquals(50, Ft857Cat.CTCSS.size)
        assertEquals(104, Ft857Cat.DCS.size)
    }

    @Test fun statusBytes() {
        val tx = Ft857Cat.parseTx(0x0C)          // keyed (bit 7 clear), split on (bit 5 clear), PO 12
        assertTrue(tx.ptt); assertTrue(tx.split); assertEquals(12, tx.po); assertFalse(tx.highSwr)
        val idle = Ft857Cat.parseTx(0xA0)
        assertFalse(idle.ptt); assertFalse(idle.split)
        assertEquals(44, Ft857Cat.sToPercent(9)); assertEquals(100, Ft857Cat.sToPercent(15)); assertEquals(0, Ft857Cat.sToPercent(0))
        assertEquals("FM-N", Ft857Cat.modeLabel(0x88)); assertEquals("CW", Ft857Cat.modeLabel(0x82)); assertNull(Ft857Cat.modeLabel(0x55))
    }

    @Test fun probeFindsTheRadioEvenAfterStrayBytes() {
        val radio = FakeFt857()
        radio.write("ID;".toByteArray())          // an FT-891 probe the FT-857D half-swallowed
        val c = Ft857Client(radio)
        assertEquals(14_074_000L to "USB", c.probe())
        // the stray bytes and fillers only ever completed frames whose code is filler or junk: nothing was set
        assertFalse(radio.lock); assertFalse(radio.ptt); assertEquals(14_074_000L, radio.freqA)
        assertTrue(radio.executed.none { it in setOf(0x00, 0x08, 0x01, 0x07, 0x81, 0x02) })
    }

    @Test fun pollReadsTheRadio() {
        val radio = FakeFt857().apply { mode = 0x02; sMeter = 9; split = true }
        val c = Ft857Client(radio)
        val s = c.poll(RigState(), false)
        assertEquals(14_074_000L, s.vfoAHz); assertEquals("A", s.activeVfo); assertEquals("CW", s.mode)
        assertEquals(44, s.smeter); assertTrue(s.split); assertFalse(s.ptt)
        assertNull(s.volume); assertNull(s.powerSetting)
        c.setPtt(true)
        val t = c.poll(s, false)
        assertTrue(t.ptt); assertEquals(80, t.powerMeter); assertEquals("OK", t.swrRatio)
        c.setPtt(false)
    }

    @Test fun controlsReachTheRadio() {
        val radio = FakeFt857()
        val c = Ft857Client(radio)
        c.poll(RigState(), false)
        c.setFrequency(7_200_000.0); assertEquals(7_200_000L, radio.freqA)
        c.setMode("LSB"); assertEquals(0x00, radio.mode)
        c.setVfoB(146_520_000.0)                   // the other VFO: switch, set, switch back
        assertEquals(146_520_000L, radio.freqB); assertFalse(radio.b); assertEquals(7_200_000L, radio.freqA)
        c.swapVfo(); assertTrue(radio.b)
        assertEquals("B", c.poll(RigState(), false).activeVfo)
        c.setSplit(true); assertTrue(radio.split)
        c.setLock(true); assertTrue(radio.lock)
        c.setClarifier(true); c.setClarifierOffset(-500); assertTrue(radio.clar); assertEquals(-500, radio.clarHz)
        c.setRepeaterShift(Ft857Cat.Shift.MINUS); c.setRepeaterOffset(600_000)
        assertEquals(0x09, radio.shift); assertEquals(600_000L, radio.offsetHz)
        c.setToneMode(Ft857Cat.ToneMode.TONE); c.setCtcss(100.0); assertEquals(0x4A, radio.toneMode); assertEquals(1000, radio.ctcss)
        c.setToneMode(Ft857Cat.ToneMode.DCS); c.setDcs(754); assertEquals(0x0A, radio.toneMode); assertEquals(754, radio.dcs)
    }

    @Test fun consoleSpeaksHex() {
        val c = Ft857Client(FakeFt857())
        assertEquals("01 40 74 00 01", c.catString("00 00 00 00 03"))
        assertNotNull(Ft857Cat.parseHex("0x00,0x00,0x00,0x00,0x03"))
        assertTrue(c.catString("FA;").startsWith("Type a five-byte"))
    }
}
