package com.receiptbridge.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class ConnectionType {
    BLUETOOTH, NETWORK, USB
}

const val PAPER_WIDTH_58_MM = 58
const val PAPER_WIDTH_80_MM = 80

@Entity(tableName = "printer_profiles")
data class PrinterProfile(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val connectionType: ConnectionType,
    val address: String, // MAC, IP, etc.
    val paperWidthMm: Int = PAPER_WIDTH_80_MM,
    val charactersPerLine: Int = defaultCharactersPerLineForPaperWidthMm(PAPER_WIDTH_80_MM),
    val feedLines: Int = 2,
    val autoCut: Boolean = true,
    val isDefault: Boolean = false
)

fun normalizePaperWidthMm(paperWidthMm: Int): Int {
    return if (paperWidthMm == PAPER_WIDTH_58_MM) PAPER_WIDTH_58_MM else PAPER_WIDTH_80_MM
}

fun defaultCharactersPerLineForPaperWidthMm(paperWidthMm: Int): Int {
    return if (normalizePaperWidthMm(paperWidthMm) == PAPER_WIDTH_58_MM) 32 else 48
}

fun defaultImageWidthForPaperWidthMm(paperWidthMm: Int): Int {
    return if (normalizePaperWidthMm(paperWidthMm) == PAPER_WIDTH_58_MM) 384 else 576
}
