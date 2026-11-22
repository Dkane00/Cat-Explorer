// Copyright (C) 2026 David Kane (W6MZT)
// SPDX-License-Identifier: GPL-3.0-or-later
package net.kanenet.catexplorer.rig

/**
 * The FT-857D's CAT language (the FT-897D and FT-817 use the same one): every command is five bytes, four parameters then
 * the command code. Frequencies and tones are packed BCD. Only a few commands answer, with a fixed number of bytes.
 * Taken from Yaesu's FT-857D operating manual; the EEPROM read (0xBB) is undocumented and follows Hamlib's FT-857 driver.
 *
 * Pure functions only, so they can be tested without a radio.
 */
object Ft857Cat {
    // command codes
    const val LOCK_ON = 0x00
    const val LOCK_OFF = 0x80
    const val PTT_ON = 0x08
    const val PTT_OFF = 0x88
    const val SET_FREQ = 0x01
    const val READ_FREQ_MODE = 0x03
    const val SET_MODE = 0x07
    const val CLAR_ON = 0x05
    const val CLAR_OFF = 0x85
    const val CLAR_FREQ = 0xF5
    const val VFO_TOGGLE = 0x81
    const val SPLIT_ON = 0x02
    const val SPLIT_OFF = 0x82
    const val RPT_SHIFT = 0x09
    const val RPT_OFFSET = 0xF9
    const val TONE_MODE = 0x0A
    const val CTCSS_TONE = 0x0B
    const val DCS_CODE = 0x0C
    const val READ_RX = 0xE7
    const val READ_TX = 0xF7
    const val READ_EEPROM = 0xBB

    /** A padding byte that isn't a command: sent to bring the radio's five-byte framing back into step. */
    const val FILLER = 0xFF

    fun frame(cmd: Int, p1: Int = FILLER, p2: Int = FILLER, p3: Int = FILLER, p4: Int = FILLER) =
        byteArrayOf(p1.toByte(), p2.toByte(), p3.toByte(), p4.toByte(), cmd.toByte())

    /** A read command: the parameters don't matter, so they're filler bytes that do nothing if the framing is off. */
    fun read(cmd: Int) = frame(cmd)

    // ---- BCD ----

    /** [value] as [digits] BCD digits, most significant first, two to a byte. */
    fun bcd(value: Long, digits: Int): ByteArray {
        val s = value.toString().padStart(digits, '0').takeLast(digits)
        return ByteArray(digits / 2) { i -> (((s[2 * i] - '0') shl 4) or (s[2 * i + 1] - '0')).toByte() }
    }

    /** BCD bytes back to a number, or null if a nibble isn't a decimal digit. */
    fun fromBcd(b: ByteArray): Long? {
        var v = 0L
        for (x in b) {
            val hi = (x.toInt() shr 4) and 0x0F
            val lo = x.toInt() and 0x0F
            if (hi > 9 || lo > 9) return null
            v = v * 100 + hi * 10 + lo
        }
        return v
    }

    /** Set frequency: eight BCD digits of the frequency in 10 Hz steps (439.70000 MHz -> 43 97 00 00). */
    fun setFrequency(hz: Long): ByteArray {
        val d = bcd((hz + 5) / 10, 8)
        return frame(SET_FREQ, d[0].toInt() and 0xFF, d[1].toInt() and 0xFF, d[2].toInt() and 0xFF, d[3].toInt() and 0xFF)
    }

    // ---- modes ----

    /** The labels the app shows, in the order the Mode picker lists them. */
    val MODES = listOf("LSB", "USB", "CW", "CW-R", "AM", "FM", "FM-N", "WFM", "DIG", "PKT")

    private val SET_CODES = mapOf(
        "LSB" to 0x00, "USB" to 0x01, "CW" to 0x02, "CW-R" to 0x03, "AM" to 0x04, "WFM" to 0x06,
        "FM" to 0x08, "FM-N" to 0x88, "DIG" to 0x0A, "PKT" to 0x0C,
    )

    fun modeCode(label: String): Int? = SET_CODES[label]

    /** The mode byte the radio reports, as a label; the narrow variants (bit 7) read as their plain mode except FM-N. */
    fun modeLabel(code: Int): String? = when (code and 0xFF) {
        0x00 -> "LSB"; 0x01 -> "USB"; 0x02, 0x82 -> "CW"; 0x03, 0x83 -> "CW-R"; 0x04, 0x84 -> "AM"; 0x06 -> "WFM"
        0x08 -> "FM"; 0x88 -> "FM-N"; 0x0A, 0x8A -> "DIG"; 0x0C, 0x8C -> "PKT"
        else -> null
    }

    /** The five-byte answer to [READ_FREQ_MODE]: frequency in Hz and mode label, or null if it doesn't look like one. */
    fun parseFreqMode(b: ByteArray): Pair<Long, String>? {
        if (b.size != 5) return null
        val tens = fromBcd(b.copyOfRange(0, 4)) ?: return null
        val mode = modeLabel(b[4].toInt()) ?: return null
        val hz = tens * 10
        if (hz < 100_000 || hz > 470_000_000) return null
        return hz to mode
    }

    // ---- status bytes ----

    /** Receive status: S-meter 0..15 (S0..S9, then +10..+60 dB) and whether a signal is opening the squelch. */
    data class Rx(val sMeter: Int, val squelchOpen: Boolean)

    fun parseRx(b: Int) = Rx(sMeter = b and 0x0F, squelchOpen = (b and 0x80) == 0)

    /** Transmit status: keyed, high SWR, split on, PO meter 0..15. The PTT and split bits read 0 when on. */
    data class Tx(val ptt: Boolean, val highSwr: Boolean, val split: Boolean, val po: Int)

    fun parseTx(b: Int) = Tx(ptt = (b and 0x80) == 0, highSwr = (b and 0x40) != 0, split = (b and 0x20) == 0, po = b and 0x0F)

    /** S-meter 0..15 on the app's 0..100 scale (S9 at 44, S9+60 at 100). */
    fun sToPercent(s: Int): Int = if (s <= 9) s * 44 / 9 else 44 + (s - 9) * 56 / 6

    // ---- clarifier, repeater, tones ----

    /** Clarifier offset, +/-9.99 kHz in 10 Hz steps: P1 = 0 for up, 1 for down; P3 P4 = the offset in BCD. */
    fun clarifierOffset(hz: Int): ByteArray {
        val d = bcd((kotlin.math.abs(hz).coerceAtMost(9_990) / 10).toLong(), 4)
        return frame(CLAR_FREQ, if (hz < 0) 0x01 else 0x00, 0x00, d[0].toInt() and 0xFF, d[1].toInt() and 0xFF)
    }

    enum class Shift(val code: Int, val label: String) { MINUS(0x09, "−"), SIMPLEX(0x89, "Simplex"), PLUS(0x49, "+") }

    fun repeaterShift(s: Shift) = frame(RPT_SHIFT, s.code, 0x00, 0x00, 0x00)

    /** Repeater offset, eight BCD digits in 10 Hz steps like a frequency (600 kHz -> 00 06 00 00). */
    fun repeaterOffset(hz: Long): ByteArray {
        val d = bcd(hz / 10, 8)
        return frame(RPT_OFFSET, d[0].toInt() and 0xFF, d[1].toInt() and 0xFF, d[2].toInt() and 0xFF, d[3].toInt() and 0xFF)
    }

    /** Tone squelch modes: P1 of [TONE_MODE]. TONE sends a CTCSS tone, TSQ also listens for it, DCS uses a digital code. */
    enum class ToneMode(val code: Int, val label: String) { OFF(0x8A, "Off"), TONE(0x4A, "Tone"), TSQ(0x2A, "TSQ"), DCS(0x0A, "DCS") }

    fun toneMode(m: ToneMode) = frame(TONE_MODE, m.code, 0x00, 0x00, 0x00)

    /** The 50 standard CTCSS tones the FT-857D offers, in Hz. */
    val CTCSS = listOf(
        67.0, 69.3, 71.9, 74.4, 77.0, 79.7, 82.5, 85.4, 88.5, 91.5, 94.8, 97.4, 100.0, 103.5, 107.2, 110.9, 114.8, 118.8,
        123.0, 127.3, 131.8, 136.5, 141.3, 146.2, 151.4, 156.7, 159.8, 162.2, 165.5, 167.9, 171.3, 173.8, 177.3, 179.9,
        183.5, 186.2, 189.9, 192.8, 196.6, 199.5, 203.5, 206.5, 210.7, 218.1, 225.7, 229.1, 233.6, 241.8, 250.3, 254.1,
    )

    /** CTCSS tone for transmit and receive: P1 P2 and P3 P4 are the tone in tenths of a Hz, BCD (88.5 -> 08 85). */
    fun ctcssTone(hz: Double): ByteArray {
        val d = bcd(Math.round(hz * 10), 4)
        val a = d[0].toInt() and 0xFF; val b = d[1].toInt() and 0xFF
        return frame(CTCSS_TONE, a, b, a, b)
    }

    /** The 104 standard DCS codes. */
    val DCS = listOf(
        23, 25, 26, 31, 32, 36, 43, 47, 51, 53, 54, 65, 71, 72, 73, 74, 114, 115, 116, 122, 125, 131, 132, 134, 143, 145, 152,
        155, 156, 162, 165, 172, 174, 205, 212, 223, 225, 226, 243, 244, 245, 246, 251, 252, 255, 261, 263, 265, 266, 271, 274,
        306, 311, 315, 325, 331, 332, 343, 346, 351, 356, 364, 365, 371, 411, 412, 413, 423, 431, 432, 445, 446, 452, 454, 455,
        462, 464, 465, 466, 503, 506, 516, 523, 526, 532, 546, 565, 606, 612, 624, 627, 631, 632, 654, 662, 664, 703, 712, 723,
        731, 732, 734, 743, 754,
    )

    /** DCS code for transmit and receive: P1 P2 and P3 P4 are the code's digits in BCD (023 -> 00 23). */
    fun dcsCode(code: Int): ByteArray {
        val d = bcd(code.toLong(), 4)
        val a = d[0].toInt() and 0xFF; val b = d[1].toInt() and 0xFF
        return frame(DCS_CODE, a, b, a, b)
    }

    /** EEPROM read (undocumented): P1 P2 = address; answers two bytes, the byte there and the next. */
    fun readEeprom(addr: Int) = frame(READ_EEPROM, (addr shr 8) and 0xFF, addr and 0xFF, 0x00, 0x00)

    /** EEPROM byte whose bit 0 says which VFO is in use (0 = A, 1 = B), as Hamlib's FT-857 driver reads it. */
    const val EEPROM_VFO = 0x0068

    /** "00 00 00 00 03"-style text for the console, and back (whole five-byte commands only). */
    fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    fun parseHex(text: String): ByteArray? {
        val parts = text.trim().removeSuffix(";").split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.map { it.removePrefix("0x").removePrefix("0X") }
        // whole commands only: five bytes each, two hex digits per byte
        if (parts.isEmpty() || parts.size % 5 != 0 || parts.any { it.length != 2 || it.toIntOrNull(16) == null }) return null
        return ByteArray(parts.size) { parts[it].toInt(16).toByte() }
    }
}
