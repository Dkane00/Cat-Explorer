// Copyright (C) 2026 David Kane (W6MZT)
// SPDX-License-Identifier: GPL-3.0-or-later
package net.kanenet.catexplorer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A tick on a bar meter: position 0..100 and its label. */
data class Tick(val at: Int, val label: String)

val S_TICKS = listOf(Tick(0, "S1"), Tick(11, "3"), Tick(22, "5"), Tick(33, "7"), Tick(44, "9"), Tick(66, "+20"), Tick(88, "+40"), Tick(100, "+60"))
val SWR_TICKS = listOf(Tick(0, "1"), Tick(12, "1.5"), Tick(25, "2"), Tick(50, "3"), Tick(100, "∞"))

fun powerTicks(maxW: Int) = (0..4).map { Tick(it * 25, "${maxW * it / 4}") }

/** S-meter reading from FLRig's 0..100 value, as text ("S7", "S9+20"). */
fun sUnitsText(v: Int): String =
    if (v <= 44) "S${(v * 9 / 44).coerceIn(0, 9)}"
    else "S9+${(v - 44) * 60 / 56}"

@Composable
fun BarMeter(
    title: String,
    value: Int,
    ticks: List<Tick>,
    valueText: String,
    barColor: Color = LocalThemeSettings.current[ThemeColor.METER_S],
    warnFrom: Int = 101,
    modifier: Modifier = Modifier,
) {
    val th = LocalThemeSettings.current
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 13.sp, color = th[ThemeColor.LABEL], modifier = Modifier.width(52.dp))
            Canvas(Modifier.weight(1f).height(22.dp)) {
                drawRoundRect(th[ThemeColor.METER_TRACK], size = size, cornerRadius = CornerRadius(5f))
                val w = size.width * value.coerceIn(0, 100) / 100f
                val warnX = size.width * warnFrom / 100f
                drawRoundRect(barColor, size = Size(minOf(w, warnX), size.height), cornerRadius = CornerRadius(5f))
                if (w > warnX) drawRect(th[ThemeColor.METER_OVER], Offset(warnX, 0f), Size(w - warnX, size.height))
            }
            Text(valueText, fontSize = 15.sp, modifier = Modifier.width(150.dp).padding(start = 8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(start = 52.dp, end = 150.dp)) {
            // Evenly weighted labels placed by position.
            Box(Modifier.fillMaxWidth().height(20.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    ticks.forEach { t ->
                        val x = size.width * t.at / 100f
                        drawLine(th[ThemeColor.METER_TICK], Offset(x, 0f), Offset(x, 5f), 2f)
                    }
                }
                TickLabels(ticks)
            }
        }
    }
}

@Composable
private fun TickLabels(ticks: List<Tick>) {
    val th = LocalThemeSettings.current
    androidx.compose.ui.layout.Layout(content = {
        ticks.forEach { Text(it.label, fontSize = 10.sp, color = th[ThemeColor.LABEL]) }
    }) { measurables, constraints ->
        val ps = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        layout(constraints.maxWidth, ps.maxOfOrNull { it.height } ?: 0) {
            ps.forEachIndexed { i, p ->
                val x = (constraints.maxWidth * ticks[i].at / 100f - p.width / 2f).toInt()
                    .coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0))
                p.place(x, 0)
            }
        }
    }
}

private const val GAUGE_START = 200f
private const val GAUGE_SWEEP = 140f

/** Needle gauge showing the same reading as [BarMeter]: scale across the top, needle from the bottom centre. */
@Composable
fun AnalogMeter(
    title: String,
    value: Int,
    ticks: List<Tick>,
    valueText: String,
    barColor: Color,
    warnFrom: Int = 101,
    modifier: Modifier = Modifier,
) {
    val th = LocalThemeSettings.current
    val measurer = rememberTextMeasurer()
    val pos by animateFloatAsState(value.coerceIn(0, 100) / 100f, tween(150), label = "needle")
    val labelStyle = TextStyle(fontSize = 9.sp, color = th[ThemeColor.LABEL])
    val track = th[ThemeColor.METER_TRACK]
    val over = th[ThemeColor.METER_OVER]
    val tick = th[ThemeColor.METER_TICK]
    val needle = th[ThemeColor.TEXT]
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1.7f)) {
            val cx = size.width / 2f
            val py = size.height * 0.92f
            val r = size.width * 0.37f
            val arcTop = Offset(cx - r, py - r)
            val arcSize = GeoSize(2f * r, 2f * r)
            val band = r * 0.12f
            fun angle(frac: Float) = GAUGE_START + GAUGE_SWEEP * frac
            fun rad(frac: Float) = Math.toRadians(angle(frac).toDouble())

            drawArc(track, GAUGE_START, GAUGE_SWEEP, false, arcTop, arcSize, style = Stroke(band))
            val warn = warnFrom / 100f
            if (warn < 1f) drawArc(over.copy(alpha = 0.35f), angle(warn), GAUGE_SWEEP * (1f - warn), false, arcTop, arcSize, style = Stroke(band))
            val okEnd = minOf(pos, warn)
            if (okEnd > 0f) drawArc(barColor, GAUGE_START, GAUGE_SWEEP * okEnd, false, arcTop, arcSize, style = Stroke(band))
            if (pos > warn) drawArc(over, angle(warn), GAUGE_SWEEP * (pos - warn), false, arcTop, arcSize, style = Stroke(band))

            ticks.forEach { t ->
                val a = rad(t.at / 100f)
                val c = Math.cos(a).toFloat(); val sn = Math.sin(a).toFloat()
                drawLine(tick, Offset(cx + c * r * 1.09f, py + sn * r * 1.09f), Offset(cx + c * r * 1.17f, py + sn * r * 1.17f), 2f)
                val layout = measurer.measure(t.label, labelStyle)
                drawText(layout, topLeft = Offset(cx + c * r * 1.30f - layout.size.width / 2f, py + sn * r * 1.30f - layout.size.height / 2f))
            }

            val na = rad(pos)
            drawLine(needle, Offset(cx, py), Offset(cx + Math.cos(na).toFloat() * r * 0.9f, py + Math.sin(na).toFloat() * r * 0.9f), 4f, cap = StrokeCap.Round)
            drawCircle(needle, 8f, Offset(cx, py))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 13.sp, color = th[ThemeColor.LABEL])
            Text(valueText, fontSize = 14.sp)
        }
    }
}
