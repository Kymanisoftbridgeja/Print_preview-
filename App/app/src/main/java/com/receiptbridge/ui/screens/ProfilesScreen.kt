package com.receiptbridge.ui.screens
 
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
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
                        Column(
                            modifier = Modifier.padding(16.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
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

                            if (!profile.isDefault) {
                                Spacer(modifier = Modifier.height(12.dp))
                                TextButton(
                                    onClick = { viewModel.setDefault(profile) },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("Set As Default")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        AddPrinterDialog(
            viewModel = viewModel,
            hasExistingDefault = profiles.any { it.isDefault },
            onDismiss = { showDialog = false },
            onConfirm = { name, type, address, isDefault ->
                viewModel.addProfile(name, type, address, isDefault)
                showDialog = false
            }
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
fun AddPrinterDialog(
    viewModel: PrinterViewModel,
    hasExistingDefault: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, ConnectionType, String, Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("192.168.1.100") }
    var type by remember { mutableStateOf(ConnectionType.NETWORK) }
    var setAsDefault by remember(hasExistingDefault) { mutableStateOf(!hasExistingDefault) }
    
    val usbDevices = remember { viewModel.getUsbDevices() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Printer") },
        text = {
            Column {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Connection Type Selection
                Row(modifier = Modifier.padding(vertical = 8.dp)) {
                    Button(
                        onClick = {
                            type = ConnectionType.NETWORK
                            address = "192.168.1.100"
                        },
                        enabled = type != ConnectionType.NETWORK,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Net")
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Button(
                        onClick = {
                            type = ConnectionType.BLUETOOTH
                            address = ""
                        },
                        enabled = type != ConnectionType.BLUETOOTH,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("BT")
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Button(
                        onClick = {
                            type = ConnectionType.USB
                            address = ""
                        },
                        enabled = type != ConnectionType.USB,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("USB")
                    }
                }
                
                if (type == ConnectionType.USB) {
                    Text("Select USB Device:", style = MaterialTheme.typography.labelMedium)
                    if (usbDevices.isEmpty()) {
                        Text("No USB devices found", color = MaterialTheme.colorScheme.error)
                    } else {
                        LazyColumn(modifier = Modifier.height(100.dp)) {
                            items(usbDevices) { device ->
                                Text(
                                    text = device.deviceName,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { address = device.deviceName }
                                        .padding(8.dp),
                                    color = if (address == device.deviceName) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                } else if (type == ConnectionType.BLUETOOTH) {
                    val btDevices by viewModel.foundBtDevices.collectAsState()
                    val isBluetoothScanning by viewModel.isBluetoothScanning.collectAsState()
                    val bluetoothScanMessage by viewModel.bluetoothScanMessage.collectAsState()
                    Button(
                        onClick = { viewModel.scanBluetooth() },
                        enabled = !isBluetoothScanning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isBluetoothScanning) "Scanning..." else "Scan Bluetooth")
                    }
                    bluetoothScanMessage?.let { message ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (btDevices.isEmpty()) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    LazyColumn(modifier = Modifier.height(100.dp)) {
                        items(btDevices) { device ->
                            val deviceName = device.name?.takeIf { it.isNotBlank() } ?: "Unknown"
                            Text(
                                text = "$deviceName (${device.address})",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        address = device.address
                                        if (name.isBlank()) {
                                            name = if (deviceName == "Unknown") "Bluetooth Printer" else deviceName
                                        }
                                    }
                                    .padding(8.dp),
                                color = if (address == device.address) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    TextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("MAC Address") },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (type == ConnectionType.NETWORK) {
                    val ipDevices by viewModel.foundIpDevices.collectAsState()
                    Button(onClick = { viewModel.scanNetwork() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Scan Network (Port 9100)")
                    }
                    LazyColumn(modifier = Modifier.height(100.dp)) {
                        items(ipDevices) { ip ->
                            Text(
                                text = ip,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { address = ip }
                                    .padding(8.dp),
                                color = if (address == ip) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    TextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("IP Address") },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    TextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("MAC Address") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (hasExistingDefault) {
                                Modifier.toggleable(
                                    value = setAsDefault,
                                    onValueChange = { setAsDefault = it },
                                    role = Role.Checkbox
                                )
                            } else {
                                Modifier
                            }
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (hasExistingDefault) {
                            "Set as default printer"
                        } else {
                            "First printer becomes default automatically"
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (setAsDefault) "Yes" else "No",
                        color = if (setAsDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, type, address, setAsDefault) },
                enabled = name.isNotBlank() && address.isNotBlank()
            ) {
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
