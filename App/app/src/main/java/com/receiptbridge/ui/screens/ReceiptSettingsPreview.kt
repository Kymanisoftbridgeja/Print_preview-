package com.receiptbridge.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.receiptbridge.data.AppSettings
import com.receiptbridge.data.ConnectionType
import com.receiptbridge.data.PrinterProfile
import com.receiptbridge.data.defaultCharactersPerLineForPrintAreaDots
import com.receiptbridge.data.resolvedPrintAreaDots
import kotlin.math.roundToInt

@Composable
fun ReceiptSettingsPreview(
    settings: AppSettings,
    previewFillPercent: Int,
    profile: PrinterProfile?
) {
    val previewProfile = remember(profile) {
        profile ?: PrinterProfile(
            name = "Preview Printer",
            connectionType = ConnectionType.NETWORK,
            address = "192.168.1.100"
        )
    }
    val baseCharactersPerLine = remember(previewProfile) {
        defaultCharactersPerLineForPrintAreaDots(previewProfile.resolvedPrintAreaDots())
    }
    val contentCharactersPerLine = remember(previewFillPercent, baseCharactersPerLine) {
        ((baseCharactersPerLine * (previewFillPercent / 100f)).roundToInt())
            .coerceIn(20, baseCharactersPerLine)
    }
    val receiptWidthFraction = remember(previewFillPercent) {
        (previewFillPercent / 100f).coerceIn(0.75f, 1f)
    }
    val headerBitmap = remember(settings.globalHeader) {
        decodePreviewBitmap(settings.globalHeader)
    }
    val footerBitmap = remember(settings.globalFooter) {
        decodePreviewBitmap(settings.globalFooter)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Receipt Preview",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (profile == null) {
                    "Previewing a default 80 mm layout. Add a printer to match its exact paper width and printable area."
                } else {
                    "Previewing ${previewProfile.name} at ${previewProfile.paperWidthMm} mm / ${previewProfile.resolvedPrintAreaDots()} dots with width fill set to $previewFillPercent%."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(receiptWidthFraction),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)
                    ) {
                        ReceiptPreviewHeader(
                            rawValue = settings.globalHeader,
                            headerBitmapAvailable = headerBitmap != null,
                            contentCharactersPerLine = contentCharactersPerLine
                        )
                        headerBitmap?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 120.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        ReceiptPreviewCenteredLine("SOFTBRIDGE")
                        ReceiptPreviewCenteredLine("Order #1042")
                        ReceiptPreviewCenteredLine("2026-04-29 12:00 PM", small = true)

                        Spacer(modifier = Modifier.height(12.dp))
                        ReceiptPreviewDivider()
                        Spacer(modifier = Modifier.height(10.dp))

                        ReceiptPreviewItemRow(
                            left = "1  Jerk Chicken Meal Large",
                            right = "18.00"
                        )
                        ReceiptPreviewSubLine("Rice, peas, salad")
                        Spacer(modifier = Modifier.height(6.dp))
                        ReceiptPreviewItemRow(
                            left = "2  Bottled Water",
                            right = "6.00"
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        ReceiptPreviewItemRow(
                            left = "1  Plantain Side Order",
                            right = "4.50"
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        ReceiptPreviewDivider()
                        Spacer(modifier = Modifier.height(10.dp))

                        ReceiptPreviewItemRow("Subtotal", "28.50", bold = true)
                        ReceiptPreviewItemRow("Tax", "4.28")
                        ReceiptPreviewItemRow("Total", "32.78", emphasized = true)

                        Spacer(modifier = Modifier.height(12.dp))
                        ReceiptPreviewFooter(
                            rawValue = settings.globalFooter,
                            footerBitmapAvailable = footerBitmap != null,
                            contentCharactersPerLine = contentCharactersPerLine
                        )
                        footerBitmap?.let { bitmap ->
                            if (headerBitmap == null || bitmap != headerBitmap) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 100.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                        }
                        ReceiptPreviewCenteredLine("Thank you for testing", small = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceiptPreviewHeader(
    rawValue: String?,
    headerBitmapAvailable: Boolean,
    contentCharactersPerLine: Int
) {
    if (rawValue.isNullOrBlank() || headerBitmapAvailable) {
        return
    }

    ReceiptPreviewWrappedText(
        text = rawValue,
        contentCharactersPerLine = contentCharactersPerLine,
        centered = true,
        bold = true
    )
    Spacer(modifier = Modifier.height(12.dp))
}

@Composable
private fun ReceiptPreviewFooter(
    rawValue: String?,
    footerBitmapAvailable: Boolean,
    contentCharactersPerLine: Int
) {
    if (rawValue.isNullOrBlank() || footerBitmapAvailable) {
        return
    }

    ReceiptPreviewWrappedText(
        text = rawValue,
        contentCharactersPerLine = contentCharactersPerLine,
        centered = true,
        small = true
    )
    Spacer(modifier = Modifier.height(10.dp))
}

@Composable
private fun ReceiptPreviewWrappedText(
    text: String,
    contentCharactersPerLine: Int,
    centered: Boolean = false,
    bold: Boolean = false,
    small: Boolean = false
) {
    wrapPreviewText(text, contentCharactersPerLine).forEach { line ->
        Text(
            text = line,
            modifier = Modifier.fillMaxWidth(),
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            style = if (small) {
                MaterialTheme.typography.bodySmall
            } else {
                MaterialTheme.typography.bodyMedium
            },
            fontFamily = FontFamily.Monospace,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ReceiptPreviewCenteredLine(
    text: String,
    small: Boolean = false
) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (small) FontWeight.Normal else FontWeight.SemiBold
    )
}

@Composable
private fun ReceiptPreviewItemRow(
    left: String,
    right: String,
    bold: Boolean = false,
    emphasized: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = left,
            modifier = Modifier.weight(1f),
            style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (bold || emphasized) FontWeight.SemiBold else FontWeight.Normal
        )
        Text(
            text = right,
            style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (bold || emphasized) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun ReceiptPreviewSubLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ReceiptPreviewDivider() {
    Text(
        text = "--------------------------------",
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.outline
    )
}

private fun wrapPreviewText(
    text: String,
    maxCharactersPerLine: Int
): List<String> {
    if (text.isBlank()) {
        return listOf("")
    }

    val maxLength = maxCharactersPerLine.coerceAtLeast(10)
    return text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split('\n')
        .flatMap { paragraph ->
            wrapPreviewParagraph(paragraph.trim(), maxLength)
        }
}

private fun wrapPreviewParagraph(
    paragraph: String,
    maxCharactersPerLine: Int
): List<String> {
    if (paragraph.isBlank()) {
        return listOf("")
    }

    val lines = mutableListOf<String>()
    var currentLine = ""

    paragraph.split(Regex("\\s+")).forEach { word ->
        if (currentLine.isEmpty()) {
            currentLine = word
            return@forEach
        }

        val candidate = "$currentLine $word"
        if (candidate.length <= maxCharactersPerLine) {
            currentLine = candidate
        } else {
            lines += currentLine
            currentLine = word
        }
    }

    if (currentLine.isNotEmpty()) {
        lines += currentLine
    }

    return lines
}

private fun decodePreviewBitmap(rawValue: String?): android.graphics.Bitmap? {
    if (rawValue.isNullOrBlank()) {
        return null
    }

    val normalized = rawValue
        .substringAfter("base64,", missingDelimiterValue = rawValue)
        .removePrefix("base64:")
        .trim()

    return runCatching {
        val decoded = Base64.decode(normalized, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
    }.getOrNull()
}
