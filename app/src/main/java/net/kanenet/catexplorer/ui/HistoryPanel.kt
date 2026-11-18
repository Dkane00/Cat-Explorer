package net.kanenet.catexplorer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kanenet.catexplorer.HISTORY_LEN
import net.kanenet.catexplorer.HistorySample

/**
 * Signal history, like the CatTouch's RX/TX trace: S-meter while receiving, power and SWR
 * while transmitting. Transmit periods are shaded red. Newest sample is on the right.
 */
@Composable
fun HistoryPanel(history: List<HistorySample>, modifier: Modifier = Modifier, header: @Composable RowScope.() -> Unit = {}) {
    val th = LocalThemeSettings.current
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            header()
            Text("— S", fontSize = 12.sp, color = th[ThemeColor.HIST_S])
            Text("— PWR", fontSize = 12.sp, color = th[ThemeColor.HIST_PWR])
            Text("— SWR", fontSize = 12.sp, color = th[ThemeColor.HIST_SWR])
        }
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)) {
            drawRect(th[ThemeColor.GRAPH_BG])
            for (g in 1..3) {
                val y = size.height * g / 4f
                drawLine(th[ThemeColor.GRAPH_GRID], Offset(0f, y), Offset(size.width, y), 1f)
            }
            if (history.size < 2) return@Canvas
            val dx = size.width / (HISTORY_LEN - 1)
            val x0 = size.width - dx * (history.size - 1)
            fun x(i: Int) = x0 + dx * i
            fun y(v: Int) = size.height * (1f - v.coerceIn(0, 100) / 100f)

            history.forEachIndexed { i, h ->
                if (h.tx) drawRect(th[ThemeColor.HIST_TX].copy(alpha = 0.2f), Offset(x(i), 0f), androidx.compose.ui.geometry.Size(dx + 1f, size.height))
            }
            fun trace(color: Color, pick: (HistorySample) -> Int, only: ((HistorySample) -> Boolean)? = null) {
                val path = Path()
                val fill = Path()   // the same trace closed down to the bottom, shaded faintly under the line
                var pen = false
                var startX = 0f
                var lastX = 0f
                fun closeFill() { if (pen) { fill.lineTo(lastX, size.height); fill.lineTo(startX, size.height); fill.close() } }
                history.forEachIndexed { i, h ->
                    if (only != null && !only(h)) { closeFill(); pen = false; return@forEachIndexed }
                    val px = x(i); val py = y(pick(h))
                    if (!pen) {
                        path.moveTo(px, py); fill.moveTo(px, py); startX = px; pen = true
                    } else { path.lineTo(px, py); fill.lineTo(px, py) }
                    lastX = px
                }
                closeFill()
                drawPath(fill, color.copy(alpha = 0.25f))
                drawPath(path, color, style = Stroke(2.5f))
            }
            trace(th[ThemeColor.HIST_S], { it.s }, { !it.tx })
            trace(th[ThemeColor.HIST_PWR], { it.pwr }, { it.tx })
            trace(th[ThemeColor.HIST_SWR], { it.swr }, { it.tx })
        }
    }
}
