package com.receiptbridge.escpos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.gson.Gson
import com.receiptbridge.data.ConnectionType
import com.receiptbridge.data.PrintJob
import com.receiptbridge.data.PrinterProfile
import com.receiptbridge.data.defaultImageWidthForPaperWidthMm
import com.receiptbridge.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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
                                        renderPdfPageStrips(page, profile).forEach { rasterImage ->
                                            val pageData = EscPosBuilder()
                                                .align("center")
                                                .imageColumnFormat(
                                                    rasterImage.width,
                                                    rasterImage.height,
                                                    rasterImage.rasterBytes
                                                )
                                                .newLine()
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
        profile: PrinterProfile
    ): List<EscPosRasterImage> {
        val targetWidth = defaultImageWidthForPaperWidthMm(profile.paperWidthMm)
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
            val trimmedBitmap = contentBounds?.let { bounds ->
                Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width, bounds.height)
            } ?: bitmap
            val targetContentWidth = contentBounds?.let { bounds ->
                ((bounds.width.toFloat() / bitmap.width.toFloat()) * targetWidth)
                    .roundToInt()
                    .coerceIn(1, targetWidth)
            } ?: targetWidth
            try {
                buildRasterBands(trimmedBitmap, targetWidth, targetContentWidth)
            } finally {
                if (trimmedBitmap !== bitmap) {
                    trimmedBitmap.recycle()
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun buildRasterBands(
        bitmap: Bitmap,
        targetWidth: Int,
        targetContentWidth: Int
    ): List<EscPosRasterImage> {
        val safeTargetContentWidth = targetContentWidth.coerceIn(1, targetWidth)
        val scaledHeight = ((bitmap.height * (safeTargetContentWidth.toFloat() / bitmap.width.toFloat()))
            .toInt()
            .coerceAtLeast(1))
        val bandCount = max(1, (scaledHeight + SYSTEM_PRINT_BAND_HEIGHT_PX - 1) / SYSTEM_PRINT_BAND_HEIGHT_PX)
        val renderBandHeight = max(1, bitmap.height / bandCount)
        val bands = mutableListOf<EscPosRasterImage>()

        var top = 0
        while (top < bitmap.height) {
            val bandHeight = minOf(renderBandHeight, bitmap.height - top)
            val bandBitmap = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bandHeight)
            val trimmedBandBitmap = trimVerticalWhitespace(bandBitmap)
            try {
                if (trimmedBandBitmap != null) {
                    val rasterBand = EscPosImageEncoder.encodeBitmap(
                        bitmap = trimmedBandBitmap,
                        targetWidth = safeTargetContentWidth,
                        options = EscPosEncodingOptions(
                            grayscaleThreshold = SYSTEM_PRINT_FIXED_GRAYSCALE_THRESHOLD,
                            bolden = true,
                            scaleWithFilter = true,
                            allowUpscale = false
                        )
                    )
                    bands += centerRasterImage(rasterBand, targetWidth)
                }
            } finally {
                if (trimmedBandBitmap != null && trimmedBandBitmap !== bandBitmap) {
                    trimmedBandBitmap.recycle()
                }
                bandBitmap.recycle()
            }
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
        val targetWidthBytes = (targetWidth + 7) / 8
        val leftPaddingPixels = ((targetWidth - rasterImage.width) / 2).coerceAtLeast(0)
        val centeredBytes = ByteArray(targetWidthBytes * rasterImage.height)

        for (y in 0 until rasterImage.height) {
            for (x in 0 until rasterImage.width) {
                if (!isRasterPixelBlack(rasterImage.rasterBytes, sourceWidthBytes, x, y, rasterImage.height)) {
                    continue
                }

                val centeredX = x + leftPaddingPixels
                setRasterPixelBlack(centeredBytes, targetWidthBytes, targetWidth, rasterImage.height, centeredX, y)
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

    private fun trimVerticalWhitespace(bitmap: Bitmap): Bitmap? {
        val contentBounds = detectContentBounds(bitmap) ?: return null
        if (contentBounds.top == 0 && contentBounds.bottom == bitmap.height - 1) {
            return bitmap
        }

        return Bitmap.createBitmap(bitmap, 0, contentBounds.top, bitmap.width, contentBounds.height)
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
        const val RECEIPT_HORIZONTAL_TRIM_PADDING_PX = 12
        const val RECEIPT_MIN_ALPHA_THRESHOLD = 16
        const val RECEIPT_WHITESPACE_GRAYSCALE_THRESHOLD = 210f
        const val SYSTEM_PRINT_RENDER_SCALE_FACTOR = 4
        const val SYSTEM_PRINT_FIXED_GRAYSCALE_THRESHOLD = 232f
        const val SYSTEM_PRINT_BAND_HEIGHT_PX = 256
        const val CONTENT_ROW_DARK_PIXEL_DIVISOR = 250
        const val CONTENT_COLUMN_DARK_PIXEL_DIVISOR = 300
        const val BASE_ROW_ACTIVITY_RATIO = 0.12f
        const val BASE_COLUMN_ACTIVITY_RATIO = 0.12f
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
