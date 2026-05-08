package com.receiptbridge.desktop.model

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

enum class ConnectionType {
    BLUETOOTH,
    NETWORK,
    USB
}

enum class OdooReceiptRenderMode {
    NATIVE_THERMAL,
    EXACT_LAYOUT
}

enum class JobStatus {
    PENDING,
    PRINTING,
    COMPLETED,
    FAILED
}

const val PAPER_WIDTH_58_MM = 58
const val PAPER_WIDTH_80_MM = 80
const val DEFAULT_PRINT_AREA_DOTS_58_MM = 384
const val DEFAULT_PRINT_AREA_DOTS_80_MM = 576
const val MIN_PRINT_AREA_DOTS = 320
const val MAX_PRINT_AREA_DOTS = 1200
const val PRINT_AREA_DOTS_STEP = 8

const val MIN_KEEP_HISTORY_DAYS = 0
const val MAX_KEEP_HISTORY_DAYS = 365
const val DEFAULT_KEEP_HISTORY_DAYS = 30
const val MIN_SYSTEM_PRINT_CONTENT_FILL_PERCENT = 75
const val MAX_SYSTEM_PRINT_CONTENT_FILL_PERCENT = 100
const val DEFAULT_SYSTEM_PRINT_CONTENT_FILL_PERCENT = 92
const val DEFAULT_EXACT_LAYOUT_RENDERED_RECEIPT_FILL_PERCENT = MAX_SYSTEM_PRINT_CONTENT_FILL_PERCENT
const val DEFAULT_ODOO_ALLOWED_ORIGINS = "*"

data class PrinterProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val connectionType: ConnectionType,
    val address: String,
    val paperWidthMm: Int = PAPER_WIDTH_80_MM,
    val printAreaDots: Int = defaultPrintAreaDotsForPaperWidthMm(paperWidthMm),
    val charactersPerLine: Int = defaultCharactersPerLineForPrintAreaDots(printAreaDots),
    val renderedReceiptFillPercent: Int = DEFAULT_SYSTEM_PRINT_CONTENT_FILL_PERCENT,
    val renderedReceiptSmartFit: Boolean? = null,
    val odooReceiptRenderMode: OdooReceiptRenderMode? = null,
    val feedLines: Int = 2,
    val autoCut: Boolean = true,
    val isDefault: Boolean = false
)

data class PrintJob(
    val id: String = UUID.randomUUID().toString(),
    val printerProfileId: String? = null,
    val payloadJson: String,
    val status: JobStatus = JobStatus.PENDING,
    val timestamp: Long = System.currentTimeMillis(),
    val errorMessage: String? = null,
    val copies: Int = 1
)

data class AppSettings(
    val globalHeader: String? = null,
    val globalFooter: String? = null,
    val autoPrintOnConnect: Boolean = false,
    val keepHistoryDays: Int = DEFAULT_KEEP_HISTORY_DAYS,
    val systemPrintContentFillPercent: Int = DEFAULT_SYSTEM_PRINT_CONTENT_FILL_PERCENT,
    val odooAllowedOrigins: String = DEFAULT_ODOO_ALLOWED_ORIGINS,
    val odooApiToken: String? = null,
    val acceptLaunchPayloads: Boolean = true
)

data class PrintPayload(
    @SerializedName("printer_profile_id") val printerProfileId: String?,
    @SerializedName("paper_size") val paperSize: String?,
    @SerializedName("copies") val copies: Int = 1,
    @SerializedName("content") val content: PrintContent
)

data class PrintContent(
    @SerializedName("type") val type: String,
    @SerializedName("blocks") val blocks: List<PrintBlock> = emptyList(),
    @SerializedName("text") val text: String? = null,
    @SerializedName("html") val html: String? = null,
    @SerializedName("image") val image: String? = null,
    @SerializedName("structured_receipt") val structuredReceipt: JsonObject? = null,
    @SerializedName("rendered_image_meta") val renderedImageMeta: JsonObject? = null
)

data class PrintBlock(
    @SerializedName("cmd") val cmd: String,
    @SerializedName("value") val value: Any?,
    @SerializedName("left") val left: String?,
    @SerializedName("right") val right: String?
)

fun normalizePaperWidthMm(paperWidthMm: Int): Int {
    return if (paperWidthMm == PAPER_WIDTH_58_MM) PAPER_WIDTH_58_MM else PAPER_WIDTH_80_MM
}

fun defaultCharactersPerLineForPaperWidthMm(paperWidthMm: Int): Int {
    return if (normalizePaperWidthMm(paperWidthMm) == PAPER_WIDTH_58_MM) 32 else 48
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

fun sanitizeRenderedReceiptFillPercent(value: Int): Int {
    val candidate = if (value <= 0) DEFAULT_SYSTEM_PRINT_CONTENT_FILL_PERCENT else value
    return sanitizeSystemPrintContentFillPercent(candidate)
}

fun defaultRenderedReceiptFillPercentFor(
    renderMode: OdooReceiptRenderMode,
    fallbackPercent: Int = DEFAULT_SYSTEM_PRINT_CONTENT_FILL_PERCENT
): Int {
    return if (renderMode == OdooReceiptRenderMode.EXACT_LAYOUT) {
        DEFAULT_EXACT_LAYOUT_RENDERED_RECEIPT_FILL_PERCENT
    } else {
        sanitizeRenderedReceiptFillPercent(fallbackPercent)
    }
}

fun PrinterProfile.resolvedRenderedReceiptFillPercent(
    fallbackPercent: Int = DEFAULT_SYSTEM_PRINT_CONTENT_FILL_PERCENT
): Int {
    val resolvedMode = resolvedOdooReceiptRenderMode()
    val candidate = when {
        renderedReceiptFillPercent <= 0 -> defaultRenderedReceiptFillPercentFor(resolvedMode, fallbackPercent)
        resolvedMode == OdooReceiptRenderMode.EXACT_LAYOUT &&
            renderedReceiptFillPercent == DEFAULT_SYSTEM_PRINT_CONTENT_FILL_PERCENT -> {
            DEFAULT_EXACT_LAYOUT_RENDERED_RECEIPT_FILL_PERCENT
        }
        else -> renderedReceiptFillPercent
    }
    return sanitizeRenderedReceiptFillPercent(candidate)
}

fun PrinterProfile.resolvedRenderedReceiptSmartFit(): Boolean {
    return renderedReceiptSmartFit ?: true
}

fun defaultOdooReceiptRenderModeFor(connectionType: ConnectionType): OdooReceiptRenderMode {
    return if (connectionType == ConnectionType.USB) {
        OdooReceiptRenderMode.EXACT_LAYOUT
    } else {
        OdooReceiptRenderMode.NATIVE_THERMAL
    }
}

fun PrinterProfile.resolvedOdooReceiptRenderMode(): OdooReceiptRenderMode {
    return odooReceiptRenderMode ?: defaultOdooReceiptRenderModeFor(connectionType)
}

fun sanitizeKeepHistoryDays(value: Int): Int {
    return value.coerceIn(MIN_KEEP_HISTORY_DAYS, MAX_KEEP_HISTORY_DAYS)
}

fun sanitizeSystemPrintContentFillPercent(value: Int): Int {
    return value.coerceIn(
        MIN_SYSTEM_PRINT_CONTENT_FILL_PERCENT,
        MAX_SYSTEM_PRINT_CONTENT_FILL_PERCENT
    )
}

fun AppSettings.sanitized(): AppSettings {
    return copy(
        keepHistoryDays = sanitizeKeepHistoryDays(keepHistoryDays),
        systemPrintContentFillPercent = sanitizeSystemPrintContentFillPercent(systemPrintContentFillPercent),
        odooAllowedOrigins = odooAllowedOrigins.trim().ifBlank { DEFAULT_ODOO_ALLOWED_ORIGINS },
        odooApiToken = odooApiToken?.trim()?.ifBlank { null }
    )
}

fun PrinterProfile.normalizedSystemPrintAddress(): String {
    return address.trim().lowercase(Locale.US)
}
