// Copyright (C) 2026 David Kane (W6MZT)
// SPDX-License-Identifier: GPL-3.0-or-later
package net.kanenet.catexplorer.rig

/**
 * Everything the app can ask FLRig to do. Blocking calls — run them off the main thread
 * (the ViewModel does this).
 */
class FlrigClient(host: String, port: Int) : RigClient {
    private val rpc = XmlRpcClient(host, port)

    // ---- reads -------------------------------------------------------------------------

    /** One-time (and occasional) info that doesn't change while you operate. */
    /** The radio FLRig is set up for, as of the last [readStatic]. */
    @Volatile private var xcvr = ""

    override fun readStatic(): RigClient.StaticInfo {
        val r = rpc.multicall(
            listOf(
                "rig.get_xcvr" to emptyList(),
                "main.get_version" to emptyList(),
                "rig.get_modes" to emptyList(),
                "rig.get_bws" to emptyList(),
                "rig.get_pwrmax" to emptyList(),
            )
        )
        val bws = (r[3] as? List<*>)?.let { first ->
            // FLRig returns [[ "Label", bw1, bw2... ]] — first element is a heading
            val inner = (first.firstOrNull() as? List<*>) ?: first
            inner.drop(if (inner.firstOrNull()?.toString()?.toIntOrNull() == null) 1 else 0).map { it.toString() }
        } ?: emptyList()
        xcvr = r[0]?.toString().orEmpty()
        return RigClient.StaticInfo(
            xcvr = xcvr,
            version = r[1]?.toString().orEmpty(),
            modes = (r[2] as? List<*>)?.map { it.toString() } ?: emptyList(),
            bandwidths = bws,
            maxPower = r[4]?.toString()?.trim()?.toIntOrNull() ?: 100,
        )
    }

    /** Fast poll — everything that changes. */
    override fun poll(previous: RigState, extraMeters: Boolean): RigState {
        // ALC / COMP / ID only mean anything while transmitting, so only ask the radio for them then.
        val readExtras = extraMeters && previous.ptt
        val r = rpc.multicall(
            listOf<Pair<String, List<Any>>>(
                "rig.get_vfoA" to emptyList(),   // 0
                "rig.get_vfoB" to emptyList(),   // 1
                "rig.get_AB" to emptyList(),     // 2
                "rig.get_split" to emptyList(),  // 3
                "rig.get_mode" to emptyList(),   // 4
                "rig.get_bw" to emptyList(),     // 5
                "rig.get_ptt" to emptyList(),    // 6
                "rig.get_smeter" to emptyList(), // 7
                "rig.get_pwrmeter" to emptyList(), // 8
                "rig.get_swrmeter" to emptyList(), // 9
                "rig.get_volume" to emptyList(), // 10
                "rig.get_rfgain" to emptyList(), // 11
                "rig.get_micgain" to emptyList(), // 12
                "rig.get_power" to emptyList(),  // 13
                "rig.get_modeB" to emptyList(),  // 14
                "rig.get_SWR" to emptyList(),    // 15
                "rig.get_DBM" to emptyList(),    // 16
            ) + if (readExtras) listOf<Pair<String, List<Any>>>(
                "rig.cat_string" to listOf<Any>("RM4;"), // 17 ALC
                "rig.cat_string" to listOf<Any>("RM3;"), // 18 COMP
                "rig.cat_string" to listOf<Any>("RM7;"), // 19 ID
            ) else emptyList()
        )
        fun num(i: Int): Int? = (r[i] as? Number)?.toInt() ?: (r[i] as? String)?.trim()?.toIntOrNull()
        fun hz(i: Int): Long = (r[i] as? Number)?.toLong() ?: (r[i] as? String)?.trim()?.toLongOrNull() ?: 0L
        val bw = (r[5] as? List<*>)?.firstOrNull()?.toString().orEmpty()
        return previous.copy(
            connected = true,
            error = null,
            vfoAHz = hz(0),
            vfoBHz = hz(1),
            activeVfo = (r[2] as? String)?.trim()?.ifEmpty { "A" } ?: "A",
            split = (num(3) ?: 0) != 0,
            mode = (r[4] as? String)?.trim().orEmpty(),
            modeB = (r[14] as? String)?.trim().orEmpty(),
            bandwidth = bw,
            ptt = (num(6) ?: 0) != 0,
            smeter = (num(7) ?: 0).coerceIn(0, 100),
            powerMeter = (num(8) ?: 0).coerceIn(0, 100),
            swrMeter = (num(9) ?: 0).coerceIn(0, 100),
            volume = num(10),
            rfGain = num(11),
            micGain = num(12),
            powerSetting = num(13),
            swrRatio = (r[15] as? String)?.trim().orEmpty(),
            dbm = num(16),
            hasExtraMeters = extraMeters,
            alc = if (readExtras) parseRm(r[17]) ?: 0 else 0,
            comp = if (readExtras) parseRm(r[18]) ?: 0 else 0,
            drain = if (readExtras) parseRm(r[19]) ?: 0 else 0,
        )
    }

    // ---- writes ------------------------------------------------------------------------

    override fun setFrequency(hz: Double) { rpc.call("rig.set_frequency", hz) }
    override fun setVfoA(hz: Double) { rpc.call("rig.set_vfoA", hz) }
    override fun setVfoB(hz: Double) { rpc.call("rig.set_vfoB", hz) }
    override fun modVfo(deltaHz: Double, vfoB: Boolean) { rpc.call(if (vfoB) "rig.mod_vfoB" else "rig.mod_vfoA", deltaHz) }
    override fun setMode(mode: String) { rpc.call("rig.set_mode", mode) }
    override fun setModeB(mode: String) { rpc.call("rig.set_modeB", mode) }
    override fun setBandwidth(bw: Int) { rpc.call("rig.set_bandwidth", bw) }
    override fun setActiveVfo(ab: String) { rpc.call("rig.set_AB", ab) }
    override fun setSplit(on: Boolean) { rpc.call("rig.set_split", if (on) 1 else 0) }
    // FLRig's rig.swap does nothing on the FT-891, so use the radio's own SWAP VFO command (SV;); other radios use FLRig's
    override fun swapVfo() { if (RadioModel.of(xcvr) == RadioModel.FT891) cat(YaesuDsp.swapVfo()) else rpc.call("rig.swap") }
    override fun vfoAtoB() { rpc.call("rig.vfoA2B") }
    override fun setPtt(on: Boolean) { rpc.call("rig.set_ptt", if (on) 1 else 0) }
    override fun tune() { rpc.call("rig.tune") }
    override fun setVolume(v: Int) { rpc.call("rig.set_volume", v) }
    override fun setRfGain(v: Int) { rpc.call("rig.set_rfgain", v) }
    override fun setMicGain(v: Int) { rpc.call("rig.set_micgain", v) }
    override fun setPower(watts: Int) { rpc.call("rig.set_power", watts) }
    override fun cwSpeed(wpm: Int) { rpc.call("rig.cwio_set_wpm", wpm) }
    override fun cwText(text: String) { rpc.call("rig.cwio_text", text) }
    override fun cwAbort() { rpc.call("rig.cwio_send", 0) }
    override fun cwGetWpm(): Int = (rpc.call("rig.cwio_get_wpm") as? Number)?.toInt() ?: 20
    /** FLRig user-defined command button, 1..24 (25..48 = shifted). */
    override fun userCmd(n: Int) { rpc.call("rig.cmd", n) }

    /** Sends a raw CAT string (e.g. "FA;" on Yaesu) and returns FLRig's reply. */
    override fun catString(cmd: String): String = rpc.call("rig.cat_string", cmd)?.toString().orEmpty()

    companion object {
        /** Last memory channel number to look at (the FT-891 uses 1..99, then the P-1/P-2 and 60 m channels above that). */
        const val MEM_LAST = 125

        /** Radios whose Yaesu "RM" meter codes we've checked against the CAT manual. */
        fun supportsYaesuMeters(xcvr: String) = xcvr == "FT-891"
        fun supportsYaesuDsp(xcvr: String) = xcvr == "FT-891"

        /** Parses a Yaesu "RMnPPP;" reply (PPP = 0..255) into the number, or null. */
        fun parseRm(reply: Any?): Int? {
            val t = (reply as? String)?.trim() ?: return null
            if (t.length < 7 || !t.startsWith("RM")) return null
            return t.substring(3, 6).toIntOrNull()
        }
    }

    /** Sends a Yaesu CAT set-command, followed by a read of the same setting so FLRig gets a reply straight away. */
    override fun cat(cmd: String) { rpc.call("rig.cat_string", cmd + YaesuDsp.readbackFor(cmd)) }

    override fun catReads(cmds: List<String>): List<Any?> =
        rpc.multicall(cmds.map { "rig.cat_string" to listOf<Any>(it) })
}
