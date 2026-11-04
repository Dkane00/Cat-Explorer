package net.kanenet.catexplorer.rig

/** A snapshot of everything we show on screen. Meter values use FLRig's 0..100 scale. */
data class RigState(
    val connected: Boolean = false,
    val error: String? = null,
    val xcvr: String = "",
    val flrigVersion: String = "",
    val vfoAHz: Long = 0,
    val vfoBHz: Long = 0,
    val activeVfo: String = "A",
    val split: Boolean = false,
    val mode: String = "",
    val modeB: String = "",
    val bandwidth: String = "",
    val ptt: Boolean = false,
    val smeter: Int = 0,
    val powerMeter: Int = 0,
    val swrMeter: Int = 0,
    val swrRatio: String = "",
    val dbm: Int? = null,
    /** Yaesu-only extras read with the CAT "RM" command; raw 0..255. [hasExtraMeters] says whether the radio supports them. */
    val hasExtraMeters: Boolean = false,
    val alc: Int = 0,
    val comp: Int = 0,
    val drain: Int = 0,
    val volume: Int? = null,
    val rfGain: Int? = null,
    val micGain: Int? = null,
    val powerSetting: Int? = null,
    val maxPower: Int = 100,
    val modes: List<String> = emptyList(),
    val bandwidths: List<String> = emptyList(),
) {
    val activeHz: Long get() = if (activeVfo == "B") vfoBHz else vfoAHz
    val idleHz: Long get() = if (activeVfo == "B") vfoAHz else vfoBHz
}
