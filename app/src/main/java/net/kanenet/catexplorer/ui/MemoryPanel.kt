package net.kanenet.catexplorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.kanenet.catexplorer.RigViewModel
import net.kanenet.catexplorer.rig.FlrigClient
import net.kanenet.catexplorer.rig.MemChannel
import net.kanenet.catexplorer.rig.YaesuDsp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType

/** Table of every programmed memory channel. Tapping a row moves the radio to that channel. */
@Composable
fun MemoryDialog(vm: RigViewModel, onClose: () -> Unit) {
    val th = LocalThemeSettings.current
    val list by vm.memories.collectAsState()
    val scan by vm.memScan.collectAsState()
    val d by vm.dsp.collectAsState()
    val rig by vm.state.collectAsState()
    val msg by vm.memMessage.collectAsState()
    var editing by remember { mutableStateOf<MemChannel?>(null) }
    LaunchedEffect(Unit) { if (list.isEmpty()) vm.loadMemories() }

    val head = th[ThemeColor.LABEL]
    // every ordinary slot (1..99) is listed, empty or not; the special channels above that only when they hold something
    val byCh = list.associateBy { it.ch }
    val rows = (1..99).map { byCh[it] ?: MemChannel(it, 0, "", "", "", "") } + list.filter { it.ch > 99 }
    editing?.let { m -> MemoryEditDialog(m, isNew = m.hz == 0L, defaultHz = rig.activeHz, onSave = { vm.writeMemory(it); editing = null }, onCancel = { editing = null }) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.92f).heightIn(max = 640.dp).padding(8.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Memory channels", fontSize = 22.sp, modifier = Modifier.weight(1f))
                    TextButton({ vm.loadMemories() }, enabled = scan < 0) { Text("Rescan") }
                    TextButton(onClose) { Text("Close") }
                }
                Text(
                    when {
                        scan >= 0 -> "Reading channel $scan of ${FlrigClient.MEM_LAST}… (meters paused)"
                        else -> "${list.size} programmed — tap a row to go to that channel"
                    },
                    fontSize = 13.sp, color = head,
                )
                msg?.let { Text(it, fontSize = 14.sp, color = if (it.contains("saved")) th[ThemeColor.OK] else th[ThemeColor.WARN]) }
                if (rig.ptt) Text("Transmitting — channel changes are disabled.", fontSize = 13.sp, color = th[ThemeColor.DANGER])
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Cell("CH", 0.6f, head); Cell("FREQUENCY", 1.5f, head); Cell("MODE", 1f, head)
                    Cell("TONE", 1.1f, head); Cell("SHIFT", 0.6f, head); Cell("NAME", 2f, head)
                    Spacer(Modifier.width(EDIT_COL))
                }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(rows, key = { it.ch }) { m ->
                        val empty = m.hz == 0L
                        val current = d.memoryMode && d.memChannel == m.ch
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (current) th[ThemeColor.ACCENT].copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable(enabled = !rig.ptt && !empty) { vm.selectMemory(m.ch, d.memoryMode) }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (empty) MemEmptyRow(m, th) else MemRow(m, th)
                            if (m.ch <= 99) TextButton({ vm.clearMemMessage(); editing = m }, Modifier.width(EDIT_COL), enabled = !rig.ptt && scan < 0) { Text(if (empty) "Add" else "Edit") }
                            else Spacer(Modifier.width(EDIT_COL))
                        }
                        HorizontalDivider(color = th[ThemeColor.GRAPH_GRID])
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.Cell(text: String, weight: Float, color: androidx.compose.ui.graphics.Color, mono: Boolean = false, size: Int = 13) {
    Text(text, Modifier.weight(weight), fontSize = size.sp, color = color, maxLines = 1, fontFamily = if (mono) FontFamily.Monospace else null)
}

@Composable
private fun RowScope.MemEmptyRow(m: MemChannel, th: ThemeSettings) {
    val dim = th[ThemeColor.LABEL].copy(alpha = 0.55f)
    Cell("%03d".format(m.ch), 0.6f, dim, mono = true, size = 17)
    Cell("empty", 6.2f, dim, size = 15)
}

@Composable
private fun RowScope.MemRow(m: MemChannel, th: ThemeSettings) {
    val text = th[ThemeColor.TEXT]
    Cell("%03d".format(m.ch), 0.6f, th[ThemeColor.LABEL], mono = true, size = 17)
    Cell(formatHz(m.hz), 1.5f, text, mono = true, size = 17)
    Cell(m.mode, 1f, text, size = 17)
    Cell(m.tone, 1.1f, text, size = 15)
    Cell(m.shift, 0.6f, th[ThemeColor.WARN], size = 17)
    Cell(m.name.ifEmpty { "—" }, 2f, if (m.name.isEmpty()) th[ThemeColor.LABEL] else text, mono = true, size = 17)
}

private val EDIT_COL = 84.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryEditDialog(m: MemChannel, isNew: Boolean, defaultHz: Long, onSave: (MemChannel) -> Unit, onCancel: () -> Unit) {
    val th = LocalThemeSettings.current
    var freq by remember { mutableStateOf("%.6f".format((if (isNew) defaultHz else m.hz) / 1e6).trimEnd('0').trimEnd('.')) }
    var mode by remember { mutableStateOf(m.modeCode) }
    var tone by remember { mutableIntStateOf(m.toneCode) }
    var shift by remember { mutableIntStateOf(m.shiftCode) }
    var name by remember { mutableStateOf(m.name) }
    val hz = freq.toDoubleOrNull()?.let { Math.round(it * 1e6) }?.takeIf { it in 30_000L..56_000_000L }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (isNew) "Add channel %03d".format(m.ch) else "Edit channel %03d".format(m.ch)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    freq, { freq = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Frequency (MHz)") }, singleLine = true,
                    isError = hz == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    name, { name = YaesuDsp.cleanTag(it) }, label = { Text("Name (up to 12 characters)") }, singleLine = true,
                    isError = name.any { it == 'x' || it == 'X' },
                    supportingText = { Text(if (name.any { it == 'x' || it == 'X' }) "The radio refuses the letter X in names" else "${name.length}/12 · the radio stores letters as lower case") },
                )
                Text("Mode", fontSize = 13.sp, color = th[ThemeColor.LABEL])
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    YaesuDsp.MEM_MODE_CHOICES.forEach { (code, label) -> FilterChip(mode == code, { mode = code }, { Text(label) }) }
                }
                Text("Tone", fontSize = 13.sp, color = th[ThemeColor.LABEL])
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    YaesuDsp.MEM_TONE_CHOICES.forEach { (code, label) -> FilterChip(tone == code, { tone = code }, { Text(label) }) }
                }
                Text("Repeater shift", fontSize = 13.sp, color = th[ThemeColor.LABEL])
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    YaesuDsp.MEM_SHIFT_CHOICES.forEach { (code, label) -> FilterChip(shift == code, { shift = code }, { Text(label) }) }
                }
                Text(
                    "This writes straight into the radio's memory. The channel list is saved to a backup file before the first write.",
                    fontSize = 12.sp, color = th[ThemeColor.WARN],
                )
            }
        },
        confirmButton = {
            TextButton({ hz?.let { onSave(m.copy(hz = it, modeCode = mode, toneCode = tone, shiftCode = shift, name = name)) } }, enabled = hz != null && name.none { it == 'x' || it == 'X' }) { Text("Save to radio") }
        },
        dismissButton = { TextButton(onCancel) { Text("Cancel") } },
    )
}
