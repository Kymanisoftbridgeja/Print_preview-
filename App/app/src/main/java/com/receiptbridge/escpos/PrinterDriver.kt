package com.receiptbridge.escpos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.google.zxing.BarcodeFormat
import com.google.gson.Gson
import com.google.zxing.qrcode.QRCodeWriter
import com.receiptbridge.data.AppSettings
import com.receiptbridge.data.ConnectionType
import com.receiptbridge.data.PrintJob
import com.receiptbridge.data.PrinterProfile
import com.receiptbridge.data.resolvedPrintAreaDots
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
import kotlin.math.ceil
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

                // Refresh settings
                settingsRepository.refreshSettings()
                val settings = settingsRepository.settings.value

                val rasterizedPrintData = buildRasterizedReceiptPrintData(
                    payload = payload,
                    profile = profile,
                    settings = settings
                )
                if (rasterizedPrintData != null) {
                    repeat(resolveCopies(job, payload)) {
                        connection.write(rasterizedPrintData)
                    }
                    return@withContext
                }

                val builder = EscPosBuilder().reset()
                
                // Apply Global Header
                settings.globalHeader?.let { header ->
                    if (header.startsWith("base64:")) {
                         val rasterImage = EscPosImageEncoder.decodeBase64Image(
                             base64Data = header,
                             targetWidth = profile.resolvedPrintAreaDots()
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
                val copies = resolveCopies(job, payload)
                repeat(copies) {
                    connection.write(printData)
                }
                
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun resolveCopies(job: PrintJob, payload: PrintPayload): Int {
        return when {
            job.copies > 0 -> job.copies
            payload.copies > 0 -> payload.copies
            else -> 1
        }
    }

    private fun buildRasterizedReceiptPrintData(
        payload: PrintPayload,
        profile: PrinterProfile,
        settings: AppSettings
    ): ByteArray? {
        if (!payload.content.type.equals("escpos_blocks", ignoreCase = true)) {
            return null
        }

        val blocks = payload.content.blocks
        if (blocks.isEmpty() || blocks.any { it.cmd.equals("raw", ignoreCase = true) }) {
            return null
        }

        val receiptBitmap = renderReceiptBitmap(blocks, profile, settings) ?: return null
        return try {
            val rasterImage = EscPosImageEncoder.encodeBitmap(
                bitmap = receiptBitmap,
                targetWidth = profile.resolvedPrintAreaDots(),
                options = EscPosEncodingOptions(
                    grayscaleThreshold = DIRECT_RECEIPT_GRAYSCALE_THRESHOLD,
                    bolden = false,
                    scaleWithFilter = false,
                    allowUpscale = false
                )
            )

            val builder = EscPosBuilder()
                .reset()
                .align("center")

            if (blocks.any { it.cmd.equals("drawer", ignoreCase = true) }) {
                builder.drawerOpen()
            }

            splitRasterBands(rasterImage, DIRECT_RECEIPT_RASTER_BAND_HEIGHT_PX).forEach { band ->
                builder.imageColumnFormat(
                    band.width,
                    band.height,
                    band.rasterBytes
                )
            }

            repeat(blocks.count { it.cmd.equals("beep", ignoreCase = true) }.coerceAtMost(3)) {
                builder.beep()
            }

            val shouldCut = profile.autoCut || blocks.any { it.cmd.equals("cut", ignoreCase = true) }
            if (shouldCut) {
                builder.feed(profile.feedLines)
                builder.cut()
            } else {
                builder.feed(profile.feedLines)
            }

            builder.build()
        } finally {
            receiptBitmap.recycle()
        }
    }

    private fun renderReceiptBitmap(
        blocks: List<PrintBlock>,
        profile: PrinterProfile,
        settings: AppSettings
    ): Bitmap? {
        val targetWidth = profile.resolvedPrintAreaDots()
        val paints = createRasterReceiptPaints(targetWidth)
        val sidePadding = (targetWidth * DIRECT_RECEIPT_SIDE_PADDING_RATIO)
            .roundToInt()
            .coerceIn(DIRECT_RECEIPT_MIN_SIDE_PADDING_PX, max(DIRECT_RECEIPT_MIN_SIDE_PADDING_PX, targetWidth / 5))
        val contentWidth = (targetWidth - (sidePadding * 2))
            .coerceAtLeast((targetWidth * DIRECT_RECEIPT_MIN_CONTENT_RATIO).roundToInt())

        val elements = mutableListOf<RasterReceiptElement>()
        appendConfiguredHeaderOrFooter(
            destination = elements,
            configuredValue = settings.globalHeader,
            paints = paints,
            contentWidth = contentWidth,
            style = ReceiptTextStyle.TITLE
        )

        var currentAlign = ReceiptAlign.LEFT
        for (block in blocks) {
            when (block.cmd.lowercase(Locale.US)) {
                "align" -> {
                    currentAlign = parseReceiptAlign(block.value?.toString())
                }
                "text" -> {
                    appendTextElement(
                        destination = elements,
                        text = block.value?.toString().orEmpty(),
                        align = currentAlign,
                        paint = paints.bodyPaint,
                        style = ReceiptTextStyle.BODY,
                        maxWidth = contentWidth
                    )
                }
                "row2" -> {
                    val rightText = block.right.orEmpty()
                    val rightWidth = ceil(paints.amountPaint.measureText(rightText).toDouble()).toInt()
                    val leftWidth = (contentWidth - rightWidth - paints.rowGap)
                        .coerceAtLeast(contentWidth / 2)
                    val leftLines = wrapReceiptText(
                        text = block.left.orEmpty(),
                        paint = paints.bodyPaint,
                        maxWidth = leftWidth
                    )
                    elements += RasterReceiptElement.Row(
                        leftLines = leftLines,
                        right = rightText
                    )
                }
                "feed" -> {
                    val lines = (block.value as? Number)?.toInt()?.coerceAtLeast(1) ?: 1
                    elements += RasterReceiptElement.Spacer(lines * paints.bodyLineHeight)
                }
                "image" -> {
                    createBitmapElement(
                        base64Data = block.value?.toString().orEmpty(),
                        requestedWidth = block.left?.toIntOrNull(),
                        requestedHeight = block.right?.toIntOrNull(),
                        maxWidth = contentWidth,
                        align = currentAlign
                    )?.let(elements::add)
                }
                "qr" -> {
                    createQrElement(
                        value = block.value?.toString().orEmpty(),
                        sizeHint = block.left?.toIntOrNull(),
                        targetWidth = targetWidth,
                        maxWidth = contentWidth,
                        align = currentAlign
                    )?.let(elements::add)
                }
                "cut", "drawer", "beep", "charset", "codepage" -> {
                    // These are handled outside raster layout or are not needed for raster text rendering.
                }
                else -> {
                    recycleElementBitmaps(elements)
                    return null
                }
            }
        }

        appendConfiguredHeaderOrFooter(
            destination = elements,
            configuredValue = settings.globalFooter,
            paints = paints,
            contentWidth = contentWidth,
            style = ReceiptTextStyle.FOOTER
        )

        if (elements.none { it !is RasterReceiptElement.Spacer }) {
            recycleElementBitmaps(elements)
            return null
        }

        val totalHeight = (
            paints.topPadding +
                elements.sumOf { measureElementHeight(it, paints) } +
                paints.bottomPadding
            ).coerceAtLeast(targetWidth / 2)

        val bitmap = Bitmap.createBitmap(targetWidth, totalHeight, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            var y = paints.topPadding.toFloat()
            elements.forEach { element ->
                y = drawElement(
                    canvas = canvas,
                    element = element,
                    paints = paints,
                    pageWidth = targetWidth,
                    sidePadding = sidePadding,
                    y = y
                )
            }
            return bitmap
        } finally {
            recycleElementBitmaps(elements)
        }
    }

    private fun createRasterReceiptPaints(targetWidth: Int): RasterReceiptPaints {
        val scale = targetWidth / BASE_RECEIPT_RENDER_WIDTH.toFloat()

        fun receiptPaint(
            textSize: Float,
            bold: Boolean,
            align: Paint.Align = Paint.Align.LEFT
        ): Paint {
            return Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                this.textSize = (textSize * scale).coerceAtLeast(16f)
                typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
                textAlign = align
                isLinearText = true
                isSubpixelText = false
                isDither = true
                isFakeBoldText = bold
            }
        }

        val titlePaint = receiptPaint(textSize = 34f, bold = true)
        val bodyPaint = receiptPaint(textSize = 25f, bold = false).apply {
            isFakeBoldText = true
        }
        val amountPaint = receiptPaint(textSize = 27f, bold = true)
        val footerPaint = receiptPaint(textSize = 22f, bold = true)

        return RasterReceiptPaints(
            titlePaint = titlePaint,
            bodyPaint = bodyPaint,
            amountPaint = amountPaint,
            footerPaint = footerPaint,
            titleLineHeight = calculateLineHeight(titlePaint, 1.12f),
            bodyLineHeight = calculateLineHeight(bodyPaint, 1.16f),
            footerLineHeight = calculateLineHeight(footerPaint, 1.14f),
            blockSpacing = (14f * scale).roundToInt().coerceAtLeast(10),
            rowGap = (22f * scale).roundToInt().coerceAtLeast(14),
            topPadding = (18f * scale).roundToInt().coerceAtLeast(12),
            bottomPadding = (22f * scale).roundToInt().coerceAtLeast(14)
        )
    }

    private fun appendConfiguredHeaderOrFooter(
        destination: MutableList<RasterReceiptElement>,
        configuredValue: String?,
        paints: RasterReceiptPaints,
        contentWidth: Int,
        style: ReceiptTextStyle
    ) {
        if (configuredValue.isNullOrBlank()) {
            return
        }

        createBitmapElement(
            base64Data = configuredValue,
            requestedWidth = contentWidth,
            requestedHeight = null,
            maxWidth = contentWidth,
            align = ReceiptAlign.CENTER
        )?.let {
            destination += it
            return
        }

        appendTextElement(
            destination = destination,
            text = configuredValue,
            align = ReceiptAlign.CENTER,
            paint = when (style) {
                ReceiptTextStyle.TITLE -> paints.titlePaint
                ReceiptTextStyle.FOOTER -> paints.footerPaint
                ReceiptTextStyle.BODY -> paints.bodyPaint
            },
            style = style,
            maxWidth = contentWidth
        )
    }

    private fun appendTextElement(
        destination: MutableList<RasterReceiptElement>,
        text: String,
        align: ReceiptAlign,
        paint: Paint,
        style: ReceiptTextStyle,
        maxWidth: Int
    ) {
        val sanitizedText = text.replace("\r\n", "\n").replace('\r', '\n')
        val wrappedLines = sanitizedText
            .split('\n')
            .flatMap { paragraph -> wrapReceiptText(paragraph, paint, maxWidth) }

        if (wrappedLines.isEmpty()) {
            destination += RasterReceiptElement.Spacer(calculateLineHeight(paint, 1.0f))
            return
        }

        destination += RasterReceiptElement.Text(
            lines = wrappedLines,
            align = align,
            style = style
        )
    }

    private fun wrapReceiptText(
        text: String,
        paint: Paint,
        maxWidth: Int
    ): List<String> {
        if (text.isBlank()) {
            return listOf("")
        }

        val lines = mutableListOf<String>()
        var currentLine = ""
        text.trim().split(WHITESPACE_REGEX).forEach { word ->
            if (word.isBlank()) {
                return@forEach
            }

            if (currentLine.isEmpty()) {
                if (paint.measureText(word) <= maxWidth) {
                    currentLine = word
                } else {
                    val splitWordParts = splitLongWord(word, paint, maxWidth)
                    if (splitWordParts.isNotEmpty()) {
                        lines += splitWordParts.dropLast(1)
                        currentLine = splitWordParts.last()
                    }
                }
                return@forEach
            }

            val candidate = "$currentLine $word"
            if (paint.measureText(candidate) <= maxWidth) {
                currentLine = candidate
            } else {
                lines += currentLine
                if (paint.measureText(word) <= maxWidth) {
                    currentLine = word
                } else {
                    val splitWordParts = splitLongWord(word, paint, maxWidth)
                    if (splitWordParts.isNotEmpty()) {
                        lines += splitWordParts.dropLast(1)
                        currentLine = splitWordParts.last()
                    } else {
                        currentLine = word
                    }
                }
            }
        }

        if (currentLine.isNotEmpty()) {
            lines += currentLine
        }

        return lines.ifEmpty { listOf("") }
    }

    private fun splitLongWord(
        word: String,
        paint: Paint,
        maxWidth: Int
    ): List<String> {
        if (word.isEmpty()) {
            return emptyList()
        }

        val parts = mutableListOf<String>()
        var chunk = StringBuilder()
        word.forEach { character ->
            val candidate = buildString {
                append(chunk)
                append(character)
            }
            if (chunk.isNotEmpty() && paint.measureText(candidate) > maxWidth) {
                parts += chunk.toString()
                chunk = StringBuilder().append(character)
            } else {
                chunk.append(character)
            }
        }

        if (chunk.isNotEmpty()) {
            parts += chunk.toString()
        }

        return parts
    }

    private fun createBitmapElement(
        base64Data: String,
        requestedWidth: Int?,
        requestedHeight: Int?,
        maxWidth: Int,
        align: ReceiptAlign
    ): RasterReceiptElement.Image? {
        val bitmap = decodeBitmapFromBase64(base64Data) ?: return null
        val scaledBitmap = scaleBitmapForReceipt(
            bitmap = bitmap,
            targetWidth = (requestedWidth ?: maxWidth).coerceAtMost(maxWidth).coerceAtLeast(1),
            targetHeight = requestedHeight
        )
        return RasterReceiptElement.Image(
            bitmap = scaledBitmap,
            align = align
        )
    }

    private fun decodeBitmapFromBase64(base64Data: String): Bitmap? {
        val normalized = base64Data
            .substringAfter("base64,", missingDelimiterValue = base64Data)
            .removePrefix("base64:")
            .trim()
        val decoded = try {
            android.util.Base64.decode(normalized, android.util.Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
    }

    private fun scaleBitmapForReceipt(
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int?
    ): Bitmap {
        val safeTargetWidth = targetWidth.coerceAtLeast(1)
        val safeTargetHeight = targetHeight?.coerceAtLeast(1)
            ?: ((bitmap.height * (safeTargetWidth.toFloat() / bitmap.width.toFloat())).roundToInt()).coerceAtLeast(1)

        if (bitmap.width == safeTargetWidth && bitmap.height == safeTargetHeight) {
            return bitmap
        }

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, safeTargetWidth, safeTargetHeight, true)
        if (scaledBitmap !== bitmap) {
            bitmap.recycle()
        }
        return scaledBitmap
    }

    private fun createQrElement(
        value: String,
        sizeHint: Int?,
        targetWidth: Int,
        maxWidth: Int,
        align: ReceiptAlign
    ): RasterReceiptElement.Image? {
        if (value.isBlank()) {
            return null
        }

        val scale = targetWidth / BASE_RECEIPT_RENDER_WIDTH.toFloat()
        val requestedSize = sizeHint
            ?.let { (it * 28f * scale).roundToInt() }
            ?: (targetWidth * DIRECT_RECEIPT_DEFAULT_QR_RATIO).roundToInt()
        val qrSize = requestedSize.coerceIn(
            (targetWidth * DIRECT_RECEIPT_MIN_QR_RATIO).roundToInt().coerceAtLeast(96),
            maxWidth
        )

        return try {
            val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, qrSize, qrSize)
            val pixels = IntArray(qrSize * qrSize)
            for (y in 0 until qrSize) {
                for (x in 0 until qrSize) {
                    pixels[(y * qrSize) + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            val bitmap = Bitmap.createBitmap(qrSize, qrSize, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, qrSize, 0, 0, qrSize, qrSize)
            }
            RasterReceiptElement.Image(bitmap = bitmap, align = align)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseReceiptAlign(rawAlign: String?): ReceiptAlign {
        return when (rawAlign?.lowercase(Locale.US)) {
            "center" -> ReceiptAlign.CENTER
            "right" -> ReceiptAlign.RIGHT
            else -> ReceiptAlign.LEFT
        }
    }

    private fun measureElementHeight(
        element: RasterReceiptElement,
        paints: RasterReceiptPaints
    ): Int {
        return when (element) {
            is RasterReceiptElement.Image -> element.bitmap.height + paints.blockSpacing
            is RasterReceiptElement.Row -> {
                val rowLines = max(1, element.leftLines.size)
                (rowLines * paints.bodyLineHeight) + paints.blockSpacing
            }
            is RasterReceiptElement.Spacer -> element.height
            is RasterReceiptElement.Text -> {
                val lineHeight = when (element.style) {
                    ReceiptTextStyle.TITLE -> paints.titleLineHeight
                    ReceiptTextStyle.BODY -> paints.bodyLineHeight
                    ReceiptTextStyle.FOOTER -> paints.footerLineHeight
                }
                (element.lines.size * lineHeight) + paints.blockSpacing
            }
        }
    }

    private fun drawElement(
        canvas: Canvas,
        element: RasterReceiptElement,
        paints: RasterReceiptPaints,
        pageWidth: Int,
        sidePadding: Int,
        y: Float
    ): Float {
        var currentY = y
        return when (element) {
            is RasterReceiptElement.Image -> {
                val x = when (element.align) {
                    ReceiptAlign.LEFT -> sidePadding.toFloat()
                    ReceiptAlign.CENTER -> ((pageWidth - element.bitmap.width) / 2f)
                    ReceiptAlign.RIGHT -> (pageWidth - sidePadding - element.bitmap.width).toFloat()
                }
                canvas.drawBitmap(element.bitmap, x, currentY, null)
                currentY + element.bitmap.height + paints.blockSpacing
            }
            is RasterReceiptElement.Row -> {
                val leftX = sidePadding.toFloat()
                val rightX = (pageWidth - sidePadding).toFloat()
                val bodyPaint = paints.bodyPaint
                val amountPaint = paints.amountPaint

                if (element.leftLines.isEmpty()) {
                    val baseline = currentY - amountPaint.fontMetrics.ascent
                    amountPaint.textAlign = Paint.Align.RIGHT
                    canvas.drawText(element.right, rightX, baseline, amountPaint)
                    currentY += paints.bodyLineHeight
                } else {
                    element.leftLines.forEachIndexed { index, line ->
                        val baseline = currentY - bodyPaint.fontMetrics.ascent
                        bodyPaint.textAlign = Paint.Align.LEFT
                        canvas.drawText(line, leftX, baseline, bodyPaint)
                        if (index == 0 && element.right.isNotBlank()) {
                            amountPaint.textAlign = Paint.Align.RIGHT
                            canvas.drawText(element.right, rightX, baseline, amountPaint)
                        }
                        currentY += paints.bodyLineHeight
                    }
                }
                currentY + paints.blockSpacing
            }
            is RasterReceiptElement.Spacer -> currentY + element.height
            is RasterReceiptElement.Text -> {
                val paint = when (element.style) {
                    ReceiptTextStyle.TITLE -> paints.titlePaint
                    ReceiptTextStyle.BODY -> paints.bodyPaint
                    ReceiptTextStyle.FOOTER -> paints.footerPaint
                }
                val lineHeight = when (element.style) {
                    ReceiptTextStyle.TITLE -> paints.titleLineHeight
                    ReceiptTextStyle.BODY -> paints.bodyLineHeight
                    ReceiptTextStyle.FOOTER -> paints.footerLineHeight
                }
                val x = when (element.align) {
                    ReceiptAlign.LEFT -> sidePadding.toFloat()
                    ReceiptAlign.CENTER -> pageWidth / 2f
                    ReceiptAlign.RIGHT -> (pageWidth - sidePadding).toFloat()
                }
                paint.textAlign = when (element.align) {
                    ReceiptAlign.LEFT -> Paint.Align.LEFT
                    ReceiptAlign.CENTER -> Paint.Align.CENTER
                    ReceiptAlign.RIGHT -> Paint.Align.RIGHT
                }
                element.lines.forEach { line ->
                    val baseline = currentY - paint.fontMetrics.ascent
                    canvas.drawText(line, x, baseline, paint)
                    currentY += lineHeight
                }
                currentY + paints.blockSpacing
            }
        }
    }

    private fun recycleElementBitmaps(elements: List<RasterReceiptElement>) {
        elements.forEach { element ->
            if (element is RasterReceiptElement.Image && !element.bitmap.isRecycled) {
                element.bitmap.recycle()
            }
        }
    }

    private fun calculateLineHeight(
        paint: Paint,
        multiplier: Float
    ): Int {
        val metrics = paint.fontMetrics
        val rawHeight = metrics.descent - metrics.ascent
        return (rawHeight * multiplier).roundToInt().coerceAtLeast(1)
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
                "Paper: ${profile.paperWidthMm} mm / ${profile.resolvedPrintAreaDots()} dots",
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
        val targetWidth = profile.resolvedPrintAreaDots()
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
                    allowUpscale = true
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
                val width = block.left?.toIntOrNull() ?: profile.resolvedPrintAreaDots()
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
        val WHITESPACE_REGEX = "\\s+".toRegex()
        const val BASE_RECEIPT_RENDER_WIDTH = 576
        const val DIRECT_RECEIPT_SIDE_PADDING_RATIO = 0.055f
        const val DIRECT_RECEIPT_MIN_CONTENT_RATIO = 0.72f
        const val DIRECT_RECEIPT_MIN_SIDE_PADDING_PX = 12
        const val DIRECT_RECEIPT_DEFAULT_QR_RATIO = 0.28f
        const val DIRECT_RECEIPT_MIN_QR_RATIO = 0.18f
        const val DIRECT_RECEIPT_GRAYSCALE_THRESHOLD = 212f
        const val DIRECT_RECEIPT_RASTER_BAND_HEIGHT_PX = 240
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

    private data class RasterReceiptPaints(
        val titlePaint: Paint,
        val bodyPaint: Paint,
        val amountPaint: Paint,
        val footerPaint: Paint,
        val titleLineHeight: Int,
        val bodyLineHeight: Int,
        val footerLineHeight: Int,
        val blockSpacing: Int,
        val rowGap: Int,
        val topPadding: Int,
        val bottomPadding: Int
    )

    private sealed class RasterReceiptElement {
        data class Text(
            val lines: List<String>,
            val align: ReceiptAlign,
            val style: ReceiptTextStyle
        ) : RasterReceiptElement()

        data class Row(
            val leftLines: List<String>,
            val right: String
        ) : RasterReceiptElement()

        data class Image(
            val bitmap: Bitmap,
            val align: ReceiptAlign
        ) : RasterReceiptElement()

        data class Spacer(
            val height: Int
        ) : RasterReceiptElement()
    }

    private enum class ReceiptAlign {
        LEFT, CENTER, RIGHT
    }

    private enum class ReceiptTextStyle {
        TITLE, BODY, FOOTER
    }
}
