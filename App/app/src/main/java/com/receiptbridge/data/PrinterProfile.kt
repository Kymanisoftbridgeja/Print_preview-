package com.receiptbridge.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID
import kotlin.math.roundToInt

enum class ConnectionType {
    BLUETOOTH, NETWORK, USB
}

const val PAPER_WIDTH_58_MM = 58
const val PAPER_WIDTH_80_MM = 80
const val DEFAULT_PRINT_AREA_DOTS_58_MM = 384
const val DEFAULT_PRINT_AREA_DOTS_80_MM = 576
const val MIN_PRINT_AREA_DOTS = 320
const val MAX_PRINT_AREA_DOTS = 1200
const val PRINT_AREA_DOTS_STEP = 8

@Entity(tableName = "printer_profiles")
data class PrinterProfile(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val connectionType: ConnectionType,
    val address: String, // MAC, IP, etc.
    val paperWidthMm: Int = PAPER_WIDTH_80_MM,
    val printAreaDots: Int = defaultPrintAreaDotsForPaperWidthMm(paperWidthMm),
    val charactersPerLine: Int = defaultCharactersPerLineForPrintAreaDots(printAreaDots),
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

fun defaultPrintAreaDotsForPaperWidthMm(paperWidthMm: Int): Int {
    return if (normalizePaperWidthMm(paperWidthMm) == PAPER_WIDTH_58_MM) {
        DEFAULT_PRINT_AREA_DOTS_58_MM
    } else {
        DEFAULT_PRINT_AREA_DOTS_80_MM
    }
}

fun sanitizePrintAreaDots(printAreaDots: Int): Int {
    return printAreaDots.coerceIn(MIN_PRINT_AREA_DOTS, MAX_PRINT_AREA_DOTS)
}

fun defaultCharactersPerLineForPrintAreaDots(printAreaDots: Int): Int {
    val sanitizedDots = sanitizePrintAreaDots(printAreaDots)
    return ((sanitizedDots / DEFAULT_PRINT_AREA_DOTS_80_MM.toFloat()) * 48f)
        .roundToInt()
        .coerceIn(24, 96)
}

fun PrinterProfile.resolvedPrintAreaDots(): Int {
    return sanitizePrintAreaDots(printAreaDots)
}
