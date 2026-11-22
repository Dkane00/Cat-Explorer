// Copyright (C) 2026 David Kane (W6MZT)
// SPDX-License-Identifier: GPL-3.0-or-later
package net.kanenet.catexplorer.rig

/**
 * What the app needs from a radio connection. Two implementations: [FlrigClient] (through FLRig on a PC) and
 * [UsbCatClient] (straight to the radio's USB serial port). All calls block — run them off the main thread.
 */
interface RigClient {
    fun readStatic(): StaticInfo
    /** Fast poll — everything that changes. */
    fun poll(previous: RigState, extraMeters: Boolean = false): RigState

    fun setFrequency(hz: Double)
    fun setVfoA(hz: Double)
    fun setVfoB(hz: Double)
    fun modVfo(deltaHz: Double, vfoB: Boolean)
    fun setMode(mode: String)
    fun setModeB(mode: String)
    fun setBandwidth(bw: Int)
    fun setActiveVfo(ab: String)
    fun setSplit(on: Boolean)
    fun swapVfo()
    fun vfoAtoB()
    fun setPtt(on: Boolean)
    fun tune()
    fun setVolume(v: Int)
    fun setRfGain(v: Int)
    fun setMicGain(v: Int)
    fun setPower(watts: Int)
    fun cwSpeed(wpm: Int)
    fun cwText(text: String)
    fun cwAbort()
    fun cwGetWpm(): Int
    /** FLRig user-defined command button, 1..24 (25..48 = shifted). */
    fun userCmd(n: Int)

    /** Sends a raw CAT string (e.g. "FA;" on Yaesu) and returns the radio's reply. */
    fun catString(cmd: String): String
    /** Sends each read command and returns its reply (null where the radio gave none). */
    fun catReads(cmds: List<String>): List<Any?>

    /** Sends a Yaesu CAT set-command. */
    fun cat(cmd: String)

    fun readDsp(old: DspState): DspState {
        val d = YaesuDsp.parseDsp(catReads(YaesuDsp.DSP_READS), old)
        if (!d.memoryMode) return d
        // the channel name needs its own read; it's only shown in memory mode
        val tag = YaesuDsp.parseMemTag(runCatching { catString("MT%03d;".format(d.memChannel)) }.getOrNull(), d.memChannel)
        return if (tag != null) d.copy(memTag = tag) else d
    }

    /** Reads every memory channel (1..[FlrigClient.MEM_LAST]) with MT. [progress] gets each channel number before it is read, [found] each programmed channel as it turns up. */
    fun readMemories(progress: (Int) -> Unit, found: (MemChannel) -> Unit) {
        for (ch in 1..FlrigClient.MEM_LAST) {
            progress(ch)
            YaesuDsp.parseMemChannel(runCatching { catString("MT%03d;".format(ch)) }.getOrNull(), ch)?.let(found)
        }
    }

    /**
     * Writes one memory channel and reads it straight back in the same request. Returns what the radio now holds, or null if
     * the read-back wasn't understood.
     */
    fun writeMemory(m: MemChannel): MemChannel? =
        YaesuDsp.parseMemChannel(catString(YaesuDsp.memWrite(m) + "MT%03d;".format(m.ch)), m.ch)

    fun readCw(old: CwState): CwState = YaesuDsp.parseCw(catReads(YaesuDsp.CW_READS), old)
    fun readFm(old: FmState): FmState = YaesuDsp.parseFm(catReads(YaesuDsp.FM_READS), old)
    fun readVoice(old: VoiceState): VoiceState = YaesuDsp.parseVoice(catReads(YaesuDsp.VOICE_READS), old)
    fun readMic(old: MicState): MicState = YaesuDsp.parseMic(catReads(YaesuDsp.MIC_READS), old)

    data class StaticInfo(
        val xcvr: String,
        val version: String,
        val modes: List<String>,
        /** Empty = the connection fills [RigState.bandwidths] itself on every poll. */
        val bandwidths: List<String>,
        val maxPower: Int,
    )
}
