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
import net.kanenet.catexplorer.rig.RigClient
import net.kanenet.catexplorer.rig.UsbLink
import net.kanenet.catexplorer.rig.MemChannel
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
    private val _baud = MutableStateFlow(prefs.getInt("usb_baud", 9600))
    val baud = _baud.asStateFlow()

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

    /** Carrier mode: tap to start, tap again to stop. Goes to AM at the tune power, keys the radio, then puts mode/width/power back. */
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
                // AM, not CW: keying the radio over CAT in CW mode sends nothing until a key closes, but an AM carrier is steady RF
                val cwMode = "AM"
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
                    // the radio ignores commands for a moment after it drops out of transmit, so wait, then ask until the mode is really back
                    if (keyed) delay(500)
                    if (st.mode.isNotEmpty()) {
                        repeat(4) {
                            if (_state.value.mode == st.mode) return@repeat
                            runCatching { c.setMode(st.mode) }
                            delay(700)   // a poll runs about every 400 ms
                        }
                    }
                    runCatching { savedPower?.let { c.catString("PC$it;PC;") } }
                    // the width is refused while the radio is still in AM, so it goes after the mode
                    runCatching { savedWidth?.let { w -> repeat(3) { n -> if (n == 0 || !c.catString("SH0;").contains(w)) { c.catString("$w;SH0;"); delay(300) } } } }
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
        if (pollPaused) return
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

    private var client: RigClient? = null
    /** Set while the memory list is being read, so FLRig answers those reads without the meter polling competing. */
    @Volatile private var pollPaused = false
    private var pollJob: Job? = null

    fun connect(host: String, port: Int) {
        prefs.edit().putString("host", host).putInt("port", port).apply()
        _host.value = host; _port.value = port
        disconnect()
        startPolling { FlrigClient(host.trim(), port) }
    }

    /** Connects straight to the radio's USB serial port (OTG cable), no FLRig. */
    fun connectUsb(baud: Int) {
        prefs.edit().putInt("usb_baud", baud).apply()
        _baud.value = baud
        disconnect()
        startPolling { UsbLink.open(getApplication(), baud) }
    }

    private fun startPolling(open: () -> RigClient) {
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            _state.value = RigState(error = "Connecting…")
            val c = try { open() } catch (e: Exception) {
                _state.value = RigState(error = e.message ?: "Couldn't connect")
                return@launch
            }
            client = c
            var failures = 0
            var info: RigClient.StaticInfo? = null
            var tick = 0
            try {
                while (isActive) {
                    if (pollPaused) { delay(100); continue }
                    try {
                        if (info == null || tick % 100 == 0) info = c.readStatic()
                        val i = info!!
                        val polled = c.poll(_state.value, FlrigClient.supportsYaesuMeters(i.xcvr))
                        val next = polled.copy(
                            xcvr = i.xcvr, flrigVersion = i.version, modes = i.modes,
                            bandwidths = i.bandwidths.ifEmpty { polled.bandwidths }, maxPower = i.maxPower,
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
            } finally {
                (c as? java.io.Closeable)?.let { withContext(kotlinx.coroutines.NonCancellable) { runCatching { it.close() } } }
            }
        }
    }

    fun disconnect() {
        stopCarrier()
        stopWaterfall()
        pollJob?.cancel(); pollJob = null; client = null; pollPaused = false
        _history.value = emptyList()
        _state.value = RigState()
    }

    /** Runs a command on the IO thread; the next poll picks up the new radio state. */
    fun send(block: RigClient.() -> Unit) {
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

    private val _memories = MutableStateFlow<List<MemChannel>>(emptyList())
    /** The programmed memory channels from the last scan. */
    val memories = _memories.asStateFlow()
    private val _memScan = MutableStateFlow(-1)
    /** Channel being read during a scan, or -1 when no scan is running. */
    val memScan = _memScan.asStateFlow()

    fun loadMemories() {
        val c = client ?: return
        if (_memScan.value >= 0) return
        _memScan.value = 0
        _memories.value = emptyList()
        pollPaused = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    runCatching { c.readMemories({ _memScan.value = it }) { m -> _memories.value = _memories.value + m } }
                }
            } finally {
                pollPaused = false
                _memScan.value = -1
            }
        }
    }

    private val _memMessage = MutableStateFlow<String?>(null)
    /** Outcome of the last memory write (or a failure), shown in the memory table. */
    val memMessage = _memMessage.asStateFlow()
    fun clearMemMessage() { _memMessage.value = null }
    private var memBackedUp = false

    /** Saves every channel read so far, as the radio reported it, to a text file before the first write of a session. Returns the path, or null if it couldn't be saved. */
    private fun backupMemories(): String? = runCatching {
        val dir = getApplication<Application>().getExternalFilesDir(null) ?: return null
        val f = java.io.File(dir, "memory-backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".txt")
        f.writeText(_memories.value.joinToString("\n") { it.raw })
        f.absolutePath
    }.getOrNull()

    /** Writes one channel to the radio, then checks that the radio now holds what was sent. */
    fun writeMemory(m: MemChannel) {
        val c = client ?: return
        if (_memScan.value >= 0) return
        viewModelScope.launch {
            if (!memBackedUp) {
                val path = backupMemories()
                if (path == null) { _memMessage.value = "Not written: the backup of your memories couldn't be saved first."; return@launch }
                memBackedUp = true
            }
            pollPaused = true
            val back = try { withContext(Dispatchers.IO) { runCatching { c.writeMemory(m) }.getOrNull() } } finally { pollPaused = false }
            val sent = YaesuDsp.cleanTag(m.name)
            val ok = back != null && back.hz == m.hz && back.modeCode == m.modeCode && back.toneCode == m.toneCode && back.shiftCode == m.shiftCode && back.name.equals(sent.trim(), ignoreCase = true)
            if (back != null) _memories.value = (_memories.value.filter { it.ch != m.ch } + back).sortedBy { it.ch }
            _memMessage.value = if (ok) "Channel %03d saved.".format(m.ch)
                else if (back == null) "Channel %03d: the radio refused the write. Names can't contain the letter X.".format(m.ch)
                else "Channel %03d: the radio doesn't hold what was sent (it may have refused part of it). Check the row.".format(m.ch)
        }
    }

    /** Moves the radio to a memory channel (switching to memory mode first if it is on a VFO). */
    fun selectMemory(ch: Int, inMemoryMode: Boolean) = send {
        if (!inMemoryMode) cat(YaesuDsp.vfoMemory())
        cat("MC%03d;".format(ch))
    }

    // ---- Waterfall (receive audio from the PC's Panadapter) ----
    private val _audioPort = MutableStateFlow(prefs.getInt("audio_port", net.kanenet.catexplorer.audio.AUDIO_DEFAULT_PORT))
    val audioPort = _audioPort.asStateFlow()
    fun setAudioPort(p: Int) {
        _audioPort.value = p; prefs.edit().putInt("audio_port", p).apply()
        if (_waterfallOn.value) { stopWaterfall(); startWaterfall() }
    }

    private val _waterfallOn = MutableStateFlow(prefs.getBoolean("waterfall_on", false))
    val waterfallOn = _waterfallOn.asStateFlow()
    private val _listen = MutableStateFlow(false)
    /** Also play the radio's audio on the tablet. Always starts off. */
    val listen = _listen.asStateFlow()
    fun setListen(on: Boolean) { _listen.value = on }

    private val _waterfallRange = MutableStateFlow(prefs.getInt("waterfall_range", 45))
    /** Colour range in dB: smaller shows weak signals brighter. */
    val waterfallRange = _waterfallRange.asStateFlow()
    fun setWaterfallRange(db: Int) {
        _waterfallRange.value = db.coerceIn(20, 80); prefs.edit().putInt("waterfall_range", _waterfallRange.value).apply()
        engine?.rangeDb = _waterfallRange.value.toFloat()
        wide?.rangeDb = _waterfallRange.value.toFloat()
    }

    private val _waterfallTheme = MutableStateFlow(net.kanenet.catexplorer.audio.WaterfallThemes.named(prefs.getString("waterfall_theme", null)).name)
    /** Name of the waterfall colour theme (see WaterfallThemes). */
    val waterfallTheme = _waterfallTheme.asStateFlow()
    fun setWaterfallTheme(name: String) { _waterfallTheme.value = name; prefs.edit().putString("waterfall_theme", name).apply() }

    private val _bandEdges = MutableStateFlow(prefs.getBoolean("band_edges", true))
    /** Draw the US band and license-class edges on the waterfall. */
    val bandEdges = _bandEdges.asStateFlow()
    fun setBandEdges(on: Boolean) { _bandEdges.value = on; prefs.edit().putBoolean("band_edges", on).apply() }

    /** Spans the SDR view can show, in Hz. */
    val wideSpans = listOf(10_000, 20_000, 50_000, 100_000, 200_000)
    private val _wideSpan = MutableStateFlow(prefs.getInt("wide_span", 200_000).let { if (it in wideSpans) it else 200_000 })
    val wideSpan = _wideSpan.asStateFlow()
    fun stepWideSpan(dir: Int) {
        val i = (wideSpans.indexOf(_wideSpan.value) + dir).coerceIn(0, wideSpans.lastIndex)
        _wideSpan.value = wideSpans[i]; prefs.edit().putInt("wide_span", wideSpans[i]).apply()
        wide?.spanHz = wideSpans[i]
    }

    /** true while the PC is sending an SDR (wide) view rather than audio. */
    private val _wideMode = MutableStateFlow(false)
    val wideMode = _wideMode.asStateFlow()
    @Volatile var wide: net.kanenet.catexplorer.audio.WideEngine? = null
        private set

    /** "" while running normally; otherwise what's wrong (no panadapter, connecting…). */
    private val _waterfallStatus = MutableStateFlow("")
    val waterfallStatus = _waterfallStatus.asStateFlow()
    /** Goes up by one for every new waterfall row, so the screen knows to redraw. */
    private val _waterfallFrame = MutableStateFlow(0)
    val waterfallFrame = _waterfallFrame.asStateFlow()
    @Volatile var engine: net.kanenet.catexplorer.audio.WaterfallEngine? = null
        private set
    private var waterfallJob: Job? = null
    @Volatile private var audioLink: net.kanenet.catexplorer.audio.AudioLink? = null

    fun setWaterfallOn(on: Boolean) {
        _waterfallOn.value = on; prefs.edit().putBoolean("waterfall_on", on).apply()
        if (on) startWaterfall() else stopWaterfall()
    }

    /** Called by the screen when the waterfall card is showing; does nothing if already running. */
    fun startWaterfall() {
        if (!_waterfallOn.value || waterfallJob?.isActive == true) return
        waterfallJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val host = _host.value.trim()
                _waterfallStatus.value = "Connecting to the Panadapter on the PC…"
                val link = net.kanenet.catexplorer.audio.AudioLink(host, _audioPort.value).also { audioLink = it }
                try {
                    link.open()
                    _wideMode.value = link.spectrum
                    if (link.spectrum) {
                        val w = wide ?: net.kanenet.catexplorer.audio.WideEngine().also { wide = it }
                        w.rangeDb = _waterfallRange.value.toFloat()
                        w.spanHz = _wideSpan.value
                        _waterfallStatus.value = ""
                        link.pumpSpectrum({ isActive }) { f ->
                            if (w.add(f, frozen = _state.value.ptt)) _waterfallFrame.value++
                        }
                        throw java.io.IOException("The Panadapter stopped sending")
                    }
                    val e = engine?.takeIf { it.binHz == link.rate / 2048.0 }
                        ?: net.kanenet.catexplorer.audio.WaterfallEngine(link.rate).also { engine = it }
                    e.rangeDb = _waterfallRange.value.toFloat()
                    _waterfallStatus.value = ""
                    var lastDial = _state.value.activeHz
                    var lastMode = _state.value.mode
                    link.pump({ isActive }, { _listen.value }) { buf, n ->
                        val st = _state.value
                        // a blank reading (a poll that failed) must not wipe the picture
                        if (st.mode.isNotEmpty() && st.mode != lastMode) { e.clear(); lastMode = st.mode; lastDial = st.activeHz }
                        else if (st.activeHz > 0 && st.activeHz != lastDial) {
                            val sign = net.kanenet.catexplorer.audio.TuneMath.sense(st.mode).sign
                            if (lastDial == 0L) Unit
                            else if (sign == 0) e.clear() else e.shift((-sign * (st.activeHz - lastDial) / e.binHz).toInt())
                            lastDial = st.activeHz
                        }
                        if (e.add(buf, n, frozen = st.ptt)) _waterfallFrame.value++
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _waterfallStatus.value = e.message ?: "Audio connection lost"
                } finally {
                    link.close()
                }
                delay(3000)   // try again: the panadapter may simply not be started yet
            }
        }
    }

    fun stopWaterfall() {
        waterfallJob?.cancel(); waterfallJob = null
        audioLink?.close(); audioLink = null   // unblocks a read that is waiting on the network
        _waterfallStatus.value = ""
    }

    /**
     * A tap on the waterfall at [audioHz]: tunes so that signal lands where the mode wants it — on the CW pitch (snapping to
     * the strongest tone nearby), at 1500 Hz in DATA, or with the tapped point as the carrier in SSB.
     */
    fun waterfallTap(audioHz: Double) {
        val st = _state.value
        val e = engine ?: return
        val pitch = _dsp.value.keyPitchHz
        val hz = if (st.mode.startsWith("CW")) e.peakNear(audioHz, 60.0) else audioHz
        val dial = net.kanenet.catexplorer.audio.TuneMath.retune(hz, st.activeHz, st.mode, pitch) ?: return
        send { setFrequency(dial.toDouble()) }
    }

    /**
     * A tap on the SDR view at pixel column [x]: tunes the radio to that signal (snapping to the strongest signal within
     * ±300 Hz in CW and DATA, and putting it at 1500 Hz in DATA).
     */
    fun wideTap(x: Double) {
        val w = wide ?: return
        val st = _state.value
        // the picture isn't around the radio's dial (the Panadapter's test frequency, or not caught up yet): don't tune
        if (kotlin.math.abs(w.centreHz - st.activeHz) > w.spanHz / 2) return
        // CW and DATA signals are narrow: find the exact peak near the tap; SSB is tuned exactly where tapped
        val tapped = w.rfAt(x)
        val rf = if (st.mode.startsWith("CW") || st.mode.startsWith("DATA")) w.refinePeak(tapped, 300.0) else tapped
        send { setFrequency(net.kanenet.catexplorer.audio.TuneMath.wideRetune(rf, st.mode).toDouble()) }
    }

    override fun onCleared() { disconnect() }
}
