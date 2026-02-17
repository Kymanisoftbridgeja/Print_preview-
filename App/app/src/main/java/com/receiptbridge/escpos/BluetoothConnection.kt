package com.receiptbridge.escpos

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

class BluetoothConnection(
    private val bluetoothAdapter: BluetoothAdapter,
    private val macAddress: String
) : PrinterConnection {

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    
    // Standard SPP UUID
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @SuppressLint("MissingPermission")
    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            try {
                val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(macAddress)
                // Use createRfcommSocketToServiceRecord for secure connection, or createInsecureRfcommSocketToServiceRecord if needed
                // Trying standard secure first
                socket = device.createRfcommSocketToServiceRecord(sppUuid)
                socket?.connect()
                outputStream = socket?.getOutputStream()
            } catch (e: IOException) {
                disconnect()
                throw e
            }
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                outputStream?.close()
                socket?.close()
            } catch (e: IOException) {
                // Ignore
            } finally {
                outputStream = null
                socket = null
            }
        }
    }

    override suspend fun write(data: ByteArray) {
        withContext(Dispatchers.IO) {
            if (!isConnected()) {
                throw IllegalStateException("Bluetooth socket is not connected")
            }
            outputStream?.write(data)
            outputStream?.flush()
        }
    }

    override fun isConnected(): Boolean {
        return socket?.isConnected == true
    }
}
