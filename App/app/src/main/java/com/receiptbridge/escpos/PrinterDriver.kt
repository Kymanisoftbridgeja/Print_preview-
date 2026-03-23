package com.receiptbridge.escpos

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.google.gson.Gson
import com.receiptbridge.data.ConnectionType
import com.receiptbridge.data.PrintJob
import com.receiptbridge.data.PrinterProfile
import com.receiptbridge.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrinterDriver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    private val gson = Gson()
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter

    suspend fun print(job: PrintJob, profile: PrinterProfile) {
        withContext(Dispatchers.IO) {
            val payload = try {
                gson.fromJson(job.payloadJson, PrintPayload::class.java)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid JSON payload: ${e.message}")
            }

            val connection = createConnection(profile)
            
            try {
                connection.connect()
                
                val builder = EscPosBuilder().reset()
                
                // Refresh settings
                settingsRepository.refreshSettings()
                val settings = settingsRepository.settings.value
                
                // Apply Global Header
                settings.globalHeader?.let { header ->
                    // For now assume header is simple text if no "base64:" prefix
                    if (header.startsWith("base64:")) {
                         val decoded = android.util.Base64.decode(header.removePrefix("base64:"), android.util.Base64.DEFAULT)
                         builder.image(384, 100, decoded) // Default dimensions
                    } else {
                         builder.align("center").text(header).newLine()
                    }
                }
                
                // Process Blocks
                val blocks = payload.content.blocks
                for (block in blocks) {
                    processBlock(builder, block, profile)
                }
                
                // Apply Global Footer
                settings.globalFooter?.let { footer ->
                    builder.newLine().align("center").text(footer).newLine()
                }

                // Finalize (Feed & Cut)
                if (profile.autoCut) {
                     builder.feed(profile.feedLines)
                     builder.cut()
                }

                val printData = builder.build()
                
                // Honor copies
                val copies = if (payload.copies > 0) payload.copies else 1
                repeat(copies) {
                    connection.write(printData)
                }
                
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun createConnection(profile: PrinterProfile): PrinterConnection {
        return when (profile.connectionType) {
            ConnectionType.NETWORK -> {
                val parts = profile.address.split(":")
                val host = parts[0]
                val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 9100 else 9100
                NetworkConnection(host, port)
            }
            ConnectionType.BLUETOOTH -> {
                if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                     throw IllegalStateException("Bluetooth is not enabled")
                }
                BluetoothConnection(bluetoothAdapter, profile.address)
            }
            ConnectionType.USB -> {
                UsbConnection(context, profile.address)
            }
        }
    }

    private fun processBlock(builder: EscPosBuilder, block: PrintBlock, profile: PrinterProfile) {
        when (block.cmd) {
            "text" -> {
                val text = block.value as? String ?: ""
                builder.text(text)
                // Auto-newline if not manual? usually text block implies a line or part of it.
                // Prompt example: {"cmd":"text","value":"CIAO BELLA"}
                // Let's assume explicit newline is needed OR we add it. 
                // "text" usually implies println in these JSON DSLs.
                builder.newLine() 
            }
            "align" -> {
                val align = block.value as? String ?: "left"
                builder.align(align)
            }
            "feed" -> {
                val lines = (block.value as? Double)?.toInt() ?: 1
                builder.feed(lines)
            }
            "cut" -> {
                val mode = block.value as? String ?: "full"
                builder.cut(mode == "full")
            }
            "charset" -> {
                val charset = block.value as? String ?: "UTF-8"
                builder.setEncoding(charset)
            }
            "codepage" -> {
                val page = (block.value as? Double)?.toInt() ?: 0
                builder.setCodePage(page)
            }
            "row2" -> {
                // Two column row: "Item A" .... "$10.00"
                val left = block.left ?: ""
                val right = block.right ?: ""
                
                // We need to calculate spacing based on paper width (chars per line)
                val width = profile.charactersPerLine
                
                // Simple logic: Left + Spaces + Right
                val totalLen = left.length + right.length
                if (totalLen >= width) {
                     // Overflow or just print tightly
                     builder.text("$left $right")
                } else {
                     val spaces = width - totalLen
                     builder.text(left + " ".repeat(spaces) + right)
                }
                builder.newLine()
            }
            "qr" -> {
                val data = block.value as? String ?: return
                val size = (block.left?.toIntOrNull() ?: 3).coerceIn(1, 16)
                builder.qrCode(data, size)
            }
            "image" -> {
                // Expects base64 encoded raster data
                val base64Data = block.value as? String ?: return
                val width = block.left?.toIntOrNull() ?: 384
                val height = block.right?.toIntOrNull() ?: 100
                try {
                    val decoded = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                    builder.image(width, height, decoded)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            "drawer" -> {
                builder.drawerOpen()
            }
            "beep" -> {
                builder.beep()
            }
            "raw" -> {
                val hex = block.value as? String ?: return
                // Simple hex to byte array
                val hexChars = hex.replace(" ", "").toCharArray()
                val bytes = ByteArray(hexChars.size / 2)
                for (i in 0 until bytes.size) {
                    bytes[i] = ((Character.digit(hexChars[i * 2], 16) shl 4) +
                                Character.digit(hexChars[i * 2 + 1], 16)).toByte()
                }
                builder.raw(bytes)
            }
        }
    }
}
