package com.receiptbridge.ui.viewmodel

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.bluetooth.BluetoothDevice as BtDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.receiptbridge.data.ConnectionType
import com.receiptbridge.data.PAPER_WIDTH_58_MM
import com.receiptbridge.data.PrintJob
import com.receiptbridge.data.PrinterProfile
import com.receiptbridge.data.defaultCharactersPerLineForPrintAreaDots
import com.receiptbridge.data.defaultPrintAreaDotsForPaperWidthMm
import com.receiptbridge.data.normalizePaperWidthMm
import com.receiptbridge.data.resolvedPrintAreaDots
import com.receiptbridge.data.sanitizePrintAreaDots
import com.receiptbridge.data.repository.JobRepository
import com.receiptbridge.data.repository.PrinterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InterfaceAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import javax.inject.Inject

@HiltViewModel
class PrinterViewModel @Inject constructor(
    private val repository: PrinterRepository,
    private val jobRepository: JobRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val bluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var bluetoothScanReceiver: BroadcastReceiver? = null
    private var bluetoothLeScanCallback: ScanCallback? = null
    private var bluetoothScanTimeoutJob: Job? = null
    private var isClassicDiscoveryRunning = false
    private var isBleDiscoveryRunning = false

    val profiles = repository.allProfiles
    
    private val _foundBtDevices = MutableStateFlow<List<BtDevice>>(emptyList())
    val foundBtDevices: StateFlow<List<BtDevice>> = _foundBtDevices.asStateFlow()

    private val _isBluetoothScanning = MutableStateFlow(false)
    val isBluetoothScanning: StateFlow<Boolean> = _isBluetoothScanning.asStateFlow()

    private val _bluetoothScanMessage = MutableStateFlow<String?>(null)
    val bluetoothScanMessage: StateFlow<String?> = _bluetoothScanMessage.asStateFlow()

    private val _foundIpDevices = MutableStateFlow<List<String>>(emptyList())
    val foundIpDevices: StateFlow<List<String>> = _foundIpDevices.asStateFlow()

    private val _isNetworkScanning = MutableStateFlow(false)
    val isNetworkScanning: StateFlow<Boolean> = _isNetworkScanning.asStateFlow()

    private val _networkScanMessage = MutableStateFlow<String?>(null)
    val networkScanMessage: StateFlow<String?> = _networkScanMessage.asStateFlow()

    private val _isTestingNetworkAddress = MutableStateFlow(false)
    val isTestingNetworkAddress: StateFlow<Boolean> = _isTestingNetworkAddress.asStateFlow()

    private val _networkAddressTestMessage = MutableStateFlow<String?>(null)
    val networkAddressTestMessage: StateFlow<String?> = _networkAddressTestMessage.asStateFlow()

    private val _printerActionMessage = MutableStateFlow<String?>(null)
    val printerActionMessage: StateFlow<String?> = _printerActionMessage.asStateFlow()

    fun getUsbDevices(): List<UsbDevice> {
        return usbManager.deviceList.values.toList()
    }

    @SuppressLint("MissingPermission")
    fun refreshBluetoothDevices() {
        getReadyBluetoothAdapter() ?: return
        stopBluetoothScan()

        val bondedDevices = getBondedBluetoothDevices()
        _foundBtDevices.value = bondedDevices
        _bluetoothScanMessage.value = when {
            bondedDevices.isEmpty() -> {
                "No paired Bluetooth printers found yet. Pair the printer in Android Bluetooth settings, then tap Refresh Paired."
            }
            bondedDevices.any(::isLikelyPrinter) -> {
                "Showing paired Bluetooth printers. Use Scan Nearby if the printer is still in pairing mode."
            }
            else -> {
                "Showing paired Bluetooth devices. If your printer is missing, pair it in Android Bluetooth settings, then refresh."
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun scanBluetooth() {
        val adapter = getReadyBluetoothAdapter() ?: return

        stopBluetoothScan()
        _foundBtDevices.value = getBondedBluetoothDevices()
        _bluetoothScanMessage.value = if (_foundBtDevices.value.isEmpty()) {
            "Searching for nearby Bluetooth printers and devices. Pair the printer in Android Bluetooth settings if it does not appear."
        } else {
            "Paired devices loaded. Searching for more nearby Bluetooth printers and devices..."
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BtDevice.ACTION_FOUND -> {
                        intent.getBluetoothDevice()?.let(::addBluetoothDevice)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        isClassicDiscoveryRunning = false
                        if (!isBleDiscoveryRunning) {
                            finishBluetoothScan(cancelDiscovery = false)
                        }
                    }
                }
            }
        }

        val intentFilter = IntentFilter().apply {
            addAction(BtDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }

        val registered = runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                intentFilter,
                ContextCompat.RECEIVER_EXPORTED
            )
        }.isSuccess
        if (!registered) {
            _bluetoothScanMessage.value = "Bluetooth scan could not register its discovery listener."
            return
        }

        bluetoothScanReceiver = receiver
        isClassicDiscoveryRunning = runCatching { adapter.startDiscovery() }.getOrDefault(false)
        isBleDiscoveryRunning = startBluetoothLeScan(adapter)
        if (!isClassicDiscoveryRunning && !isBleDiscoveryRunning) {
            stopBluetoothScan(cancelDiscovery = false)
            _bluetoothScanMessage.value = if (_foundBtDevices.value.isEmpty()) {
                "Bluetooth scan could not start. Make sure the printer is powered on and in pairing mode."
            } else {
                "Showing paired devices. Nearby scan could not start."
            }
            return
        }

        _isBluetoothScanning.value = true
        bluetoothScanTimeoutJob = viewModelScope.launch {
            delay(BLUETOOTH_SCAN_TIMEOUT_MS)
            finishBluetoothScan()
        }
    }

    fun scanNetwork() {
        if (_isNetworkScanning.value) {
            return
        }

        viewModelScope.launch {
            _isNetworkScanning.value = true
            _foundIpDevices.value = emptyList()
            _networkScanMessage.value = "Scanning your local network for printers on port 9100..."
            try {
                val reachableDevices = withContext(Dispatchers.IO) {
                    getLocalSubnets()
                        .flatMap(::buildCandidateIps)
                        .flatMap { ip ->
                            listOf(async { if (isPortOpen(ip, DEFAULT_NETWORK_PRINTER_PORT)) ip else null })
                        }
                        .awaitAll()
                        .filterNotNull()
                        .distinct()
                        .sortedBy(::ipSortKey)
                }
                _foundIpDevices.value = reachableDevices
                _networkScanMessage.value = if (reachableDevices.isEmpty()) {
                    "No printers answered on port 9100. You can still add the printer manually using its IP address."
                } else {
                    "Found ${reachableDevices.size} reachable network printer address${if (reachableDevices.size == 1) "" else "es"}. Tap one to use it or type an IP manually."
                }
            } catch (error: Exception) {
                _networkScanMessage.value = "Network scan failed: ${error.message ?: "Unknown error"}"
            } finally {
                _isNetworkScanning.value = false
            }
        }
    }

    fun testNetworkAddress(address: String) {
        val target = parseNetworkTarget(address)
        if (target == null) {
            _networkAddressTestMessage.value = "Enter a valid printer address like 192.168.1.50 or 192.168.1.50:9100."
            return
        }

        if (_isTestingNetworkAddress.value) {
            return
        }

        viewModelScope.launch {
            _isTestingNetworkAddress.value = true
            try {
                val connected = withContext(Dispatchers.IO) {
                    isPortOpen(target.host, target.port, timeoutMs = NETWORK_TEST_TIMEOUT_MS)
                }
                _networkAddressTestMessage.value = if (connected) {
                    "Connection successful to ${target.host}:${target.port}. Save this printer and run Connection Test next."
                } else {
                    "Could not reach ${target.host}:${target.port}. Make sure the printer is on the same network and that raw printing is enabled."
                }
            } catch (error: Exception) {
                _networkAddressTestMessage.value =
                    "Network test failed for ${target.host}:${target.port}: ${error.message ?: "Unknown error"}"
            } finally {
                _isTestingNetworkAddress.value = false
            }
        }
    }

    fun clearNetworkAddressTestMessage() {
        _networkAddressTestMessage.value = null
    }

    private fun getLocalSubnets(): List<Ipv4Subnet> {
        return runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { networkInterface ->
                    networkInterface.interfaceAddresses
                        .asSequence()
                        .mapNotNull { interfaceAddress ->
                            interfaceAddress.toIpv4Subnet()
                        }
                }
                .distinctBy { it.networkAddress to it.prefixLength }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int = PORT_PROBE_TIMEOUT_MS): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun ipSortKey(ip: String): String {
        return ip.split('.')
            .joinToString(".") { part -> (part.toIntOrNull() ?: Int.MAX_VALUE).toString().padStart(3, '0') }
    }

    private fun buildCandidateIps(subnet: Ipv4Subnet): List<String> {
        val hostBits = 32 - subnet.prefixLength
        if (hostBits <= 1) {
            return emptyList()
        }

        val usableHosts = (1L shl hostBits) - 2
        val rangeStart = subnet.networkAddress + 1
        val rangeEnd = subnet.networkAddress + usableHosts
        val scanStart = if (usableHosts > MAX_SCAN_HOSTS) {
            maxOf(rangeStart, subnet.localAddress - MAX_SCAN_HOSTS / 2)
        } else {
            rangeStart
        }
        val scanEnd = if (usableHosts > MAX_SCAN_HOSTS) {
            minOf(rangeEnd, scanStart + MAX_SCAN_HOSTS - 1)
        } else {
            rangeEnd
        }

        return (scanStart..scanEnd)
            .asSequence()
            .filter { it != subnet.localAddress }
            .map(::longToIpv4)
            .toList()
    }

    private fun InterfaceAddress.toIpv4Subnet(): Ipv4Subnet? {
        val ipv4 = address as? Inet4Address ?: return null
        if (ipv4.isLoopbackAddress || ipv4.isLinkLocalAddress) {
            return null
        }

        val prefixLength = networkPrefixLength.toInt().coerceIn(0, 32)
        if (prefixLength >= 31) {
            return null
        }

        val localAddress = ipv4ToLong(ipv4)
        val mask = prefixLengthToMask(prefixLength)
        return Ipv4Subnet(
            networkAddress = localAddress and mask,
            prefixLength = prefixLength,
            localAddress = localAddress
        )
    }

    private fun ipv4ToLong(address: Inet4Address): Long {
        return address.address.fold(0L) { result, byte ->
            (result shl 8) or (byte.toInt() and 0xFF).toLong()
        }
    }

    private fun prefixLengthToMask(prefixLength: Int): Long {
        if (prefixLength == 0) {
            return 0L
        }
        return (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
    }

    private fun longToIpv4(value: Long): String {
        return listOf(24, 16, 8, 0)
            .joinToString(".") { shift -> ((value shr shift) and 0xFF).toString() }
    }

    private fun parseNetworkTarget(address: String): NetworkTarget? {
        val trimmedAddress = address.trim()
        if (trimmedAddress.isBlank()) {
            return null
        }

        val parts = trimmedAddress.split(":")
        if (parts.size > 2) {
            return null
        }

        val host = parts.first().trim()
        if (host.isBlank()) {
            return null
        }

        val port = if (parts.size == 2) {
            parts[1].trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        } else {
            DEFAULT_NETWORK_PRINTER_PORT
        }

        return NetworkTarget(host = host, port = port)
    }

    @SuppressLint("MissingPermission")
    private fun getBondedBluetoothDevices(): List<BtDevice> {
        val adapter = bluetoothAdapter ?: return emptyList()
        return sortBluetoothDevices(adapter.bondedDevices.orEmpty().toList())
    }

    private fun addBluetoothDevice(device: BtDevice) {
        _foundBtDevices.value = sortBluetoothDevices(_foundBtDevices.value + device)
    }

    @SuppressLint("MissingPermission")
    private fun finishBluetoothScan(cancelDiscovery: Boolean = true) {
        stopBluetoothScan(cancelDiscovery = cancelDiscovery)
        val foundDevices = _foundBtDevices.value
        _bluetoothScanMessage.value = if (foundDevices.isEmpty()) {
            "No Bluetooth devices found. Make sure the printer is on and in pairing mode, or pair it first in Android Bluetooth settings."
        } else if (foundDevices.any(::isLikelyPrinter)) {
            "Bluetooth scan finished. Printer-like devices are listed first. Tap the printer you want to save."
        } else {
            "Bluetooth scan finished. If your printer still does not appear, pair it in Android Bluetooth settings first, then scan again."
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBluetoothScan(cancelDiscovery: Boolean = true) {
        bluetoothScanTimeoutJob?.cancel()
        bluetoothScanTimeoutJob = null

        val adapter = bluetoothAdapter
        if (cancelDiscovery && adapter != null && hasBluetoothScanAccess() && adapter.isDiscovering) {
            runCatching { adapter.cancelDiscovery() }
        }
        bluetoothLeScanCallback?.let { callback ->
            runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        }
        bluetoothLeScanCallback = null
        isClassicDiscoveryRunning = false
        isBleDiscoveryRunning = false

        bluetoothScanReceiver?.let { receiver ->
            runCatching { context.unregisterReceiver(receiver) }
        }
        bluetoothScanReceiver = null
        _isBluetoothScanning.value = false
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothLeScan(adapter: BluetoothAdapter): Boolean {
        val scanner = adapter.bluetoothLeScanner ?: return false
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                result.device?.let(::addBluetoothDevice)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result ->
                    result.device?.let(::addBluetoothDevice)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                bluetoothLeScanCallback = null
                isBleDiscoveryRunning = false
                if (!isClassicDiscoveryRunning) {
                    stopBluetoothScan(cancelDiscovery = false)
                    _bluetoothScanMessage.value = "Bluetooth scan could not start. Make sure the printer is powered on and nearby."
                }
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val started = runCatching {
            scanner.startScan(null, settings, callback)
            true
        }.getOrDefault(false)
        if (started) {
            bluetoothLeScanCallback = callback
        }
        return started
    }

    private fun sortBluetoothDevices(devices: List<BtDevice>): List<BtDevice> {
        return devices
            .distinctBy { it.address }
            .sortedWith(
                compareByDescending<BtDevice> { isLikelyPrinter(it) }
                    .thenByDescending { it.bondState == BtDevice.BOND_BONDED }
                    .thenBy { bluetoothDeviceName(it).ifBlank { it.address } }
                    .thenBy { it.address }
            )
    }

    @SuppressLint("MissingPermission")
    private fun bluetoothDeviceName(device: BtDevice): String {
        return runCatching { device.name.orEmpty().trim() }.getOrDefault("")
    }

    @SuppressLint("MissingPermission")
    private fun isLikelyPrinter(device: BtDevice): Boolean {
        val bluetoothClass = device.bluetoothClass
        if (bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.IMAGING) {
            return true
        }

        val deviceName = bluetoothDeviceName(device).lowercase()
        return PRINTER_NAME_KEYWORDS.any(deviceName::contains)
    }

    private fun getReadyBluetoothAdapter(): BluetoothAdapter? {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            _isBluetoothScanning.value = false
            _foundBtDevices.value = emptyList()
            _bluetoothScanMessage.value = "Bluetooth is not available on this device."
            return null
        }

        if (missingBluetoothPermissions().isNotEmpty()) {
            _isBluetoothScanning.value = false
            _foundBtDevices.value = emptyList()
            _bluetoothScanMessage.value = buildBluetoothPermissionMessage()
            return null
        }

        if (!adapter.isEnabled) {
            _isBluetoothScanning.value = false
            _foundBtDevices.value = emptyList()
            _bluetoothScanMessage.value = "Turn on Bluetooth and try again."
            return null
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabled()) {
            _isBluetoothScanning.value = false
            _foundBtDevices.value = emptyList()
            _bluetoothScanMessage.value = "Turn on Location to discover Bluetooth devices on Android 11 and lower."
            return null
        }

        return adapter
    }

    private fun missingBluetoothPermissions(): List<String> {
        val missingPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                missingPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                missingPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            missingPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return missingPermissions
    }

    private fun hasBluetoothScanAccess(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun buildBluetoothPermissionMessage(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "Bluetooth permission is missing. Enable Nearby devices for this app and try again."
        } else {
            "Location permission is required to discover Bluetooth devices on Android 11 and lower."
        }
    }

    private fun isLocationEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return true
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
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

    private fun Intent.getBluetoothDevice(): BtDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BtDevice.EXTRA_DEVICE, BtDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BtDevice.EXTRA_DEVICE)
        }
    }

    fun addProfile(
        name: String,
        type: ConnectionType,
        address: String,
        isDefault: Boolean,
        paperWidthMm: Int,
        printAreaDots: Int
    ) {
        viewModelScope.launch {
            val hadDefaultBeforeSave = repository.getDefaultProfile() != null
            val normalizedPaperWidth = normalizePaperWidthMm(paperWidthMm)
            val sanitizedPrintAreaDots = sanitizePrintAreaDots(printAreaDots)
            val profile = PrinterProfile(
                name = name,
                connectionType = type,
                address = address,
                paperWidthMm = normalizedPaperWidth,
                printAreaDots = sanitizedPrintAreaDots,
                charactersPerLine = defaultCharactersPerLineForPrintAreaDots(sanitizedPrintAreaDots),
                isDefault = isDefault
            )
            repository.saveProfile(profile)
            _printerActionMessage.value = if (isDefault || !hadDefaultBeforeSave) {
                "Printer saved and ready: $name is now the active printer (${normalizedPaperWidth} mm, ${sanitizedPrintAreaDots} dots)."
            } else {
                "Printer saved: $name (${normalizedPaperWidth} mm, ${sanitizedPrintAreaDots} dots)"
            }
        }
    }

    fun deleteProfile(profile: PrinterProfile) {
        viewModelScope.launch {
            repository.deleteProfile(profile)
            _printerActionMessage.value = "Printer removed: ${profile.name}"
        }
    }
    
    fun setDefault(profile: PrinterProfile) {
        viewModelScope.launch {
             repository.saveProfile(profile.copy(isDefault = true))
             _printerActionMessage.value = "Default printer set to ${profile.name}"
        }
    }

    fun updatePaperWidth(profile: PrinterProfile, paperWidthMm: Int) {
        viewModelScope.launch {
            val normalizedPaperWidth = normalizePaperWidthMm(paperWidthMm)
            if (profile.paperWidthMm == normalizedPaperWidth) {
                return@launch
            }

            val currentDefaultDots = defaultPrintAreaDotsForPaperWidthMm(profile.paperWidthMm)
            val newDefaultDots = defaultPrintAreaDotsForPaperWidthMm(normalizedPaperWidth)
            val nextPrintAreaDots = if (profile.resolvedPrintAreaDots() == currentDefaultDots) {
                newDefaultDots
            } else {
                profile.resolvedPrintAreaDots()
            }

            repository.saveProfile(
                profile.copy(
                    paperWidthMm = normalizedPaperWidth,
                    printAreaDots = nextPrintAreaDots,
                    charactersPerLine = defaultCharactersPerLineForPrintAreaDots(nextPrintAreaDots)
                )
            )
            _printerActionMessage.value =
                "Receipt size for ${profile.name} set to ${normalizedPaperWidth} mm (${nextPrintAreaDots} dots)."
        }
    }

    fun updatePrintAreaDots(profile: PrinterProfile, printAreaDots: Int) {
        viewModelScope.launch {
            val sanitizedPrintAreaDots = sanitizePrintAreaDots(printAreaDots)
            if (profile.resolvedPrintAreaDots() == sanitizedPrintAreaDots) {
                return@launch
            }

            repository.saveProfile(
                profile.copy(
                    printAreaDots = sanitizedPrintAreaDots,
                    charactersPerLine = defaultCharactersPerLineForPrintAreaDots(sanitizedPrintAreaDots)
                )
            )
            _printerActionMessage.value =
                "Print area for ${profile.name} set to ${sanitizedPrintAreaDots} dots."
        }
    }

    fun queueTestPrint(profile: PrinterProfile) {
        viewModelScope.launch {
            val testJob = PrintJob(
                printerProfileId = profile.id,
                payloadJson = buildTestPrintPayload(profile)
            )
            jobRepository.createJob(testJob)
            _printerActionMessage.value =
                "Connection test queued for ${profile.name}. Use Settings > Print Width Test for width tuning."
        }
    }

    fun clearPrinterActionMessage() {
        _printerActionMessage.value = null
    }

    override fun onCleared() {
        stopBluetoothScan()
        super.onCleared()
    }

    private data class Ipv4Subnet(
        val networkAddress: Long,
        val prefixLength: Int,
        val localAddress: Long
    )

    private data class NetworkTarget(
        val host: String,
        val port: Int
    )

    private fun buildTestPrintPayload(profile: PrinterProfile): String {
        val root = JsonObject().apply {
            addProperty("printer_profile_id", profile.id)
            addProperty("copies", 1)
            add("content", JsonObject().apply {
                addProperty("type", "escpos_blocks")
                add("blocks", JsonArray().apply {
                    addCommand("align", "center")
                    addCommand("text", "Softbridge Connection Test")
                    addCommand("text", profile.name)
                    addCommand("align", "left")
                    addCommand("text", "Paper: ${profile.paperWidthMm} mm")
                    addCommand("text", "Print area: ${profile.resolvedPrintAreaDots()} dots")
                    addCommand("text", "Connection: ${profile.connectionType}")
                    addCommand("text", "Address: ${profile.address}")
                    addCommand("text", "If this prints, the saved printer profile is working.")
                    addCommand("text", "This does not use the Settings width control.")
                })
            })
        }
        return root.toString()
    }

    private fun JsonArray.addCommand(command: String, value: String) {
        add(JsonObject().apply {
            addProperty("cmd", command)
            addProperty("value", value)
        })
    }

    private companion object {
        const val BLUETOOTH_SCAN_TIMEOUT_MS = 20_000L
        const val MAX_SCAN_HOSTS = 512L
        const val DEFAULT_NETWORK_PRINTER_PORT = 9100
        const val PORT_PROBE_TIMEOUT_MS = 200
        const val NETWORK_TEST_TIMEOUT_MS = 1500
        val PRINTER_NAME_KEYWORDS = listOf(
            "printer",
            "woosim",
            "wsp",
            "pos",
            "epson",
            "tm-",
            "bixolon",
            "zebra",
            "star",
            "sewoo",
            "citizen",
            "rongta"
        )
    }
}
