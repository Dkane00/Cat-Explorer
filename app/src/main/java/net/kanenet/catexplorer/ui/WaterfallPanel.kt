package net.kanenet.catexplorer.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.kanenet.catexplorer.RigViewModel
import net.kanenet.catexplorer.audio.BandPlan
import net.kanenet.catexplorer.audio.Sense
import net.kanenet.catexplorer.audio.WaterfallThemes
import net.kanenet.catexplorer.audio.TuneMath
import net.kanenet.catexplorer.rig.RigState

/**
 * Audio waterfall of the receive passband (about 0–4 kHz), fed by the PC Panadapter. Tap a signal to tune to it.
 * It can only show what's inside the radio's filter — the FT-891 has no wide band-scope output.
 */
@Composable
fun WaterfallPanel(s: RigState, vm: RigViewModel, modifier: Modifier = Modifier, header: @Composable RowScope.() -> Unit = {}) {
    val th = LocalThemeSettings.current
    val on by vm.waterfallOn.collectAsState()
    val listen by vm.listen.collectAsState()
    val status by vm.waterfallStatus.collectAsState()
    val frame by vm.waterfallFrame.collectAsState()
    val range by vm.waterfallRange.collectAsState()
    val dsp by vm.dsp.collectAsState()
    val wideMode by vm.wideMode.collectAsState()
    val span by vm.wideSpan.collectAsState()
    val themeName by vm.waterfallTheme.collectAsState()
    val edgesOn by vm.bandEdges.collectAsState()
    val barSetting by vm.licenseBars.collectAsState()
    val bars = if (edgesOn) BandPlan.barClasses(barSetting) else emptyList()
    val palette = WaterfallThemes.named(themeName).colors
    val e = vm.engine
    val w = vm.wide

    LaunchedEffect(on, s.connected) { if (on && s.connected) vm.startWaterfall() }
    // the CW pitch decides where a CW signal is tuned to; it only changes from the radio's menu, so a slow re-read is plenty
    LaunchedEffect(on, s.mode) { if (on && s.mode.startsWith("CW")) while (true) { vm.refreshDsp(); delay(5000) } }

    val pitch = dsp.keyPitchHz
    val sense = TuneMath.sense(s.mode)
    val target = TuneMath.target(s.mode, pitch)

    Column(modifier) {
        Row(Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            header()
            if (on) {
                Spacer(Modifier.width(8.dp))
                if (wideMode) {
                    Text("Span", fontSize = 12.sp, color = th[ThemeColor.LABEL])
                    TextButton({ vm.stepWideSpan(-1) }, Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("◀", fontSize = 12.sp) }
                    Text("${span / 1000} kHz", fontSize = 12.sp)
                    TextButton({ vm.stepWideSpan(1) }, Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("▶", fontSize = 12.sp) }
                } else {
                    FilterChip(listen, { vm.setListen(!listen) }, { Text("Listen", fontSize = 12.sp) }, Modifier.height(28.dp), colors = selectedBlue())
                }
                Text("Range", fontSize = 12.sp, color = th[ThemeColor.LABEL])
                TextButton({ vm.setWaterfallRange(range - 5) }, Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("◀", fontSize = 12.sp) }
                Text("$range dB", fontSize = 12.sp)
                TextButton({ vm.setWaterfallRange(range + 5) }, Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("▶", fontSize = 12.sp) }
                val clip = if (wideMode) 0f else e?.clipFraction ?: 0f
                @Suppress("UNUSED_EXPRESSION") frame   // re-read the clip level as rows arrive
                when {
                    status.isNotEmpty() -> Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    wideMode && w?.overload == true -> Text(if (s.directUsb) "SDR overloaded — use a shorter antenna or an attenuator" else "SDR overloaded — lower its gain in the Panadapter window", fontSize = 12.sp, color = th[ThemeColor.HIST_SWR], modifier = Modifier.weight(1f))
                    clip > 0.001f -> Text("Audio too loud — turn down the sound card's input level on the PC", fontSize = 12.sp, color = th[ThemeColor.HIST_SWR], modifier = Modifier.weight(1f))
                }
            }
        }
        if (wideMode && w != null) {
            WideView(s, vm, w, frame, palette, edgesOn, bars)
            return@Column
        }
        if (e == null) {
            Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        s.directUsb -> status.ifEmpty { "Starting the RTL-SDR…" }
                        status.isEmpty() -> "Waiting for audio…"
                        else -> "Start Cat Explorer Panadapter on the FLRig PC (Ham Radio menu) and the waterfall appears here."
                    },
                    fontSize = 12.sp, color = th[ThemeColor.LABEL],
                )
            }
            return@Column
        }

        val bitmap = remember(e) { Bitmap.createBitmap(e.width, e.height, Bitmap.Config.ARGB_8888) }
        val argb = remember(e) { IntArray(e.width * e.height) }
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        val measurer = rememberTextMeasurer()
        val labelStyle = TextStyle(fontSize = 10.sp, color = th[ThemeColor.LABEL])
        val traceColor = th[ThemeColor.HIST_S]
        val accent = th[ThemeColor.ACCENT]

        Canvas(
            Modifier.fillMaxWidth().height((230 + if (sense != Sense.NONE) stripDp(bars.size) else 0).dp).padding(top = 4.dp).pointerInput(e, sense) {
                if (sense != Sense.NONE) detectTapGestures { p -> vm.waterfallTap(p.x.toDouble() / size.width * e.maxHz) }
            },
        ) {
            frame   // redraw on every new row
            val specH = size.height * 0.25f
            val axisH = 14.dp.toPx()
            // RF of an audio tone and back, for the band edges (none in AM/FM, where there's no simple mapping)
            val tone = TuneMath.dialTone(s.mode, pitch)
            val showEdges = edgesOn && sense != Sense.NONE && s.activeHz > 0
            val stripH = if (showEdges) stripDp(bars.size).dp.toPx() else 0f
            val wfTop = specH
            val wfH = size.height - wfTop - stripH - axisH
            fun xOf(hz: Double) = (hz / e.maxHz * size.width).toFloat()

            // spectrum trace
            drawRect(th[ThemeColor.GRAPH_BG], size = androidx.compose.ui.geometry.Size(size.width, specH))
            val path = Path()
            e.paint(argb, palette)
            bitmap.setPixels(argb, 0, e.width, 0, 0, e.width, e.height)
            synchronized(e.lock) {
                for (x in 0 until e.width) {
                    val px = (x + 0.5f) / e.width * size.width
                    val py = specH * (1f - (e.trace[x] / e.rangeDb).coerceIn(0f, 1f))
                    if (x == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
            }
            drawPath(path, traceColor, style = Stroke(1.5f))

            // waterfall
            drawImage(
                image, srcOffset = IntOffset.Zero, srcSize = IntSize(e.width, e.height),
                dstOffset = IntOffset(0, wfTop.toInt()), dstSize = IntSize(size.width.toInt(), wfH.toInt()),
                filterQuality = FilterQuality.Low,
            )

            // 500 Hz grid and labels
            for (hz in 500 until e.maxHz step 500) {
                val x = xOf(hz.toDouble())
                drawLine(th[ThemeColor.GRAPH_GRID], Offset(x, 0f), Offset(x, specH), 1f)
                val label = if (hz % 1000 == 0) "${hz / 1000}k" else "%.1fk".format(hz / 1000.0)
                val m = measurer.measure(label, labelStyle)
                drawText(m, topLeft = Offset(x - m.size.width / 2f, size.height - axisH + 1f))
            }
            if (showEdges) {
                val rfA = s.activeHz + sense.sign * (0.0 - tone); val rfB = s.activeHz + sense.sign * (e.maxHz - tone.toDouble())
                drawBandPlan(
                    minOf(rfA, rfB), maxOf(rfA, rfB), { rf -> xOf(tone + sense.sign * (rf - s.activeHz)) },
                    wfTop + wfH, bars, measurer, labelStyle,
                )
            }
            // where a tap sends a signal
            if (sense != Sense.NONE && target > 0) {
                val x = xOf(target.toDouble())
                drawLine(accent, Offset(x, 0f), Offset(x, size.height - axisH), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            }
        }
        Text(
            when {
                sense == Sense.NONE -> "Tap-to-tune works in SSB, CW and DATA modes (not ${s.mode.ifEmpty { "this mode" }})."
                s.mode.startsWith("CW") -> "Tap a signal to tune it onto your CW pitch ($pitch Hz, dashed line)."
                s.mode.startsWith("DATA") -> "Tap a signal to move it to 1500 Hz (dashed line)."
                else -> "SSB: tap the ${if (sense == Sense.UPPER) "left (low)" else "right (high)"} edge of a voice signal — that becomes its carrier."
            } + if (s.ptt) "  Paused while transmitting." else "",
            fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, color = th[ThemeColor.LABEL],
        )
        if (bars.isNotEmpty() && sense != Sense.NONE) BandLegend(bars)
    }
}

/** The RTL-SDR view: real RF frequencies around the dial, the filter passband shaded, tap a signal to tune to it. */
@Composable
private fun WideView(
    s: RigState, vm: RigViewModel, w: net.kanenet.catexplorer.audio.WideEngine, frame: Int, palette: IntArray, edgesOn: Boolean,
    bars: List<BandPlan.License>,
) {
    val th = LocalThemeSettings.current
    val bitmap = remember(w) { Bitmap.createBitmap(w.width, w.height, Bitmap.Config.ARGB_8888) }
    val argb = remember(w) { IntArray(w.width * w.height) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = th[ThemeColor.LABEL])
    val traceColor = th[ThemeColor.HIST_S]
    val accent = th[ThemeColor.ACCENT]
    val bwHz = Regex("\\d+").find(s.bandwidth)?.value?.toIntOrNull()

    Canvas(
        Modifier.fillMaxWidth().height((230 + stripDp(bars.size)).dp).padding(top = 4.dp).pointerInput(w) {
            detectTapGestures { p -> vm.wideTap(p.x.toDouble() / size.width * w.width) }
        },
    ) {
        frame   // redraw on every new row
        val specH = size.height * 0.25f
        val axisH = 14.dp.toPx()
        val stripH = stripDp(bars.size).dp.toPx()
        val wfTop = specH
        val wfH = size.height - wfTop - stripH - axisH
        val centre = w.centreHz
        val span = w.spanHz
        fun xOf(hz: Double) = ((hz - (centre - span / 2.0)) / span * size.width).toFloat()

        drawRect(th[ThemeColor.GRAPH_BG], size = androidx.compose.ui.geometry.Size(size.width, specH))
        // the radio's receive filter, shaded on the spectrum
        if (bwHz != null && centre > 0) {
            val (a, b) = when (s.mode) {
                "USB", "DATA-U", "RTTY-U" -> centre.toDouble() to centre + bwHz.toDouble()
                "LSB", "DATA-L", "RTTY-L" -> centre - bwHz.toDouble() to centre.toDouble()
                else -> centre - bwHz / 2.0 to centre + bwHz / 2.0
            }
            val x0 = xOf(a); val x1 = maxOf(xOf(b), x0 + 2f)
            drawRect(accent.copy(alpha = 0.18f), Offset(x0, 0f), androidx.compose.ui.geometry.Size(x1 - x0, specH))
        }
        val path = Path()
        w.paint(argb, palette)
        bitmap.setPixels(argb, 0, w.width, 0, 0, w.width, w.height)
        synchronized(w.lock) {
            for (x in 0 until w.width) {
                val px = (x + 0.5f) / w.width * size.width
                val py = specH * (1f - (w.trace[x] / w.rangeDb).coerceIn(0f, 1f))
                if (x == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
        }
        drawPath(path, traceColor, style = Stroke(1.5f))
        drawImage(
            image, srcOffset = IntOffset.Zero, srcSize = IntSize(w.width, w.height),
            dstOffset = IntOffset(0, wfTop.toInt()), dstSize = IntSize(size.width.toInt(), wfH.toInt()),
            filterQuality = FilterQuality.Low,
        )
        // frequency grid: about eight labels across, on round numbers
        if (centre > 0) {
            val step = niceStep(span / 8.0)
            var hz = Math.ceil((centre - span / 2.0) / step) * step
            while (hz < centre + span / 2.0) {
                val x = xOf(hz)
                drawLine(th[ThemeColor.GRAPH_GRID], Offset(x, 0f), Offset(x, specH), 1f)
                val label = if (step >= 1000) "%.3f".format(hz / 1e6) else "%.4f".format(hz / 1e6)
                val m = measurer.measure(label, labelStyle)
                if (x - m.size.width / 2f > 0 && x + m.size.width / 2f < size.width)
                    drawText(m, topLeft = Offset(x - m.size.width / 2f, size.height - axisH + 1f))
                hz += step
            }
            if (edgesOn) drawBandPlan(centre - span / 2.0, centre + span / 2.0, ::xOf, wfTop + wfH, bars, measurer, labelStyle)
            // the dial
            val xd = xOf(centre.toDouble())
            drawLine(th[ThemeColor.HIST_TX], Offset(xd, 0f), Offset(xd, size.height - axisH), 1.5f)
        }
    }
    Text(
        when {
            s.mode.startsWith("CW") -> "RTL-SDR: tap a signal to tune to it (snaps to the strongest signal nearby). Red line = dial."
            s.mode.startsWith("DATA") -> "RTL-SDR: tap a signal to put it at 1500 Hz in your audio (snaps to the nearest signal). Red line = dial."
            s.mode == "USB" || s.mode == "LSB" -> "RTL-SDR: tap the carrier edge of a voice signal to tune to it. Red line = dial, shading = your filter."
            else -> "RTL-SDR: tap a signal to tune to it. Red line = dial."
        } + if (s.ptt) "  Paused while transmitting." else "",
        fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, color = th[ThemeColor.LABEL],
    )
    if (bars.isNotEmpty()) BandLegend(bars)
}

/** A round grid step (1, 2, 2.5 or 5 × a power of ten) close to [rough] Hz. */
internal fun niceStep(rough: Double): Double {
    val p = Math.pow(10.0, Math.floor(Math.log10(rough)))
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * p }.first { it >= rough }
}

/** License bars under the waterfall: one row of [STRIP_ROW_DP] per class, with 1 dp gaps. */
private const val STRIP_ROW_DP = 8
private fun stripDp(rows: Int) = if (rows == 0) 0 else rows * STRIP_ROW_DP + rows + 1

private val useColors = mapOf(
    BandPlan.Use.CW to Color(0xFFFFC107),
    BandPlan.Use.DATA to Color(0xFF29B6F6),
    BandPlan.Use.PHONE to Color(0xFF66BB6A),
    BandPlan.Use.ALL to Color(0xFFBA68C8),
)

/** On a class's bar: part of the band where only a higher license may transmit. */
private val higherClassColor = Color(0xFFE53935)

/**
 * Band plan overlay for the RF range [rfLo]..[rfHi]: dims what's outside the ham bands, draws band edges (solid) and
 * sub-band edges (dashed) down through the bars, labels them in MHz, and draws one bar per class in [classes] just
 * below the waterfall (from [stripTop]) showing where that class may transmit, coloured by mode.
 */
private fun DrawScope.drawBandPlan(
    rfLo: Double, rfHi: Double, xOfRf: (Double) -> Float, stripTop: Float, classes: List<BandPlan.License>,
    measurer: TextMeasurer, labelStyle: TextStyle,
) {
    val bottom = stripTop + stripDp(classes.size).dp.toPx()
    fun x(rf: Double) = xOfRf(rf).coerceIn(0f, size.width)
    val bands = BandPlan.bandsIn(rfLo, rfHi)
    // dim outside the bands
    val shade = Color.Black.copy(alpha = 0.45f)
    var from = rfLo
    for (b in bands.sortedBy { it.lo }) {
        if (b.lo > from) drawSpan(x(from), x(b.lo.toDouble()), 0f, stripTop, shade)
        from = maxOf(from, b.hi.toDouble())
    }
    if (from < rfHi) drawSpan(x(from), x(rfHi), 0f, stripTop, shade)
    if (classes.isNotEmpty()) drawRect(Color(0xFF101010), Offset(0f, stripTop), androidx.compose.ui.geometry.Size(size.width, bottom - stripTop))

    // license bars: one row per chosen class, Extra at the top
    val rowH = STRIP_ROW_DP.dp.toPx(); val gap = 1.dp.toPx()
    val small = labelStyle.copy(fontSize = 8.sp, lineHeight = 8.sp, color = Color.White)
    classes.forEachIndexed { i, lic ->
        val y = stripTop + gap + i * (rowH + gap)
        drawRect(Color(0xFF202020), Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, rowH))
        // red where only a higher class may transmit, then this class's own privileges on top
        for (b in bands) for (seg in b.segments) {
            if (seg.hi < rfLo || seg.lo > rfHi || seg.classes.none { it.ordinal < lic.ordinal }) continue
            drawSpan(x(seg.lo.toDouble()), x(seg.hi.toDouble()), y, y + rowH, higherClassColor)
        }
        for (b in bands) for (seg in b.privileges(lic)) {
            if (seg.hi < rfLo || seg.lo > rfHi) continue
            drawSpan(x(seg.lo.toDouble()), x(seg.hi.toDouble()), y, y + rowH, useColors.getValue(seg.use))
        }
    }
    // class letters at the left
    classes.forEachIndexed { i, lic ->
        val m = measurer.measure(lic.short, small)
        val y = stripTop + gap + i * (rowH + gap) + rowH / 2 - m.size.height / 2f
        val boxW = maxOf(m.size.width + 8f, rowH * 1.6f)
        drawRect(Color.Black.copy(alpha = 0.8f), Offset(0f, stripTop + gap + i * (rowH + gap)), androidx.compose.ui.geometry.Size(boxW, rowH))
        drawText(m, topLeft = Offset((boxW - m.size.width) / 2f, y))
    }

    // edge lines and their frequencies
    var lastLabelEnd = -1f
    for (edge in BandPlan.edgesIn(rfLo, rfHi)) {
        val xe = xOfRf(edge.hz.toDouble())
        if (xe < 0f || xe > size.width) continue
        if (edge.bandEdge) drawLine(Color.White, Offset(xe, 0f), Offset(xe, bottom), 2.dp.toPx())
        else drawLine(Color.White.copy(alpha = 0.6f), Offset(xe, 0f), Offset(xe, bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        val label = "%.4f".format(edge.hz / 1e6).trimEnd('0').let { if (it.substringAfter('.').length < 3) "%.3f".format(edge.hz / 1e6) else it }
        val m = measurer.measure(label, labelStyle.copy(color = Color.White))
        val lx = (xe - m.size.width / 2f).coerceIn(0f, size.width - m.size.width)
        if (lx > lastLabelEnd + 4f) {
            drawRect(Color.Black.copy(alpha = 0.65f), Offset(lx - 2f, 1f), androidx.compose.ui.geometry.Size(m.size.width + 4f, m.size.height.toFloat()))
            drawText(m, topLeft = Offset(lx, 1f))
            lastLabelEnd = lx + m.size.width
        }
    }
}

private fun DrawScope.drawSpan(xa: Float, xb: Float, top: Float, bottom: Float, color: Color) {
    val x0 = minOf(xa, xb); val x1 = maxOf(xa, xb)
    if (x1 - x0 < 0.5f) return
    drawRect(color, Offset(x0, top), androidx.compose.ui.geometry.Size(x1 - x0, bottom - top))
}

/** Key for the license bars: the classes shown, and which colour is which mode. */
@Composable
private fun BandLegend(classes: List<BandPlan.License>) {
    val th = LocalThemeSettings.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Bars: " + classes.joinToString(", ") { "${it.short} = ${it.label}" } + " —", fontSize = 11.sp, color = th[ThemeColor.LABEL])
        useColors.forEach { (use, c) ->
            Canvas(Modifier.size(10.dp)) { drawRect(c) }
            Text(use.label, fontSize = 11.sp, color = th[ThemeColor.LABEL])
        }
        if (classes.any { it != BandPlan.License.EXTRA }) {
            Canvas(Modifier.size(10.dp)) { drawRect(higherClassColor) }
            Text("Higher class only — don't transmit", fontSize = 11.sp, color = th[ThemeColor.LABEL])
        }
        Text("· dimmed = out of band", fontSize = 11.sp, color = th[ThemeColor.LABEL])
    }
}

/** A small left-to-right preview of a waterfall colour theme. */
@Composable
internal fun PaletteStrip(name: String, modifier: Modifier) {
    val colors = WaterfallThemes.named(name).colors
    Canvas(modifier) {
        val n = 64
        val w = size.width / n
        for (i in 0 until n) drawRect(Color(colors[i * 255 / (n - 1)]), Offset(i * w, 0f), androidx.compose.ui.geometry.Size(w + 1f, size.height))
    }
}
