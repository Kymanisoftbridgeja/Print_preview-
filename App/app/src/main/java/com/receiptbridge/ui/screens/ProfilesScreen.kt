package com.receiptbridge.ui.screens
 
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.receiptbridge.data.ConnectionType
import com.receiptbridge.data.DEFAULT_PRINT_AREA_DOTS_58_MM
import com.receiptbridge.data.DEFAULT_PRINT_AREA_DOTS_80_MM
import com.receiptbridge.data.MAX_PRINT_AREA_DOTS
import com.receiptbridge.data.MIN_PRINT_AREA_DOTS
import com.receiptbridge.data.PAPER_WIDTH_58_MM
import com.receiptbridge.data.PAPER_WIDTH_80_MM
import com.receiptbridge.data.PRINT_AREA_DOTS_STEP
import com.receiptbridge.data.defaultPrintAreaDotsForPaperWidthMm
import com.receiptbridge.data.resolvedPrintAreaDots
import com.receiptbridge.data.sanitizePrintAreaDots
import com.receiptbridge.ui.viewmodel.PrinterViewModel

@Composable
fun ProfilesScreen(
    onNavigateBack: () -> Unit,
    viewModel: PrinterViewModel = hiltViewModel()
) {
    val profiles by viewModel.profiles.collectAsState(initial = emptyList())
    val printerActionMessage by viewModel.printerActionMessage.collectAsState()
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
            printerActionMessage?.let { message ->
                Spacer(modifier = Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                items(
                    items = profiles,
                    key = { profile -> profile.id }
                ) { profile ->
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
                                    Text("Receipt: ${profile.paperWidthMm} mm - ${profile.resolvedPrintAreaDots()} dots")
                                    if (profile.isDefault) {
                                        Text("DEFAULT", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                IconButton(onClick = { viewModel.deleteProfile(profile) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            ReceiptSizeSelector(
                                selectedPaperWidthMm = profile.paperWidthMm,
                                onSelect = { selectedPaperWidth -> 
                                    viewModel.updatePaperWidth(profile, selectedPaperWidth)
                                }
                            )

                            Spacer(modifier = Modifier.height(12.dp))
                            PrintAreaDotsEditor(
                                currentPrintAreaDots = profile.resolvedPrintAreaDots(),
                                paperWidthMm = profile.paperWidthMm,
                                onChange = { updatedPrintAreaDots ->
                                    viewModel.updatePrintAreaDots(profile, updatedPrintAreaDots)
                                }
                            )

                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { viewModel.queueTestPrint(profile) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Connection Test")
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
            onConfirm = { name, type, address, isDefault, paperWidthMm, printAreaDots ->
                viewModel.addProfile(name, type, address, isDefault, paperWidthMm, printAreaDots)
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
    onConfirm: (String, ConnectionType, String, Boolean, Int, Int) -> Unit
) {
    val context = LocalContext.current
    val bluetoothAdapter = remember(context) {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("192.168.1.100") }
    var type by remember { mutableStateOf(ConnectionType.NETWORK) }
    var paperWidthMm by remember { mutableStateOf(PAPER_WIDTH_80_MM) }
    var printAreaDots by remember { mutableStateOf(defaultPrintAreaDotsForPaperWidthMm(PAPER_WIDTH_80_MM)) }
    var setAsDefault by remember(hasExistingDefault) { mutableStateOf(!hasExistingDefault) }
    var pendingBluetoothAction by remember { mutableStateOf<BluetoothAction?>(null) }
    
    val usbDevices = remember { viewModel.getUsbDevices() }
    var runBluetoothAction by remember { mutableStateOf<(BluetoothAction) -> Unit>({}) }
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val action = pendingBluetoothAction
        pendingBluetoothAction = null
        if (permissions.values.all { it } && action != null) {
            runBluetoothAction(action)
        } else {
            viewModel.refreshBluetoothDevices()
        }
    }
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        pendingBluetoothAction?.let(runBluetoothAction)
    }
    val openLocationSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        pendingBluetoothAction?.let(runBluetoothAction)
    }
    val openBluetoothSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        runBluetoothAction(BluetoothAction.REFRESH)
    }
    runBluetoothAction = { action ->
        val missingPermissions = bluetoothScanPermissions().filterNot(context::hasPermission)
        when {
            bluetoothAdapter == null -> viewModel.refreshBluetoothDevices()
            missingPermissions.isNotEmpty() -> {
                pendingBluetoothAction = action
                bluetoothPermissionLauncher.launch(missingPermissions.toTypedArray())
            }
            !bluetoothAdapter.isEnabled -> {
                pendingBluetoothAction = action
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !context.isLocationEnabled() -> {
                pendingBluetoothAction = action
                openLocationSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            action == BluetoothAction.REFRESH -> {
                pendingBluetoothAction = null
                viewModel.refreshBluetoothDevices()
            }
            else -> {
                pendingBluetoothAction = null
                viewModel.scanBluetooth()
            }
        }
    }
    LaunchedEffect(type) {
        if (type == ConnectionType.BLUETOOTH) {
            runBluetoothAction(BluetoothAction.REFRESH)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Printer") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
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
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { runBluetoothAction(BluetoothAction.REFRESH) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Refresh Paired")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { runBluetoothAction(BluetoothAction.SCAN) },
                            enabled = !isBluetoothScanning,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isBluetoothScanning) "Scanning..." else "Scan All Bluetooth")
                        }
                    }
                    TextButton(
                        onClick = {
                            openBluetoothSettingsLauncher.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Open Bluetooth Settings")
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
                    if (btDevices.isEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Scan All Bluetooth looks for paired, nearby classic Bluetooth, and BLE devices. Pair Woosim and other printers in Android Bluetooth settings if they still do not appear.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                Text("Receipt Width", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(8.dp))
                ReceiptSizeSelector(
                    selectedPaperWidthMm = paperWidthMm,
                    onSelect = { selectedPaperWidth ->
                        paperWidthMm = selectedPaperWidth
                        printAreaDots = defaultPrintAreaDotsForPaperWidthMm(selectedPaperWidth)
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))
                PrintAreaDotsEditor(
                    currentPrintAreaDots = printAreaDots,
                    paperWidthMm = paperWidthMm,
                    onChange = { updatedPrintAreaDots ->
                        printAreaDots = updatedPrintAreaDots
                    }
                )

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
                onClick = { onConfirm(name, type, address, setAsDefault, paperWidthMm, printAreaDots) },
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

private fun bluetoothScanPermissions(): List<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

private fun Context.hasPermission(permission: String): Boolean {
    return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

private fun Context.isLocationEnabled(): Boolean {
    val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return true

    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }.getOrDefault(true)
}

private enum class BluetoothAction {
    REFRESH,
    SCAN
}

@Composable
private fun ReceiptSizeSelector(
    selectedPaperWidthMm: Int,
    onSelect: (Int) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { onSelect(PAPER_WIDTH_80_MM) },
            enabled = selectedPaperWidthMm != PAPER_WIDTH_80_MM,
            modifier = Modifier.weight(1f)
        ) {
            Text("80 mm")
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = { onSelect(PAPER_WIDTH_58_MM) },
            enabled = selectedPaperWidthMm != PAPER_WIDTH_58_MM,
            modifier = Modifier.weight(1f)
        ) {
            Text("58 mm")
        }
    }
}

@Composable
private fun PrintAreaDotsEditor(
    currentPrintAreaDots: Int,
    paperWidthMm: Int,
    onChange: (Int) -> Unit
) {
    val defaultDots = defaultPrintAreaDotsForPaperWidthMm(paperWidthMm)
    val presets = listOf(
        defaultDots,
        DEFAULT_PRINT_AREA_DOTS_58_MM,
        DEFAULT_PRINT_AREA_DOTS_80_MM,
        832,
        960
    ).distinct()

    Column {
        Text("Print Area (dots)", style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(8.dp))
        StepperSetting(
            title = "Fine Tune",
            valueText = "${currentPrintAreaDots} dots",
            supportingText = "Common starting points: 384 for many 58 mm printers and 576 for many 80 mm printers. This controls the printer's real printable width.",
            canDecrease = currentPrintAreaDots > MIN_PRINT_AREA_DOTS,
            canIncrease = currentPrintAreaDots < MAX_PRINT_AREA_DOTS,
            onDecrease = {
                onChange(sanitizePrintAreaDots(currentPrintAreaDots - PRINT_AREA_DOTS_STEP))
            },
            onIncrease = {
                onChange(sanitizePrintAreaDots(currentPrintAreaDots + PRINT_AREA_DOTS_STEP))
            }
        )
        Spacer(modifier = Modifier.height(8.dp))
        presets.chunked(3).forEach { presetRow ->
            Row(modifier = Modifier.fillMaxWidth()) {
                presetRow.forEach { preset ->
                    Button(
                        onClick = { onChange(preset) },
                        enabled = currentPrintAreaDots != preset,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("$preset")
                    }
                    if (preset != presetRow.last()) {
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }
                repeat(3 - presetRow.size) {
                    Spacer(modifier = Modifier.weight(1f))
                    if (it < 3 - presetRow.size - 1) {
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        TextButton(
            onClick = { onChange(defaultDots) },
            enabled = currentPrintAreaDots != defaultDots,
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("Reset to ${defaultDots} dots")
        }
    }
}
