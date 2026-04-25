package com.receiptbridge.escpos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.gson.Gson
import com.receiptbridge.data.ConnectionType
import com.receiptbridge.data.PrintJob
import com.receiptbridge.data.PrinterProfile
import com.receiptbridge.data.defaultImageWidthForPaperWidthMm
import com.receiptbridge.data.sanitizeSystemPrintContentFillPercent
import com.receiptbridge.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
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
                    if (header.startsWith("base64:")) {
                         val rasterImage = EscPosImageEncoder.decodeBase64Image(
                             base64Data = header,
                             targetWidth = defaultImageWidthForPaperWidthMm(profile.paperWidthMm)
                         )
                         if (rasterImage != null) {
                             builder.align("center").image(
                                 rasterImage.width,
                                 rasterImage.height,
                                 rasterImage.rasterBytes
                             ).newLine()
                         } else {
                             builder.align("center").text(header.removePrefix("base64:")).newLine()
                         }
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
                val copies = when {
                    job.copies > 0 -> job.copies
                    payload.copies > 0 -> payload.copies
                    else -> 1
                }
                repeat(copies) {
                    connection.write(printData)
                }
                
            } finally {
                connection.disconnect()
            }
        }
    }

    suspend fun printPdfDocument(
        documentData: ParcelFileDescriptor,
        profile: PrinterProfile,
        copies: Int = 1
    ) {
        withContext(Dispatchers.IO) {
            settingsRepository.refreshSettings()
            val settings = settingsRepository.settings.value
            val seekablePdfFile = createSeekablePdfCopy(documentData)
            try {
                ParcelFileDescriptor.open(
                    seekablePdfFile,
                    ParcelFileDescriptor.MODE_READ_ONLY
                ).use { parcelFileDescriptor ->
                    PdfRenderer(parcelFileDescriptor).use { renderer ->
                        val connection = createConnection(profile)
                        try {
                            connection.connect()
                            repeat(copies.coerceAtLeast(1)) {
                                connection.write(EscPosBuilder().reset().build())
                                for (pageIndex in 0 until renderer.pageCount) {
                                    renderer.openPage(pageIndex).use { page ->
                                        renderPdfPageStrips(
                                            page = page,
                                            profile = profile,
                                            contentFillPercent = settings.systemPrintContentFillPercent
                                        ).forEach { rasterImage ->
                                            val pageData = EscPosBuilder()
                                                .align("left")
                                                .imageColumnFormat(
                                                    rasterImage.width,
                                                    rasterImage.height,
                                                    rasterImage.rasterBytes
                                                )
                                                .build()
                                            connection.write(pageData)
                                        }
                                        if (pageIndex < renderer.pageCount - 1) {
                                            connection.write(EscPosBuilder().feed(1).build())
                                        }
                                    }
                                }

                                val finalizeBuilder = EscPosBuilder()
                                if (profile.autoCut) {
                                    finalizeBuilder.feed(profile.feedLines).cut()
                                } else {
                                    finalizeBuilder.feed(profile.feedLines)
                                }
                                connection.write(finalizeBuilder.build())
                            }
                        } finally {
                            connection.disconnect()
                        }
                    }
                }
            } finally {
                seekablePdfFile.delete()
            }
        }
    }

    suspend fun printSystemSettingsTest(profile: PrinterProfile) {
        withContext(Dispatchers.IO) {
            val pdfFile = createSystemSettingsTestPdf(profile)
            try {
                ParcelFileDescriptor.open(
                    pdfFile,
                    ParcelFileDescriptor.MODE_READ_ONLY
                ).use { parcelFileDescriptor ->
                    printPdfDocument(
                        documentData = parcelFileDescriptor,
                        profile = profile,
                        copies = 1
                    )
                }
            } finally {
                pdfFile.delete()
            }
        }
    }

    private fun createSeekablePdfCopy(documentData: ParcelFileDescriptor): File {
        val spoolFile = File.createTempFile("receiptbridge-print-", ".pdf", context.cacheDir)
        try {
            documentData.use { sourceDescriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(sourceDescriptor).use { input ->
                    FileOutputStream(spoolFile).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
            }

            if (spoolFile.length() <= 0L) {
                throw IllegalStateException("Android print service delivered an empty PDF document.")
            }

            return spoolFile
        } catch (error: Exception) {
            spoolFile.delete()
            throw error
        }
    }

    private fun createSystemSettingsTestPdf(profile: PrinterProfile): File {
        val pdfFile = File.createTempFile("receiptbridge-settings-test-", ".pdf", context.cacheDir)
        val pageWidth = 1200
        val pageHeight = 2000
        val receiptWidth = 660
        val receiptLeft = (pageWidth - receiptWidth) / 2
        val receiptRight = receiptLeft + receiptWidth
        val document = PdfDocument()

        try {
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas

            val backgroundPaint = Paint().apply { color = Color.WHITE }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(230, 230, 230)
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 8f
            }
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 42f
                typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 28f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                textAlign = Paint.Align.LEFT
            }
            val rightAlignedBodyPaint = Paint(bodyPaint).apply {
                textAlign = Paint.Align.RIGHT
            }
            val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 22f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                textAlign = Paint.Align.LEFT
            }
            val centeredBodyPaint = Paint(bodyPaint).apply {
                textAlign = Paint.Align.CENTER
            }
            val leftAlignedTitlePaint = Paint(titlePaint).apply {
                textAlign = Paint.Align.LEFT
                textSize = 38f
            }
            val rightAlignedTitlePaint = Paint(titlePaint).apply {
                textAlign = Paint.Align.RIGHT
                textSize = 38f
            }

            canvas.drawColor(Color.WHITE)
            canvas.drawRect(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat(), backgroundPaint)
            canvas.drawRect(
                receiptLeft.toFloat(),
                80f,
                receiptRight.toFloat(),
                1600f,
                borderPaint
            )

            val centerX = pageWidth / 2f
            canvas.drawOval(centerX - 130f, 130f, centerX + 130f, 250f, logoPaint)
            canvas.drawText("ReceiptBridge", centerX, 225f, titlePaint)
            canvas.drawText("Width Settings Test", centerX, 315f, centeredBodyPaint)

            var y = 400f
            val leftTextX = receiptLeft + 36f
            val rightTextX = receiptRight - 36f
            val rowStep = 70f

            val lines = listOf(
                "Printer: ${profile.name}",
                "Paper: ${profile.paperWidthMm} mm",
                "Path: Android print-service raster",
                "This test should grow/shrink with the width setting."
            )
            lines.forEach { line ->
                canvas.drawText(line, leftTextX, y, bodyPaint)
                y += 52f
            }

            y += 20f
            canvas.drawLine(leftTextX, y, rightTextX, y, fillPaint)
            y += 60f

            val items = listOf(
                Triple("1  Goldie 18pk Plain Tx", "960.00", "B / Box"),
                Triple("1  Burger Bread", "255.00", "S / Single"),
                Triple("1  Cheese Bread Tx", "124.35", "S / Single"),
                Triple("1  Cinnamon Loaf Tx", "1260.00", "S / Units")
            )

            items.forEach { (label, price, subline) ->
                canvas.drawText(label, leftTextX, y, bodyPaint)
                canvas.drawText(price, rightTextX, y, rightAlignedBodyPaint)
                y += 36f
                canvas.drawText(subline, leftTextX + 32f, y, smallPaint)
                y += rowStep
            }

            y += 12f
            canvas.drawLine(leftTextX, y, rightTextX, y, fillPaint)
            y += 56f
            canvas.drawText("Subtotal", leftTextX, y, bodyPaint)
            canvas.drawText("2,599.35", rightTextX, y, rightAlignedBodyPaint)
            y += 48f
            canvas.drawText("Tax", leftTextX, y, bodyPaint)
            canvas.drawText("389.90", rightTextX, y, rightAlignedBodyPaint)
            y += 56f
            canvas.drawText("Total", leftTextX, y, leftAlignedTitlePaint)
            canvas.drawText("2,989.25", rightTextX, y, rightAlignedTitlePaint)

            y += 110f
            val footerCenterX = pageWidth / 2f
            val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 24f
                typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(
                "Generated ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}",
                footerCenterX,
                y,
                centeredBodyPaint
            )
            y += 46f
            canvas.drawText(
                "Use Settings > Receipt Content Width, then print this again.",
                footerCenterX,
                y,
                stampPaint
            )
            y += 60f
            canvas.drawText(
                "The logo, totals, and side margins should visibly change.",
                footerCenterX,
                y,
                stampPaint
            )

            document.finishPage(page)
            FileOutputStream(pdfFile).use { output ->
                document.writeTo(output)
                output.fd.sync()
            }
            return pdfFile
        } catch (error: Exception) {
            pdfFile.delete()
            throw error
        } finally {
            document.close()
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

    private fun renderPdfPageStrips(
        page: PdfRenderer.Page,
        profile: PrinterProfile,
        contentFillPercent: Int
    ): List<EscPosRasterImage> {
        val targetWidth = defaultImageWidthForPaperWidthMm(profile.paperWidthMm)
        val sanitizedFillPercent = sanitizeSystemPrintContentFillPercent(contentFillPercent)
        val targetContentWidth = ((targetWidth * (sanitizedFillPercent / 100f)).roundToInt())
            .coerceIn(1, targetWidth)
        val renderWidth = targetWidth * SYSTEM_PRINT_RENDER_SCALE_FACTOR
        val scale = renderWidth.toFloat() / page.width.toFloat()
        val renderHeight = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
        return try {
            Canvas(bitmap).drawColor(Color.WHITE)
            val matrix = Matrix().apply {
                postScale(scale, scale)
            }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            val contentBounds = detectContentBounds(bitmap)
            val croppedBitmap = cropToContentBounds(bitmap, contentBounds)
            try {
                val rasterPage = EscPosImageEncoder.encodeBitmap(
                    bitmap = croppedBitmap,
                    targetWidth = targetContentWidth,
                    options = EscPosEncodingOptions(
                        grayscaleThreshold = SYSTEM_PRINT_FIXED_GRAYSCALE_THRESHOLD,
                        bolden = true,
                        scaleWithFilter = true,
                        allowUpscale = false
                    )
                )
                splitRasterBands(
                    centerRasterImage(rasterPage, targetWidth),
                    SYSTEM_PRINT_RASTER_BAND_HEIGHT_PX
                )
            } finally {
                if (croppedBitmap !== bitmap) {
                    croppedBitmap.recycle()
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun splitRasterBands(
        rasterImage: EscPosRasterImage,
        maxBandHeight: Int
    ): List<EscPosRasterImage> {
        if (rasterImage.height <= maxBandHeight) {
            return listOf(rasterImage)
        }

        val widthBytes = (rasterImage.width + 7) / 8
        val bands = mutableListOf<EscPosRasterImage>()
        var top = 0
        while (top < rasterImage.height) {
            val bandHeight = minOf(maxBandHeight, rasterImage.height - top)
            val startIndex = top * widthBytes
            val endIndex = startIndex + (bandHeight * widthBytes)
            bands += EscPosRasterImage(
                width = rasterImage.width,
                height = bandHeight,
                rasterBytes = rasterImage.rasterBytes.copyOfRange(startIndex, endIndex)
            )
            top += bandHeight
        }

        return bands
    }

    private fun centerRasterImage(
        rasterImage: EscPosRasterImage,
        targetWidth: Int
    ): EscPosRasterImage {
        if (rasterImage.width >= targetWidth) {
            return rasterImage
        }

        val sourceWidthBytes = (rasterImage.width + 7) / 8
        val widthBytes = (targetWidth + 7) / 8
        val leftPaddingPixels = ((targetWidth - rasterImage.width) / 2).coerceAtLeast(0)
        val centeredBytes = ByteArray(widthBytes * rasterImage.height)

        for (y in 0 until rasterImage.height) {
            for (x in 0 until rasterImage.width) {
                if (!isRasterPixelBlack(rasterImage.rasterBytes, sourceWidthBytes, x, y, rasterImage.height)) {
                    continue
                }

                setRasterPixelBlack(
                    centeredBytes,
                    widthBytes,
                    targetWidth,
                    rasterImage.height,
                    x + leftPaddingPixels,
                    y
                )
            }
        }

        return EscPosRasterImage(
            width = targetWidth,
            height = rasterImage.height,
            rasterBytes = centeredBytes
        )
    }

    private fun detectContentBounds(bitmap: Bitmap): ContentBounds? {
        val rowBuffer = IntArray(bitmap.width)
        val rowDarkCounts = IntArray(bitmap.height)
        val columnDarkCounts = IntArray(bitmap.width)

        for (y in 0 until bitmap.height) {
            bitmap.getPixels(rowBuffer, 0, bitmap.width, 0, y, bitmap.width, 1)
            var rowDarkCount = 0
            for (x in rowBuffer.indices) {
                if (isDarkContentPixel(rowBuffer[x])) {
                    rowDarkCount++
                    columnDarkCounts[x]++
                }
            }
            rowDarkCounts[y] = rowDarkCount
        }

        val maxRowDarkCount = rowDarkCounts.maxOrNull() ?: 0
        val maxColumnDarkCount = columnDarkCounts.maxOrNull() ?: 0

        val minDarkPixelsPerRow = max(
            4,
            max(
                bitmap.width / CONTENT_ROW_DARK_PIXEL_DIVISOR,
                (maxRowDarkCount * BASE_ROW_ACTIVITY_RATIO).toInt()
            )
        )
        val minDarkPixelsPerColumn = max(
            4,
            max(
                bitmap.height / CONTENT_COLUMN_DARK_PIXEL_DIVISOR,
                (maxColumnDarkCount * BASE_COLUMN_ACTIVITY_RATIO).toInt()
            )
        )

        val top = rowDarkCounts.indexOfFirst { it >= minDarkPixelsPerRow }
        val bottom = rowDarkCounts.indexOfLast { it >= minDarkPixelsPerRow }
        val left = columnDarkCounts.indexOfFirst { it >= minDarkPixelsPerColumn }
        val right = columnDarkCounts.indexOfLast { it >= minDarkPixelsPerColumn }

        if (top == -1 || bottom == -1 || left == -1 || right == -1) {
            return null
        }

        val cropTop = (top - RECEIPT_VERTICAL_TRIM_PADDING_PX).coerceAtLeast(0)
        val cropBottom = (bottom + RECEIPT_VERTICAL_TRIM_PADDING_PX).coerceAtMost(bitmap.height - 1)
        val cropLeft = (left - RECEIPT_HORIZONTAL_TRIM_PADDING_PX).coerceAtLeast(0)
        val cropRight = (right + RECEIPT_HORIZONTAL_TRIM_PADDING_PX).coerceAtMost(bitmap.width - 1)

        return ContentBounds(
            left = cropLeft,
            top = cropTop,
            right = cropRight,
            bottom = cropBottom
        )
    }

    private fun cropToContentBounds(
        bitmap: Bitmap,
        contentBounds: ContentBounds?
    ): Bitmap {
        if (contentBounds == null) {
            return bitmap
        }

        if (
            contentBounds.left == 0 &&
            contentBounds.top == 0 &&
            contentBounds.right == bitmap.width - 1 &&
            contentBounds.bottom == bitmap.height - 1
        ) {
            return bitmap
        }

        return Bitmap.createBitmap(
            bitmap,
            contentBounds.left,
            contentBounds.top,
            contentBounds.width,
            contentBounds.height
        )
    }

    private fun isDarkContentPixel(pixel: Int): Boolean {
        val alpha = (pixel ushr 24) and 0xFF
        if (alpha <= RECEIPT_MIN_ALPHA_THRESHOLD) {
            return false
        }

        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        val grayscale = (red * 0.299f) + (green * 0.587f) + (blue * 0.114f)
        return grayscale < RECEIPT_WHITESPACE_GRAYSCALE_THRESHOLD
    }

    private fun isRasterPixelBlack(
        data: ByteArray,
        widthBytes: Int,
        x: Int,
        y: Int,
        height: Int
    ): Boolean {
        if (x < 0 || y < 0 || y >= height) {
            return false
        }

        val byteIndex = y * widthBytes + (x / 8)
        if (byteIndex !in data.indices) {
            return false
        }

        val mask = 0x80 shr (x % 8)
        return (data[byteIndex].toInt() and mask) != 0
    }

    private fun setRasterPixelBlack(
        data: ByteArray,
        widthBytes: Int,
        width: Int,
        height: Int,
        x: Int,
        y: Int
    ) {
        if (x !in 0 until width || y !in 0 until height) {
            return
        }

        val byteIndex = y * widthBytes + (x / 8)
        if (byteIndex !in data.indices) {
            return
        }

        val mask = 0x80 shr (x % 8)
        data[byteIndex] = (data[byteIndex].toInt() or mask).toByte()
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
                val base64Data = block.value as? String ?: return
                val width = block.left?.toIntOrNull() ?: defaultImageWidthForPaperWidthMm(profile.paperWidthMm)
                val height = block.right?.toIntOrNull()
                val rasterImage = EscPosImageEncoder.decodeBase64Image(base64Data, width, height)

                if (rasterImage != null) {
                    builder.image(
                        rasterImage.width,
                        rasterImage.height,
                        rasterImage.rasterBytes
                    )
                } else {
                    // Backward compatibility for callers already sending packed raster bytes.
                    try {
                        val decoded = android.util.Base64.decode(
                            base64Data.substringAfter("base64,", missingDelimiterValue = base64Data),
                            android.util.Base64.DEFAULT
                        )
                        builder.image(width, height ?: 100, decoded)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
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

    private companion object {
        const val RECEIPT_VERTICAL_TRIM_PADDING_PX = 8
        const val RECEIPT_HORIZONTAL_TRIM_PADDING_PX = 20
        const val RECEIPT_MIN_ALPHA_THRESHOLD = 16
        const val RECEIPT_WHITESPACE_GRAYSCALE_THRESHOLD = 210f
        const val SYSTEM_PRINT_RENDER_SCALE_FACTOR = 4
        const val SYSTEM_PRINT_FIXED_GRAYSCALE_THRESHOLD = 232f
        const val SYSTEM_PRINT_RASTER_BAND_HEIGHT_PX = 240
        const val CONTENT_ROW_DARK_PIXEL_DIVISOR = 250
        const val CONTENT_COLUMN_DARK_PIXEL_DIVISOR = 300
        const val BASE_ROW_ACTIVITY_RATIO = 0.12f
        const val BASE_COLUMN_ACTIVITY_RATIO = 0.02f
    }

    private data class ContentBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int
            get() = right - left + 1

        val height: Int
            get() = bottom - top + 1
    }
}
