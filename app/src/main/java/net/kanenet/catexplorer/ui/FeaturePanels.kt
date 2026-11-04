package net.kanenet.catexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.kanenet.catexplorer.RigViewModel
import net.kanenet.catexplorer.rig.YaesuDsp

/** The feature panels that open on the main screen from the CW / FM / VOICE buttons. */
enum class Feature(val label: String) { CW("CW"), FM("FM"), VOICE("VOICE") }

private val LABEL: Color @Composable get() = LocalThemeSettings.current[ThemeColor.LABEL]

/** Slider that shows the radio's value, follows it while idle, and sends only when released. */
@Composable
private fun SettingSlider(label: String, value: Int, range: IntRange, display: String, modifier: Modifier = Modifier, enabled: Boolean = true, onValueClick: (() -> Unit)? = null, onSet: (Int) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value.toFloat()) }
    LaunchedEffect(value) { if (!dragging) local = value.toFloat() }
    Row(modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(44.dp), fontSize = 12.sp, color = LABEL)
        Slider(
            local, { dragging = true; local = it }, Modifier.weight(1f),
            enabled = enabled, valueRange = range.first.toFloat()..range.last.toFloat(),
            onValueChangeFinished = { dragging = false; onSet(local.toInt()) },
        )
        val shown = if (dragging) display.replace(value.toString(), local.toInt().toString()) else display
        if (onValueClick != null) {
            // the number is a button: tap it to type a value instead of dragging
            Surface(onClick = onValueClick, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(start = 4.dp).widthIn(min = 44.dp)) {
                Text(shown, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 14.sp)
            }
        } else Text(shown, Modifier.width(60.dp), fontSize = 13.sp)
    }
}

/** CW settings. Everything here is readable over CAT, so it always shows what the radio has. */
@Composable
fun CwSettingsPanel(vm: RigViewModel, height: Dp) {
    val th = LocalThemeSettings.current
    val cw by vm.cw.collectAsState()
    val rig by vm.state.collectAsState()
    // the radio refuses CW settings (answers "?;") unless it is in a CW mode
    val on = rig.mode.startsWith("CW")
    LaunchedEffect(Unit) { while (true) { vm.refreshCw(); delay(1500) } }
    Column(Modifier.height(height).verticalScroll(rememberScrollState())) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(cw.keyerOn, { vm.setCw(YaesuDsp.keyer(!cw.keyerOn)) { copy(keyerOn = !keyerOn) } }, { Text("Keyer") }, enabled = on, colors = selectedBlue())
            FilterChip(cw.breakIn, { vm.setCw(YaesuDsp.breakIn(!cw.breakIn)) { copy(breakIn = !breakIn) } }, { Text("Break-in") }, enabled = on, colors = selectedBlue())
            FilterChip(cw.spotOn, { vm.setCw(YaesuDsp.cwSpot(!cw.spotOn)) { copy(spotOn = !spotOn) } }, { Text("Spot") }, enabled = on, colors = selectedBlue())
            if (!on) Text("CW mode only", fontSize = 12.sp, color = th[ThemeColor.WARN])
        }
        Row {
            SettingSlider("Speed", cw.speed, 4..60, "${cw.speed} wpm", Modifier.weight(1f), on) { v -> vm.setCw(YaesuDsp.keySpeed(v)) { copy(speed = v) } }
            SettingSlider("Pitch", cw.pitchIdx, 0..75, "${cw.pitchHz} Hz", Modifier.weight(1f), on) { v -> vm.setCw(YaesuDsp.keyPitch(v)) { copy(pitchIdx = v) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingSlider("Delay", cw.delayMs, 30..3000, "${cw.delayMs} ms", Modifier.weight(1f), on) { v -> vm.setCw(YaesuDsp.breakInDelay(v / 10 * 10)) { copy(delayMs = v / 10 * 10) } }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Memory", Modifier.padding(start = 8.dp, end = 2.dp), fontSize = 12.sp, color = LABEL)
                (1..5).forEach { n ->
                    OutlinedButton({ vm.send { cat(YaesuDsp.playKeyerMemory(n)) } }, Modifier.size(34.dp), enabled = on, contentPadding = PaddingValues(0.dp)) { Text("$n", fontSize = 14.sp) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
/** FM settings: repeater shift, tone mode and tone, squelch. Shift only works in an FM mode. */
@Composable
fun FmSettingsPanel(vm: RigViewModel, height: Dp) {
    val th = LocalThemeSettings.current
    val fm by vm.fm.collectAsState()
    val rig by vm.state.collectAsState()
    val isFm = rig.mode.startsWith("FM")
    LaunchedEffect(Unit) { while (true) { vm.refreshFm(); delay(1500) } }
    Column(Modifier.height(height).verticalScroll(rememberScrollState())) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Shift", Modifier.width(44.dp), fontSize = 12.sp, color = LABEL)
            listOf("Simplex", "+", "−").forEachIndexed { n, t ->
                FilterChip(fm.shift == n, { vm.setFm(YaesuDsp.repeaterShift(n)) { copy(shift = n) } }, { Text(t) }, enabled = isFm, colors = selectedBlue())
            }
            if (!isFm) Text("FM mode only", fontSize = 12.sp, color = th[ThemeColor.WARN])
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tone", Modifier.width(44.dp), fontSize = 12.sp, color = LABEL)
            listOf("Off", "Enc", "Enc/Dec", "DCS").forEachIndexed { n, t ->
                FilterChip(fm.toneMode == n, { vm.setFm(YaesuDsp.toneMode(n)) { copy(toneMode = n) } }, { Text(t) }, colors = selectedBlue())
            }
        }
        var picking by remember { mutableStateOf(false) }
        var typingSql by remember { mutableStateOf(false) }
        var sqlText by remember { mutableStateOf("") }
        if (typingSql) {
            val typed = sqlText.toIntOrNull()
            val valid = typed != null && typed in 0..100
            AlertDialog(
                onDismissRequest = { typingSql = false },
                title = { Text("Squelch level (0–100)") },
                text = {
                    OutlinedTextField(
                        sqlText, { sqlText = it.filter(Char::isDigit).take(3) }, singleLine = true,
                        label = { Text("Squelch") },
                        supportingText = { Text("Enter a number from 0 to 100") },
                        isError = sqlText.isNotEmpty() && !valid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                },
                confirmButton = {
                    TextButton({
                        typed?.takeIf { valid }?.let { v -> vm.setFm(YaesuDsp.squelch(v)) { copy(squelch = v) } }
                        typingSql = false
                    }, enabled = valid) { Text("Set") }
                },
                dismissButton = { TextButton({ typingSql = false }) { Text("Cancel") } },
            )
        }
        val dcs = fm.toneMode == 3
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(if (dcs) "Code" else "Freq", Modifier.width(44.dp), fontSize = 12.sp, color = LABEL)
                FilledTonalButton({ picking = true }, Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Text(if (dcs) "DCS " + YaesuDsp.DCS_CODES.getOrElse(fm.dcsIdx) { "?" } else YaesuDsp.CTCSS_HZ.getOrElse(fm.toneIdx) { "?" } + " Hz", fontSize = 14.sp)
                }
            }
        if (picking) {
            val names = if (dcs) YaesuDsp.DCS_CODES else YaesuDsp.CTCSS_HZ
            val current = if (dcs) fm.dcsIdx else fm.toneIdx
            AlertDialog(
                onDismissRequest = { picking = false },
                title = { Text(if (dcs) "DCS code" else "CTCSS tone (Hz)") },
                text = {
                    FlowRow(Modifier.verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        names.forEachIndexed { i, n ->
                            FilterChip(i == current, {
                                if (dcs) vm.setFm(YaesuDsp.dcsCode(i)) { copy(dcsIdx = i) } else vm.setFm(YaesuDsp.ctcssTone(i)) { copy(toneIdx = i) }
                                picking = false
                            }, { Text(n, fontSize = 16.sp) }, colors = selectedBlue())
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton({ picking = false }) { Text("Cancel") } },
            )
        }
            SettingSlider("SQL", fm.squelch, 0..100, "${fm.squelch}", Modifier.weight(1f), onValueClick = { sqlText = fm.squelch.toString(); typingSql = true }) { v -> vm.setFm(YaesuDsp.squelch(v)) { copy(squelch = v) } }
        }
    }
}

/** Voice memories 1-5 (the radio's DVS). Pick Play or Record, then its row of message slots appears. */
@Composable
fun VoicePanel(vm: RigViewModel, height: Dp) {
    val th = LocalThemeSettings.current
    val v by vm.voice.collectAsState()
    LaunchedEffect(Unit) { while (true) { vm.refreshVoice(); delay(1000) } }
    var confirmRecord by remember { mutableIntStateOf(0) }
    var action by remember { mutableStateOf(0) }   // 0 = none chosen, 1 = Play, 2 = Record
    // if the radio is already recording (started from its front panel), show that row
    LaunchedEffect(v.recording) { if (v.recording != 0) action = 2 }
    val red = Color(0xFFE57373)
    Column(Modifier.height(height).verticalScroll(rememberScrollState())) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(action == 1, { action = if (action == 1) 0 else 1 }, { Text("Play") }, colors = selectedBlue())
            FilterChip(action == 2, { action = if (action == 2) 0 else 2 }, { Text("Record") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = red, selectedLabelColor = Color.Black))
        }
        when (action) {
            1 -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                // the radio doesn't report playback over CAT (it reads "not playing" while a message plays), so these just send
                (1..5).forEach { n ->
                    OutlinedButton({ vm.send { cat(YaesuDsp.playVoice(n)) } }, Modifier.size(width = 58.dp, height = 42.dp), contentPadding = PaddingValues(0.dp)) { Text("$n", fontSize = 15.sp) }
                }
                OutlinedButton({ vm.send { cat(YaesuDsp.playVoice(0)) } }, Modifier.height(42.dp), contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Stop") }
            }
            2 -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                (1..5).forEach { n ->
                    val on = v.recording == n
                    val mod = Modifier.size(width = 58.dp, height = 42.dp)
                    val go = { if (on) vm.setVoice(YaesuDsp.recordVoice(0)) { copy(recording = 0) } else confirmRecord = n }
                    if (on) Button(go, mod, colors = ButtonDefaults.buttonColors(containerColor = red), contentPadding = PaddingValues(0.dp)) { Text("$n", fontSize = 15.sp) }
                    else OutlinedButton(go, mod, contentPadding = PaddingValues(0.dp)) { Text("$n", fontSize = 15.sp) }
                }
                OutlinedButton({ vm.setVoice(YaesuDsp.recordVoice(0)) { copy(recording = 0) } }, Modifier.height(42.dp), enabled = v.recording != 0, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Stop") }
            }
            else -> Text("Choose Play or Record to pick a message slot.", fontSize = 12.sp, color = LABEL)
        }
        when (action) {
            // the radio's own PB setting (front-panel REC SETTING list) decides this, and CAT can't read or change it
            1 -> Text("Transmits if PB is on at the radio; otherwise plays in the speaker only.", fontSize = 11.sp, color = th[ThemeColor.WARN], lineHeight = 13.sp)
            2 -> Text("Recording overwrites that message with what the mic hears.", fontSize = 11.sp, color = LABEL, lineHeight = 13.sp)
            else -> Text("Voice messages work in SSB and AM modes.", fontSize = 11.sp, color = LABEL, lineHeight = 13.sp)
        }
    }
    if (confirmRecord != 0) {
        val n = confirmRecord
        AlertDialog(
            onDismissRequest = { confirmRecord = 0 },
            title = { Text("Record voice message $n?") },
            text = { Text("This overwrites whatever is stored in message $n. Recording starts at once and uses the radio's mic; press the number again or Stop to finish.") },
            confirmButton = { TextButton({ vm.setVoice(YaesuDsp.recordVoice(n)) { copy(recording = n) }; confirmRecord = 0 }) { Text("Record") } },
            dismissButton = { TextButton({ confirmRecord = 0 }) { Text("Cancel") } },
        )
    }
}

/** Placeholder until the panel is built. */
@Composable
fun ComingSoonPanel(feature: Feature, height: Dp) {
    Column(Modifier.height(height)) { Text("${feature.label} settings — coming next", color = LABEL, fontSize = 13.sp) }
}
