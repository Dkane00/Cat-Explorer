// Copyright (C) 2026 David Kane (W6MZT)
// SPDX-License-Identifier: GPL-3.0-or-later
package net.kanenet.catexplorer.rig

import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.Closeable
import java.io.IOException

/** Bytes in and out of a serial line; a USB serial port on the tablet, or a simulated radio in tests. */
interface ByteLink : Closeable {
    fun write(b: ByteArray)
    /** Reads what has arrived into [buf] within [timeoutMs]; returns the count (0 if nothing came). */
    fun read(buf: ByteArray, timeoutMs: Int): Int
}

class UsbByteLink(private val port: UsbSerialPort) : ByteLink {
    override fun write(b: ByteArray) = port.write(b, 500)
    override fun read(buf: ByteArray, timeoutMs: Int): Int = port.read(buf, timeoutMs)
    override fun close() { runCatching { port.close() } }
}

/**
 * Talks to an FT-857D (or the FT-897D, which shares its CAT language) straight over its CAT port, no FLRig. See [Ft857Cat]
 * for the protocol. The radio can report only its frequency, mode, S-meter, PO meter, PTT, split, high SWR and (through an
 * EEPROM read) which VFO is in use; everything else is set-only.
 *
 * Written from Yaesu's manual and Hamlib's driver, and tested against a simulated radio only; it hasn't met a real FT-857D.
 */
class Ft857Client(private val link: ByteLink, private val name: String = "FT-857D") : RigClient, Closeable {
    private val lock = Any()
    private val buf = ByteArray(64)

    @Volatile private var activeB = false
    @Volatile private var lastHz = 0L
    @Volatile private var vfoKnown = false

    // ---- serial plumbing ----

    private fun drain() {
        repeat(8) { if (link.read(buf, 5) <= 0) return }
    }

    private fun send(frame: ByteArray) {
        Log.d(TAG, "TX ${Ft857Cat.hex(frame)}")
        link.write(frame)
    }

    /** Sends [frame] and reads exactly [replyLen] bytes, or null if they don't all arrive within [timeoutMs]. */
    private fun transact(frame: ByteArray, replyLen: Int, timeoutMs: Long = REPLY_TIMEOUT_MS): ByteArray? = synchronized(lock) {
        drain()
        send(frame)
        val out = ByteArray(replyLen)
        var got = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (got < replyLen && System.currentTimeMillis() < deadline) {
            val n = link.read(buf, 20)
            for (i in 0 until n) if (got < replyLen) out[got++] = buf[i]
        }
        if (got < replyLen) null else out.also { Log.d(TAG, "RX ${Ft857Cat.hex(it)}") }
    }

    private fun command(frame: ByteArray) = synchronized(lock) { send(frame); Thread.sleep(GAP_MS) }

    /**
     * Brings the radio's five-byte framing into step and checks it's there: asks for the frequency and mode, and if no
     * sensible answer comes, sends one filler byte (which shifts the framing by one) and asks again. Five tries cover any
     * leftover bytes from an earlier, different conversation (such as an FT-891 "ID;" probe). Filler bytes that end up as a
     * command code are not a command, so nothing happens on the radio.
     */
    fun probe(): Pair<Long, String>? {
        repeat(5) { attempt ->
            if (attempt > 0) synchronized(lock) { send(byteArrayOf(Ft857Cat.FILLER.toByte())) }
            val r = transact(Ft857Cat.read(Ft857Cat.READ_FREQ_MODE), 5, PROBE_TIMEOUT_MS) ?: return@repeat
            Ft857Cat.parseFreqMode(r)?.let { return it }
        }
        return null
    }

    override fun close() {
        // never leave the radio transmitting if the link goes away
        runCatching { command(Ft857Cat.frame(Ft857Cat.PTT_OFF)) }
        link.close()
    }

    // ---- RigClient ----

    override fun readStatic(): RigClient.StaticInfo {
        if (probe() == null) throw IOException("No answer from the $name. Check its CAT RATE menu setting matches the baud rate chosen here.")
        return RigClient.StaticInfo(xcvr = name, version = "USB CAT", modes = Ft857Cat.MODES, bandwidths = emptyList(), maxPower = 100)
    }

    override fun poll(previous: RigState, extraMeters: Boolean): RigState {
        val fm = transact(Ft857Cat.read(Ft857Cat.READ_FREQ_MODE), 5)?.let { Ft857Cat.parseFreqMode(it) }
            ?: throw IOException("The $name stopped answering")
        val tx = transact(Ft857Cat.read(Ft857Cat.READ_TX), 1)?.let { Ft857Cat.parseTx(it[0].toInt() and 0xFF) }
        val keyed = tx?.ptt ?: previous.ptt
        val rx = if (!keyed) transact(Ft857Cat.read(Ft857Cat.READ_RX), 1)?.let { Ft857Cat.parseRx(it[0].toInt() and 0xFF) } else null
        // which VFO: an undocumented EEPROM read (as Hamlib does); if it gives nothing, the app's own count of A/B presses stands
        transact(Ft857Cat.readEeprom(Ft857Cat.EEPROM_VFO), 2)?.let { activeB = (it[0].toInt() and 0x01) != 0; vfoKnown = true }
        lastHz = fm.first
        val active = if (activeB) "B" else "A"
        return previous.copy(
            connected = true,
            error = null,
            vfoAHz = if (activeB) previous.vfoAHz else fm.first,
            vfoBHz = if (activeB) fm.first else previous.vfoBHz,
            activeVfo = active,
            split = tx?.split ?: previous.split,
            mode = fm.second,
            bandwidth = "",
            bandwidths = emptyList(),
            ptt = keyed,
            smeter = if (keyed) 0 else rx?.let { Ft857Cat.sToPercent(it.sMeter) } ?: previous.smeter,
            powerMeter = if (keyed) (tx?.po ?: 0) * 100 / 15 else 0,
            swrMeter = if (keyed && tx?.highSwr == true) 60 else 0,
            swrRatio = if (keyed) (if (tx?.highSwr == true) "HIGH" else "OK") else "",
            volume = null, rfGain = null, micGain = null, powerSetting = null,
            hasExtraMeters = false,
        )
    }

    override fun setFrequency(hz: Double) = command(Ft857Cat.setFrequency(hz.toLong()))

    /** The radio can only set the VFO in use, so the other one is reached by switching to it and back. */
    private fun setVfo(b: Boolean, hz: Double) {
        if (b == activeB) { setFrequency(hz); return }
        command(Ft857Cat.frame(Ft857Cat.VFO_TOGGLE))
        setFrequency(hz)
        command(Ft857Cat.frame(Ft857Cat.VFO_TOGGLE))
    }
    override fun setVfoA(hz: Double) = setVfo(false, hz)
    override fun setVfoB(hz: Double) = setVfo(true, hz)
    override fun modVfo(deltaHz: Double, vfoB: Boolean) {
        if (vfoB != activeB) return
        val now = transact(Ft857Cat.read(Ft857Cat.READ_FREQ_MODE), 5)?.let { Ft857Cat.parseFreqMode(it) }?.first ?: return
        setFrequency(now + deltaHz)
    }
    override fun setMode(mode: String) { Ft857Cat.modeCode(mode)?.let { command(Ft857Cat.frame(Ft857Cat.SET_MODE, it, 0, 0, 0)) } }
    override fun setModeB(mode: String) = setMode(mode)
    override fun setBandwidth(bw: Int) = unsupported("Choosing a filter width")
    override fun setActiveVfo(ab: String) {
        if ((ab == "B") != activeB) { command(Ft857Cat.frame(Ft857Cat.VFO_TOGGLE)); if (!vfoKnown) activeB = !activeB }
    }
    override fun setSplit(on: Boolean) = command(Ft857Cat.frame(if (on) Ft857Cat.SPLIT_ON else Ft857Cat.SPLIT_OFF))
    /** The FT-857D's A/B key: switches to the other VFO. */
    override fun swapVfo() { command(Ft857Cat.frame(Ft857Cat.VFO_TOGGLE)); if (!vfoKnown) activeB = !activeB }
    override fun vfoAtoB() = unsupported("Copying VFO A to B")
    override fun setPtt(on: Boolean) = command(Ft857Cat.frame(if (on) Ft857Cat.PTT_ON else Ft857Cat.PTT_OFF))
    override fun tune() = unsupported("Starting a tuner")
    override fun setVolume(v: Int) = unsupported("Volume")
    override fun setRfGain(v: Int) = unsupported("RF gain")
    override fun setMicGain(v: Int) = unsupported("Mic gain")
    override fun setPower(watts: Int) = unsupported("Transmit power")
    override fun cwSpeed(wpm: Int) = unsupported("CW speed")
    override fun cwText(text: String) = unsupported("Sending CW text")
    override fun cwAbort() = unsupported("Stopping CW")
    override fun cwGetWpm(): Int = unsupported("Reading the CW speed")
    override fun userCmd(n: Int) = unsupported("FLRig user buttons")

    // ---- FT-857D extras ----

    override fun setLock(on: Boolean) = command(Ft857Cat.frame(if (on) Ft857Cat.LOCK_ON else Ft857Cat.LOCK_OFF))
    override fun setClarifier(on: Boolean) = command(Ft857Cat.frame(if (on) Ft857Cat.CLAR_ON else Ft857Cat.CLAR_OFF))
    override fun setClarifierOffset(hz: Int) = command(Ft857Cat.clarifierOffset(hz))
    override fun setRepeaterShift(shift: Ft857Cat.Shift) = command(Ft857Cat.repeaterShift(shift))
    override fun setRepeaterOffset(hz: Long) = command(Ft857Cat.repeaterOffset(hz))
    override fun setToneMode(mode: Ft857Cat.ToneMode) = command(Ft857Cat.toneMode(mode))
    override fun setCtcss(hz: Double) = command(Ft857Cat.ctcssTone(hz))
    override fun setDcs(code: Int) = command(Ft857Cat.dcsCode(code))

    /**
     * The console speaks bytes: type five hex bytes ("00 00 00 00 03"). The reply is whatever comes back, also in hex.
     */
    override fun catString(cmd: String): String {
        val bytes = Ft857Cat.parseHex(cmd) ?: return "Type a five-byte command in hex, e.g. 00 00 00 00 03 (read frequency and mode)"
        return synchronized(lock) {
            drain()
            send(bytes)
            val sb = ArrayList<Byte>()
            val deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) { val n = link.read(buf, 30); for (i in 0 until n) sb += buf[i] }
            Ft857Cat.hex(sb.toByteArray())
        }
    }

    override fun cat(cmd: String) { Ft857Cat.parseHex(cmd)?.let { command(it) } }

    /** The FT-891's text reads mean nothing here: every one gets no answer. */
    override fun catReads(cmds: List<String>): List<Any?> = cmds.map { null }

    private fun unsupported(what: String): Nothing = throw UnsupportedOperationException("$what isn't possible on the $name over CAT.")

    private companion object {
        const val TAG = "Ft857"
        const val REPLY_TIMEOUT_MS = 400L
        const val PROBE_TIMEOUT_MS = 300L
        /** A short pause after each set command: the radio is slow to take the next one. */
        const val GAP_MS = 30L
    }
}
