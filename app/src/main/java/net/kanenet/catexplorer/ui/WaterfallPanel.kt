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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.kanenet.catexplorer.RigViewModel
import net.kanenet.catexplorer.audio.Sense
import net.kanenet.catexplorer.audio.TuneMath
import net.kanenet.catexplorer.rig.RigState

/**
 * Audio waterfall of the receive passband (about 0–4 kHz), fed by the PC audio helper. Tap a signal to tune to it.
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
    val e = vm.engine

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
                FilterChip(listen, { vm.setListen(!listen) }, { Text("Listen", fontSize = 12.sp) }, Modifier.height(28.dp), colors = selectedBlue())
                Text("Range", fontSize = 12.sp, color = th[ThemeColor.LABEL])
                TextButton({ vm.setWaterfallRange(range - 5) }, Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("◀", fontSize = 12.sp) }
                Text("$range dB", fontSize = 12.sp)
                TextButton({ vm.setWaterfallRange(range + 5) }, Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("▶", fontSize = 12.sp) }
                val clip = e?.clipFraction ?: 0f
                @Suppress("UNUSED_EXPRESSION") frame   // re-read the clip level as rows arrive
                when {
                    status.isNotEmpty() -> Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    clip > 0.001f -> Text("Audio too loud — turn down the sound card's input level on the PC", fontSize = 12.sp, color = th[ThemeColor.HIST_SWR], modifier = Modifier.weight(1f))
                }
            }
        }
        if (e == null) {
            Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (status.isEmpty()) "Waiting for audio…" else "Needs cat-audio-server.py running on the FLRig PC (see pc-helper in the Cat Explorer repo).",
                    fontSize = 12.sp, color = th[ThemeColor.LABEL],
                )
            }
            return@Column
        }

        val bitmap = remember(e) { Bitmap.createBitmap(e.width, e.height, Bitmap.Config.ARGB_8888) }
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        val measurer = rememberTextMeasurer()
        val labelStyle = TextStyle(fontSize = 10.sp, color = th[ThemeColor.LABEL])
        val traceColor = th[ThemeColor.HIST_S]
        val accent = th[ThemeColor.ACCENT]

        Canvas(
            Modifier.fillMaxWidth().height(230.dp).padding(top = 4.dp).pointerInput(e, sense) {
                if (sense != Sense.NONE) detectTapGestures { p -> vm.waterfallTap(p.x.toDouble() / size.width * e.maxHz) }
            },
        ) {
            frame   // redraw on every new row
            val specH = size.height * 0.25f
            val axisH = 14.dp.toPx()
            val wfTop = specH
            val wfH = size.height - specH - axisH
            fun xOf(hz: Double) = (hz / e.maxHz * size.width).toFloat()

            // spectrum trace
            drawRect(th[ThemeColor.GRAPH_BG], size = androidx.compose.ui.geometry.Size(size.width, specH))
            val path = Path()
            synchronized(e.lock) {
                bitmap.setPixels(e.pixels, 0, e.width, 0, 0, e.width, e.height)
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
    }
}
