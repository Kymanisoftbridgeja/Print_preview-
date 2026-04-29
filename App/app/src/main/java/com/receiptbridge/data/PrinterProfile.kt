package com.receiptbridge.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Locale
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

fun PrinterProfile.systemPrintLocalId(): String {
    return buildString {
        append(SYSTEM_PRINT_ID_PREFIX)
        append(connectionType.name)
        append(SYSTEM_PRINT_ID_SEPARATOR)
        append(normalizedSystemPrintAddress())
    }
}

fun PrinterProfile.normalizedSystemPrintAddress(): String {
    return address.trim().lowercase(Locale.US)
}

fun parseSystemPrintLocalId(localId: String): SystemPrintProfileSelector? {
    if (!localId.startsWith(SYSTEM_PRINT_ID_PREFIX)) {
        return null
    }

    val payload = localId.removePrefix(SYSTEM_PRINT_ID_PREFIX)
    val separatorIndex = payload.indexOf(SYSTEM_PRINT_ID_SEPARATOR)
    if (separatorIndex <= 0 || separatorIndex == payload.lastIndex) {
        return null
    }

    val connectionType = runCatching {
        ConnectionType.valueOf(payload.substring(0, separatorIndex))
    }.getOrNull() ?: return null

    val normalizedAddress = payload.substring(separatorIndex + 1).trim()
    if (normalizedAddress.isBlank()) {
        return null
    }

    return SystemPrintProfileSelector(
        connectionType = connectionType,
        normalizedAddress = normalizedAddress
    )
}

fun looksLikeLegacyPrinterProfileId(localId: String): Boolean {
    return runCatching { UUID.fromString(localId) }.isSuccess
}

data class SystemPrintProfileSelector(
    val connectionType: ConnectionType,
    val normalizedAddress: String
)

private const val SYSTEM_PRINT_ID_PREFIX = "receiptbridge:"
private const val SYSTEM_PRINT_ID_SEPARATOR = '|'
