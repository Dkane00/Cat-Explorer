package net.kanenet.catexplorer

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kanenet.catexplorer.rig.CwState
import net.kanenet.catexplorer.rig.DspMenu
import net.kanenet.catexplorer.rig.DspState
import net.kanenet.catexplorer.rig.EqBand
import net.kanenet.catexplorer.rig.EqState
import net.kanenet.catexplorer.rig.FmState
import net.kanenet.catexplorer.rig.YaesuDsp
import net.kanenet.catexplorer.rig.FlrigClient
import net.kanenet.catexplorer.rig.MicState
import net.kanenet.catexplorer.rig.VoiceState
import net.kanenet.catexplorer.rig.RigState
import net.kanenet.catexplorer.ui.ThemeColor
import net.kanenet.catexplorer.ui.ThemeSettings
import net.kanenet.catexplorer.ui.toArgb32
import androidx.compose.ui.graphics.Color

data class HistorySample(val s: Int, val pwr: Int, val swr: Int, val tx: Boolean)

const val HISTORY_LEN = 600
const val CW_SLOTS = 6
const val CARRIER_MAX_MS = 60_000L
private val DEFAULT_CW = listOf("CQ CQ DE W6MZT W6MZT K", "599 TU", "DE W6MZT", "QRZ?", "AGN PSE", "73 SK")

class RigViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("catexplorer", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(RigState())
    val state: StateFlow<RigState> = _state.asStateFlow()

    private val _host = MutableStateFlow(prefs.getString("host", "192.168.1.100") ?: "")
    val host = _host.asStateFlow()
    private val _port = MutableStateFlow(prefs.getInt("port", 12345))
    val port = _port.asStateFlow()

    /** Lines of the raw CAT console: "> cmd" and "< reply". */
    private val _console = MutableStateFlow<List<String>>(emptyList())
    val console = _console.asStateFlow()

    /** Rolling meter history for the signal trace (newest last), ~120 ms per sample. */
    private val _history = MutableStateFlow<List<HistorySample>>(emptyList())
    val history = _history.asStateFlow()

    private fun loadTheme(): ThemeSettings {
        fun colors(mode: String) = ThemeColor.entries.mapNotNull { r ->
            if (prefs.contains("tc_${mode}_${r.name}")) r to prefs.getInt("tc_${mode}_${r.name}", 0) else null
        }.toMap()
        return ThemeSettings(prefs.getBoolean("theme_dark", true), colors("dark"), colors("light"))
    }

    private val _theme = MutableStateFlow(loadTheme())
    /** Dark/light choice and any colours the user changed. */
    val theme = _theme.asStateFlow()

    private fun saveTheme() {
        val t = _theme.value
        prefs.edit().apply {
            putBoolean("theme_dark", t.dark)
            for (r in ThemeColor.entries) for ((mode, map) in listOf("dark" to t.darkColors, "light" to t.lightColors)) {
                val v = map[r]
                if (v != null) putInt("tc_${mode}_${r.name}", v) else remove("tc_${mode}_${r.name}")
            }
        }.apply()
    }

    fun setThemeDark(dark: Boolean) { _theme.value = _theme.value.copy(dark = dark); saveTheme() }

    /** Changes (or with null resets) one colour in the current mode; save = false is a live preview that isn't stored yet. */
    fun setThemeColor(role: ThemeColor, color: Color?, save: Boolean = true) {
        _theme.value = _theme.value.with(role, color)
        if (save) saveTheme()
    }

    fun resetThemeColors() { _theme.value = _theme.value.resetCurrent(); saveTheme() }

    private val _meterAnalog = MutableStateFlow(prefs.getBoolean("meter_analog", false))
    /** false = bar meters (default), true = analogue needle gauges. */
    val meterAnalog = _meterAnalog.asStateFlow()
    fun setMeterAnalog(on: Boolean) { _meterAnalog.value = on; prefs.edit().putBoolean("meter_analog", on).apply() }

    // ---- Tune button ----
    private val _tuneCarrier = MutableStateFlow(prefs.getBoolean("tune_carrier", false))
    /** false = the Tune button runs the radio's own tuner (default); true = it sends a low-power carrier for an external tuner. */
    val tuneCarrier = _tuneCarrier.asStateFlow()
    private val _tunePowerW = MutableStateFlow(prefs.getInt("tune_power", 5).coerceIn(5, 10))
    val tunePowerW = _tunePowerW.asStateFlow()
    fun setTuneCarrier(on: Boolean) { if (!on) stopCarrier(); _tuneCarrier.value = on; prefs.edit().putBoolean("tune_carrier", on).apply() }
    fun setTunePower(w: Int) { _tunePowerW.value = w.coerceIn(5, 10); prefs.edit().putInt("tune_power", _tunePowerW.value).apply() }

    private val _carrierLeft = MutableStateFlow(0)
    /** Seconds left on the carrier timer; 0 when no carrier is being sent. */
    val carrierLeft = _carrierLeft.asStateFlow()
    private var carrierJob: Job? = null

    fun stopCarrier() { carrierJob?.cancel() }

    /** Carrier mode: tap to start, tap again to stop. Goes to CW at the tune power, keys the radio, then puts mode/width/power back. */
    fun toggleCarrier() {
        if (carrierJob?.isActive == true) { stopCarrier(); return }
        val c = client ?: return
        val st = _state.value
        val watts = _tunePowerW.value
        carrierJob = viewModelScope.launch(Dispatchers.IO) {
            var keyed = false
            var savedPower: String? = null
            var savedWidth: String? = null
            try {
                val cwMode = listOf("CW-U", "CW", "CW-L").firstOrNull { it in st.modes } ?: "CW-U"
                savedPower = Regex("PC(\\d{3})").find(c.catString("PC;"))?.groupValues?.get(1)
                savedWidth = Regex("SH0\\d{3}").find(c.catString("SH0;"))?.value
                if (savedPower == null) { _state.value = _state.value.copy(error = "Tune: couldn't read the radio's power, so nothing was sent"); return@launch }
                if (st.mode != cwMode) c.setMode(cwMode)
                c.catString("PC%03d;PC;".format(watts))
                keyed = true
                c.setPtt(true)
                val start = System.currentTimeMillis()
                while (isActive) {
                    val elapsed = System.currentTimeMillis() - start
                    if (elapsed >= CARRIER_MAX_MS) break
                    if (elapsed > 2000 && !_state.value.ptt) break      // keyed off at the radio, or the link dropped
                    _carrierLeft.value = ((CARRIER_MAX_MS - elapsed) / 1000 + 1).toInt()
                    delay(200)
                }
            } catch (_: Exception) {
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) {
                    runCatching { if (keyed) c.setPtt(false) }
                    runCatching { if (st.mode.isNotEmpty() && st.mode != _state.value.mode) c.setMode(st.mode) }
                    runCatching { savedPower?.let { c.catString("PC$it;PC;") } }
                    runCatching { savedWidth?.let { c.catString("$it;SH0;") } }
                    _carrierLeft.value = 0
                }
            }
        }
    }

    private val _cwWpm = MutableStateFlow(20)
    val cwWpm = _cwWpm.asStateFlow()

    private val _cwMemories = MutableStateFlow(List(CW_SLOTS) { prefs.getString("cw$it", DEFAULT_CW[it]) ?: "" })
    val cwMemories = _cwMemories.asStateFlow()

    fun setCwMemory(i: Int, text: String) {
        _cwMemories.value = _cwMemories.value.toMutableList().also { it[i] = text }
        prefs.edit().putString("cw$i", text).apply()
    }

    fun setCwWpm(w: Int) { _cwWpm.value = w; send { cwSpeed(w) } }

    private val _dsp = MutableStateFlow(DspState())
    val dsp = _dsp.asStateFlow()
    private val _mic = MutableStateFlow(MicState())
    val mic = _mic.asStateFlow()

    /** Re-reads the DSP/mic settings from the radio. The panels call this a few times a second while they're open. */
    suspend fun refreshDsp() {
        val c = client ?: return
        runCatching { withContext(Dispatchers.IO) { c.readDsp(_dsp.value) } }.onSuccess { _dsp.value = it }
    }

    private val _cw = MutableStateFlow(CwState())
    val cw = _cw.asStateFlow()
    suspend fun refreshCw() {
        val c = client ?: return
        runCatching { withContext(Dispatchers.IO) { c.readCw(_cw.value) } }.onSuccess { _cw.value = it }
    }
    fun setCw(cmd: String, update: CwState.() -> CwState) {
        _cw.value = _cw.value.update()
        send { cat(cmd) }
    }

    private val _fm = MutableStateFlow(FmState())
    val fm = _fm.asStateFlow()
    suspend fun refreshFm() {
        val c = client ?: return
        runCatching { withContext(Dispatchers.IO) { c.readFm(_fm.value) } }.onSuccess { _fm.value = it }
    }
    fun setFm(cmd: String, update: FmState.() -> FmState) {
        _fm.value = _fm.value.update()
        send { cat(cmd) }
    }

    private val _voice = MutableStateFlow(VoiceState())
    val voice = _voice.asStateFlow()
    suspend fun refreshVoice() {
        val c = client ?: return
        runCatching { withContext(Dispatchers.IO) { c.readVoice(_voice.value) } }.onSuccess { _voice.value = it }
    }
    fun setVoice(cmd: String, update: VoiceState.() -> VoiceState) {
        _voice.value = _voice.value.update()
        send { cat(cmd) }
    }

    suspend fun refreshMic() {
        val c = client ?: return
        runCatching { withContext(Dispatchers.IO) { c.readMic(_mic.value) } }.onSuccess { _mic.value = it }
    }

    /** Sends a CAT set-command and updates the on-screen copy straight away; the next refresh confirms it. */
    fun setDsp(cmd: String, update: DspState.() -> DspState) {
        _dsp.value = _dsp.value.update()
        send { cat(cmd) }
    }

    private val _dspMenu = MutableStateFlow(DspMenu.decode(prefs.getString("dspmenu", null)))
    /** Contour level/width, APF width and notch width. Not readable from the radio — what was last set from the app. */
    val dspMenu = _dspMenu.asStateFlow()

    fun setDspMenu(cmd: String, update: DspMenu.() -> DspMenu) {
        _dspMenu.value = _dspMenu.value.update()
        prefs.edit().putString("dspmenu", _dspMenu.value.encode()).apply()
        send { cat(cmd) }
    }

    private val _eq = MutableStateFlow(EqState.decode(prefs.getString("eq", null)))
    /** Mic EQ band settings. The radio can't report these, so this is what was last set from the app (remembered on the tablet). */
    val eq = _eq.asStateFlow()

    fun setEqBand(parametric: Boolean, band: Int, new: EqBand) {
        val old = _eq.value.set(parametric).bands[band]
        _eq.value = _eq.value.with(parametric, band, new)
        prefs.edit().putString("eq", _eq.value.encode()).apply()
        send {
            val enabling = old.freqIdx == 0 && new.freqIdx > 0   // the radio's own values for this band are unknown, so write them all
            if (new.freqIdx != old.freqIdx) cat(YaesuDsp.eqFreq(parametric, band, new.freqIdx))
            if (enabling || new.level != old.level) cat(YaesuDsp.eqLevel(parametric, band, new.level))
            if (enabling || new.bw != old.bw) cat(YaesuDsp.eqBw(parametric, band, new.bw))
        }
    }

    fun setMic(cmd: String, update: MicState.() -> MicState) {
        _mic.value = _mic.value.update()
        send { cat(cmd) }
    }

    private var client: FlrigClient? = null
    private var pollJob: Job? = null

    fun connect(host: String, port: Int) {
        prefs.edit().putString("host", host).putInt("port", port).apply()
        _host.value = host; _port.value = port
        disconnect()
        val c = FlrigClient(host.trim(), port)
        client = c
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            _state.value = RigState(error = "Connecting…")
            var failures = 0
            var info: FlrigClient.StaticInfo? = null
            var tick = 0
            while (isActive) {
                try {
                    if (info == null || tick % 100 == 0) info = c.readStatic()
                    val i = info!!
                    val next = c.poll(_state.value, FlrigClient.supportsYaesuMeters(i.xcvr)).copy(
                        xcvr = i.xcvr, flrigVersion = i.version, modes = i.modes,
                        bandwidths = i.bandwidths, maxPower = i.maxPower,
                    )
                    ensureActive()   // a poll that was in flight when Disconnect was pressed must not bring the screen back
                    _state.value = next
                    _history.value = (_history.value + HistorySample(next.smeter, next.powerMeter, next.swrMeter, next.ptt)).takeLast(HISTORY_LEN)
                    if (tick == 0) _cwWpm.value = runCatching { c.cwGetWpm() }.getOrDefault(_cwWpm.value)
                    failures = 0
                    tick++
                    delay(250)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    // one slow reply shouldn't flash an error; only report after repeated failures
                    if (failures >= 3 || !_state.value.connected) _state.value = _state.value.copy(connected = false, error = e.message ?: "Connection lost")
                    delay(if (failures < 5) 500 else 2000)
                }
            }
        }
    }

    fun disconnect() {
        stopCarrier()
        pollJob?.cancel(); pollJob = null; client = null
        _history.value = emptyList()
        _state.value = RigState()
    }

    /** Runs a command on the IO thread; the next poll picks up the new radio state. */
    fun send(block: FlrigClient.() -> Unit) {
        val c = client ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try { c.block() } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Command failed: ${e.message}")
            }
        }
    }

    fun sendRawCat(cmd: String) {
        val c = client ?: return
        if (cmd.isBlank()) return
        viewModelScope.launch {
            val reply = withContext(Dispatchers.IO) {
                try { c.catString(cmd.trim()) } catch (e: Exception) { "error: ${e.message}" }
            }
            _console.value = (_console.value + "> ${cmd.trim()}" + "< ${reply.ifEmpty { "(no reply)" }}").takeLast(200)
        }
    }

    override fun onCleared() { disconnect() }
}
