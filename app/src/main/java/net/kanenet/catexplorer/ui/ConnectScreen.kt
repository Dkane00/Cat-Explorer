// Copyright (C) 2026 David Kane (W6MZT)
// SPDX-License-Identifier: GPL-3.0-or-later
package net.kanenet.catexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.kanenet.catexplorer.RigViewModel

@Composable
fun ConnectScreen(vm: RigViewModel, onSettings: () -> Unit) {
    val savedHost by vm.host.collectAsState()
    val savedPort by vm.port.collectAsState()
    val savedBaud by vm.baud.collectAsState()
    val state by vm.state.collectAsState()
    var host by remember(savedHost) { mutableStateOf(savedHost) }
    var port by remember(savedPort) { mutableStateOf(savedPort.toString()) }
    var baud by remember(savedBaud) { mutableStateOf(savedBaud.toString()) }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp).widthIn(max = 480.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Cat Explorer", style = MaterialTheme.typography.headlineLarge)
        Text("Connect to FLRig on your PC", color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(host, { host = it }, label = { Text("FLRig PC address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            port, { port = it.filter(Char::isDigit) }, label = { Text("XML-RPC port (default 12345)") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button({ vm.connect(host, port.toIntOrNull() ?: 12345) }, Modifier.fillMaxWidth()) { Text("Connect") }
        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Text("Or plug the radio into this tablet", color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            baud, { baud = it.filter(Char::isDigit) }, label = { Text("Baud rate (radio menu 05-06 CAT RATE)") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button({ vm.connectUsb(baud.toIntOrNull() ?: 9600) }, Modifier.fillMaxWidth()) { Text("Connect over USB") }
        TextButton(onSettings) { Text("Settings") }
        Spacer(Modifier.height(24.dp))
        Text(
            "On the PC: FLRig → Config → Setup → UI → Server → enable XML-RPC, and allow the port " +
                "through the firewall. FLRig must be listening on the network, not only on localhost.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
