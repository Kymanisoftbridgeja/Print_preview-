package com.receiptbridge.ui.viewmodel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice as BtDevice
import android.os.Build
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.receiptbridge.data.ConnectionType
import com.receiptbridge.data.PrinterProfile
import com.receiptbridge.data.repository.PrinterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    private val bluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter

    val profiles = repository.allProfiles
    
    private val _foundBtDevices = MutableStateFlow<List<BtDevice>>(emptyList())
    val foundBtDevices: StateFlow<List<BtDevice>> = _foundBtDevices.asStateFlow()

    private val _foundIpDevices = MutableStateFlow<List<String>>(emptyList())
    val foundIpDevices: StateFlow<List<String>> = _foundIpDevices.asStateFlow()

    fun getUsbDevices(): List<UsbDevice> {
        return usbManager.deviceList.values.toList()
    }

    @SuppressLint("MissingPermission")
    fun scanBluetooth() {
        if (bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter.cancelDiscovery()
        }
        
        _foundBtDevices.value = emptyList()
        
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (BtDevice.ACTION_FOUND == intent.action) {
                    val device = intent.getBluetoothDevice()
                    device?.let { 
                        if (!_foundBtDevices.value.contains(it)) {
                            _foundBtDevices.value = _foundBtDevices.value + it
                        }
                    }
                }
            }
        }
        
        context.registerReceiver(receiver, IntentFilter(BtDevice.ACTION_FOUND))
        bluetoothAdapter?.startDiscovery()
        
        // Auto-unregister after 12 seconds
        viewModelScope.launch {
            kotlinx.coroutines.delay(12000)
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {}
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

    private data class Ipv4Subnet(
        val networkAddress: Long,
        val prefixLength: Int,
        val localAddress: Long
    )

    private companion object {
        const val MAX_SCAN_HOSTS = 512L
    }
}
