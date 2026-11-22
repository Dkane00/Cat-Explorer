// Copyright (C) 2026 David Kane (W6MZT)
// SPDX-License-Identifier: GPL-3.0-or-later
package net.kanenet.catexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kanenet.catexplorer.RigViewModel
import net.kanenet.catexplorer.rig.Ft857Cat
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Panels for the FT-857D's set-only controls. The radio can't report these back over CAT, so each panel shows what the app
 * last sent (and starts from the radio's usual defaults when you connect).
 */

@Composable
private fun SetOnlyNote() {
    Text(
        "The FT-857D can't report this back, so this shows what the app last sent.",
        fontSize = 11.sp, color = LocalThemeSettings.current[ThemeColor.LABEL],
    )
}

/** Clarifier (RIT): on/off and an offset of up to ±9.99 kHz in 10 Hz steps. */
@Composable
fun ClarifierPanel(vm: RigViewModel, height: Dp) {
    val ft by vm.ft857.collectAsState()
    val th = LocalThemeSettings.current
    var drag by remember(ft.clarHz) { mutableFloatStateOf(ft.clarHz.toFloat()) }
    fun setOffset(hz: Int) {
        val v = (hz / 10 * 10).coerceIn(-9_990, 9_990)
        vm.setFt857({ copy(clarHz = v) }) { setClarifierOffset(v) }
    }
    Column(Modifier.height(height).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Clarifier", fontSize = 12.sp, color = th[ThemeColor.LABEL])
            FilterChip(ft.clarOn, { vm.setFt857({ copy(clarOn = !clarOn) }) { setClarifier(!ft.clarOn) } }, { Text(if (ft.clarOn) "On" else "Off") }, colors = selectedBlue())
            Text(String.format(Locale.US, "%+.2f kHz", drag / 1000.0), fontSize = 16.sp)
            TextButton({ setOffset(0) }) { Text("Zero") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ setOffset(ft.clarHz - 10) }) { Text("◀") }
            Slider(
                drag, { drag = it }, onValueChangeFinished = { setOffset(drag.roundToInt()) },
                valueRange = -9_990f..9_990f, modifier = Modifier.weight(1f),
            )
            TextButton({ setOffset(ft.clarHz + 10) }) { Text("▶") }
        }
        SetOnlyNote()
    }
}

/** Repeater shift and offset, and tone squelch: CTCSS tone or DCS code. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RepeaterPanel(vm: RigViewModel, height: Dp) {
    val ft by vm.ft857.collectAsState()
    val rig by vm.state.collectAsState()
    val th = LocalThemeSettings.current
    val isFm = rig.mode.startsWith("FM") || rig.mode == "PKT"
    var picking by remember { mutableStateOf<String?>(null) }   // "ctcss", "dcs" or "offset"
    Column(Modifier.height(height).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Shift", Modifier.width(48.dp), fontSize = 12.sp, color = th[ThemeColor.LABEL])
            Ft857Cat.Shift.entries.forEach { sh ->
                FilterChip(ft.shift == sh, { vm.setFt857({ copy(shift = sh) }) { setRepeaterShift(sh) } }, { Text(sh.label) }, colors = selectedBlue())
            }
            OutlinedButton({ picking = "offset" }) { Text(String.format(Locale.US, "Offset %.3f MHz", ft.offsetHz / 1e6)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tone", Modifier.width(48.dp), fontSize = 12.sp, color = th[ThemeColor.LABEL])
            Ft857Cat.ToneMode.entries.forEach { m ->
                FilterChip(ft.toneMode == m, { vm.setFt857({ copy(toneMode = m) }) { setToneMode(m) } }, { Text(m.label) }, colors = selectedBlue())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("", Modifier.width(48.dp))
            OutlinedButton({ picking = "ctcss" }) { Text(String.format(Locale.US, "CTCSS %.1f Hz", ft.ctcss)) }
            OutlinedButton({ picking = "dcs" }) { Text("DCS %03d".format(ft.dcs)) }
        }
        if (!isFm) Text("Shift and tones work in FM.", fontSize = 11.sp, color = th[ThemeColor.WARN])
        SetOnlyNote()
    }

    when (picking) {
        "ctcss" -> PickDialog("CTCSS tone (Hz)", Ft857Cat.CTCSS.map { String.format(Locale.US, "%.1f", it) }, String.format(Locale.US, "%.1f", ft.ctcss), { picking = null }) { t ->
            val hz = t.toDouble()
            vm.setFt857({ copy(ctcss = hz) }) { setCtcss(hz) }
        }
        "dcs" -> PickDialog("DCS code", Ft857Cat.DCS.map { "%03d".format(it) }, "%03d".format(ft.dcs), { picking = null }) { t ->
            val code = t.toInt()
            vm.setFt857({ copy(dcs = code) }) { setDcs(code) }
        }
        "offset" -> {
            var text by remember { mutableStateOf(String.format(Locale.US, "%.3f", ft.offsetHz / 1e6)) }
            val mhz = text.replace(",", ".").toDoubleOrNull()
            val valid = mhz != null && mhz in 0.0..99.99
            AlertDialog(
                onDismissRequest = { picking = null },
                title = { Text("Repeater offset") },
                text = {
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("0.600" to "2 m", "1.600" to "1.25 m", "5.000" to "70 cm").forEach { (v, band) ->
                                OutlinedButton({ text = v }) { Text("$v ($band)", fontSize = 12.sp) }
                            }
                        }
                        OutlinedTextField(
                            text, { text = it }, singleLine = true, label = { Text("MHz") }, isError = !valid,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                    }
                },
                confirmButton = {
                    TextButton({
                        val hz = Math.round(mhz!! * 1e6)
                        vm.setFt857({ copy(offsetHz = hz) }) { setRepeaterOffset(hz) }
                        picking = null
                    }, enabled = valid) { Text("Set") }
                },
                dismissButton = { TextButton({ picking = null }) { Text("Cancel") } },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickDialog(title: String, options: List<String>, current: String, onClose: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            FlowRow(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                options.forEach { o -> FilterChip(o == current, { onPick(o); onClose() }, { Text(o) }) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}
