package com.receiptbridge.escpos

interface PrinterConnection {
    suspend fun connect()
    suspend fun disconnect()
    suspend fun write(data: ByteArray)
    fun isConnected(): Boolean
}
