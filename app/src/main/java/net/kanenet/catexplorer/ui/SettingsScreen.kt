package net.kanenet.catexplorer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kanenet.catexplorer.RigViewModel

/** Settings panel that slides in over the right side, so the screen behind stays visible while colours change. */
@Composable
fun SettingsPanel(vm: RigViewModel, onClose: () -> Unit) {
    val theme by vm.theme.collectAsState()
    var editing by remember { mutableStateOf<ThemeColor?>(null) }

    // an overlay in the main window (not a Dialog) so it shares the activity's system-bar insets and icon colours
    androidx.activity.compose.BackHandler { if (editing != null) { editing = null } else onClose() }
    run {
        Box(Modifier.fillMaxSize().systemBarsPadding().clickable(onClick = onClose, indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }), contentAlignment = Alignment.CenterEnd) {
            Surface(
                Modifier.fillMaxHeight().widthIn(max = 460.dp).fillMaxWidth().padding(8.dp)
                    .pointerInput(Unit) { detectTapGestures { } },
                shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp, shadowElevation = 12.dp,
            ) {
                val role = editing
                if (role == null) {
                    SettingsList(vm, theme, onClose, onPick = { editing = it })
                } else {
                    ColorEditor(vm, theme, role, onDone = { editing = null })
                }
            }
        }
    }
}

@Composable
private fun SettingsList(vm: RigViewModel, theme: ThemeSettings, onClose: () -> Unit, onPick: (ThemeColor) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", fontSize = 22.sp, modifier = Modifier.weight(1f))
            TextButton(onClose) { Text("Close") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SectionTitle("Display")
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Dark mode")
                    Text("Off gives the light theme. Each mode keeps its own colour changes.", fontSize = 12.sp, color = theme[ThemeColor.LABEL])
                }
                Switch(theme.dark, { vm.setThemeDark(it) })
            }
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.height(8.dp))
            SectionTitle("Meters")
            val analog by vm.meterAnalog.collectAsState()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!analog, { vm.setMeterAnalog(false) }, { Text("Bar") }, colors = selectedBlue())
                FilterChip(analog, { vm.setMeterAnalog(true) }, { Text("Meter") }, colors = selectedBlue())
            }
            Text("Bar is the straight level bars; Meter is round needle gauges.", fontSize = 12.sp, color = theme[ThemeColor.LABEL], modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            SectionTitle("Waterfall")
            val audioPort by vm.audioPort.collectAsState()
            var portText by remember(audioPort) { mutableStateOf(audioPort.toString()) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    portText, { portText = it.filter(Char::isDigit).take(5) }, label = { Text("Panadapter port") }, singleLine = true,
                    modifier = Modifier.width(180.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                )
                TextButton({ portText.toIntOrNull()?.takeIf { it in 1..65535 }?.let { vm.setAudioPort(it) } }, enabled = portText != audioPort.toString()) { Text("Save") }
            }
            Text(
                "The waterfall gets the radio's audio from Cat Explorer Panadapter on the same PC as FLRig (default port 7366).",
                fontSize = 12.sp, color = theme[ThemeColor.LABEL], modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            val wfTheme by vm.waterfallTheme.collectAsState()
            var themeMenu by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Colours")
                Box {
                    OutlinedButton({ themeMenu = true }) {
                        PaletteStrip(wfTheme, Modifier.width(120.dp).height(16.dp))
                        Text(wfTheme, Modifier.padding(start = 10.dp))
                    }
                    DropdownMenu(themeMenu, { themeMenu = false }, Modifier.heightIn(max = 420.dp)) {
                        net.kanenet.catexplorer.audio.WaterfallThemes.all.forEach { t ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        PaletteStrip(t.name, Modifier.width(120.dp).height(16.dp))
                                        Text(t.name, Modifier.padding(start = 10.dp), color = if (t.name == wfTheme) theme[ThemeColor.ACCENT] else Color.Unspecified)
                                    }
                                },
                                onClick = { vm.setWaterfallTheme(t.name); themeMenu = false },
                            )
                        }
                    }
                }
            }
            Text(
                "Quiet on the left, loud on the right. All but Cat Explorer are the palettes that come with JS8Call.",
                fontSize = 12.sp, color = theme[ThemeColor.LABEL], modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(4.dp))
            val edges by vm.bandEdges.collectAsState()
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Band edges (US)")
                    Text(
                        "Marks band edges (solid lines, outside dimmed) and sub-band edges (dashed), with a strip showing where " +
                            "Extra, Advanced, General and Technician licensees may transmit and which modes. FCC rules — a guide only.",
                        fontSize = 12.sp, color = theme[ThemeColor.LABEL],
                    )
                }
                Switch(edges, { vm.setBandEdges(it) })
            }
            Spacer(Modifier.height(8.dp))
            SectionTitle("Tune button")
            val carrier by vm.tuneCarrier.collectAsState()
            val tuneW by vm.tunePowerW.collectAsState()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!carrier, { vm.setTuneCarrier(false) }, { Text("Radio's tuner") }, colors = selectedBlue())
                FilterChip(carrier, { vm.setTuneCarrier(true) }, { Text("Carrier for external tuner") }, colors = selectedBlue())
            }
            Text(
                if (!carrier) "Tune asks the radio to run its own antenna tuner (for a tuner that works with the radio, like a Yaesu one)."
                else "Tune becomes an on/off button: it switches to AM, sends a steady carrier at the power below, and stops by itself after 1 minute. Mode, width and power are put back afterwards.",
                fontSize = 12.sp, color = theme[ThemeColor.LABEL], modifier = Modifier.padding(top = 4.dp),
            )
            if (carrier) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Carrier power", Modifier.padding(end = 12.dp))
                    Slider(tuneW.toFloat(), { vm.setTunePower(it.toInt()) }, valueRange = 5f..10f, steps = 4, modifier = Modifier.weight(1f))
                    Text("$tuneW W", Modifier.width(48.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            SectionTitle("Colours (${if (theme.dark) "dark" else "light"} mode) — tap one to change it")
            ThemeColor.entries.groupBy { it.group }.forEach { (group, roles) ->
                Spacer(Modifier.height(10.dp))
                Text(group, fontSize = 13.sp, color = theme[ThemeColor.ACCENT])
                roles.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(r) }.padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Swatch(theme[r], 30)
                        Text(r.label, Modifier.padding(start = 12.dp).weight(1f), fontSize = 15.sp)
                        if (theme.isCustom(r)) Text("changed", fontSize = 11.sp, color = theme[ThemeColor.LABEL])
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton({ vm.resetThemeColors() }, enabled = ThemeColor.entries.any { theme.isCustom(it) }) {
                Text("Reset all ${if (theme.dark) "dark" else "light"} colours to default")
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
}

@Composable
private fun Swatch(color: Color, size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(6.dp)).background(color).border(1.dp, Color.Gray.copy(alpha = 0.6f), RoundedCornerShape(6.dp)))
}

private fun Modifier.pick(onPick: (Offset) -> Unit): Modifier = this
    .pointerInput(Unit) { detectTapGestures { onPick(it) } }
    .pointerInput(Unit) { detectDragGestures(onDragStart = { onPick(it) }, onDrag = { c, _ -> c.consume(); onPick(c.position) }) }

/** Picker for one colour: full hue/saturation spectrum, a light–dark bar and a hex box. Changes show live behind the panel. */
@Composable
private fun ColorEditor(vm: RigViewModel, theme: ThemeSettings, role: ThemeColor, onDone: () -> Unit) {
    val original = remember(role) { if (theme.isCustom(role)) theme[role] else null }
    val builtIn = theme.defaultOf(role)
    val start = remember(role) { theme[role] }
    var hsl by remember(role) { mutableStateOf(toHsl(start)) }
    var hex by remember(role) { mutableStateOf(colorHex(start)) }
    var boxSize by remember { mutableStateOf(IntSize(1, 1)) }
    var barSize by remember { mutableStateOf(IntSize(1, 1)) }
    val current = hslColor(hsl.first, hsl.second, hsl.third)
    androidx.activity.compose.BackHandler { vm.setThemeColor(role, original, save = false); onDone() }

    fun set(h: Float, s: Float, l: Float) {
        hsl = Triple(h, s, l)
        val c = hslColor(h, s, l)
        hex = colorHex(c)
        vm.setThemeColor(role, c, save = false)
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(role.label, fontSize = 20.sp)
        Text("Drag across the spectrum for the colour (across = shade, down = fades to grey), then slide the bar for lighter or darker.", fontSize = 12.sp, color = theme[ThemeColor.LABEL])

        val hueStops = (0..6).map { hslColor(it * 60f, 1f, 0.5f) }
        Canvas(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(8.dp)).onSizeChanged { boxSize = it }
            .pick { p -> set((p.x / boxSize.width).coerceIn(0f, 1f) * 360f, 1f - (p.y / boxSize.height).coerceIn(0f, 1f), hsl.third) }) {
            drawRect(Brush.horizontalGradient(hueStops))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF808080))))
            val l = hsl.third
            if (l < 0.5f) drawRect(Color.Black.copy(alpha = 1f - 2f * l)) else if (l > 0.5f) drawRect(Color.White.copy(alpha = 2f * l - 1f))
            val c = Offset(hsl.first / 360f * size.width, (1f - hsl.second) * size.height)
            drawCircle(Color.Black, 13f, c, style = Stroke(3f)); drawCircle(Color.White, 10f, c, style = Stroke(3f))
        }

        Text("Lighter / darker", fontSize = 12.sp, color = theme[ThemeColor.LABEL])
        val mid = hslColor(hsl.first, hsl.second, 0.5f)
        Canvas(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp)).onSizeChanged { barSize = it }
            .pick { p -> set(hsl.first, hsl.second, (p.x / barSize.width).coerceIn(0f, 1f)) }) {
            drawRect(Brush.horizontalGradient(listOf(Color.Black, mid, Color.White)))
            val x = hsl.third * size.width
            drawLine(Color.Black, Offset(x, 0f), Offset(x, size.height), 7f); drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 3f)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Swatch(builtIn, 44); Text("Default", fontSize = 11.sp, color = theme[ThemeColor.LABEL]) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Swatch(current, 44); Text("New", fontSize = 11.sp, color = theme[ThemeColor.LABEL]) }
            OutlinedTextField(
                hex, { v ->
                    hex = v
                    parseHex(v)?.let { c -> toHsl(c).let { hsl = it }; vm.setThemeColor(role, c, save = false) }
                },
                label = { Text("Hex colour") }, singleLine = true, modifier = Modifier.weight(1f),
                isError = parseHex(hex) == null,
            )
        }

        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ val (h, s, l) = toHsl(builtIn); set(h, s, l) }) { Text("Use default") }
            Spacer(Modifier.weight(1f))
            TextButton({ vm.setThemeColor(role, original, save = false); onDone() }) { Text("Cancel") }
            Button({
                // a colour equal to the built-in one is stored as "unchanged"
                vm.setThemeColor(role, if (current == builtIn) null else current, save = true); onDone()
            }) { Text("OK") }
        }
    }
}
