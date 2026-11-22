// Copyright (C) 2026 David Kane (W6MZT)
// SPDX-License-Identifier: GPL-3.0-or-later
package net.kanenet.catexplorer.rig

import com.hoho.android.usbserial.driver.UsbSerialPort
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.util.Locale

/**
 * Talks Yaesu CAT straight to an FT-891 over its USB serial port — no FLRig, no PC. Replies are text ending in ';';
 * a refused or unknown command is answered "?;". Only one thing may use the serial line at a time, so every
 * exchange takes [lock]. Blocking calls — run them off the main thread.
 */
class UsbCatClient(private val port: UsbSerialPort) : RigClient, Closeable {
    private val lock = Any()
    private val buf = ByteArray(256)

    /** Which VFO the radio is receiving on, as of the last poll; used to aim frequency changes. */
    @Volatile private var activeB = false
    @Volatile private var modeCode = '2'
    @Volatile private var narrow = false

    // ---- serial plumbing ----------------------------------------------------------------

    private fun write(text: String) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        Log.d(TAG, "TX $text")
        port.write(bytes, WRITE_TIMEOUT_MS)
    }

    /** Throws away anything the radio sent that nobody asked for (late replies, "?;"). */
    private fun drain() {
        repeat(8) {
            val n = port.read(buf, 2)
            if (n <= 0) return
            Log.d(TAG, "RX (unasked) " + String(buf, 0, n, Charsets.US_ASCII))
        }
    }

    /**
     * Reads until [replies] ';'-terminated answers have arrived, or [firstMs] passes with nothing / [gapMs] passes
     * after the last byte. With [replies] <= 0, stops on the gap alone.
     */
    private fun readReplies(replies: Int, firstMs: Long, gapMs: Long): String {
        val sb = StringBuilder()
        var terminators = 0
        val start = System.currentTimeMillis()
        var last = start
        while (true) {
            val now = System.currentTimeMillis()
            val limit = if (sb.isEmpty()) firstMs else gapMs
            if (now - (if (sb.isEmpty()) start else last) > limit) break
            val n = port.read(buf, 20)
            if (n > 0) {
                last = System.currentTimeMillis()
                for (i in 0 until n) {
                    val c = (buf[i].toInt() and 0xFF).toChar()
                    sb.append(c)
                    if (c == ';') terminators++
                }
                if (replies > 0 && terminators >= replies) break
            }
        }
        if (sb.isNotEmpty()) Log.d(TAG, "RX $sb")
        return sb.toString()
    }

    private fun split(text: String): List<String> {
        val out = ArrayList<String>()
        var from = 0
        while (true) {
            val i = text.indexOf(';', from)
            if (i < 0) break
            out.add(text.substring(from, i + 1).trim())
            from = i + 1
        }
        return out
    }

    /** One read command, one reply ("" if the radio said nothing, null if it answered "?;"). */
    private fun query(cmd: String): String? = synchronized(lock) {
        drain()
        write(cmd)
        val prefix = cmd.take(2)
        val deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val got = split(readReplies(1, deadline - System.currentTimeMillis(), 100))
            val r = got.firstOrNull() ?: break
            if (r == "?;") return@synchronized null
            if (r.startsWith(prefix)) return@synchronized r
        }
        ""
    }

    /** Raw string out, whatever comes back within a short while in. Used for the console and multi-command strings. */
    override fun catString(cmd: String): String = synchronized(lock) {
        drain()
        write(cmd)
        readReplies(0, REPLY_TIMEOUT_MS, IDLE_GAP_MS)
    }

    /** Set command; the radio doesn't answer these. */
    override fun cat(cmd: String) {
        synchronized(lock) { write(cmd) }
    }

    /**
     * Sends all the reads in one go and collects the answers (every read gets exactly one ';'-terminated reply), checking each
     * reply starts like its command. If anything is missing or out of step, asks one at a time instead.
     */
    override fun catReads(cmds: List<String>): List<Any?> {
        if (cmds.isEmpty()) return emptyList()
        val out = ArrayList<Any?>(cmds.size)
        for (chunk in cmds.chunked(BATCH)) {
            val replies = synchronized(lock) {
                drain()
                write(chunk.joinToString(""))
                split(readReplies(chunk.size, REPLY_TIMEOUT_MS + 200L * chunk.size, IDLE_GAP_MS))
            }
            val inStep = replies.size == chunk.size &&
                chunk.indices.all { replies[it] == "?;" || replies[it].startsWith(chunk[it].take(2)) }
            if (inStep) out.addAll(replies.map { if (it == "?;") null else it })
            else out.addAll(chunk.map { query(it) })
        }
        return out
    }

    private fun value(reply: String?, prefix: Int): Int? {
        val t = reply?.trim() ?: return null
        if (!t.endsWith(";") || t.length <= prefix + 1) return null
        return t.substring(prefix, t.length - 1).toIntOrNull()
    }

    /** Sets the line state the radio's CAT port expects, then asks which radio this is. Null = no answer. */
    fun probe(): String? {
        // a "?;" means the radio is listening but choked on a leftover fragment of an earlier command — ask again
        repeat(3) { query("ID;")?.takeIf { it.startsWith("ID") }?.let { return it } }
        return null
    }

    fun setLines(rts: Boolean, dtr: Boolean) {
        runCatching { port.setRTS(rts) }
        runCatching { port.setDTR(dtr) }
    }

    override fun close() {
        // never leave the radio transmitting if the link goes away
        runCatching { synchronized(lock) { write("TX0;") } }
        runCatching { port.close() }
    }

    // ---- RigClient ----------------------------------------------------------------------

    override fun readStatic(): RigClient.StaticInfo {
        write("AI0;")   // no unsolicited updates
        val id = probe() ?: throw IOException("No answer from the radio. Check the CAT RATE (menu 05-06) matches the baud rate chosen here.")
        val code = id.trim().removeSuffix(";").removePrefix("ID")
        val name = if (code == "0650") "FT-891" else "Yaesu $code"
        return RigClient.StaticInfo(
            xcvr = name, version = "USB CAT",
            modes = YaesuDsp.MD_LABELS, bandwidths = emptyList(), maxPower = 100,
        )
    }

    override fun poll(previous: RigState, extraMeters: Boolean): RigState {
        val tx = previous.ptt
        val reads = mutableListOf("FA;", "FB;", "VS;", "ST;", "MD0;", "TX;", "NA0;", "SH0;", "AG0;", "RG0;", "MG;", "PC;")
        reads += if (tx) listOf("RM5;", "RM6;") else listOf("SM0;")
        if (extraMeters && tx) reads += listOf("RM4;", "RM3;", "RM7;")
        val r = catReads(reads)

        fun str(i: Int) = (r[i] as? String)
        fun num(i: Int, prefix: Int) = value(str(i), prefix)
        fun pct(raw: Int?, max: Int) = raw?.let { (it * 100 / max).coerceIn(0, 100) }

        val vfo = num(2, 2)          // VS0 = A, VS1 = B
        val aIsActive = vfo?.let { it == 0 } ?: !activeB
        activeB = !aIsActive
        val modeChar = str(4)?.trim()?.getOrNull(3)
        if (modeChar != null) modeCode = modeChar
        val mode = modeChar?.let { YaesuDsp.modeLabel(it) } ?: previous.mode
        narrow = (num(6, 3) ?: if (narrow) 1 else 0) != 0
        val group = YaesuDsp.modeGroup(mode)
        val widths = YaesuDsp.widthTable(group, narrow)
        val shIdx = str(7)?.trim()?.takeIf { it.length == 7 && it.startsWith("SH") }?.substring(4, 6)?.toIntOrNull()
        val bwHz = shIdx?.let { widths[it] }
        val txNow = str(5)?.trim()?.let { it != "TX0;" && it.startsWith("TX") } ?: previous.ptt
        val meterBase = 12
        val sRaw = if (tx) null else num(meterBase, 3)
        val po = if (tx) FlrigClient.parseRm(r[meterBase]) else null
        val swr = if (tx) FlrigClient.parseRm(r[meterBase + 1]) else null
        val extra = if (extraMeters && tx) meterBase + 2 else -1

        return previous.copy(
            connected = true,
            error = null,
            vfoAHz = str(0)?.let { value(it, 2)?.toLong() } ?: previous.vfoAHz,
            vfoBHz = str(1)?.let { value(it, 2)?.toLong() } ?: previous.vfoBHz,
            activeVfo = if (aIsActive) "A" else "B",
            split = num(3, 2)?.let { it != 0 } ?: previous.split,
            mode = mode,
            bandwidth = bwHz?.toString() ?: previous.bandwidth,
            bandwidths = widths.values.distinct().sorted().map { it.toString() },
            ptt = txNow,
            smeter = pct(sRaw, 255) ?: if (tx) 0 else previous.smeter,
            powerMeter = pct(po, 255) ?: if (tx) previous.powerMeter else 0,
            swrMeter = pct(swr, 255) ?: if (tx) previous.swrMeter else 0,
            volume = pct(num(8, 3), 250),   // AG0 000..250
            rfGain = pct(num(9, 3), 30),    // RG0 000..030
            micGain = num(10, 2),
            powerSetting = num(11, 2),
            hasExtraMeters = extraMeters,
            alc = if (extra >= 0) FlrigClient.parseRm(r[extra]) ?: 0 else 0,
            comp = if (extra >= 0) FlrigClient.parseRm(r[extra + 1]) ?: 0 else 0,
            drain = if (extra >= 0) FlrigClient.parseRm(r[extra + 2]) ?: 0 else 0,
        )
    }

    private fun fmt(format: String, vararg a: Any) = String.format(Locale.US, format, *a)
    private fun f(hz: Double) = fmt("%09d", hz.toLong())
    private fun scaled(percent: Int, max: Int) = (percent.coerceIn(0, 100) * max / 100)

    override fun setFrequency(hz: Double) = cat((if (activeB) "FB" else "FA") + f(hz) + ";")
    override fun setVfoA(hz: Double) = cat("FA" + f(hz) + ";")
    override fun setVfoB(hz: Double) = cat("FB" + f(hz) + ";")
    override fun modVfo(deltaHz: Double, vfoB: Boolean) {
        val cmd = if (vfoB) "FB" else "FA"
        val now = value(query("$cmd;"), 2) ?: return
        cat(cmd + f(now + deltaHz) + ";")
    }
    override fun setMode(mode: String) { YaesuDsp.modeCode(mode)?.let { cat("MD0$it;") } }
    override fun setModeB(mode: String) { setMode(mode) }   // the FT-891 has one MD command, for the active VFO
    override fun setBandwidth(bw: Int) {
        val group = YaesuDsp.modeGroup(YaesuDsp.modeLabel(modeCode) ?: return)
        val idx = YaesuDsp.widthTable(group, narrow).entries.firstOrNull { it.value == bw }?.key ?: return
        cat(YaesuDsp.width(1, idx))   // P2 = 1 switches the radio's WIDTH function on; with 0 it ignores the new width
    }
    override fun setActiveVfo(ab: String) { cat(if (ab == "B") "VS1;" else "VS0;") }
    override fun setSplit(on: Boolean) { cat(if (on) "ST1;" else "ST0;") }
    override fun swapVfo() { cat(YaesuDsp.swapVfo()) }
    override fun vfoAtoB() { cat("AB;") }
    override fun setPtt(on: Boolean) { cat(if (on) "TX1;" else "TX0;") }
    override fun tune() { cat("AC002;") }
    override fun setVolume(v: Int) { cat(fmt("AG0%03d;", scaled(v, 250))) }
    override fun setRfGain(v: Int) { cat(fmt("RG0%03d;", scaled(v, 30))) }
    override fun setMicGain(v: Int) { cat(YaesuDsp.micGain(v)) }
    override fun setPower(watts: Int) { cat(fmt("PC%03d;", watts.coerceIn(5, 100))) }
    override fun cwSpeed(wpm: Int) { cat(YaesuDsp.keySpeed(wpm)) }
    override fun cwGetWpm(): Int = value(query("KS;"), 2) ?: 20
    override fun cwText(text: String) { throw UnsupportedOperationException("Typed CW text isn't available over direct USB yet — use the keyer memories (1–5).") }
    override fun cwAbort() { throw UnsupportedOperationException("Stopping CW isn't available over direct USB yet.") }
    override fun userCmd(n: Int) { throw UnsupportedOperationException("FLRig user buttons need a FLRig connection.") }

    private companion object {
        const val TAG = "CatUSB"
        const val WRITE_TIMEOUT_MS = 500
        const val REPLY_TIMEOUT_MS = 600L
        const val IDLE_GAP_MS = 60L
        const val BATCH = 8
    }
}
