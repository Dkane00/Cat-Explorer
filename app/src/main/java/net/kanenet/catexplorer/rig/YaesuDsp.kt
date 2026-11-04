package net.kanenet.catexplorer.rig

import java.util.Locale

/** DSP / filter settings of a Yaesu FT-891, read and written with CAT commands (see the FT-891 CAT Reference Book). */
data class DspState(
    val nbOn: Boolean = false,
    val nbLevel: Int = 0,          // 0..10
    val nrOn: Boolean = false,
    val nrLevel: Int = 1,          // 1..15
    val autoNotch: Boolean = false,
    val notchOn: Boolean = false,
    val notchHz: Int = 1500,       // 10..3200 (stored in 10 Hz steps by the radio)
    val contourOn: Boolean = false,
    val contourHz: Int = 300,      // 10..3200
    val apfOn: Boolean = false,
    val agc: Int = 0,              // 0 off, 1 fast, 2 mid, 3 slow, 4..6 = auto (fast/mid/slow)
    val preampOn: Boolean = false, // false = IPO, true = AMP
    val attOn: Boolean = false,
    val narrow: Boolean = false,
    val ifShiftOn: Boolean = false,
    val ifShift: Int = 0,          // -1200..+1200 Hz, 20 Hz steps
    val widthIdx: Int = 0,         // SH index; see [YaesuDsp.widthTable]
    val widthP2: Int = 1,          // the SH command's second digit, echoed back when we change the width
    val keyPitchHz: Int = 700,     // CW pitch; centres the CW passband
    val apfHz: Int = 0,            // -250..+250 Hz
    val lockOn: Boolean = false,     // VFO dial lock
    val memoryMode: Boolean = false, // radio is on a memory channel rather than a VFO
    val memChannel: Int = 0,         // current memory channel from IF; (also reported in VFO mode: the last one used)
    val memFreqHz: Long = 0,         // frequency the radio reports in IF;
    val memTag: String = "",         // channel name (12 chars max), blank when the channel has none
)

/** Microphone / transmit-audio settings. */
data class MicState(
    val micGain: Int = 50,         // 0..100
    val procOn: Boolean = false,
    val procLevel: Int = 0,        // 0..100
    val eqOn: Boolean = false,     // parametric mic equalizer
    val monitorOn: Boolean = false,
    val monitorLevel: Int = 0,     // 0..100
)

/** CW settings (all readable over CAT). */
data class CwState(
    val speed: Int = 20,          // KS, words per minute 4..60
    val pitchIdx: Int = 40,       // KP 0..75 -> 300..1050 Hz in 10 Hz steps
    val keyerOn: Boolean = false, // KR
    val breakIn: Boolean = false, // BI
    val delayMs: Int = 200,       // SD, semi break-in delay 30..3000 ms
    val spotOn: Boolean = false,  // CS
) { val pitchHz get() = 300 + pitchIdx * 10 }

/** FM settings (all readable over CAT; repeater shift only works in an FM mode). */
data class FmState(
    val shift: Int = 0,        // OS: 0 simplex, 1 plus, 2 minus
    val toneMode: Int = 0,     // CT: 0 off, 1 encode+decode, 2 encode, 3 DCS
    val toneIdx: Int = 8,      // CN00: CTCSS tone number 0..49
    val dcsIdx: Int = 0,       // CN01: DCS code number 0..103
    val squelch: Int = 0,      // SQ: 0..100
)

/** Voice memory (DVS): which of channels 1..5 is playing or recording, 0 = none. */
/** One programmed memory channel, as read with MT. */
data class MemChannel(
    val ch: Int, val hz: Long, val mode: String, val tone: String, val shift: String, val name: String,
    // the raw codes behind the text above, kept so a channel can be written back unchanged apart from what was edited
    val modeCode: Char = '2', val toneCode: Int = 0, val shiftCode: Int = 0, val clar: String = "+00000", val raw: String = "",
)

data class VoiceState(val playing: Int = 0, val recording: Int = 0)

/** One band of the mic equaliser. freqIdx 0 = band off (the radio's menu uses 00 for OFF). */
data class EqBand(val freqIdx: Int = 0, val level: Int = 0, val bw: Int = 5)

/** Low / mid / high bands. */
data class EqSet(val bands: List<EqBand> = List(3) { EqBand() })

/** Both equaliser sets of the radio: "standard" (menu 15-01..09, used with the processor on) and "parametric" (15-10..18, processor off). */
data class EqState(val standard: EqSet = EqSet(), val parametric: EqSet = EqSet()) {
    fun set(parametric: Boolean) = if (parametric) this.parametric else standard
    fun with(parametric: Boolean, band: Int, b: EqBand): EqState {
        val s = set(parametric).let { EqSet(it.bands.toMutableList().also { l -> l[band] = b }) }
        return if (parametric) copy(parametric = s) else copy(standard = s)
    }
    fun encode(): String = listOf(standard, parametric).joinToString("|") { s -> s.bands.joinToString(";") { "${it.freqIdx},${it.level},${it.bw}" } }

    companion object {
        fun decode(text: String?): EqState {
            val sets = text?.split("|")?.map { set ->
                EqSet(set.split(";").map { b ->
                    val v = b.split(",").mapNotNull { it.toIntOrNull() }
                    if (v.size == 3) EqBand(v[0], v[1], v[2]) else EqBand()
                }.let { if (it.size == 3) it else List(3) { EqBand() } })
            }
            return if (sets != null && sets.size == 2) EqState(sets[0], sets[1]) else EqState()
        }
    }
}

/**
 * DSP settings that only exist as radio menu items (12-01..04). The radio won't answer reads for them, so this is
 * what was last set from the app; the starting values are guesses at the factory defaults.
 */
data class DspMenu(val contourLevel: Int = -15, val contourWidth: Int = 10, val apfWidth: Int = 1, val notchWide: Boolean = true) {
    fun encode() = "$contourLevel,$contourWidth,$apfWidth,${if (notchWide) 1 else 0}"
    companion object {
        fun decode(text: String?): DspMenu {
            val v = text?.split(",")?.mapNotNull { it.toIntOrNull() } ?: return DspMenu()
            return if (v.size == 4) DspMenu(v[0].coerceIn(-40, 20), v[1].coerceIn(1, 11), v[2].coerceIn(0, 2), v[3] == 1) else DspMenu()
        }
    }
}

/** CAT strings for the FT-891 and parsers for its replies. Kept free of network code so they can be unit-tested. */
object YaesuDsp {
    val DSP_READS = listOf("NB0;", "NL0;", "NR0;", "RL0;", "BC0;", "BP00;", "BP01;", "CO00;", "CO01;", "CO02;",
        "GT0;", "PA0;", "RA0;", "NA0;", "IS0;", "SH0;", "KP;", "CO03;", "IF;", "LK;")
    val CW_READS = listOf("KS;", "KP;", "KR;", "BI;", "SD;", "CS;")
    val FM_READS = listOf("OS0;", "CT0;", "CN00;", "CN01;", "SQ0;")
    val VOICE_READS = listOf("PB0;", "LM0;")
    val MIC_READS = listOf("MG;", "PR0;", "PL;", "PR1;", "ML0;", "ML1;")

    val AGC_NAMES = listOf("Off", "Fast", "Mid", "Slow", "Auto")

    /** Digits of a reply after the command name, e.g. ("NL0005;", 3) -> "0005". Null for "?;" or junk. */
    private fun body(reply: Any?, prefixLen: Int): String? {
        val t = (reply as? String)?.trim() ?: return null
        if (!t.endsWith(";") || t.length <= prefixLen + 1) return null
        return t.substring(prefixLen, t.length - 1)
    }
    private fun int(reply: Any?, prefixLen: Int): Int? = body(reply, prefixLen)?.toIntOrNull()

    fun parseDsp(r: List<Any?>, old: DspState): DspState {
        fun on(i: Int, prefix: Int) = int(r[i], prefix)?.let { it != 0 }
        val isBody = body(r[14], 4) // "IS0" + on/off digit + "+0100"
        val isOn = (r[14] as? String)?.trim()?.getOrNull(3)?.let { it == '1' }
        val shift = isBody?.toIntOrNull()
        return old.copy(
            nbOn = on(0, 3) ?: old.nbOn,
            nbLevel = int(r[1], 3) ?: old.nbLevel,
            nrOn = on(2, 3) ?: old.nrOn,
            nrLevel = int(r[3], 3) ?: old.nrLevel,
            autoNotch = on(4, 3) ?: old.autoNotch,
            notchOn = int(r[5], 4)?.let { it != 0 } ?: old.notchOn,
            notchHz = int(r[6], 4)?.let { it * 10 } ?: old.notchHz,
            contourOn = int(r[7], 4)?.let { it != 0 } ?: old.contourOn,
            contourHz = int(r[8], 4)?.takeIf { it > 0 } ?: old.contourHz,
            apfOn = int(r[9], 4)?.let { it != 0 } ?: old.apfOn,
            agc = int(r[10], 3)?.let { if (it >= 4) 4 else it } ?: old.agc,
            preampOn = on(11, 3) ?: old.preampOn,
            attOn = on(12, 3) ?: old.attOn,
            narrow = on(13, 3) ?: old.narrow,
            ifShiftOn = isOn ?: old.ifShiftOn,
            ifShift = shift ?: old.ifShift,
            widthIdx = int(r[15], 4)?.takeIf { (r[15] as? String)?.length == 7 }?.let { it % 100 } ?: old.widthIdx,
            widthP2 = (r[15] as? String)?.trim()?.getOrNull(3)?.digitToIntOrNull() ?: old.widthP2,
            keyPitchHz = int(r[16], 2)?.let { 300 + it * 10 } ?: old.keyPitchHz,
            apfHz = int(r[17], 4)?.let { (it - 25) * 10 } ?: old.apfHz,
            lockOn = on(19, 2) ?: old.lockOn,
            // IF; answer is 28 chars; character 23 is 0 = VFO, 1 = memory, 2 = memory tune, 5 = PMS
            memChannel = ifBody(r[18])?.substring(2, 5)?.toIntOrNull() ?: old.memChannel,
            memFreqHz = ifBody(r[18])?.substring(5, 14)?.toLongOrNull() ?: old.memFreqHz,
            memoryMode = (r[18] as? String)?.trim()?.takeIf { it.length == 28 && it.startsWith("IF") }?.let { it[22] != '0' } ?: old.memoryMode,
        )
    }

    private fun ifBody(r: Any?) = (r as? String)?.trim()?.takeIf { it.length == 28 && it.startsWith("IF") }

    /** Channel name from an MT read ("MT" + channel(3) + ... + tag-on flag at 27 + 12 tag characters). Null if the reply is for another channel. */
    fun parseMemTag(reply: String?, channel: Int): String? {
        val t = reply?.trimEnd() ?: return null
        if (t.length < 40 || !t.startsWith("MT") || t.substring(2, 5).toIntOrNull() != channel) return null
        return if (t[27] == '1') t.substring(28, 40).trim() else ""
    }

    private val MEM_MODES = mapOf(
        '1' to "LSB", '2' to "USB", '3' to "CW-U", '4' to "FM", '5' to "AM", '6' to "RTTY-L", '7' to "CW-L", '8' to "DATA-L",
        '9' to "RTTY-U", 'A' to "DATA-FM", 'B' to "FM-N", 'C' to "DATA-U", 'D' to "AM-N", 'E' to "C4FM",
    )
    /** Mode name for an MD / IF / MT mode code, or null if unknown. */
    fun modeLabel(code: Char): String? = MEM_MODES[code]
    /** MD code for a mode name, or null. */
    fun modeCode(label: String): Char? = MEM_MODES.entries.firstOrNull { it.value == label }?.key
    /** Mode names offered for the MD command, in the order the picker shows them. */
    val MD_LABELS: List<String> = listOf('1', '2', '3', '7', '4', 'B', 'A', '5', 'D', '8', 'C', '6', '9').map { MEM_MODES.getValue(it) }

    private val MEM_TONES = listOf("—", "CTCSS T/R", "CTCSS enc", "DCS T/R", "DCS enc")

    /**
     * A whole-channel MT read: "MT" + channel(3) + freq(9) + clarifier(5) + RX clar + TX clar + mode + type + tone + "00" + shift + tag flag + 12 tag chars.
     * The channel number inside the radio's reply is not the one asked for, so the caller supplies it. Null for an empty channel ("?;").
     */
    fun parseMemChannel(reply: String?, channel: Int): MemChannel? {
        val t = reply?.trimEnd() ?: return null
        if (t.length < 28 || !t.startsWith("MT")) return null
        val hz = t.substring(5, 14).toLongOrNull() ?: return null
        val name = if (t[27] == '1') t.substring(28, minOf(40, t.length)).trim() else ""
        return MemChannel(
            channel, hz, MEM_MODES[t[21]] ?: "?",
            MEM_TONES.getOrElse(t[23].digitToIntOrNull() ?: 0) { "?" },
            when (t[26]) { '1' -> "+"; '2' -> "−"; else -> "" },
            name,
            modeCode = t[21], toneCode = t[23].digitToIntOrNull() ?: 0, shiftCode = t[26].digitToIntOrNull() ?: 0,
            clar = t.substring(14, 20), raw = t,
        )
    }

    /** Modes the memory write accepts, as (code, label). */
    val MEM_MODE_CHOICES: List<Pair<Char, String>> = listOf('1', '2', '3', '7', '4', 'B', '5', 'D', '8', 'C', '6', '9').map { it to MEM_MODES.getValue(it) }
    /** Tone settings the memory write accepts: (code, label). */
    val MEM_TONE_CHOICES = listOf(0 to "Off", 1 to "CTCSS T/R", 2 to "CTCSS enc")
    val MEM_SHIFT_CHOICES = listOf(0 to "Simplex", 1 to "+", 2 to "−")

    fun memLabels(m: MemChannel): MemChannel = m.copy(
        mode = MEM_MODES[m.modeCode] ?: "?", tone = MEM_TONES.getOrElse(m.toneCode) { "?" },
        shift = when (m.shiftCode) { 1 -> "+"; 2 -> "−"; else -> "" },
    )

    /**
     * Tag characters the radio takes: printable ASCII, 12 at most. Written over CAT the radio stores every letter as lower case
     * (and refuses the letter X), so what comes back can differ in case from what was sent.
     */
    fun cleanTag(s: String) = s.filter { it.code in 32..126 }.take(12)

    /** MT set: writes frequency, mode, tone, shift and name of one regular channel (001-099). */
    fun memWrite(m: MemChannel): String {
        val tag = cleanTag(m.name)
        return "MT%03d%09d%s0%c0%d00%d%d%s;".format(
            m.ch, m.hz, m.clar, m.modeCode, m.toneCode, m.shiftCode, if (tag.isEmpty()) 0 else 1, tag.padEnd(12),
        )
    }

    fun parseCw(r: List<Any?>, old: CwState): CwState = old.copy(
        speed = int(r[0], 2) ?: old.speed,
        pitchIdx = int(r[1], 2) ?: old.pitchIdx,
        keyerOn = int(r[2], 2)?.let { it != 0 } ?: old.keyerOn,
        breakIn = int(r[3], 2)?.let { it != 0 } ?: old.breakIn,
        delayMs = int(r[4], 2) ?: old.delayMs,
        spotOn = int(r[5], 2)?.let { it != 0 } ?: old.spotOn,
    )

    fun parseFm(r: List<Any?>, old: FmState): FmState = old.copy(
        shift = int(r[0], 3) ?: old.shift,
        toneMode = int(r[1], 3) ?: old.toneMode,
        toneIdx = int(r[2], 4) ?: old.toneIdx,
        dcsIdx = int(r[3], 4) ?: old.dcsIdx,
        squelch = int(r[4], 3) ?: old.squelch,
    )

    fun parseVoice(r: List<Any?>, old: VoiceState): VoiceState = old.copy(
        playing = int(r[0], 3) ?: old.playing,
        recording = int(r[1], 3) ?: old.recording,
    )

    fun parseMic(r: List<Any?>, old: MicState): MicState = old.copy(
        micGain = int(r[0], 2) ?: old.micGain,
        procOn = int(r[1], 3)?.let { it != 0 } ?: old.procOn,
        procLevel = int(r[2], 2) ?: old.procLevel,
        eqOn = int(r[3], 3)?.let { it != 0 } ?: old.eqOn,
        monitorOn = int(r[4], 3)?.let { it != 0 } ?: old.monitorOn,
        monitorLevel = int(r[5], 3) ?: old.monitorLevel,
    )

    /**
     * The read command that asks the radio for the setting a set-command just changed. FLRig waits ~6 s for a
     * reply to a bare set-command (the radio sends none), but returns at once if a read follows it.
     */
    fun readbackFor(set: String): String = when (set.take(2)) {
        "MG", "PL", "LK" -> set.take(2) + ";"
        "BP", "CO" -> set.take(4) + ";"
        "VM", "SV", "MC" -> "IF;"
        "KS", "KP", "KR", "BI", "SD", "CS" -> set.take(2) + ";"
        "CN" -> set.take(4) + ";"
        "KY" -> "KS;"   // keyer-memory playback has no read of its own
        "EX" -> "PR1;"   // menu items can't be read back, so read something harmless instead
        else -> set.take(3) + ";"   // NB NL NR RL BC GT PA RA NA IS (P1 fixed 0), PR ML (P1 picks the item)
    }

    enum class ModeGroup { SSB, CW, DIGITAL, OTHER }

    /** FLRig mode name -> which column of the FT-891 width table applies. AM/FM have no selectable width. */
    fun modeGroup(mode: String): ModeGroup = when {
        mode.startsWith("CW") -> ModeGroup.CW
        mode.startsWith("RTTY") || mode.startsWith("DATA") -> ModeGroup.DIGITAL
        mode == "LSB" || mode == "USB" -> ModeGroup.SSB
        else -> ModeGroup.OTHER
    }

    private val CW_NARROW = mapOf(0 to 500, 1 to 50, 2 to 100, 3 to 150, 4 to 200, 5 to 250, 6 to 300, 7 to 350, 8 to 400, 9 to 450, 10 to 500)
    private val CW_WIDE = mapOf(0 to 2400, 10 to 500, 11 to 800, 12 to 1200, 13 to 1400, 14 to 1700, 15 to 2000, 16 to 2400, 17 to 3000)
    private val DIGI_NARROW = CW_NARROW + (0 to 300)
    private val DIGI_WIDE = CW_WIDE + (0 to 500)
    private val SSB_NARROW = mapOf(0 to 1500, 1 to 200, 2 to 400, 3 to 600, 4 to 850, 5 to 1100, 6 to 1350, 7 to 1500, 8 to 1650, 9 to 1800)
    private val SSB_WIDE = mapOf(0 to 2400, 9 to 1800, 10 to 1950, 11 to 2100, 12 to 2200, 13 to 2300, 14 to 2400,
        15 to 2500, 16 to 2600, 17 to 2700, 18 to 2800, 19 to 2900, 20 to 3000, 21 to 3200)

    /** SH index -> bandwidth in Hz for this mode and narrow/wide setting (from the FT-891 CAT manual's width table). */
    fun widthTable(group: ModeGroup, narrow: Boolean): Map<Int, Int> = when (group) {
        ModeGroup.CW -> if (narrow) CW_NARROW else CW_WIDE
        ModeGroup.DIGITAL -> if (narrow) DIGI_NARROW else DIGI_WIDE
        ModeGroup.SSB -> if (narrow) SSB_NARROW else SSB_WIDE
        ModeGroup.OTHER -> emptyMap()
    }

    private fun b(v: Boolean) = if (v) 1 else 0
    private fun f(fmt: String, vararg a: Any) = String.format(Locale.US, fmt, *a)

    // ---- command builders (all end with ';') ----
    fun nb(on: Boolean) = "NB0${b(on)};"
    fun nbLevel(v: Int) = f("NL0%03d;", v.coerceIn(0, 10))
    fun nr(on: Boolean) = "NR0${b(on)};"
    fun nrLevel(v: Int) = f("RL0%02d;", v.coerceIn(1, 15))
    fun autoNotch(on: Boolean) = "BC0${b(on)};"
    fun notch(on: Boolean) = f("BP00%03d;", b(on))
    fun notchFreq(hz: Int) = f("BP01%03d;", (hz / 10).coerceIn(1, 320))
    fun contour(on: Boolean) = f("CO00%04d;", b(on))
    fun contourFreq(hz: Int) = f("CO01%04d;", hz.coerceIn(10, 3200))
    fun apf(on: Boolean) = f("CO02%04d;", b(on))
    fun agc(v: Int) = "GT0${v.coerceIn(0, 4)};"
    /** The radio's [V/M] key: switches between VFO and memory-channel operation. Set-only; read the state with IF;. */
    fun vfoMemory() = "VM;"
    /** Exchanges the VFO A and VFO B settings (the radio's A/B swap). */
    fun swapVfo() = "SV;"
    /** The radio's VFO dial lock, which stops a bumped tuning knob changing the frequency. */
    fun lock(on: Boolean) = "LK${b(on)};"
    fun preamp(amp: Boolean) = "PA0${b(amp)};"
    fun att(on: Boolean) = "RA0${b(on)};"
    fun width(p2: Int, idx: Int) = f("SH0%d%02d;", p2.coerceIn(0, 1), idx.coerceIn(0, 21))
    fun apfFreq(hz: Int) = f("CO03%04d;", ((hz.coerceIn(-250, 250) + 250) / 10))
    fun narrow(on: Boolean) = "NA0${b(on)};"
    fun ifShift(on: Boolean, hz: Int): String {
        val v = (hz / 20 * 20).coerceIn(-1200, 1200)
        return f("IS0%d%s%04d;", b(on), if (v < 0) "-" else "+", kotlin.math.abs(v))
    }

    fun micGain(v: Int) = f("MG%03d;", v.coerceIn(0, 100))
    fun proc(on: Boolean) = "PR0${b(on)};"
    fun procLevel(v: Int) = f("PL%03d;", v.coerceIn(0, 100))
    // Menu items (EX): write-only, see DspMenu
    fun contourLevel(dB: Int) = f("EX1202%+03d;", dB.coerceIn(-40, 20))
    fun contourWidth(n: Int) = f("EX1203%02d;", n.coerceIn(1, 11))
    fun apfWidth(n: Int) = f("EX1201%d;", n.coerceIn(0, 2))
    fun notchWidth(wide: Boolean) = f("EX1204%d;", b(wide))
    fun repeaterShift(n: Int) = "OS0${n.coerceIn(0, 2)};"
    fun toneMode(n: Int) = "CT0${n.coerceIn(0, 3)};"
    fun ctcssTone(idx: Int) = f("CN00%03d;", idx.coerceIn(0, 49))
    fun dcsCode(idx: Int) = f("CN01%03d;", idx.coerceIn(0, 103))
    fun squelch(v: Int) = f("SQ0%03d;", v.coerceIn(0, 100))
    /** CTCSS tone frequencies (Hz) and DCS codes, indexed by the radio's tone / code number. */
    val CTCSS_HZ = "67.0 69.3 71.9 74.4 77.0 79.7 82.5 85.4 88.5 91.5 94.8 97.4 100.0 103.5 107.2 110.9 114.8 118.8 123.0 127.3 131.8 136.5 141.3 146.2 151.4 156.7 159.8 162.2 165.5 167.9 171.3 173.8 177.3 179.9 183.5 186.2 189.9 192.8 196.6 199.5 203.5 206.5 210.7 218.1 225.7 229.1 233.6 241.8 250.3 254.1".split(" ")
    val DCS_CODES = "023 025 026 031 032 036 043 047 051 053 054 065 071 072 073 074 114 115 116 122 125 131 132 134 143 145 152 155 156 162 165 172 174 205 212 223 225 226 243 244 245 246 251 252 255 261 263 265 266 271 274 306 311 315 325 331 332 343 346 351 356 364 365 371 411 412 413 423 431 432 445 446 452 454 455 462 464 465 466 503 506 516 523 526 532 546 565 606 612 624 627 631 632 654 662 664 703 712 723 731 732 734 743 754".split(" ")
    /** Voice memory: n = 1..5 starts, 0 stops. Playback transmits; recording overwrites the channel. */
    fun playVoice(n: Int) = "PB0${n.coerceIn(0, 5)};"
    fun recordVoice(n: Int) = "LM0${n.coerceIn(0, 5)};"
    fun keySpeed(wpm: Int) = f("KS%03d;", wpm.coerceIn(4, 60))
    fun keyPitch(idx: Int) = f("KP%02d;", idx.coerceIn(0, 75))
    fun keyer(on: Boolean) = "KR${b(on)};"
    fun breakIn(on: Boolean) = "BI${b(on)};"
    fun breakInDelay(ms: Int) = f("SD%04d;", ms.coerceIn(30, 3000))
    fun cwSpot(on: Boolean) = "CS${b(on)};"
    fun playKeyerMemory(n: Int) = "KY${n.coerceIn(1, 5)};"
    fun micEq(on: Boolean) = "PR1${b(on)};"

    // Equaliser band settings are radio menu items (EX). The radio won't answer reads for them, so these are write-only.
    val EQ_MAX_IDX = listOf(7, 9, 18)
    val EQ_BAND_NAMES = listOf("LOW", "MID", "HIGH")
    /** Centre frequency of a band for a menu index (null = OFF). Low 100-700, mid 700-1500, high 1500-3200 Hz, 100 Hz steps. */
    fun eqFreqHz(band: Int, idx: Int): Int? = if (idx <= 0) null else when (band) { 0 -> idx * 100; 1 -> 600 + idx * 100; else -> 1400 + idx * 100 }
    fun eqIdxForHz(band: Int, hz: Float): Int {
        val raw = when (band) { 0 -> hz / 100f; 1 -> (hz - 600f) / 100f; else -> (hz - 1400f) / 100f }
        return Math.round(raw).coerceIn(1, EQ_MAX_IDX[band])
    }
    private fun eqMenu(parametric: Boolean, band: Int, item: Int) = (if (parametric) 1510 else 1501) + band * 3 + item
    fun eqFreq(parametric: Boolean, band: Int, idx: Int) = f("EX%04d%02d;", eqMenu(parametric, band, 0), idx.coerceIn(0, EQ_MAX_IDX[band]))
    fun eqLevel(parametric: Boolean, band: Int, dB: Int) = f("EX%04d%+03d;", eqMenu(parametric, band, 1), dB.coerceIn(-20, 10))
    fun eqBw(parametric: Boolean, band: Int, bw: Int) = f("EX%04d%02d;", eqMenu(parametric, band, 2), bw.coerceIn(1, 10))
    fun monitor(on: Boolean) = f("ML0%03d;", b(on))
    fun monitorLevel(v: Int) = f("ML1%03d;", v.coerceIn(0, 100))
}
