// Copyright (C) 2026 David Kane (W6MZT)
// SPDX-License-Identifier: GPL-3.0-or-later
package net.kanenet.catexplorer.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Every colour the user can change, with its built-in value for dark mode and for light mode. */
enum class ThemeColor(val group: String, val label: String, val dark: Long, val light: Long) {
    ACCENT("General", "Highlight (selected buttons, titles)", 0xFF4FC3F7, 0xFF0277BD),
    BACKGROUND("General", "Screen background", 0xFF0B0F14, 0xFFEEF1F4),
    PANEL("General", "Panel background", 0xFF141A22, 0xFFFFFFFF),
    CONTROL("General", "Button and field background", 0xFF49454F, 0xFFE3E8ED),
    TEXT("Text", "Main text", 0xFFE6EDF3, 0xFF15202B),
    LABEL("Text", "Labels and dim text", 0xFF8B98A5, 0xFF5A6672),
    WARN("Text", "Warnings and SPLIT", 0xFFFFB74D, 0xFFE65100),
    DANGER("Text", "Transmit and errors", 0xFFEF5350, 0xFFC62828),
    OK("Text", "Connected and receiving", 0xFF66BB6A, 0xFF2E7D32),

    METER_TRACK("Meter bars", "Bar background", 0xFF1E2833, 0xFFD6DDE4),
    METER_S("Meter bars", "S-meter bar", 0xFF4FC3F7, 0xFF0277BD),
    METER_PWR("Meter bars", "Power bar", 0xFF66BB6A, 0xFF2E7D32),
    METER_SWR("Meter bars", "SWR bar", 0xFFFFB74D, 0xFFEF6C00),
    METER_ALC("Meter bars", "ALC bar", 0xFFBA68C8, 0xFF8E24AA),
    METER_COMP("Meter bars", "COMP bar", 0xFF4DB6AC, 0xFF00897B),
    METER_ID("Meter bars", "ID bar", 0xFFE57373, 0xFFD32F2F),
    METER_OVER("Meter bars", "Over-limit zone", 0xFFEF5350, 0xFFC62828),
    METER_TICK("Meter bars", "Scale tick marks", 0xFF5C6B7A, 0xFF8A96A3),

    HIST_S("Signal history", "S-meter trace", 0xFF4FC3F7, 0xFF0277BD),
    HIST_PWR("Signal history", "Power trace", 0xFF66BB6A, 0xFF2E7D32),
    HIST_SWR("Signal history", "SWR trace", 0xFFFFB74D, 0xFFEF6C00),
    HIST_TX("Signal history", "Transmit shading", 0xFFEF5350, 0xFFC62828),

    GRAPH_BG("Graphs (filter, EQ, history)", "Graph background", 0xFF0E141B, 0xFFF1F4F7),
    GRAPH_GRID("Graphs (filter, EQ, history)", "Grid lines", 0xFF26313D, 0xFFD3DAE1),
    GRAPH_AXIS("Graphs (filter, EQ, history)", "Axis and zero line", 0xFF3A4654, 0xFFAAB4BF),
    FILTER_CURVE("Graphs (filter, EQ, history)", "Filter curve", 0xFF4FC3F7, 0xFF0277BD),
    NOTCH("Graphs (filter, EQ, history)", "Notch marker", 0xFFFFB74D, 0xFFEF6C00),
    CONTOUR("Graphs (filter, EQ, history)", "Contour marker", 0xFFBA68C8, 0xFF8E24AA),
    APF("Graphs (filter, EQ, history)", "APF marker", 0xFF66BB6A, 0xFF2E7D32),
    EQ_1("Graphs (filter, EQ, history)", "EQ band 1", 0xFF4FC3F7, 0xFF0277BD),
    EQ_2("Graphs (filter, EQ, history)", "EQ band 2", 0xFFE0E0E0, 0xFF555B61),
    EQ_3("Graphs (filter, EQ, history)", "EQ band 3", 0xFFFF6E9C, 0xFFD81B60),
}

/** Dark/light choice plus the colours the user changed (kept separately for each mode). */
data class ThemeSettings(
    val dark: Boolean = true,
    val darkColors: Map<ThemeColor, Int> = emptyMap(),
    val lightColors: Map<ThemeColor, Int> = emptyMap(),
) {
    private val changed get() = if (dark) darkColors else lightColors

    operator fun get(c: ThemeColor): Color = changed[c]?.let { Color(it) } ?: defaultOf(c)
    fun defaultOf(c: ThemeColor): Color = Color(if (dark) c.dark else c.light)
    fun isCustom(c: ThemeColor) = c in changed

    /** Sets (or, with null, resets) one colour in the current mode. */
    fun with(c: ThemeColor, color: Color?): ThemeSettings {
        val m = changed.toMutableMap()
        if (color == null) m.remove(c) else m[c] = color.toArgb32()
        return if (dark) copy(darkColors = m) else copy(lightColors = m)
    }

    fun resetCurrent() = if (dark) copy(darkColors = emptyMap()) else copy(lightColors = emptyMap())

    fun materialScheme(): ColorScheme {
        val base = if (dark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = this[ThemeColor.ACCENT], onPrimary = readableOn(this[ThemeColor.ACCENT]),
            background = this[ThemeColor.BACKGROUND], onBackground = this[ThemeColor.TEXT],
            surface = this[ThemeColor.PANEL], onSurface = this[ThemeColor.TEXT],
            surfaceVariant = this[ThemeColor.CONTROL], secondaryContainer = this[ThemeColor.CONTROL], onSurfaceVariant = this[ThemeColor.TEXT],
            error = this[ThemeColor.DANGER], onError = readableOn(this[ThemeColor.DANGER]),
        )
    }
}

val LocalThemeSettings = compositionLocalOf { ThemeSettings() }

fun readableOn(bg: Color): Color = if (bg.luminance() > 0.5f) Color.Black else Color.White

fun Color.toArgb32(): Int =
    ((alpha * 255f + 0.5f).toInt() shl 24) or ((red * 255f + 0.5f).toInt() shl 16) or
        ((green * 255f + 0.5f).toInt() shl 8) or (blue * 255f + 0.5f).toInt()

/** Hue 0..360, saturation and lightness 0..1 → colour. */
fun hslColor(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val hp = (h.coerceIn(0f, 359.999f) / 60f)
    val x = c * (1f - abs(hp % 2f - 1f))
    var r = 0f; var g = 0f; var b = 0f
    when (hp.toInt()) {
        0 -> { r = c; g = x }
        1 -> { r = x; g = c }
        2 -> { g = c; b = x }
        3 -> { g = x; b = c }
        4 -> { r = x; b = c }
        else -> { r = c; b = x }
    }
    val m = l - c / 2f
    return Color(r + m, g + m, b + m)
}

/** Colour → (hue 0..360, saturation 0..1, lightness 0..1). */
fun toHsl(color: Color): Triple<Float, Float, Float> {
    val r = color.red; val g = color.green; val b = color.blue
    val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
    val d = mx - mn
    val l = (mx + mn) / 2f
    if (d < 1e-6f) return Triple(0f, 0f, l)
    val s = d / (1f - abs(2f * l - 1f))
    val sector: Float = if (mx == r) ((g - b) / d) % 6f else if (mx == g) (b - r) / d + 2f else (r - g) / d + 4f
    val h = (if (sector < 0f) sector + 6f else sector) * 60f
    return Triple(h, s.coerceIn(0f, 1f), l)
}

fun colorHex(c: Color): String = "#%06X".format(c.toArgb32() and 0xFFFFFF)

fun parseHex(text: String): Color? {
    val t = text.trim().removePrefix("#")
    if (t.length != 6) return null
    val v = t.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or v)
}
