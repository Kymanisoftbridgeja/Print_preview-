package com.receiptbridge.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.receiptbridge.data.ConnectionType
import com.receiptbridge.ui.viewmodel.PrinterViewModel

@Composable
fun ProfilesScreen(
    onNavigateBack: () -> Unit,
    viewModel: PrinterViewModel = hiltViewModel()
) {
    val profiles by viewModel.profiles.collectAsState(initial = emptyList())
    var showDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Printer")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("Printer Profiles", style = MaterialTheme.typography.titleLarge)
            
            LazyColumn {
                items(profiles) { profile ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                                Text("${profile.connectionType} - ${profile.address}")
                                if (profile.isDefault) {
                                    Text("DEFAULT", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            IconButton(onClick = { viewModel.deleteProfile(profile) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        AddPrinterDialog(
            onDismiss = { showDialog = false },
            onConfirm = { name, type, address ->
                viewModel.addProfile(name, type, address)
                showDialog = false
            }
        )
    }
}

@Composable
fun AddPrinterDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, ConnectionType, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("192.168.1.100") }
    var type by remember { mutableStateOf(ConnectionType.NETWORK) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Printer") },
        text = {
            Column {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile Name") }
                )
                // Simplified Type Selection (Just 2 buttons for now)
                Row(modifier = Modifier.padding(vertical = 8.dp)) {
                    Button(onClick = { type = ConnectionType.NETWORK }, enabled = type != ConnectionType.NETWORK) {
                        Text("Network")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { type = ConnectionType.BLUETOOTH }, enabled = type != ConnectionType.BLUETOOTH) {
                        Text("Bluetooth")
                    }
                }
                
                TextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(if (type == ConnectionType.NETWORK) "IP Address" else "MAC Address") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, type, address) }) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
