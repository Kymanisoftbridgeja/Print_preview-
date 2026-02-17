package com.receiptbridge.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class ConnectionType {
    BLUETOOTH, NETWORK, USB
}

@Entity(tableName = "printer_profiles")
data class PrinterProfile(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val connectionType: ConnectionType,
    val address: String, // MAC, IP, etc.
    val paperWidthMm: Int = 80, // 58 or 80
    val charactersPerLine: Int = 48, // Override default
    val feedLines: Int = 2,
    val autoCut: Boolean = true,
    val isDefault: Boolean = false
)
