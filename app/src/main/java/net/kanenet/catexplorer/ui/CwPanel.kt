package net.kanenet.catexplorer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kanenet.catexplorer.RigViewModel

/**
 * CW keyer memories, sent with FLRig's CWIO (so FLRig needs CW-over-CAT/keying set up).
 * Tap a memory to send it; long-press to edit its text.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun CwPanel(vm: RigViewModel) {
    val th = LocalThemeSettings.current
    val memories by vm.cwMemories.collectAsState()
    val wpm by vm.cwWpm.collectAsState()
    var editIndex by remember { mutableIntStateOf(-1) }
    var editText by remember { mutableStateOf("") }
    var freeText by remember { mutableStateOf("") }
    var wpmLocal by remember(wpm) { mutableFloatStateOf(wpm.toFloat()) }

    Column {
        Text("CW keyer (tap = send, long-press = edit)", fontSize = 12.sp, color = th[ThemeColor.LABEL])
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            memories.forEachIndexed { i, m ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.combinedClickable(
                        onClick = { if (m.isNotBlank()) vm.send { cwText(m) } },
                        onLongClick = { editIndex = i; editText = m },
                    ),
                ) { Text("F${i + 1}  $m", Modifier.padding(horizontal = 12.dp, vertical = 10.dp), fontSize = 14.sp) }
            }
            Button({ vm.send { cwAbort() } }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F2A2A))) { Text("Stop") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("WPM", Modifier.width(60.dp), fontSize = 13.sp, color = th[ThemeColor.LABEL])
            Slider(wpmLocal, { wpmLocal = it }, Modifier.weight(1f), valueRange = 5f..50f, onValueChangeFinished = { vm.setCwWpm(wpmLocal.toInt()) })
            Text(wpmLocal.toInt().toString(), Modifier.width(40.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(freeText, { freeText = it.uppercase() }, Modifier.weight(1f), singleLine = true, label = { Text("Send text") })
            Button({ if (freeText.isNotBlank()) vm.send { cwText(freeText) }; freeText = "" }) { Text("Send") }
        }
    }

    if (editIndex >= 0) {
        AlertDialog(
            onDismissRequest = { editIndex = -1 },
            title = { Text("Edit F${editIndex + 1}") },
            text = { OutlinedTextField(editText, { editText = it.uppercase() }, singleLine = true) },
            confirmButton = { TextButton({ vm.setCwMemory(editIndex, editText.trim()); editIndex = -1 }) { Text("Save") } },
            dismissButton = { TextButton({ editIndex = -1 }) { Text("Cancel") } },
        )
    }
}
