package com.receiptbridge.escpos

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException

class UsbConnection(
    private val context: Context,
    private val deviceName: String // The name/id of the device from UsbDevice.deviceName
) : PrinterConnection {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbDevice: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var endpointOut: UsbEndpoint? = null
    private var usbInterface: UsbInterface? = null

    override suspend fun connect() {
        val device = usbManager.deviceList[deviceName] ?: throw IOException("USB Device $deviceName not found")
        usbDevice = device

        if (!usbManager.hasPermission(device)) {
            requestPermission(device)
            if (!usbManager.hasPermission(device)) {
                throw IOException("USB Permission denied for $deviceName")
            }
        }

        // Find printable interface and endpoint
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (j in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(j)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && 
                    endpoint.direction == UsbConstants.USB_DIR_OUT) {
                    usbInterface = iface
                    endpointOut = endpoint
                    break
                }
            }
            if (endpointOut != null) break
        }

        if (usbInterface == null || endpointOut == null) {
            throw IOException("Could not find a valid bulk output endpoint on $deviceName")
        }

        connection = usbManager.openDevice(device) ?: throw IOException("Failed to open USB device $deviceName")
        
        if (!connection!!.claimInterface(usbInterface, true)) {
            connection!!.close()
            throw IOException("Failed to claim USB interface")
        }
    }

    override suspend fun write(data: ByteArray) {
        val conn = connection ?: throw IOException("USB Connection not established")
        val endpoint = endpointOut ?: throw IOException("USB Output endpoint not found")

        // Bulk transfer might need chunking for large data (images)
        var offset = 0
        while (offset < data.size) {
            val length = minOf(data.size - offset, 16384) // 16KB chunks
            val result = conn.bulkTransfer(endpoint, data, offset, length, 5000)
            if (result < 0) {
                throw IOException("USB Bulk transfer failed: $result")
            }
            offset += result
        }
    }

    override fun isConnected(): Boolean {
        return connection != null && endpointOut != null
    }

    override suspend fun disconnect() {
        try {
            usbInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            connection = null
            endpointOut = null
            usbInterface = null
        }
    }

    private suspend fun requestPermission(device: UsbDevice) {
        val deferred = CompletableDeferred<Boolean>()
        val ACTION_USB_PERMISSION = "com.receiptbridge.USB_PERMISSION"
        
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE
        )
        
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (ACTION_USB_PERMISSION == intent.action) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    deferred.complete(granted)
                }
            }
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        
        try {
            usbManager.requestPermission(device, permissionIntent)
            deferred.await()
        } finally {
            context.unregisterReceiver(receiver)
        }
    }
}
