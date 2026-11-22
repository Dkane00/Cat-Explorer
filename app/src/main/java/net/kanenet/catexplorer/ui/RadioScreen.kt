// Copyright (C) 2026 David Kane (W6MZT)
// SPDX-License-Identifier: GPL-3.0-or-later
package net.kanenet.catexplorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kanenet.catexplorer.RigViewModel
import net.kanenet.catexplorer.rig.FlrigClient
import net.kanenet.catexplorer.rig.RigState
import net.kanenet.catexplorer.rig.YaesuDsp
import java.util.Locale

private val BANDS = listOf(
    "160" to 1_840_000L, "80" to 3_573_000L, "60" to 5_357_000L, "40" to 7_074_000L, "30" to 10_136_000L,
    "20" to 14_074_000L, "17" to 18_100_000L, "15" to 21_074_000L, "12" to 24_915_000L, "10" to 28_074_000L,
    "6" to 50_313_000L, "2" to 144_174_000L, "70cm" to 432_000_000L,
)

/**
 * Where the Band button sends the radio: a phone calling / well-used frequency and its usual mode on each band, keyed by the
 * same names as [BANDS]. 30 m has no phone allocation, so it gets its digital frequency and DATA-U. 60 m is channel 2's dial
 * frequency (USB).
 */
private val BANDS_PHONE = listOf(
    Triple("160", 1_900_000L, "LSB"), Triple("80", 3_900_000L, "LSB"), Triple("60", 5_346_500L, "USB"),
    Triple("40", 7_200_000L, "LSB"), Triple("30", 10_136_000L, "DATA-U"), Triple("20", 14_300_000L, "USB"),
    Triple("17", 18_130_000L, "USB"), Triple("15", 21_300_000L, "USB"), Triple("12", 24_950_000L, "USB"),
    Triple("10", 28_400_000L, "USB"), Triple("6", 50_125_000L, "USB"), Triple("2", 146_520_000L, "FM"),
    Triple("70cm", 446_000_000L, "FM"),
)

/** Amateur band edges in Hz, keyed by the same names as [BANDS]. */
private val BAND_EDGES = listOf(
    "160" to (1_800_000L..2_000_000L), "80" to (3_500_000L..4_000_000L), "60" to (5_300_000L..5_410_000L),
    "40" to (7_000_000L..7_300_000L), "30" to (10_100_000L..10_150_000L), "20" to (14_000_000L..14_350_000L),
    "17" to (18_068_000L..18_168_000L), "15" to (21_000_000L..21_450_000L), "12" to (24_890_000L..24_990_000L),
    "10" to (28_000_000L..29_700_000L), "6" to (50_000_000L..54_000_000L), "2" to (144_000_000L..148_000_000L),
    "70cm" to (420_000_000L..450_000_000L),
)

/** Band name for a frequency, or null when it's outside the ham bands. */
private fun bandOf(hz: Long): String? = BAND_EDGES.firstOrNull { hz in it.second }?.first

private fun bandLabel(name: String?): String = when {
    name == null -> "GEN"
    name.endsWith("cm") -> name
    else -> "$name m"
}

/**
 * Reads a typed frequency as kHz or MHz (or Hz). A trailing "k"/"khz", "m"/"mhz" or "hz" decides it; without one the
 * size does (and a 7.200.100 style entry is MHz): up to 60 is MHz (the FT-891 tunes 30 kHz to 56 MHz), up to 99999 is kHz, anything bigger is Hz.
 */
fun parseFrequencyHz(input: String): Double? {
    val t = input.trim().lowercase().replace(",", ".").replace(" ", "")
    val number = t.takeWhile { it.isDigit() || it == '.' }
    val unit = t.removePrefix(number)
    // the dotted form the screen shows, MHz.kHz.Hz (7.200.100): digits after the second dot are the rest of the kHz
    if (number.count { it == '.' } == 2 && unit.isEmpty()) {
        val (m, k, rest) = number.split(".")
        if (m.isEmpty() || k.isEmpty() || !m.all(Char::isDigit) || !k.all(Char::isDigit) || !rest.all(Char::isDigit)) return null
        val frac = if (rest.isEmpty()) 0.0 else ("0." + rest).toDouble()
        return m.toDouble() * 1_000_000 + k.toDouble() * 1_000 + frac * 1_000
    }
    val v = number.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
    return when (unit) {
        "m", "mhz" -> v * 1_000_000
        "k", "khz" -> v * 1_000
        "hz" -> v
        "" -> if (v <= 60) v * 1_000_000 else if (v < 100_000) v * 1_000 else v
        else -> null
    }
}

fun formatHz(hz: Long): String {
    val mhz = hz / 1_000_000
    val khz = (hz / 1_000) % 1_000
    val h = hz % 1_000
    return String.format(Locale.US, "%d.%03d.%03d", mhz, khz, h)
}

@Composable
fun RadioScreen(vm: RigViewModel, onSettings: () -> Unit) {
    val s by vm.state.collectAsState()
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840
    var showConsole by remember { mutableStateOf(false) }
    val analog by vm.meterAnalog.collectAsState()

    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FrequencyPanel(s, vm)
        HistoryCard(s, vm)
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MeterPanel(s, analog, Modifier.weight(1f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ControlsPanel(s, vm)
                    StatusBar(s, onConsole = { showConsole = !showConsole }, onSettings = onSettings, onDisconnect = vm::disconnect)
                }
            }
        } else {
            MeterPanel(s, analog, Modifier.fillMaxWidth())
            ControlsPanel(s, vm)
            StatusBar(s, onConsole = { showConsole = !showConsole }, onSettings = onSettings, onDisconnect = vm::disconnect)
        }
        var tab by remember { mutableIntStateOf(0) }
        // the digital-mode band shortcuts, FLRig's CW keyer and FLRig's user buttons all belong to the FLRig connection;
        // over USB there is no PC running digital modes and no FLRig, so the card is left out
        if (!s.directUsb) Card(Modifier.fillMaxWidth()) {
            val tabs = listOf("Bands", "CW", "FLRig buttons")
            TabRow(tab.coerceAtMost(tabs.lastIndex), containerColor = Color.Transparent) {
                tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
            }
            Spacer(Modifier.height(8.dp))
            when (tab) {
                0 -> BandPanel(vm)
                1 -> CwPanel(vm)
                else -> UserButtonPanel(vm)
            }
        }
        if (showConsole) ConsolePanel(vm)
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).padding(12.dp),
        content = content,
    )
}

@Composable
private fun HistoryCard(s: RigState, vm: RigViewModel) {
    val history by vm.history.collectAsState()
    val waterfallOn by vm.waterfallOn.collectAsState()
    // over FLRig the waterfall comes from the PC's Panadapter; over USB only from an SDR plugged into the tablet itself
    val tabletSdr by vm.tabletSdr.collectAsState()
    if (s.directUsb) LaunchedEffect(Unit) { while (true) { vm.checkTabletSdr(); kotlinx.coroutines.delay(3000) } }
    val sdrplay by vm.sdrplay.collectAsState()
    // an SDRplay can't be run by the tablet, but the tab still shows so the waterfall can say why
    val canWaterfall = !s.directUsb || tabletSdr || sdrplay
    val showWaterfall = waterfallOn && canWaterfall
    val th = LocalThemeSettings.current
    @Composable fun Tab(label: String, selected: Boolean, onClick: () -> Unit) = Text(
        label, fontSize = 12.sp,
        color = if (selected) th[ThemeColor.ACCENT] else th[ThemeColor.LABEL],
        fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else null,
        textDecoration = if (selected) androidx.compose.ui.text.style.TextDecoration.Underline else null,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 4.dp),
    )
    val tabs: @Composable RowScope.() -> Unit = {
        Tab("Signal history", !showWaterfall) { vm.setWaterfallOn(false) }
        if (canWaterfall) Tab("Waterfall", showWaterfall) { vm.setWaterfallOn(true) }
    }
    // signal history keeps its original height; on the waterfall tab the card grows to fit the full-height waterfall
    Card(Modifier.fillMaxWidth().then(if (showWaterfall) Modifier else Modifier.height(121.dp))) {
        if (showWaterfall) WaterfallPanel(s, vm, Modifier.fillMaxWidth(), header = tabs)
        else HistoryPanel(history, Modifier.fillMaxSize(), header = tabs)
    }
}

@Composable
private fun StatusBar(s: RigState, onConsole: () -> Unit, onSettings: () -> Unit, onDisconnect: () -> Unit) {
    val th = LocalThemeSettings.current
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val color = when {
                !s.connected -> th[ThemeColor.DANGER]
                s.ptt -> th[ThemeColor.DANGER]
                else -> th[ThemeColor.OK]
            }
            Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).background(color))
            Text(
                if (s.connected) (if (s.ptt) "TX" else "RX") + "  ${s.xcvr}" else (s.error ?: "Disconnected"),
                fontSize = 16.sp, modifier = Modifier.weight(1f),
            )
            if (s.flrigVersion.isNotEmpty()) Text(if (s.flrigVersion.startsWith("USB")) s.flrigVersion else "FLRig ${s.flrigVersion}", fontSize = 12.sp, color = th[ThemeColor.LABEL])
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onSettings) { Text("Settings") }
            TextButton(onConsole) { Text("CAT console") }
            TextButton(onDisconnect) { Text("Disconnect") }
        }
    }
}

@Composable
private fun FrequencyPanel(s: RigState, vm: RigViewModel) {
    val th = LocalThemeSettings.current
    var editing by remember { mutableStateOf(false) }
    var editingIdle by remember { mutableStateOf(false) }   // editing the other VFO's frequency, without switching to it
    var text by remember { mutableStateOf("") }
    val d by vm.dsp.collectAsState()
    // The FT-891 only does memory mode on the VFO A side. On the radio, pressing A/B while in memory mode just
    // drops back to VFO A, so the B and A⇄B buttons do the same here.
    val memMode = d.memoryMode && FlrigClient.supportsYaesuDsp(s.xcvr)
    val activeB = s.activeVfo == "B" && !memMode
    var showDsp by remember { mutableStateOf(false) }
    var showMemories by remember { mutableStateOf(false) }
    fun leaveMemory() = vm.setDsp(YaesuDsp.vfoMemory()) { copy(memoryMode = false) }
    var showMic by remember { mutableStateOf(false) }
    var feature by remember { mutableStateOf<Feature?>(null) }   // which settings panel is open beside the chips
    var lowerHeightPx by remember { mutableIntStateOf(0) }
    var leftHeightPx by remember { mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val portrait = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 840

    @Composable fun Pickers() {
      val body: @Composable () -> Unit = {
            val band = bandOf(s.activeHz)
            PickerButton("Band", bandLabel(band), BANDS_PHONE.map { it.first }, band.orEmpty(), enabled = true) { name ->
                BANDS_PHONE.firstOrNull { it.first == name }?.let { (_, hz, mode) ->
                    vm.send {
                        if (activeB) setVfoB(hz.toDouble()) else setVfoA(hz.toDouble())
                        if (mode in s.modes) setMode(mode)   // skip it if this connection doesn't use that mode name
                    }
                }
            }
            PickerButton("Mode", s.mode.ifEmpty { "—" }, s.modes, s.mode) { m -> vm.send { setMode(m) } }
            // FLRig's bandwidth value goes stale when the radio changes it itself (memory recall, band change), so on a Yaesu
            // the width comes from the radio's own SH answer, the same source the DSP graph uses
            val radioWidth = if (FlrigClient.supportsYaesuDsp(s.xcvr)) YaesuDsp.widthTable(YaesuDsp.modeGroup(s.mode), d.narrow)[d.widthIdx]?.toString() else null
            val bwNow = radioWidth ?: s.bandwidth
            PickerButton("BW", if (bwNow.isEmpty()) "—" else "$bwNow Hz", s.bandwidths, bwNow) { b ->
                b.toIntOrNull()?.let { vm.send { setBandwidth(it) } }
            }
            if (FlrigClient.supportsYaesuDsp(s.xcvr)) {
                FilledTonalButton({ showMemories = true }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) { Text("Memories", fontSize = 18.sp) }
            }
      }
      if (portrait) Box(Modifier.padding(top = 4.dp)) { FlowRowCompat(body) } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { body() }
    }

    Card(Modifier.fillMaxWidth()) {
      Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.Top) {
       Column(Modifier.weight(1f).padding(bottom = 2.dp).onSizeChanged { leftHeightPx = it.height }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val mem = memMode
            Text(if (mem) "MEM %03d".format(d.memChannel) else "VFO ${s.activeVfo}", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
            if (mem) {
                // memory mode: MEM, channel number, name (large) and frequency (a bit smaller) on the top line
                Text(
                    d.memTag.ifEmpty { "no name" }, fontSize = 40.sp, lineHeight = 56.sp, maxLines = 1,
                    fontFamily = FontFamily.Monospace, color = if (d.memTag.isEmpty()) th[ThemeColor.LABEL] else Color.Unspecified,
                )
                Text(
                    formatHz(if (d.memFreqHz > 0) d.memFreqHz else s.activeHz), fontSize = 32.sp, lineHeight = 56.sp,
                    maxLines = 1, fontFamily = FontFamily.Monospace,
                )
            } else Text(
                formatHz(s.activeHz), fontSize = 56.sp, lineHeight = 56.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { text = (s.activeHz / 1000.0).toString(); editingIdle = false; editing = true },
            )
            if (!mem) Text("MHz", color = th[ThemeColor.LABEL], maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (!portrait || !FlrigClient.supportsYaesuDsp(s.xcvr)) Pickers()
        }
        Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.then(if (feature != null && !portrait) Modifier.width(510.dp) else Modifier).onSizeChanged { lowerHeightPx = it.height }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("VFO ${if (activeB) "A" else "B"}", fontSize = 12.sp, color = th[ThemeColor.LABEL])
            Text(
                    formatHz(s.idleHz), fontSize = 22.sp, fontFamily = FontFamily.Monospace, color = th[ThemeColor.LABEL],
                    modifier = Modifier.clickable { text = (s.idleHz / 1000.0).toString(); editingIdle = true; editing = true },
                )
            if (s.split) Text("SPLIT", color = th[ThemeColor.WARN], fontSize = 14.sp)
        }
        Spacer(Modifier.height(8.dp))
        FlowRowCompat {
            FilterChip(!activeB, { vm.send { setActiveVfo("A") } }, { Text("A") }, enabled = !s.directUsb, colors = selectedBlue())
            FilterChip(activeB, { if (memMode) leaveMemory() else vm.send { setActiveVfo("B") } }, { Text("B") }, enabled = !s.directUsb, colors = selectedBlue())
            if (FlrigClient.supportsYaesuDsp(s.xcvr)) {
                // the radio's [V/M] key; FLRig has no call for it, so it goes out as a CAT command
                FilterChip(d.memoryMode, { vm.setDsp(YaesuDsp.vfoMemory()) { copy(memoryMode = !memoryMode) } }, { Text(if (d.memoryMode) "MEM" else "VFO") }, colors = selectedBlue())
            }
            OutlinedButton({ if (memMode) leaveMemory() else vm.send { swapVfo() } }) { Text("A/B") }
            OutlinedButton({ vm.send { vfoAtoB() } }, enabled = !memMode) { Text("A→B") }
            FilterChip(s.split, { vm.send { setSplit(!s.split) } }, { Text("Split") }, colors = selectedBlue())
            if (FlrigClient.supportsYaesuDsp(s.xcvr)) {
                FilterChip(d.lockOn, { vm.setDsp(YaesuDsp.lock(!d.lockOn)) { copy(lockOn = !lockOn) } }, { Text("Lock") }, colors = selectedBlue())
            }
        }
        run {
            FlowRowCompat {
                val carrierMode by vm.tuneCarrier.collectAsState()
                val left by vm.carrierLeft.collectAsState()
                val tuneModifier = Modifier.height(32.dp)
                val tunePadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                if (carrierMode) {
                    // external tuner: a low-power carrier you start and stop, cut off after a minute
                    if (left > 0) Button({ vm.stopCarrier() }, colors = ButtonDefaults.buttonColors(containerColor = th[ThemeColor.DANGER]), contentPadding = tunePadding, modifier = tuneModifier) { Text("Tune ${left}s") }
                    else OutlinedButton({ vm.toggleCarrier() }, contentPadding = tunePadding, modifier = tuneModifier) { Text("Tune") }
                } else {
                    // starts the radio's own antenna tuner; the button shows red while the radio is transmitting
                    if (s.ptt) Button({ vm.send { tune() } }, colors = ButtonDefaults.buttonColors(containerColor = th[ThemeColor.DANGER]), contentPadding = tunePadding, modifier = tuneModifier) { Text("Tune") }
                    else OutlinedButton({ vm.send { tune() } }, contentPadding = tunePadding, modifier = tuneModifier) { Text("Tune") }
                }
            if (FlrigClient.supportsYaesuDsp(s.xcvr)) {
                // PA0: off = IPO (preamp bypassed), on = AMP; RA0 = 12 dB attenuator
                FilterChip(d.preampOn, { vm.setDsp(YaesuDsp.preamp(!d.preampOn)) { copy(preampOn = !d.preampOn) } }, { Text(if (d.preampOn) "AMP" else "IPO") }, colors = selectedBlue())
                FilterChip(d.attOn, { vm.setDsp(YaesuDsp.att(!d.attOn)) { copy(attOn = !d.attOn) } }, { Text("ATT") }, colors = selectedBlue())
                Feature.values().forEach { f ->
                    FilterChip(feature == f, { feature = if (feature == f) null else f }, { Text(f.label) }, colors = selectedBlue())
                }
            }
            }
        }
        }
        if (!portrait) feature?.let { f ->
            val panelHeight = with(density) { lowerHeightPx.toDp() }
            Box(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                when (f) {
                    Feature.CW -> CwSettingsPanel(vm, panelHeight)
                    Feature.FM -> FmSettingsPanel(vm, panelHeight)
                    Feature.VOICE -> VoicePanel(vm, panelHeight)
                    else -> ComingSoonPanel(f, panelHeight)
                }
            }
        }
        }
        // portrait: Band / Mode / BW go under the chip rows (the graphs have the right-hand column to themselves)
        if (portrait && FlrigClient.supportsYaesuDsp(s.xcvr)) Pickers()
       }
       if (FlrigClient.supportsYaesuDsp(s.xcvr) && leftHeightPx > 0) {
           // each graph takes half the card's height; its width follows from the widget's fixed aspect ratio
           // two graphs + the gaps around them fit inside the left column, so they never make the card taller
           val graphHeight = with(density) { ((leftHeightPx.toDp() - 8.dp) / 2) }.coerceIn(40.dp, if (portrait) 88.dp else 100.dp)   // capped so a narrow (portrait) screen can't feed back into a runaway size
           // in portrait the column is only as wide as the graphs (the pickers wrap), which leaves the frequency row room for "MHz"
           val colWidth = if (portrait) Modifier.width((150f * graphHeight.value / 58f).dp) else Modifier
           Column(colWidth.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
               DspMiniGraph(vm, paused = showDsp, totalHeight = graphHeight) { showDsp = true }
               MicMiniGraph(vm, paused = showMic, totalHeight = graphHeight) { showMic = true }
           }
       }
      }
      // portrait: no room beside the chips, so the CW / FM / VOICE panel opens underneath the whole card
        if (portrait) feature?.let { f ->
            Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                when (f) {
                    Feature.CW -> CwSettingsPanel(vm, 130.dp)
                    Feature.FM -> FmSettingsPanel(vm, 130.dp)
                    Feature.VOICE -> VoicePanel(vm, 130.dp)
                    else -> ComingSoonPanel(f, 130.dp)
                }
            }
        }
    }
    if (showDsp) DspDialog(vm) { showDsp = false }
    if (showMic) MicDialog(vm) { showMic = false }
    if (showMemories) MemoryDialog(vm) { showMemories = false }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(if (editingIdle) "Enter VFO ${if (activeB) "A" else "B"} frequency" else "Enter frequency") },
            text = {
                OutlinedTextField(
                    text, { text = it }, singleLine = true,
                    label = { Text("kHz or MHz") },
                    supportingText = { Text("e.g. 7.074 or 7074 — add k or m to be sure") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
            },
            confirmButton = {
                TextButton({
                    parseFrequencyHz(text)?.let { hz ->
                        // the other VFO is written directly, so the selected VFO doesn't change
                        val toB = if (editingIdle) !activeB else activeB
                        vm.send { if (toB) setVfoB(hz) else setVfoA(hz) }
                    }
                    editing = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton({ editing = false }) { Text("Cancel") } },
        )
    }
}

/** Selected chips use the same blue as the filled bars in the AF / RF / MIC / PWR sliders. */
@Composable
internal fun selectedBlue() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
)

/** A button showing the current value; tapping opens a popup of the choices. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickerButton(title: String, label: String, options: List<String>, current: String, enabled: Boolean = options.isNotEmpty(), onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    FilledTonalButton({ open = true }, enabled = enabled, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
        Text(label, fontSize = 22.sp)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { o ->
                        FilterChip(o == current, { onPick(o); open = false }, { Text(o, fontSize = 18.sp, modifier = Modifier.padding(vertical = 6.dp)) })
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ open = false }) { Text("Cancel") } },
        )
    }
}

private class MeterSpec(val title: String, val value: Int, val ticks: List<Tick>, val text: String, val color: Color, val warnFrom: Int = 101)

@Composable
private fun MeterPanel(s: RigState, analog: Boolean, modifier: Modifier) {
    val th = LocalThemeSettings.current
    val sText = sUnitsText(s.smeter) + (s.dbm?.let { "  $it dBm" } ?: "")
    val watts = s.maxPower * s.powerMeter / 100
    val specs = mutableListOf(
        MeterSpec("S", s.smeter, S_TICKS, sText, th[ThemeColor.METER_S], 44),
        MeterSpec("PWR", s.powerMeter, powerTicks(s.maxPower), "$watts W", th[ThemeColor.METER_PWR]),
        MeterSpec("SWR", s.swrMeter, SWR_TICKS, s.swrRatio.ifEmpty { swrText(s.swrMeter) }, th[ThemeColor.METER_SWR], 50),
    )
    if (s.hasExtraMeters) {
        specs.add(MeterSpec("ALC", pct(s.alc), PCT_TICKS, "${pct(s.alc)}%", th[ThemeColor.METER_ALC]))
        specs.add(MeterSpec("COMP", pct(s.comp), PCT_TICKS, "${pct(s.comp)}%", th[ThemeColor.METER_COMP]))
        specs.add(MeterSpec("ID", pct(s.drain), PCT_TICKS, "${pct(s.drain)}%", th[ThemeColor.METER_ID]))
    }
    Card(modifier) {
        if (analog) {
            specs.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { m -> AnalogMeter(m.title, m.value, m.ticks, m.text, m.color, m.warnFrom, Modifier.weight(1f)) }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
            specs.forEachIndexed { i, m ->
                if (i > 0) Spacer(Modifier.height(8.dp))
                BarMeter(m.title, m.value, m.ticks, m.text, barColor = m.color, warnFrom = m.warnFrom)
            }
        }
        if (s.hasExtraMeters) Text("ALC, COMP and ID update while transmitting (0–255 scale shown as %)", fontSize = 11.sp, color = th[ThemeColor.LABEL])
    }
}

/** Fallback SWR text from FLRig's 0..100 scale (1.0 at 0, 2.0 at ~25, 3.0 at ~50, off the end at 100). */
private fun swrText(v: Int): String {
    val swr = when {
        v <= 25 -> 1.0 + v / 25.0
        v <= 50 -> 2.0 + (v - 25) / 25.0
        else -> 3.0 + (v - 50) / 50.0 * 7.0
    }
    return String.format(Locale.US, "%.1f", swr)
}

private fun pct(raw: Int) = (raw * 100 / 255).coerceIn(0, 100)
private val PCT_TICKS = (0..4).map { Tick(it * 25, "${it * 25}") }

@Composable
private fun ControlsPanel(s: RigState, vm: RigViewModel) {
    Card(Modifier.fillMaxWidth()) {
        s.volume?.let { SliderRow("VOL", it, 0..100) { v -> vm.send { setVolume(v) } } }
        s.rfGain?.let { SliderRow("RF", it, 0..100) { v -> vm.send { setRfGain(v) } } }
        s.micGain?.let { SliderRow("MIC", it, 0..100) { v -> vm.send { setMicGain(v) } } }
        // the FT-891 takes 5-100 W over CAT
        val minW = if (FlrigClient.supportsYaesuDsp(s.xcvr)) 5 else 0
        var typingPwr by remember { mutableStateOf(false) }
        var pwrText by remember { mutableStateOf("") }
        s.powerSetting?.let {
            SliderRow("PWR W", it.coerceAtLeast(minW), minW..s.maxPower, onValueClick = { pwrText = it.toString(); typingPwr = true }) { v -> vm.send { setPower(v) } }
        }
        if (typingPwr) {
            val typed = pwrText.toIntOrNull()
            val valid = typed != null && typed in minW..s.maxPower
            AlertDialog(
                onDismissRequest = { typingPwr = false },
                title = { Text("Transmit power ($minW–${s.maxPower} W)") },
                text = {
                    OutlinedTextField(
                        pwrText, { pwrText = it.filter(Char::isDigit).take(3) }, singleLine = true,
                        label = { Text("Watts") },
                        supportingText = { Text("Enter a number from $minW to ${s.maxPower}") },
                        isError = pwrText.isNotEmpty() && !valid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                },
                confirmButton = { TextButton({ typed?.takeIf { valid }?.let { v -> vm.send { setPower(v) } }; typingPwr = false }, enabled = valid) { Text("Set") } },
                dismissButton = { TextButton({ typingPwr = false }) { Text("Cancel") } },
            )
        }
    }
}

/** A slider that only sends on release, and follows the radio the rest of the time. */
@Composable
internal fun SliderRow(label: String, value: Int, range: IntRange, labelWidth: androidx.compose.ui.unit.Dp = 60.dp, onValueClick: (() -> Unit)? = null, onSet: (Int) -> Unit) {
    val th = LocalThemeSettings.current
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value.toFloat()) }
    var lastChange by remember { mutableLongStateOf(0L) }
    // follow the radio, but give a value just set from here a moment to reach it so a stale poll doesn't snap it back
    LaunchedEffect(value, lastChange) {
        if (!dragging) {
            val wait = 1500 - (System.currentTimeMillis() - lastChange)
            if (wait > 0) kotlinx.coroutines.delay(wait)
            if (!dragging) local = value.toFloat()
        }
    }
    fun step(d: Int) {
        val v = (local.toInt() + d).coerceIn(range.first, range.last)
        if (v != local.toInt()) { local = v.toFloat(); lastChange = System.currentTimeMillis(); onSet(v) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(labelWidth), fontSize = 13.sp, color = th[ThemeColor.LABEL])
        StepArrow("◀") { step(-1) }
        Slider(
            local, { dragging = true; local = it },
            Modifier.weight(1f),
            valueRange = range.first.toFloat()..range.last.toFloat(),
            onValueChangeFinished = { dragging = false; lastChange = System.currentTimeMillis(); onSet(local.toInt()) },
        )
        StepArrow("▶") { step(1) }
        if (onValueClick != null) {
            // tap the number to type a value
            Surface(onClick = onValueClick, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(start = 6.dp).width(58.dp)) {
                Text(local.toInt().toString(), Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 15.sp)
            }
        } else Text(local.toInt().toString(), Modifier.width(64.dp).padding(start = 6.dp))
    }
}

/** Small arrow button at the end of a slider: one tap moves the value by one. */
@Composable
private fun StepArrow(symbol: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.size(38.dp)) {
        Box(contentAlignment = Alignment.Center) { Text(symbol, fontSize = 16.sp) }
    }
}

@Composable
private fun BandPanel(vm: RigViewModel) {
    val th = LocalThemeSettings.current
    Text("Digital-mode calling frequencies", fontSize = 12.sp, color = th[ThemeColor.LABEL])
    FlowRowCompat {
        BANDS.forEach { (name, hz) ->
            OutlinedButton({ vm.send { setFrequency(hz.toDouble()) } }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(name) }
        }
    }
}

/** FLRig's 24 user command buttons (configured on the PC under Config → Setup → Commands). FLRig doesn't tell us their labels. */
@Composable
private fun UserButtonPanel(vm: RigViewModel) {
    val th = LocalThemeSettings.current
    Text("Runs the numbered command button you set up in FLRig", fontSize = 12.sp, color = th[ThemeColor.LABEL])
    FlowRowCompat {
        (1..24).forEach { n ->
            OutlinedButton({ vm.send { userCmd(n) } }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("$n") }
        }
    }
}

@Composable
private fun ConsolePanel(vm: RigViewModel) {
    val th = LocalThemeSettings.current
    val lines by vm.console.collectAsState()
    var cmd by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Text("Raw CAT console — sent exactly as typed via FLRig (include the trailing ; for Yaesu/Kenwood)", fontSize = 12.sp, color = th[ThemeColor.LABEL])
        Column(Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 220.dp).verticalScroll(rememberScrollState(), reverseScrolling = true)) {
            lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = if (it.startsWith(">")) MaterialTheme.colorScheme.primary else Color.Unspecified) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(cmd, { cmd = it }, Modifier.weight(1f), singleLine = true, label = { Text("e.g. FA;") })
            Button({ vm.sendRawCat(cmd); cmd = "" }) { Text("Send") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
}
