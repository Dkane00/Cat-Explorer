// Copyright (C) 2026 David Kane (W6MZT)
// SPDX-License-Identifier: GPL-3.0-or-later
package net.kanenet.catexplorer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import net.kanenet.catexplorer.rig.EqBand
import net.kanenet.catexplorer.rig.EqSet
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import net.kanenet.catexplorer.RigViewModel
import net.kanenet.catexplorer.rig.DspState
import net.kanenet.catexplorer.rig.YaesuDsp

private val DIM: Color @Composable get() = LocalThemeSettings.current[ThemeColor.LABEL]

@Composable
private fun PanelFrame(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.82f).heightIn(max = 760.dp).padding(8.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 22.sp, modifier = Modifier.weight(1f))
                    TextButton(onClose) { Text("Close") }
                }
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    Spacer(Modifier.height(10.dp))
    Text(text.uppercase(), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
}

/** A label with an on/off chip, and an optional level slider on the same row. */
@Composable
private fun ToggleRow(label: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), fontSize = 16.sp)
        Switch(on, onToggle)
    }
}

/** A pill button: filled when the setting is on, outlined when off. */
@Composable
private fun OnOffButton(label: String, on: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val mod = Modifier.width(112.dp).height(40.dp)
    if (on) Button({ onToggle(false) }, mod, enabled = enabled, contentPadding = PaddingValues(0.dp)) { Text(label, fontSize = 15.sp) }
    else OutlinedButton({ onToggle(true) }, mod, enabled = enabled, contentPadding = PaddingValues(0.dp)) { Text(label, fontSize = 15.sp) }
}

/**
 * Slider on the radio's own units. Sends on release; follows the radio otherwise. The arrows at either end move the value by
 * one [unit] per tap (the smallest step the radio accepts for that setting).
 */
@Composable
private fun PanelSlider(value: Int, range: IntRange, enabled: Boolean, display: String, modifier: Modifier = Modifier, steps: Int = 0, unit: Int = 1, onSet: (Int) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value.toFloat()) }
    // after a change, keep showing it for a moment: a radio reply that was already on its way would otherwise put the old value back
    var holdUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(value, holdUntil) {
        if (dragging) return@LaunchedEffect
        val wait = holdUntil - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        if (!dragging) local = value.toFloat()
    }
    fun step(dir: Int) {
        val v = ((Math.round(local / unit) + dir) * unit).coerceIn(range.first, range.last)
        if (v != local.toInt()) { local = v.toFloat(); holdUntil = System.currentTimeMillis() + 1500; onSet(v) }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ArrowButton("◀", enabled) { step(-1) }
        Slider(
            local, { dragging = true; local = it }, Modifier.weight(1f), enabled = enabled,
            valueRange = range.first.toFloat()..range.last.toFloat(), steps = steps,
            onValueChangeFinished = { dragging = false; holdUntil = System.currentTimeMillis() + 1500; onSet(local.toInt()) },
        )
        ArrowButton("▶", enabled) { step(1) }
        Text(display, Modifier.width(96.dp).padding(start = 8.dp), fontSize = 15.sp, color = if (enabled) Color.Unspecified else DIM)
    }
}

@Composable
private fun ArrowButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small,
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Text(symbol, fontSize = 16.sp, color = if (enabled) Color.Unspecified else DIM) }
    }
}

/** One row of the DSP screen: a toggle button on the left, controls to its right. */
@Composable
private fun DspRow(button: @Composable () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        button()
        content()
    }
}

/**
 * The IF passband, drawn on a 0..3300 Hz audio axis: width and IF shift move the trapezoid, and the
 * contour, notch and APF settings show as vertical lines. Approximate — it is a picture of the settings,
 * not a measurement from the radio.
 */
@Composable
internal fun PassbandGraph(d: DspState, widthHz: Int?, cw: Boolean, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 110.dp) {
    val th = LocalThemeSettings.current
    Canvas(modifier.fillMaxWidth().height(height)) {
        // axis runs -300..3600 Hz so the filter's slopes aren't cut off at the edges
        fun x(hz: Float) = ((hz + 300f) / 3900f).coerceIn(0f, 1f) * size.width
        drawRect(th[ThemeColor.GRAPH_BG])
        val base = size.height - 14f
        val top = 12f
        drawLine(th[ThemeColor.GRAPH_AXIS], Offset(0f, base), Offset(size.width, base), 2f)
        for (k in 0..6) drawLine(th[ThemeColor.GRAPH_AXIS], Offset(x(k * 500f), base), Offset(x(k * 500f), base + 6f), 2f)
        val centre = (if (cw) d.keyPitchHz else 1500) + (if (d.ifShiftOn) d.ifShift else 0)
        if (widthHz != null) {
            val half = widthHz / 2f
            val slope = (widthHz * 0.08f).coerceAtLeast(40f)
            val path = Path().apply {
                moveTo(x(centre - half - slope), base)
                lineTo(x(centre - half), top)
                lineTo(x(centre + half), top)
                lineTo(x(centre + half + slope), base)
            }
            drawPath(path, th[ThemeColor.FILTER_CURVE], style = Stroke(4f))
        }
        drawLine(th[ThemeColor.LABEL], Offset(x(centre.toFloat()), top), Offset(x(centre.toFloat()), base), 2f)
        if (d.notchOn) drawLine(th[ThemeColor.NOTCH], Offset(x(d.notchHz.toFloat()), top), Offset(x(d.notchHz.toFloat()), base), 4f)
        if (d.contourOn) drawLine(th[ThemeColor.CONTOUR], Offset(x(d.contourHz.toFloat()), top), Offset(x(d.contourHz.toFloat()), base), 4f)
        if (cw && d.apfOn) drawLine(th[ThemeColor.APF], Offset(x((d.keyPitchHz + d.apfHz).toFloat()), top), Offset(x((d.keyPitchHz + d.apfHz).toFloat()), base), 4f)
    }
}

/**
 * Small live passband graph for the main screen. It follows the radio's width, shift, notch, contour and APF
 * settings, and opens the full DSP screen when touched.
 */
@Composable
fun DspMiniGraph(vm: RigViewModel, paused: Boolean, totalHeight: androidx.compose.ui.unit.Dp = 58.dp, onClick: () -> Unit) {
    val th = LocalThemeSettings.current
    val d by vm.dsp.collectAsState()
    val rig by vm.state.collectAsState()
    // Keep the picture current while the full panel (which polls faster) is closed.
    LaunchedEffect(paused) { while (!paused) { vm.refreshDsp(); delay(3000) } }

    val group = YaesuDsp.modeGroup(rig.mode)
    val widthHz = YaesuDsp.widthTable(group, d.narrow)[d.widthIdx]
    // Original design was 150 x 58 dp; everything scales from that so the aspect ratio is kept.
    val scale = totalHeight.value / 58f
    Column(
        Modifier.width((150f * scale).dp).height(totalHeight).clip(MaterialTheme.shapes.medium).background(th[ThemeColor.GRAPH_BG]).clickable(onClick = onClick),
    ) {
        Text("DSP${widthHz?.let { " · $it Hz" } ?: ""}", Modifier.padding(start = (6f * scale).dp, top = (2f * scale).dp), fontSize = (10f * scale).sp, lineHeight = (14f * scale).sp, color = DIM)
        PassbandGraph(d, widthHz, group == YaesuDsp.ModeGroup.CW, height = (44f * scale).dp)
    }
}

private const val EQ_AXIS_HZ = 3300f
private const val EQ_PAD = 14f
private val EQ_DEFAULT_HZ = listOf(400, 1100, 2300)
private val EQ_COLORS: List<Color> @Composable get() = LocalThemeSettings.current.let { listOf(it[ThemeColor.EQ_1], it[ThemeColor.EQ_2], it[ThemeColor.EQ_3]) }

private fun eqHandleHz(band: Int, b: EqBand) = (YaesuDsp.eqFreqHz(band, b.freqIdx) ?: EQ_DEFAULT_HZ[band]).toFloat()
private fun eqY(db: Float, h: Float) = EQ_PAD + (10f - db) / 30f * (h - 2 * EQ_PAD)
private fun eqDb(y: Float, h: Float) = 10f - (y - EQ_PAD) / (h - 2 * EQ_PAD) * 30f

/**
 * The mic equaliser curve. With [onChange] set, the three handles can be dragged (left/right = frequency, up/down = gain);
 * the change is reported once, when the finger lifts.
 */
@Composable
internal fun EqGraph(set: EqSet, height: Dp, modifier: Modifier = Modifier, handleRadius: Dp = 5.dp, onChange: ((Int, EqBand) -> Unit)? = null) {
    val th = LocalThemeSettings.current
    val eqColors = EQ_COLORS
    var live by remember { mutableStateOf<Pair<Int, EqBand>?>(null) }
    val current by rememberUpdatedState(set)
    val report by rememberUpdatedState(onChange)
    val shown = live?.let { (i, b) -> EqSet(set.bands.toMutableList().also { it[i] = b }) } ?: set

    var m = modifier.fillMaxWidth().height(height)
    if (onChange != null) m = m.pointerInput(Unit) {
        var grabbed = 0
        detectDragGestures(
            onDragStart = { pos ->
                val w = size.width.toFloat()
                grabbed = (0..2).minBy { Math.abs(eqHandleHz(it, current.bands[it]) / EQ_AXIS_HZ * w - pos.x) }
                live = grabbed to current.bands[grabbed]
            },
            onDrag = { change, _ ->
                change.consume()
                val old = live?.second ?: return@detectDragGestures
                val hz = change.position.x / size.width * EQ_AXIS_HZ
                live = grabbed to old.copy(
                    freqIdx = YaesuDsp.eqIdxForHz(grabbed, hz),
                    level = Math.round(eqDb(change.position.y, size.height.toFloat())).coerceIn(-20, 10),
                )
            },
            onDragEnd = { live?.let { (i, b) -> report?.invoke(i, b) }; live = null },
            onDragCancel = { live = null },
        )
    }

    Canvas(m) {
        val w = size.width; val h = size.height
        fun x(hz: Float) = hz / EQ_AXIS_HZ * w
        drawRect(th[ThemeColor.GRAPH_BG])
        for (db in listOf(10, -20)) drawLine(th[ThemeColor.GRAPH_GRID], Offset(0f, eqY(db.toFloat(), h)), Offset(w, eqY(db.toFloat(), h)), 1.5f)
        drawLine(th[ThemeColor.GRAPH_AXIS], Offset(0f, eqY(0f, h)), Offset(w, eqY(0f, h)), 2f)
        for (k in 1..6) drawLine(th[ThemeColor.GRAPH_GRID], Offset(x(k * 500f), EQ_PAD), Offset(x(k * 500f), h - EQ_PAD), 1f)

        // sum of one smooth bump per active band; wider bandwidth setting = broader bump
        val path = Path()
        var px = 0f
        while (px <= w) {
            val hz = px / w * EQ_AXIS_HZ
            var g = 0f
            shown.bands.forEachIndexed { i, b ->
                if (b.freqIdx > 0) {
                    val d = (hz - eqHandleHz(i, b)) / (120f + b.bw * 50f)
                    g += b.level * Math.exp((-d * d).toDouble()).toFloat()
                }
            }
            val y = eqY(g.coerceIn(-20f, 10f), h)
            if (px == 0f) path.moveTo(px, y) else path.lineTo(px, y)
            px += 4f
        }
        drawPath(path, th[ThemeColor.FILTER_CURVE], style = Stroke(width = 4f))

        shown.bands.forEachIndexed { i, b ->
            val c = Offset(x(eqHandleHz(i, b)), eqY(b.level.toFloat(), h))
            if (b.freqIdx > 0) drawCircle(eqColors[i], handleRadius.toPx(), c)
            else drawCircle(eqColors[i].copy(alpha = 0.5f), handleRadius.toPx(), c, style = Stroke(width = 3f))
        }
    }
}

/** Main-screen mic widget: live picture of the EQ curve in use; tapping it opens the full popup. */
@Composable
fun MicMiniGraph(vm: RigViewModel, paused: Boolean, totalHeight: Dp = 58.dp, onClick: () -> Unit) {
    val th = LocalThemeSettings.current
    val m by vm.mic.collectAsState()
    val eq by vm.eq.collectAsState()
    LaunchedEffect(paused) { while (!paused) { vm.refreshMic(); delay(3000) } }

    val scale = totalHeight.value / 58f
    Column(
        Modifier.width((150f * scale).dp).height(totalHeight).clip(MaterialTheme.shapes.medium).background(th[ThemeColor.GRAPH_BG]).clickable(onClick = onClick),
    ) {
        Text(
            "MIC EQ · ${if (m.eqOn) "ON" else "OFF"}", Modifier.padding(start = (6f * scale).dp, top = (2f * scale).dp),
            fontSize = (10f * scale).sp, lineHeight = (14f * scale).sp, color = if (m.eqOn) MaterialTheme.colorScheme.primary else DIM,
        )
        Box(Modifier.alpha(if (m.eqOn) 1f else 0.4f)) {
            EqGraph(eq.set(parametric = !m.procOn), height = (44f * scale).dp, handleRadius = (4f * scale).dp)
        }
    }
}

/** DSP & IF screen, modelled on the CatTouch's "DSP IF" widget. Polls the radio while open. */
@Composable
fun DspDialog(vm: RigViewModel, onClose: () -> Unit) {
    val th = LocalThemeSettings.current
    val d by vm.dsp.collectAsState()
    val rig by vm.state.collectAsState()
    val menu by vm.dspMenu.collectAsState()
    LaunchedEffect(Unit) { while (true) { vm.refreshDsp(); delay(700) } }

    val group = YaesuDsp.modeGroup(rig.mode)
    val cw = group == YaesuDsp.ModeGroup.CW
    val table = YaesuDsp.widthTable(group, d.narrow)
    val widthHz = table[d.widthIdx]
    // The radio refuses changes while it's transmitting, so grey everything out then.
    val ok = !rig.ptt

    PanelFrame("DSP & IF", onClose) {
        if (rig.ptt) Text("Transmitting — DSP settings can't be changed until you stop.", color = th[ThemeColor.DANGER], fontSize = 13.sp)
        PassbandGraph(d, widthHz, cw)
        Text(
            buildString {
                append(rig.mode.ifEmpty { "—" })
                widthHz?.let { append("  ·  $it Hz") }
                if (d.ifShiftOn) append("  ·  shift ${if (d.ifShift >= 0) "+" else ""}${d.ifShift} Hz")
            },
            fontSize = 13.sp, color = DIM,
        )
        Spacer(Modifier.height(6.dp))

        DspRow({ OnOffButton("SHIFT", d.ifShiftOn, ok) { vm.setDsp(YaesuDsp.ifShift(it, d.ifShift)) { copy(ifShiftOn = it) } } }) {
            PanelSlider(d.ifShift, -1200..1200, ok && d.ifShiftOn, "${if (d.ifShift >= 0) "+" else ""}${d.ifShift} Hz", Modifier.weight(1f), unit = 20) { v ->
                vm.setDsp(YaesuDsp.ifShift(d.ifShiftOn, v)) { copy(ifShift = v / 20 * 20) }
            }
        }

        if (table.isNotEmpty()) {
            // Slider positions are the valid SH indexes for this mode (index 0 is "default" and shares a value with another).
            val idxs = table.keys.filter { it != 0 }.sorted()
            val pos = idxs.indexOf(d.widthIdx).takeIf { it >= 0 } ?: idxs.indexOfFirst { table[it] == widthHz }.coerceAtLeast(0)
            DspRow({ OnOffButton("NAR", d.narrow, ok) { vm.setDsp(YaesuDsp.narrow(it)) { copy(narrow = it) } } }) {
                Text("WIDTH", Modifier.width(60.dp), fontSize = 13.sp, color = DIM)
                PanelSlider(pos, 0..(idxs.size - 1).coerceAtLeast(1), ok, "${widthHz ?: "—"} Hz", Modifier.weight(1f), steps = (idxs.size - 2).coerceAtLeast(0)) { p ->
                    idxs.getOrNull(p)?.let { idx -> vm.setDsp(YaesuDsp.width(1, idx)) { copy(widthIdx = idx) } }
                }
            }
        }

        DspRow({ OnOffButton("NB", d.nbOn, ok) { vm.setDsp(YaesuDsp.nb(it)) { copy(nbOn = it) } } }) {
            PanelSlider(d.nbLevel, 0..10, ok && d.nbOn, "${d.nbLevel}", Modifier.weight(1f), steps = 9) { v -> vm.setDsp(YaesuDsp.nbLevel(v)) { copy(nbLevel = v) } }
        }
        DspRow({ OnOffButton("CONTOUR", d.contourOn, ok) { vm.setDsp(YaesuDsp.contour(it)) { copy(contourOn = it) } } }) {
            PanelSlider(d.contourHz, 10..3200, ok && d.contourOn, "${d.contourHz} Hz", Modifier.weight(1f)) { v -> vm.setDsp(YaesuDsp.contourFreq(v)) { copy(contourHz = v) } }
        }
        DspRow({ Text("Level", Modifier.width(112.dp), fontSize = 13.sp, color = DIM) }) {
            PanelSlider(menu.contourLevel, -40..20, ok && d.contourOn, "${menu.contourLevel} dB", Modifier.weight(1f)) { v -> vm.setDspMenu(YaesuDsp.contourLevel(v)) { copy(contourLevel = v) } }
        }
        DspRow({ Text("Width", Modifier.width(112.dp), fontSize = 13.sp, color = DIM) }) {
            PanelSlider(menu.contourWidth, 1..11, ok && d.contourOn, "${menu.contourWidth}", Modifier.weight(1f)) { v -> vm.setDspMenu(YaesuDsp.contourWidth(v)) { copy(contourWidth = v) } }
        }
        DspRow({ OnOffButton("NOTCH", d.notchOn, ok) { vm.setDsp(YaesuDsp.notch(it)) { copy(notchOn = it) } } }) {
            PanelSlider(d.notchHz, 10..3200, ok && d.notchOn, "${d.notchHz} Hz", Modifier.weight(1f), unit = 10) { v -> vm.setDsp(YaesuDsp.notchFreq(v)) { copy(notchHz = v / 10 * 10) } }
        }
        DspRow({ Text("Notch width", Modifier.width(112.dp), fontSize = 13.sp, color = DIM) }) {
            FilterChip(!menu.notchWide, { vm.setDspMenu(YaesuDsp.notchWidth(false)) { copy(notchWide = false) } }, { Text("Narrow") })
            FilterChip(menu.notchWide, { vm.setDspMenu(YaesuDsp.notchWidth(true)) { copy(notchWide = true) } }, { Text("Wide") })
        }
        // APF only exists in the CW modes.
        if (cw) DspRow({ Text("APF width", Modifier.width(112.dp), fontSize = 13.sp, color = DIM) }) {
            listOf("Narrow", "Medium", "Wide").forEachIndexed { i, label ->
                FilterChip(menu.apfWidth == i, { vm.setDspMenu(YaesuDsp.apfWidth(i)) { copy(apfWidth = i) } }, { Text(label) })
            }
        }
        if (cw) DspRow({ OnOffButton("APF", d.apfOn, ok) { vm.setDsp(YaesuDsp.apf(it)) { copy(apfOn = it) } } }) {
            PanelSlider(d.apfHz, -250..250, ok && d.apfOn, "${if (d.apfHz >= 0) "+" else ""}${d.apfHz} Hz", Modifier.weight(1f), unit = 10) { v -> vm.setDsp(YaesuDsp.apfFreq(v)) { copy(apfHz = v / 10 * 10) } }
        }
        DspRow({ OnOffButton("DNF", d.autoNotch, ok) { vm.setDsp(YaesuDsp.autoNotch(it)) { copy(autoNotch = it) } } }) {
            Text("Auto notch", fontSize = 13.sp, color = DIM)
        }
        DspRow({ OnOffButton("DNR", d.nrOn, ok) { vm.setDsp(YaesuDsp.nr(it)) { copy(nrOn = it) } } }) {
            PanelSlider(d.nrLevel, 1..15, ok && d.nrOn, "${d.nrLevel}", Modifier.weight(1f), steps = 13) { v -> vm.setDsp(YaesuDsp.nrLevel(v)) { copy(nrLevel = v) } }
        }

        Section("AGC")
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YaesuDsp.AGC_NAMES.forEachIndexed { i, name ->
                FilterChip(d.agc == i, { vm.setDsp(YaesuDsp.agc(i)) { copy(agc = i) } }, { Text(name, fontSize = 16.sp, modifier = Modifier.padding(vertical = 6.dp)) }, enabled = ok)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Contour level/width and the notch and APF widths are radio menu items. The FT-891 won't report them over CAT, " +
                "so these controls show what was last set from this app (starting from guessed factory values), not what the radio holds.",
            fontSize = 12.sp, color = DIM,
        )
    }
}

@Composable
fun MicDialog(vm: RigViewModel, onClose: () -> Unit) {
    val th = LocalThemeSettings.current
    val m by vm.mic.collectAsState()
    val eq by vm.eq.collectAsState()
    val rig by vm.state.collectAsState()
    LaunchedEffect(Unit) { while (true) { vm.refreshMic(); delay(700) } }
    // The radio uses the parametric set with the processor off and the standard set with it on.
    var parametric by remember { mutableStateOf(!m.procOn) }
    val set = eq.set(parametric)

    // In DATA modes the audio comes in over USB, so the radio ignores the mic processor/EQ and refuses to change them.
    val dataMode = rig.mode.startsWith("DATA")
    PanelFrame("TX parametric EQ", onClose) {
        if (dataMode) Text("Radio is in ${rig.mode}: audio comes from USB, so the mic EQ and processor are unavailable. Switch to SSB to use them.", color = th[ThemeColor.WARN], fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OnOffButton("MIC EQ", m.eqOn && !dataMode, !dataMode) { vm.setMic(YaesuDsp.micEq(it)) { copy(eqOn = it) } }
            OnOffButton("PROC", m.procOn && !dataMode, !dataMode) { vm.setMic(YaesuDsp.proc(it)) { copy(procOn = it) } }
            Spacer(Modifier.weight(1f))
            FilterChip(parametric, { parametric = true }, { Text("Processor off set") })
            FilterChip(!parametric, { parametric = false }, { Text("Processor on set") })
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.alpha(if (m.eqOn && !dataMode) 1f else 0.5f)) {
            EqGraph(set, height = 190.dp, handleRadius = 13.dp) { band, b -> vm.setEqBand(parametric, band, b) }
        }
        Text("Drag a handle: left/right = frequency, up/down = gain (+10 to -20 dB). Dragging an off band switches it on.", fontSize = 12.sp, color = DIM)
        Spacer(Modifier.height(6.dp))
        set.bands.forEachIndexed { i, b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(YaesuDsp.EQ_BAND_NAMES[i], Modifier.width(56.dp), fontSize = 14.sp, color = EQ_COLORS[i])
                Text(
                    if (b.freqIdx == 0) "off" else "${YaesuDsp.eqFreqHz(i, b.freqIdx)} Hz  ${"%+d".format(b.level)} dB",
                    Modifier.width(130.dp), fontSize = 14.sp, color = if (b.freqIdx == 0) DIM else th[ThemeColor.TEXT],
                )
                Text("width", fontSize = 12.sp, color = DIM)
                PanelSlider(b.bw, 1..10, b.freqIdx > 0, "%02d".format(b.bw), Modifier.weight(1f)) { v -> vm.setEqBand(parametric, i, b.copy(bw = v)) }
                TextButton({ vm.setEqBand(parametric, i, b.copy(freqIdx = 0)) }, enabled = b.freqIdx > 0) { Text("Off") }
            }
        }
        Text(
            "The FT-891 won't report these band settings over CAT, so the graph shows what was last set from this app " +
                "(remembered on this tablet), not what the radio holds. A band's first change writes all three of its values.",
            fontSize = 12.sp, color = DIM,
        )

        Section("Speech processor")
        SliderRow("Level", m.procLevel, 0..100, 90.dp) { v -> vm.setMic(YaesuDsp.procLevel(v)) { copy(procLevel = v) } }

        Section("Level")
        SliderRow("Mic gain", m.micGain, 0..100, 90.dp) { v -> vm.setMic(YaesuDsp.micGain(v)) { copy(micGain = v) } }

        Section("Monitor")
        ToggleRow("TX monitor", m.monitorOn) { vm.setMic(YaesuDsp.monitor(it)) { copy(monitorOn = it) } }
        SliderRow("Level", m.monitorLevel, 0..100, 90.dp) { v -> vm.setMic(YaesuDsp.monitorLevel(v)) { copy(monitorLevel = v) } }
    }
}
