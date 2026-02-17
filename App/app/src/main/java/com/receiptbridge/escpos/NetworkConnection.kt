package com.receiptbridge.escpos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class NetworkConnection(
    private val host: String,
    private val port: Int = 9100,
    private val timeoutMs: Int = 5000
) : PrinterConnection {

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null

    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            try {
                socket = Socket()
                socket?.connect(InetSocketAddress(host, port), timeoutMs)
                outputStream = socket?.getOutputStream()
            } catch (e: Exception) {
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
            } catch (e: Exception) {
                // Ignore close errors
            } finally {
                outputStream = null
                socket = null
            }
        }
    }

    override suspend fun write(data: ByteArray) {
        withContext(Dispatchers.IO) {
            if (!isConnected()) {
                throw IllegalStateException("Socket is not connected")
            }
            outputStream?.write(data)
            outputStream?.flush()
        }
    }

    override fun isConnected(): Boolean {
        return socket?.isConnected == true && socket?.isClosed == false
    }
}
