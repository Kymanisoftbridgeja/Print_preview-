package com.receiptbridge.ui.viewmodel

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
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
import com.receiptbridge.data.ConnectionType
import com.receiptbridge.data.PrinterProfile
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
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val bluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var bluetoothScanReceiver: BroadcastReceiver? = null
    private var bluetoothScanTimeoutJob: Job? = null

    val profiles = repository.allProfiles
    
    private val _foundBtDevices = MutableStateFlow<List<BtDevice>>(emptyList())
    val foundBtDevices: StateFlow<List<BtDevice>> = _foundBtDevices.asStateFlow()

    private val _isBluetoothScanning = MutableStateFlow(false)
    val isBluetoothScanning: StateFlow<Boolean> = _isBluetoothScanning.asStateFlow()

    private val _bluetoothScanMessage = MutableStateFlow<String?>(null)
    val bluetoothScanMessage: StateFlow<String?> = _bluetoothScanMessage.asStateFlow()

    private val _foundIpDevices = MutableStateFlow<List<String>>(emptyList())
    val foundIpDevices: StateFlow<List<String>> = _foundIpDevices.asStateFlow()

    fun getUsbDevices(): List<UsbDevice> {
        return usbManager.deviceList.values.toList()
    }

    @SuppressLint("MissingPermission")
    fun scanBluetooth() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            _isBluetoothScanning.value = false
            _foundBtDevices.value = emptyList()
            _bluetoothScanMessage.value = "Bluetooth is not available on this device."
            return
        }

        if (missingBluetoothPermissions().isNotEmpty()) {
            _isBluetoothScanning.value = false
            _foundBtDevices.value = emptyList()
            _bluetoothScanMessage.value = buildBluetoothPermissionMessage()
            return
        }

        if (!adapter.isEnabled) {
            _isBluetoothScanning.value = false
            _foundBtDevices.value = emptyList()
            _bluetoothScanMessage.value = "Turn on Bluetooth and try again."
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabled()) {
            _isBluetoothScanning.value = false
            _foundBtDevices.value = emptyList()
            _bluetoothScanMessage.value = "Turn on Location to discover Bluetooth devices on Android 11 and lower."
            return
        }

        stopBluetoothScan()
        _foundBtDevices.value = getBondedBluetoothDevices()
        _bluetoothScanMessage.value = if (_foundBtDevices.value.isEmpty()) {
            "Searching for nearby Bluetooth devices..."
        } else {
            "Paired devices loaded. Searching for more nearby Bluetooth devices..."
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BtDevice.ACTION_FOUND -> {
                        intent.getBluetoothDevice()?.let(::addBluetoothDevice)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        finishBluetoothScan()
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
        val discoveryStarted = runCatching { adapter.startDiscovery() }.getOrDefault(false)
        if (!discoveryStarted) {
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
            if (hasBluetoothScanAccess() && adapter.isDiscovering) {
                runCatching { adapter.cancelDiscovery() }
            }
        }
    }

    fun scanNetwork() {
        viewModelScope.launch {
            _foundIpDevices.value = emptyList()
            val reachableDevices = withContext(Dispatchers.IO) {
                getLocalSubnets()
                    .flatMap(::buildCandidateIps)
                    .flatMap { ip ->
                        listOf(async { if (isPortOpen(ip, 9100)) ip else null })
                    }
                    .awaitAll()
                    .filterNotNull()
                    .distinct()
                    .sortedBy(::ipSortKey)
            }
            _foundIpDevices.value = reachableDevices
        }
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

    private fun isPortOpen(ip: String, port: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), 200)
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

    @SuppressLint("MissingPermission")
    private fun getBondedBluetoothDevices(): List<BtDevice> {
        val adapter = bluetoothAdapter ?: return emptyList()
        return sortBluetoothDevices(adapter.bondedDevices.orEmpty().toList())
    }

    private fun addBluetoothDevice(device: BtDevice) {
        _foundBtDevices.value = sortBluetoothDevices(_foundBtDevices.value + device)
    }

    @SuppressLint("MissingPermission")
    private fun finishBluetoothScan() {
        stopBluetoothScan(cancelDiscovery = false)
        _bluetoothScanMessage.value = if (_foundBtDevices.value.isEmpty()) {
            "No Bluetooth devices found. Make sure the printer is on and in pairing mode."
        } else {
            "Bluetooth scan finished."
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

        bluetoothScanReceiver?.let { receiver ->
            runCatching { context.unregisterReceiver(receiver) }
        }
        bluetoothScanReceiver = null
        _isBluetoothScanning.value = false
    }

    private fun sortBluetoothDevices(devices: List<BtDevice>): List<BtDevice> {
        return devices
            .distinctBy { it.address }
            .sortedWith(
                compareBy<BtDevice> { bluetoothDeviceName(it).ifBlank { it.address } }
                    .thenBy { it.address }
            )
    }

    @SuppressLint("MissingPermission")
    private fun bluetoothDeviceName(device: BtDevice): String {
        return runCatching { device.name.orEmpty().trim() }.getOrDefault("")
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

    fun addProfile(name: String, type: ConnectionType, address: String, isDefault: Boolean) {
        viewModelScope.launch {
            val profile = PrinterProfile(
                name = name,
                connectionType = type,
                address = address,
                isDefault = isDefault
            )
            repository.saveProfile(profile)
        }
    }

    fun deleteProfile(profile: PrinterProfile) {
        viewModelScope.launch {
            repository.deleteProfile(profile)
        }
    }
    
    fun setDefault(profile: PrinterProfile) {
        viewModelScope.launch {
             repository.saveProfile(profile.copy(isDefault = true))
        }
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

    private companion object {
        const val BLUETOOTH_SCAN_TIMEOUT_MS = 20_000L
        const val MAX_SCAN_HOSTS = 512L
    }
}
