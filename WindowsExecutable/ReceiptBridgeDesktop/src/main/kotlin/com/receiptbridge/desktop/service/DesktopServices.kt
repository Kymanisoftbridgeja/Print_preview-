package com.receiptbridge.desktop.service

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.receiptbridge.desktop.data.DesktopStorage
import com.receiptbridge.desktop.data.JobRepository
import com.receiptbridge.desktop.data.PrinterRepository
import com.receiptbridge.desktop.data.SettingsRepository
import com.receiptbridge.desktop.model.AppSettings
import com.receiptbridge.desktop.model.ConnectionType
import com.receiptbridge.desktop.model.DEFAULT_EXACT_LAYOUT_RENDERED_RECEIPT_FILL_PERCENT
import com.receiptbridge.desktop.model.DEFAULT_SYSTEM_PRINT_CONTENT_FILL_PERCENT
import com.receiptbridge.desktop.model.JobStatus
import com.receiptbridge.desktop.model.OdooReceiptRenderMode
import com.receiptbridge.desktop.model.PrintBlock
import com.receiptbridge.desktop.model.PrintContent
import com.receiptbridge.desktop.model.PrintJob
import com.receiptbridge.desktop.model.PrintPayload
import com.receiptbridge.desktop.model.PrinterProfile
import com.receiptbridge.desktop.model.defaultRenderedReceiptFillPercentFor
import com.receiptbridge.desktop.model.defaultOdooReceiptRenderModeFor
import com.receiptbridge.desktop.model.defaultCharactersPerLineForPrintAreaDots
import com.receiptbridge.desktop.model.defaultPrintAreaDotsForPaperWidthMm
import com.receiptbridge.desktop.model.normalizePaperWidthMm
import com.receiptbridge.desktop.model.resolvedOdooReceiptRenderMode
import com.receiptbridge.desktop.model.resolvedPrintAreaDots
import com.receiptbridge.desktop.model.resolvedRenderedReceiptFillPercent
import com.receiptbridge.desktop.model.resolvedRenderedReceiptSmartFit
import com.receiptbridge.desktop.model.sanitizeKeepHistoryDays
import com.receiptbridge.desktop.model.sanitizePrintAreaDots
import com.receiptbridge.desktop.model.sanitizeRenderedReceiptFillPercent
import com.receiptbridge.desktop.model.sanitizeSystemPrintContentFillPercent
import com.receiptbridge.desktop.model.sanitized
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.charset.Charset
import java.util.Base64
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.print.DocFlavor
import javax.print.PrintService
import javax.print.PrintServiceLookup
import javax.print.SimpleDoc
import javax.print.attribute.HashPrintRequestAttributeSet
import javax.print.attribute.standard.JobName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import kotlin.math.roundToInt

data class PrintJobMetadata(
    val printerProfileId: String?,
    val copies: Int,
    val contentType: String
)

data class WindowsPrinterQueue(
    val name: String,
    val isDefault: Boolean = false,
    val queueName: String? = null,
    val portName: String? = null
)

private data class StructuredReceiptSummaryRow(
    val label: String,
    val amount: String,
    val emphasis: Boolean = false,
    val rightLabel: String = "",
    val rightAmount: String = "",
    val role: String = ""
)

private data class StructuredReceiptFooterSections(
    val summaryRows: List<StructuredReceiptSummaryRow>,
    val footerLines: List<String>
)

object PrintJobFactory {
    fun createFromPayloadJson(payloadJson: String): PrintJob {
        val metadata = extractMetadata(payloadJson)
        return PrintJob(
            printerProfileId = metadata.printerProfileId,
            payloadJson = payloadJson,
            copies = metadata.copies
        )
    }

    fun extractMetadata(payloadJson: String): PrintJobMetadata {
        val root = try {
            JsonParser.parseString(payloadJson)
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid JSON payload: ${error.message}")
        }

        if (!root.isJsonObject) {
            throw IllegalArgumentException("Invalid JSON payload: root must be a JSON object")
        }

        val json = root.asJsonObject
        val contentType = validateRequiredStructure(json)

        return PrintJobMetadata(
            printerProfileId = json.getOptionalString("printer_profile_id"),
            copies = json.getOptionalInt("copies")?.takeIf { it > 0 } ?: 1,
            contentType = contentType
        )
    }

    private fun validateRequiredStructure(json: JsonObject): String {
        val content = json.get("content")
        if (content == null || !content.isJsonObject) {
            throw IllegalArgumentException("Invalid JSON payload: missing content object")
        }

        val contentJson = content.asJsonObject
        val contentType = contentJson.getOptionalString("type")?.lowercase(Locale.US) ?: "escpos_blocks"
        val structuredReceiptNode = contentJson.get("structured_receipt")
        if (structuredReceiptNode != null && structuredReceiptNode.isJsonObject) {
            return "odoo_structured"
        }

        when (contentType) {
            "escpos_blocks" -> {
                val blocks = contentJson.get("blocks")
                if (blocks == null || !blocks.isJsonArray) {
                    throw IllegalArgumentException("Invalid JSON payload: missing content.blocks array")
                }
                return "escpos_blocks"
            }
            "odoo_structured" -> {
                val structuredReceipt = contentJson.get("structured_receipt")
                if (structuredReceipt == null || !structuredReceipt.isJsonObject) {
                    throw IllegalArgumentException("Invalid JSON payload: missing content.structured_receipt object")
                }
                return "odoo_structured"
            }
            "image", "receipt_image", "image_receipt", "rendered_image" -> {
                val image = contentJson.getOptionalString("image")
                if (image.isNullOrBlank()) {
                    throw IllegalArgumentException("Invalid JSON payload: missing content.image")
                }
                return "receipt_image"
            }
            "plain_text", "text", "receipt_text" -> {
                val text = contentJson.getOptionalString("text")
                if (text.isNullOrBlank()) {
                    throw IllegalArgumentException("Invalid JSON payload: missing content.text")
                }
                return "receipt_text"
            }
            "html", "receipt_html", "html_receipt" -> {
                val html = contentJson.getOptionalString("html")
                if (html.isNullOrBlank()) {
                    throw IllegalArgumentException("Invalid JSON payload: missing content.html")
                }
                return "receipt_html"
            }
            else -> {
                throw IllegalArgumentException("Unsupported content.type: $contentType")
            }
        }
    }

    private fun JsonObject.getOptionalString(fieldName: String): String? {
        val value = get(fieldName) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            return null
        }
        return value.asString.trim().ifBlank { null }
    }

    private fun JsonObject.getOptionalInt(fieldName: String): Int? {
        val value = get(fieldName) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
            return null
        }
        return runCatching { value.asInt }.getOrNull()
    }
}

interface PrinterConnection {
    suspend fun connect()
    suspend fun disconnect()
    suspend fun write(data: ByteArray)
    fun isConnected(): Boolean
}

class NetworkConnection(
    private val host: String,
    private val port: Int = 9100,
    private val timeoutMs: Int = 5000
) : PrinterConnection {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null

    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            try {
                socket = Socket()
                socket?.connect(InetSocketAddress(host, port), timeoutMs)
                outputStream = socket?.getOutputStream()
            } catch (error: Exception) {
                disconnect()
                throw error
            }
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                outputStream?.close()
                socket?.close()
            } finally {
                outputStream = null
                socket = null
            }
        }
    }

    override suspend fun write(data: ByteArray) {
        withContext(Dispatchers.IO) {
            if (!isConnected()) {
                throw IllegalStateException("Socket is not connected")
            }
            outputStream?.write(data)
            outputStream?.flush()
        }
    }

    override fun isConnected(): Boolean {
        return socket?.isConnected == true && socket?.isClosed == false
    }
}

class WindowsPrinterQueueConnection(
    private val queueName: String
) : PrinterConnection {
    private var printService: PrintService? = null

    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            val normalizedQueueName = queueName.trim()
            if (normalizedQueueName.isBlank()) {
                throw IllegalArgumentException("USB printer queue name is blank")
            }

            val matchedService = PrintServiceLookup.lookupPrintServices(null, null)
                .firstOrNull { it.name.equals(normalizedQueueName, ignoreCase = true) }
                ?: throw IllegalStateException(
                    "Windows printer queue '$normalizedQueueName' was not found. Make sure the USB printer is installed in Windows."
                )

            printService = matchedService
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            printService = null
        }
    }

    override suspend fun write(data: ByteArray) {
        withContext(Dispatchers.IO) {
            val service = printService ?: throw IllegalStateException("Windows printer queue is not connected")
            val flavor = DocFlavor.BYTE_ARRAY.AUTOSENSE
            if (!service.isDocFlavorSupported(flavor)) {
                throw IllegalStateException(
                    "Windows printer queue '${service.name}' does not accept raw byte jobs. Install or switch to a raw/text-compatible printer driver."
                )
            }

            val attributes = HashPrintRequestAttributeSet().apply {
                add(JobName("ReceiptBridge USB Job", Locale.US))
            }

            service.createPrintJob().print(
                SimpleDoc(data, flavor, null),
                attributes
            )
        }
    }

    override fun isConnected(): Boolean {
        return printService != null
    }
}

class EscPosBuilder {
    private val buffer = ByteArrayOutputStream()
    private var currentCharset: Charset = Charset.forName("UTF-8")

    companion object {
        const val ESC: Byte = 0x1B
        const val GS: Byte = 0x1D
        const val LF: Byte = 0x0A
    }

    fun reset(): EscPosBuilder {
        buffer.write(byteArrayOf(ESC, '@'.code.toByte()))
        return this
    }

    fun text(text: String): EscPosBuilder {
        buffer.write(text.toByteArray(currentCharset))
        return this
    }

    fun setCodePage(page: Int): EscPosBuilder {
        buffer.write(byteArrayOf(ESC, 't'.code.toByte(), page.toByte()))
        return this
    }

    fun setEncoding(charset: String): EscPosBuilder {
        currentCharset = runCatching { Charset.forName(charset) }.getOrDefault(Charset.forName("UTF-8"))
        return this
    }

    fun feed(lines: Int = 1): EscPosBuilder {
        buffer.write(byteArrayOf(ESC, 'd'.code.toByte(), lines.toByte()))
        return this
    }

    fun newLine(): EscPosBuilder {
        buffer.write(LF.toInt())
        return this
    }

    fun align(align: String): EscPosBuilder {
        val value = when (align.lowercase(Locale.US)) {
            "center" -> 1
            "right" -> 2
            else -> 0
        }
        buffer.write(byteArrayOf(ESC, 'a'.code.toByte(), value.toByte()))
        return this
    }

    fun cut(full: Boolean = true): EscPosBuilder {
        buffer.write(byteArrayOf(GS, 'V'.code.toByte(), if (full) 0 else 1))
        return this
    }

    fun qrCode(data: String, size: Int = 3): EscPosBuilder {
        val bytes = data.toByteArray(Charsets.UTF_8)
        val pL = (bytes.size + 3) % 256
        val pH = (bytes.size + 3) / 256

        buffer.write(byteArrayOf(GS, '('.code.toByte(), 'k'.code.toByte(), 4, 0, 49, 65, 50, 0))
        buffer.write(byteArrayOf(GS, '('.code.toByte(), 'k'.code.toByte(), 3, 0, 49, 67, size.toByte()))
        buffer.write(byteArrayOf(GS, '('.code.toByte(), 'k'.code.toByte(), 3, 0, 49, 69, 49))
        buffer.write(byteArrayOf(GS, '('.code.toByte(), 'k'.code.toByte(), pL.toByte(), pH.toByte(), 49, 80, 48))
        buffer.write(bytes)
        buffer.write(byteArrayOf(GS, '('.code.toByte(), 'k'.code.toByte(), 3, 0, 49, 81, 48))
        return this
    }

    fun image(width: Int, height: Int, data: ByteArray): EscPosBuilder {
        val xBytes = (width + 7) / 8
        val xL = xBytes % 256
        val xH = xBytes / 256
        val yL = height % 256
        val yH = height / 256
        buffer.write(byteArrayOf(GS, 'v'.code.toByte(), '0'.code.toByte(), 0, xL.toByte(), xH.toByte(), yL.toByte(), yH.toByte()))
        buffer.write(data)
        return this
    }

    fun drawerOpen(): EscPosBuilder {
        buffer.write(byteArrayOf(ESC, 'p'.code.toByte(), 0, 25, 250.toByte()))
        return this
    }

    fun beep(): EscPosBuilder {
        buffer.write(byteArrayOf(ESC, 'B'.code.toByte(), 2, 1))
        return this
    }

    fun raw(bytes: ByteArray): EscPosBuilder {
        buffer.write(bytes)
        return this
    }

    fun build(): ByteArray {
        return buffer.toByteArray()
    }
}

data class EscPosRasterImage(
    val width: Int,
    val height: Int,
    val rasterBytes: ByteArray
)

data class EscPosEncodingOptions(
    val grayscaleThreshold: Float = 180f,
    val bolden: Boolean = false,
    val scaleWithFilter: Boolean = true,
    val allowUpscale: Boolean = false
)

object EscPosImageEncoder {
    private const val RECEIPT_SECTION_MIN_ASPECT_RATIO = 1.25f
    private const val RECEIPT_SECTION_MAX_COUNT = 16

    fun decodeBase64Image(
        base64Data: String,
        targetWidth: Int,
        targetHeight: Int? = null,
        allowUpscale: Boolean = false,
        smartFit: Boolean = false,
        options: EscPosEncodingOptions = EscPosEncodingOptions(allowUpscale = allowUpscale)
    ): EscPosRasterImage? {
        val normalized = base64Data.substringAfter("base64,", missingDelimiterValue = base64Data)
            .removePrefix("base64:")
            .trim()
        val decoded = runCatching { Base64.getDecoder().decode(normalized) }.getOrNull() ?: return null
        return decodeImageBytes(decoded, targetWidth, targetHeight, allowUpscale, smartFit, options)
    }

    fun decodeBase64ImageBands(
        base64Data: String,
        targetWidth: Int,
        allowUpscale: Boolean = false,
        smartFit: Boolean = false,
        options: EscPosEncodingOptions = EscPosEncodingOptions(allowUpscale = allowUpscale)
    ): List<EscPosRasterImage> {
        val normalized = base64Data.substringAfter("base64,", missingDelimiterValue = base64Data)
            .removePrefix("base64:")
            .trim()
        val decoded = runCatching { Base64.getDecoder().decode(normalized) }.getOrNull() ?: return emptyList()
        return decodeImageBytesAsBands(decoded, targetWidth, allowUpscale, smartFit, options)
    }

    fun decodeImageBytes(
        imageBytes: ByteArray,
        targetWidth: Int,
        targetHeight: Int? = null,
        allowUpscale: Boolean = false,
        smartFit: Boolean = false,
        options: EscPosEncodingOptions = EscPosEncodingOptions(allowUpscale = allowUpscale)
    ): EscPosRasterImage? {
        val image = ImageIO.read(imageBytes.inputStream()) ?: return null
        val preparedImage = prepareReceiptImage(image, smartFit)
        return encodeImage(preparedImage, targetWidth, targetHeight, options.copy(allowUpscale = allowUpscale || options.allowUpscale))
    }

    fun decodeImageBytesAsBands(
        imageBytes: ByteArray,
        targetWidth: Int,
        allowUpscale: Boolean = false,
        smartFit: Boolean = false,
        options: EscPosEncodingOptions = EscPosEncodingOptions(allowUpscale = allowUpscale)
    ): List<EscPosRasterImage> {
        val image = ImageIO.read(imageBytes.inputStream()) ?: return emptyList()
        val preparedImage = prepareReceiptImage(image, smartFit)
        val encodingOptions = options.copy(allowUpscale = allowUpscale || options.allowUpscale)
        val sections = splitReceiptImageIntoBands(preparedImage)
        return sections.map { section ->
            encodeImage(
                image = section,
                targetWidth = targetWidth,
                targetHeight = null,
                options = encodingOptions
            )
        }
    }

    private fun prepareReceiptImage(
        image: BufferedImage,
        smartFit: Boolean
    ): BufferedImage {
        return if (smartFit) cropReceiptImageToContentBounds(image) else image
    }

    private fun cropReceiptImageToContentBounds(image: BufferedImage): BufferedImage {
        val bounds = detectReceiptContentBounds(image) ?: return image
        if (bounds.width <= 0 || bounds.height <= 0) {
            return image
        }

        val paddingX = maxOf(12, (image.width * 0.02f).roundToInt())
        val paddingY = maxOf(12, (image.height * 0.02f).roundToInt())
        val left = (bounds.x - paddingX).coerceAtLeast(0)
        val top = (bounds.y - paddingY).coerceAtLeast(0)
        val right = (bounds.x + bounds.width + paddingX).coerceAtMost(image.width)
        val bottom = (bounds.y + bounds.height + paddingY).coerceAtMost(image.height)
        val croppedWidth = (right - left).coerceAtLeast(1)
        val croppedHeight = (bottom - top).coerceAtLeast(1)

        if (croppedWidth >= image.width && croppedHeight >= image.height) {
            return image
        }

        return image.getSubimage(left, top, croppedWidth, croppedHeight)
    }

    private fun splitReceiptImageIntoBands(image: BufferedImage): List<BufferedImage> {
        if (image.height <= (image.width * RECEIPT_SECTION_MIN_ASPECT_RATIO)) {
            return listOf(image)
        }

        val blankGapThreshold = maxOf(18, (image.height * 0.01f).roundToInt())
        val minSectionHeight = maxOf(40, (image.height * 0.035f).roundToInt())
        val verticalPadding = maxOf(10, (image.height * 0.008f).roundToInt())
        val rowRanges = mutableListOf<IntRange>()

        var sectionStart = -1
        var lastContentRow = -1
        for (y in 0 until image.height) {
            val hasContent = rowContainsReceiptContent(image, y)
            if (hasContent) {
                if (sectionStart < 0) {
                    sectionStart = y
                }
                lastContentRow = y
                continue
            }

            if (sectionStart >= 0 && lastContentRow >= 0 && (y - lastContentRow) >= blankGapThreshold) {
                rowRanges += sectionStart..lastContentRow
                sectionStart = -1
                lastContentRow = -1
            }
        }

        if (sectionStart >= 0 && lastContentRow >= 0) {
            rowRanges += sectionStart..lastContentRow
        }

        if (rowRanges.size < 2 || rowRanges.size > RECEIPT_SECTION_MAX_COUNT) {
            return listOf(image)
        }

        val mergedRanges = mutableListOf<IntRange>()
        rowRanges.forEach { range ->
            if (mergedRanges.isEmpty()) {
                mergedRanges += range
                return@forEach
            }

            val currentHeight = range.last - range.first + 1
            if (currentHeight < minSectionHeight) {
                val previous = mergedRanges.removeLast()
                mergedRanges += previous.first..range.last
            } else {
                mergedRanges += range
            }
        }

        val bands = mergedRanges.mapNotNull { range ->
            val top = (range.first - verticalPadding).coerceAtLeast(0)
            val bottomExclusive = (range.last + verticalPadding + 1).coerceAtMost(image.height)
            val height = (bottomExclusive - top).coerceAtLeast(1)
            val section = image.getSubimage(0, top, image.width, height)
            cropReceiptImageToContentBounds(section).takeIf { it.width > 0 && it.height > 0 }
        }

        return bands.ifEmpty { listOf(image) }
    }

    private fun rowContainsReceiptContent(
        image: BufferedImage,
        rowIndex: Int
    ): Boolean {
        for (x in 0 until image.width) {
            if (isReceiptContentPixel(image.getRGB(x, rowIndex))) {
                return true
            }
        }
        return false
    }

    private fun detectReceiptContentBounds(image: BufferedImage): Rectangle? {
        var left = image.width
        var top = image.height
        var right = -1
        var bottom = -1

        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (isReceiptContentPixel(image.getRGB(x, y))) {
                    if (x < left) left = x
                    if (y < top) top = y
                    if (x > right) right = x
                    if (y > bottom) bottom = y
                }
            }
        }

        if (right < left || bottom < top) {
            return null
        }

        return Rectangle(left, top, (right - left) + 1, (bottom - top) + 1)
    }

    private fun isReceiptContentPixel(pixel: Int): Boolean {
        val alpha = (pixel ushr 24) and 0xFF
        if (alpha < 18) {
            return false
        }

        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF

        val maxChannel = maxOf(red, green, blue)
        val minChannel = minOf(red, green, blue)
        val isNearWhite = red >= 248 && green >= 248 && blue >= 248
        val hasColorVariance = (maxChannel - minChannel) >= 6

        return !isNearWhite || hasColorVariance
    }

    private fun encodeImage(
        image: BufferedImage,
        targetWidth: Int,
        targetHeight: Int?,
        options: EscPosEncodingOptions
    ): EscPosRasterImage {
        val safeTargetWidth = targetWidth.coerceAtLeast(1)
        val desiredWidth = if (options.allowUpscale) {
            safeTargetWidth
        } else {
            image.width.coerceAtMost(safeTargetWidth)
        }
        val desiredHeight = targetHeight?.coerceAtLeast(1)
            ?: ((image.height * (desiredWidth.toFloat() / image.width.toFloat())).roundToInt()).coerceAtLeast(1)

        val scaledImage = if (desiredWidth == image.width && desiredHeight == image.height) {
            image
        } else {
            val scaled = BufferedImage(desiredWidth, desiredHeight, BufferedImage.TYPE_INT_ARGB)
            val graphics = scaled.createGraphics()
            graphics.applyDesktopHints()
            val scaleHint = if (options.scaleWithFilter) Image.SCALE_SMOOTH else Image.SCALE_DEFAULT
            graphics.drawImage(image.getScaledInstance(desiredWidth, desiredHeight, scaleHint), 0, 0, null)
            graphics.dispose()
            scaled
        }

        val widthBytes = (scaledImage.width + 7) / 8
        val raster = ByteArray(widthBytes * scaledImage.height)

        for (y in 0 until scaledImage.height) {
            for (x in 0 until scaledImage.width) {
                val pixel = scaledImage.getRGB(x, y)
                val alpha = (pixel ushr 24) and 0xFF
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                val grayscale = (red * 0.299f) + (green * 0.587f) + (blue * 0.114f)
                if (alpha > 127 && grayscale < options.grayscaleThreshold) {
                    raster.setBlackPixel(widthBytes, scaledImage.width, scaledImage.height, x, y)
                    if (options.bolden) {
                        raster.setBlackPixel(widthBytes, scaledImage.width, scaledImage.height, x + 1, y)
                        raster.setBlackPixel(widthBytes, scaledImage.width, scaledImage.height, x, y + 1)
                    }
                }
            }
        }

        return EscPosRasterImage(
            width = scaledImage.width,
            height = scaledImage.height,
            rasterBytes = raster
        )
    }

    private fun ByteArray.setBlackPixel(
        widthBytes: Int,
        width: Int,
        height: Int,
        x: Int,
        y: Int
    ) {
        if (x !in 0 until width || y !in 0 until height) {
            return
        }
        val index = y * widthBytes + (x / 8)
        this[index] = (this[index].toInt() or (0x80 shr (x % 8))).toByte()
    }

    private fun Graphics2D.applyDesktopHints() {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    }
}

class PrinterDriver(
    private val settingsRepository: SettingsRepository,
    private val bridgeEventLog: BridgeEventLog
) {
    private val gson: Gson = GsonBuilder().create()

    suspend fun print(job: PrintJob, profile: PrinterProfile) {
        try {
            withContext(Dispatchers.IO) {
            val payload = try {
                gson.fromJson(job.payloadJson, PrintPayload::class.java)
            } catch (error: Exception) {
                throw IllegalArgumentException("Invalid JSON payload: ${error.message}")
            }

            val connection = createConnection(profile)
            try {
                connection.connect()
                val settings = settingsRepository.settings.value
                val builder = EscPosBuilder().reset().align("left")
                val odooRenderMode = profile.resolvedOdooReceiptRenderMode()
                val contentType = when {
                    payload.content.structuredReceipt != null &&
                        odooRenderMode == OdooReceiptRenderMode.EXACT_LAYOUT &&
                        !payload.content.image.isNullOrBlank() -> {
                        "receipt_image"
                    }
                    payload.content.structuredReceipt != null -> {
                        "odoo_structured"
                    }
                    else -> {
                        payload.content.type.lowercase(Locale.US)
                    }
                }
                val qrBarcodeDetected = payload.content.structuredReceipt?.let(::structuredReceiptHasQrOrBarcode) == true ||
                    !payload.content.image.isNullOrBlank()
                bridgeEventLog.record(
                    source = "printer",
                    level = BridgeEventLevel.INFO,
                    message = "Print job received: ${job.id}; type: ${describePrintPayloadForLog(job.payloadJson, contentType)}; printer selected: ${profile.name}; QR/barcode image detected: $qrBarcodeDetected."
                )

                appendConfiguredHeader(builder, settings, profile)
                when (contentType) {
                    "escpos_blocks" -> {
                        payload.content.blocks.forEach { block ->
                            processBlock(builder, block, profile)
                        }
                    }
                    "odoo_structured" -> {
                        appendStructuredOdooReceipt(
                            builder = builder,
                            content = payload.content,
                            profile = profile,
                            settings = settings
                        )
                    }
                    "image", "receipt_image", "image_receipt", "rendered_image" -> {
                        appendRenderedReceiptImage(
                            builder = builder,
                            imageBase64 = payload.content.image.orEmpty(),
                            profile = profile,
                            settings = settings,
                            renderedImageMeta = payload.content.renderedImageMeta
                        )
                    }
                    "plain_text", "text", "receipt_text" -> {
                        appendPlainTextReceipt(
                            builder = builder,
                            text = payload.content.text.orEmpty(),
                            profile = profile
                        )
                    }
                    "html", "receipt_html", "html_receipt" -> {
                        appendPlainTextReceipt(
                            builder = builder,
                            text = extractReceiptTextFromHtml(payload.content.html.orEmpty()),
                            profile = profile
                        )
                    }
                    else -> {
                        throw IllegalArgumentException("Unsupported content.type: ${payload.content.type}")
                    }
                }
                appendConfiguredFooter(builder, settings)

                if (profile.autoCut) {
                    builder.feed(profile.feedLines)
                    builder.cut()
                }

                val data = builder.build()
                repeat(resolveCopies(job, payload)) {
                    connection.write(data)
                }
                bridgeEventLog.record(
                    source = "printer",
                    level = BridgeEventLevel.INFO,
                    message = "Print success: ${job.id} sent to ${profile.name} (${profile.connectionType})."
                )
            } finally {
                connection.disconnect()
            }
            }
        } catch (error: Exception) {
            bridgeEventLog.record(
                source = "printer",
                level = BridgeEventLevel.ERROR,
                message = "Print failure: ${job.id}; Windows printing returned: ${error.message ?: "Unknown error"}"
            )
            throw error
        }
    }

    private fun resolveCopies(job: PrintJob, payload: PrintPayload): Int {
        return when {
            job.copies > 0 -> job.copies
            payload.copies > 0 -> payload.copies
            else -> 1
        }
    }

    private fun describePrintPayloadForLog(
        payloadJson: String,
        resolvedContentType: String
    ): String {
        val root = runCatching { JsonParser.parseString(payloadJson).asJsonObject }.getOrNull()
        val printType = root?.getOptionalString("print_type").orEmpty()
        val documentType = root?.getOptionalString("document_type").orEmpty()
        val orderName = root?.getOptionalString("order_name").orEmpty()
        return listOf(printType, documentType, resolvedContentType, orderName)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" / ")
            .ifBlank { resolvedContentType }
    }

    private fun structuredReceiptHasQrOrBarcode(structuredReceipt: JsonObject): Boolean {
        return !structuredReceipt.getOptionalString("qr_value").isNullOrBlank() ||
            !structuredReceipt.getOptionalString("qr_image").isNullOrBlank() ||
            !structuredReceipt.getOptionalString("barcode_value").isNullOrBlank() ||
            !structuredReceipt.getOptionalString("barcode_image").isNullOrBlank()
    }

    private fun appendConfiguredHeader(
        builder: EscPosBuilder,
        settings: AppSettings,
        profile: PrinterProfile
    ) {
        val header = settings.globalHeader?.trim().orEmpty()
        if (header.isBlank()) {
            return
        }

        val image = EscPosImageEncoder.decodeBase64Image(header, profile.resolvedPrintAreaDots())
        if (image != null) {
            builder.align("center").image(image.width, image.height, image.rasterBytes).newLine()
        } else {
            builder.align("center").text(header).newLine()
        }
    }

    private fun appendConfiguredFooter(
        builder: EscPosBuilder,
        settings: AppSettings
    ) {
        val footer = settings.globalFooter?.trim().orEmpty()
        if (footer.isBlank()) {
            return
        }

        builder.newLine().align("center").text(footer).newLine()
    }

    private fun appendRenderedReceiptImage(
        builder: EscPosBuilder,
        imageBase64: String,
        profile: PrinterProfile,
        settings: AppSettings,
        renderedImageMeta: JsonObject? = null
    ) {
        val targetWidth = resolveRenderedReceiptTargetWidth(profile, settings, renderedImageMeta)
        val images = EscPosImageEncoder.decodeBase64ImageBands(
            base64Data = imageBase64,
            targetWidth = targetWidth,
            allowUpscale = true,
            smartFit = resolveRenderedReceiptSmartFit(profile, renderedImageMeta),
            options = EscPosEncodingOptions(
                grayscaleThreshold = 212f,
                bolden = true,
                scaleWithFilter = true,
                allowUpscale = true
            )
        )
        if (images.isEmpty()) {
            throw IllegalArgumentException("Receipt image could not be decoded")
        }

        builder.align("center")
        images.forEach { image ->
            builder.image(image.width, image.height, image.rasterBytes).newLine()
        }
        builder.align("left")
    }

    private fun resolveRenderedReceiptTargetWidth(
        profile: PrinterProfile,
        settings: AppSettings,
        renderedImageMeta: JsonObject? = null
    ): Int {
        val maxWidth = profile.resolvedPrintAreaDots()
        val configuredFillPercent = profile.resolvedRenderedReceiptFillPercent(settings.systemPrintContentFillPercent)
        val captureTarget = renderedImageMeta?.getOptionalString("capture_target").orEmpty()
        val receiptContentCapture =
            renderedImageMeta?.getOptionalBoolean("receipt_content_capture") == true ||
                captureTarget.equals("pos-receipt", ignoreCase = true)
        val effectiveFillPercent = when {
            receiptContentCapture && configuredFillPercent == DEFAULT_SYSTEM_PRINT_CONTENT_FILL_PERCENT -> {
                DEFAULT_EXACT_LAYOUT_RENDERED_RECEIPT_FILL_PERCENT
            }
            else -> configuredFillPercent
        }
        return ((maxWidth * (effectiveFillPercent / 100f)).roundToInt()).coerceIn(1, maxWidth)
    }

    private fun resolveRenderedReceiptSmartFit(
        profile: PrinterProfile,
        renderedImageMeta: JsonObject?
    ): Boolean {
        if (renderedImageMeta == null) {
            return profile.resolvedRenderedReceiptSmartFit()
        }

        val receiptContentCapture = renderedImageMeta.getOptionalBoolean("receipt_content_capture")
        val captureTarget = renderedImageMeta.getOptionalString("capture_target").orEmpty()
        return when {
            receiptContentCapture -> profile.resolvedRenderedReceiptSmartFit()
            captureTarget.equals("pos-receipt", ignoreCase = true) -> profile.resolvedRenderedReceiptSmartFit()
            else -> profile.resolvedRenderedReceiptSmartFit()
        }
    }

    private fun appendStructuredOdooReceipt(
        builder: EscPosBuilder,
        content: PrintContent,
        profile: PrinterProfile,
        settings: AppSettings
    ) {
        val structuredReceipt = content.structuredReceipt
        if (structuredReceipt == null) {
            if (!content.image.isNullOrBlank()) {
                appendRenderedReceiptImage(
                    builder = builder,
                    imageBase64 = content.image,
                    profile = profile,
                    settings = settings,
                    renderedImageMeta = content.renderedImageMeta
                )
                return
            }
            throw IllegalArgumentException("Structured Odoo receipt data is missing")
        }

        val structuredBlocks = buildStructuredOdooBlocks(structuredReceipt, profile)
        val rasterBands = renderReceiptBlocksToBands(structuredBlocks, profile)
        if (rasterBands.isNotEmpty()) {
            builder.align("center")
            rasterBands.forEach { image ->
                builder.image(image.width, image.height, image.rasterBytes).newLine()
            }
            builder.align("left")
            return
        }

        structuredBlocks.forEach { block ->
            processBlock(builder, block, profile)
        }
    }

    private fun buildStructuredOdooBlocks(
        structuredReceipt: JsonObject,
        profile: PrinterProfile
    ): List<PrintBlock> {
        val blocks = mutableListOf<PrintBlock>()
        val compactUsbLayout = usesCompactUsbReceiptLayout(profile)
        val logoImage = structuredReceipt.getOptionalString("logo_image")
        val qrImage = structuredReceipt.getOptionalString("qr_image")
        val qrValue = structuredReceipt.getOptionalString("qr_value")
        val barcodeImage = structuredReceipt.getOptionalString("barcode_image")
        val barcodeValue = structuredReceipt.getOptionalString("barcode_value")
        val companyName = structuredReceipt.getOptionalString("company_name").orEmpty()
        val date = structuredReceipt.getOptionalString("date").orEmpty()
        val cashier = structuredReceipt.getOptionalString("cashier").orEmpty()
        val documentTitle = structuredReceipt.getOptionalString("document_title").orEmpty()
        val signatureLabel = structuredReceipt.getOptionalString("signature_label").orEmpty()
        val logoWidth = (
            profile.resolvedPrintAreaDots() * if (compactUsbLayout) 0.31f else 0.46f
            ).roundToInt().coerceAtLeast(if (compactUsbLayout) 96 else 140)
        val qrWidth = (
            profile.resolvedPrintAreaDots() * if (compactUsbLayout) 0.34f else 0.40f
            ).roundToInt().coerceAtLeast(if (compactUsbLayout) 140 else 176)
            .coerceAtMost(profile.resolvedPrintAreaDots())
        val barcodeWidth = (
            profile.resolvedPrintAreaDots() * if (compactUsbLayout) 0.76f else 0.82f
            ).roundToInt().coerceAtLeast(if (compactUsbLayout) 260 else 360)
            .coerceAtMost(profile.resolvedPrintAreaDots())
        val supplementalLines = structuredReceipt.getOptionalArray("footer_lines")
            ?.let { footerLines ->
                buildList<String> {
                    footerLines.forEachJsonString { line ->
                        val normalizedLine = line.normalizeReceiptPrinterText().trim()
                        if (normalizedLine.isNotBlank() && none { existing -> existing.equals(normalizedLine, ignoreCase = true) }) {
                            add(normalizedLine)
                        }
                    }
                }
            }
            .orEmpty()
        val footerSections = extractStructuredReceiptFooterSections(supplementalLines)
        val footerNoticeLines = footerSections.footerLines
        val summaryRows = buildList {
            structuredReceipt.getOptionalArray("summary")?.forEachJsonObject { row ->
                val label = row.getOptionalString("label").orEmpty().normalizeReceiptPrinterText().trim()
                val amount = row.getOptionalString("amount").orEmpty().normalizeReceiptPrinterText().trim()
                val rightLabel = row.getOptionalString("right_label").orEmpty().normalizeReceiptPrinterText().trim()
                val rightAmount = row.getOptionalString("right_amount").orEmpty().normalizeReceiptPrinterText().trim()
                if (label.isNotBlank() && amount.isNotBlank()) {
                    add(
                        StructuredReceiptSummaryRow(
                            label = label,
                            amount = amount,
                            emphasis = row.getOptionalBoolean("emphasis"),
                            rightLabel = rightLabel,
                            rightAmount = rightAmount,
                            role = row.getOptionalString("role").orEmpty().normalizeReceiptPrinterText().trim()
                        )
                    )
                }
            }
        }.toMutableList().apply {
            footerSections.summaryRows.forEach { row ->
                if (none { existing ->
                        existing.label.equals(row.label, ignoreCase = true) &&
                            existing.amount.equals(row.amount, ignoreCase = true)
                    }
                ) {
                    add(row)
                }
            }
        }

        blocks += printBlock("align", "center")
        if (!logoImage.isNullOrBlank()) {
            blocks += printBlock("image", logoImage, left = logoWidth.toString())
        }
        if (companyName.isNotBlank()) {
            blocks += printBlock("text", companyName)
        }
        if (!logoImage.isNullOrBlank() || companyName.isNotBlank()) {
            blocks += printBlock("feed", 1)
        }

        if (date.isNotBlank()) {
            blocks += printBlock("text", date)
        }
        if (cashier.isNotBlank()) {
            blocks += printBlock("text", cashier)
        }
        if (documentTitle.isNotBlank()) {
            blocks += printBlock("feed", 1)
            blocks += printBlock("align", "center")
            blocks += printBlock("subtitle", documentTitle)
        }

        val lines = structuredReceipt.getOptionalArray("lines")
        if (lines?.size() ?: 0 > 0) {
            blocks += printBlock("feed", 1)
            blocks += printBlock("align", "left")
            lines?.forEachJsonObject { line ->
                val qty = line.getOptionalString("qty").orEmpty()
                val name = line.getOptionalString("name").orEmpty()
                val amount = line.getOptionalString("amount").orEmpty()
                if (qty.isNotBlank() || name.isNotBlank() || amount.isNotBlank()) {
                    blocks += printBlock("itemrow", name, left = qty, right = amount)
                }

                line.getOptionalString("subline")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { blocks += printBlock("text", "  $it") }
                line.getOptionalArray("details")?.forEachJsonString { detail ->
                    val normalizedDetail = detail.normalizeReceiptPrinterText().trim()
                    if (normalizedDetail.isNotBlank()) {
                        blocks += printBlock("text", "   - $normalizedDetail")
                    }
                }
                line.getOptionalString("note")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { blocks += printBlock("text", "   - Note: $it") }
            }
        }

        if (summaryRows.isNotEmpty()) {
            blocks += printBlock("feed", 1)
            summaryRows.forEach { row ->
                val leftText = listOf(row.label, row.amount)
                    .filter { it.isNotBlank() }
                    .joinToString(": ")
                val rightText = listOf(row.rightLabel, row.rightAmount)
                    .filter { it.isNotBlank() }
                    .joinToString(": ")
                blocks += printBlock("row2", null, left = leftText, right = rightText)
            }
        }

        if (!qrValue.isNullOrBlank()) {
            blocks += printBlock("feed", 1)
            blocks += printBlock("align", "center")
            blocks += printBlock("qr", qrValue, left = qrWidth.toString())
        } else if (!qrImage.isNullOrBlank()) {
            blocks += printBlock("feed", 1)
            blocks += printBlock("align", "center")
            blocks += printBlock("image", qrImage, left = qrWidth.toString())
        }

        if (!barcodeImage.isNullOrBlank()) {
            blocks += printBlock("feed", 1)
            blocks += printBlock("align", "center")
            blocks += printBlock("image", barcodeImage, left = barcodeWidth.toString())
        } else if (!barcodeValue.isNullOrBlank()) {
            blocks += printBlock("feed", 1)
            blocks += printBlock("align", "center")
            blocks += printBlock("text", barcodeValue)
        }

        if (footerNoticeLines.isNotEmpty()) {
            blocks += printBlock("feed", 1)
            blocks += printBlock("align", "center")
            footerNoticeLines.forEach { line ->
                if (line.isNotBlank()) {
                    blocks += printBlock("text", line)
                }
            }
        }

        if (signatureLabel.isNotBlank()) {
            blocks += printBlock("feed", if (compactUsbLayout) 2 else 3)
            blocks += printBlock("align", "center")
            blocks += printBlock("text", signatureLabel)
        }

        blocks += printBlock("align", "left")
        return blocks
    }

    private fun extractStructuredReceiptFooterSections(
        supplementalLines: List<String>
    ): StructuredReceiptFooterSections {
        if (supplementalLines.isEmpty()) {
            return StructuredReceiptFooterSections(
                summaryRows = emptyList(),
                footerLines = emptyList()
            )
        }

        val summaryRows = mutableListOf<StructuredReceiptSummaryRow>()
        var lastTotalAmount = ""
        var index = 0

        while (index < supplementalLines.size) {
            val line = supplementalLines[index].normalizeReceiptPrinterText().trim()
            if (line.isBlank()) {
                index += 1
                continue
            }

            val nextLine = supplementalLines
                .getOrNull(index + 1)
                ?.normalizeReceiptPrinterText()
                ?.trim()
                .orEmpty()

            if (
                summaryRows.none { it.label.equals("Subtotal", ignoreCase = true) } &&
                looksLikeStructuredAmountOnlyLine(line) &&
                (nextLine.isBlank() || looksLikeStructuredSummaryLabel(nextLine) || looksLikeStructuredFooterBoundary(nextLine))
            ) {
                val amount = extractStructuredTrailingAmount(line)
                if (amount.isNotBlank()) {
                    summaryRows += StructuredReceiptSummaryRow(
                        label = "Subtotal",
                        amount = amount,
                        emphasis = false
                    )
                    index += 1
                    continue
                }
            }

            val parsedInlineRow = parseStructuredSummaryRow(line)
            if (parsedInlineRow != null) {
                summaryRows += parsedInlineRow
                if (parsedInlineRow.emphasis || parsedInlineRow.label.equals("Total", ignoreCase = true)) {
                    lastTotalAmount = parsedInlineRow.amount
                }
                index += 1
                continue
            }

            if (looksLikeStructuredSummaryLabel(line)) {
                val amountFromNextLine = if (looksLikeStructuredAmountOnlyLine(nextLine)) {
                    extractStructuredTrailingAmount(nextLine)
                } else {
                    ""
                }
                val fallbackAmount = if (
                    amountFromNextLine.isBlank() &&
                    looksLikeStructuredPaymentLabel(line) &&
                    lastTotalAmount.isNotBlank()
                ) {
                    lastTotalAmount
                } else {
                    amountFromNextLine
                }

                if (fallbackAmount.isNotBlank()) {
                    val normalizedLabel = line.normalizeReceiptPrinterText().trim().trimEnd(':').trim()
                    summaryRows += StructuredReceiptSummaryRow(
                        label = normalizedLabel,
                        amount = fallbackAmount,
                        emphasis = normalizedLabel.equals("Total", ignoreCase = true)
                    )
                    if (normalizedLabel.equals("Total", ignoreCase = true)) {
                        lastTotalAmount = fallbackAmount
                    }
                    index += if (amountFromNextLine.isNotBlank()) 2 else 1
                    continue
                }
            }

            break
        }

        return StructuredReceiptFooterSections(
            summaryRows = summaryRows,
            footerLines = supplementalLines.drop(index)
        )
    }

    private fun parseStructuredSummaryRow(line: String): StructuredReceiptSummaryRow? {
        val normalizedLine = line.normalizeReceiptPrinterText().trim()
        if (normalizedLine.isBlank() || !looksLikeStructuredSummaryLabel(normalizedLine)) {
            return null
        }

        val amount = extractStructuredTrailingAmount(normalizedLine)
        if (amount.isBlank()) {
            return null
        }

        val label = normalizedLine
            .substring(0, normalizedLine.length - amount.length)
            .trim()
            .trimEnd(':')
            .trim()
        if (label.isBlank()) {
            return null
        }

        return StructuredReceiptSummaryRow(
            label = label,
            amount = amount,
            emphasis = label.equals("Total", ignoreCase = true)
        )
    }

    private fun extractStructuredTrailingAmount(line: String): String {
        val normalizedLine = line.normalizeReceiptPrinterText().trim()
        if (normalizedLine.isBlank()) {
            return ""
        }

        val match = Regex("\\$?\\s*-?\\d[\\d,]*(?:\\.\\d{1,2})?\\s*\\$?\\s*$")
            .find(normalizedLine)
            ?: return ""
        return match.value.trim()
    }

    private fun looksLikeStructuredAmountOnlyLine(line: String): Boolean {
        val normalizedLine = line.normalizeReceiptPrinterText().trim()
        if (normalizedLine.isBlank()) {
            return false
        }
        val amount = extractStructuredTrailingAmount(normalizedLine)
        return amount.isNotBlank() && normalizedLine == amount
    }

    private fun looksLikeStructuredSummaryLabel(line: String): Boolean {
        val normalizedLine = line.normalizeReceiptPrinterText().trim()
        if (normalizedLine.isBlank()) {
            return false
        }
        return Regex(
            "^(subtotal|total|tax\\b|vat\\b|gst\\b|gct\\b|change\\b|rounding\\b|discounts?\\b|cash\\b|card\\b|visa\\b|master\\s*card\\b|mastercard\\b|amex\\b|debit\\b|credit\\b|payment\\b|tender\\b|balance\\b|tip\\b|service\\b|ja\\s+tax\\b)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(normalizedLine)
    }

    private fun looksLikeStructuredPaymentLabel(line: String): Boolean {
        val normalizedLine = line.normalizeReceiptPrinterText().trim()
        if (normalizedLine.isBlank()) {
            return false
        }
        return Regex(
            "^(cash\\b|card\\b|visa\\b|master\\s*card\\b|mastercard\\b|amex\\b|debit\\b|credit\\b|payment\\b|tender\\b|balance\\b)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(normalizedLine)
    }

    private fun looksLikeStructuredFooterBoundary(line: String): Boolean {
        val normalizedLine = line.normalizeReceiptPrinterText().trim()
        if (normalizedLine.isBlank()) {
            return false
        }
        if (
            Regex("^(bus|table|guest|gct|tel|phone|powered by|email|website)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(normalizedLine)
        ) {
            return true
        }
        return normalizedLine.contains(',') || Regex("\\(\\d{3}\\)").containsMatchIn(normalizedLine)
    }

    private fun printBlock(
        cmd: String,
        value: Any? = null,
        left: String? = null,
        right: String? = null
    ): PrintBlock {
        return PrintBlock(
            cmd = cmd,
            value = value,
            left = left,
            right = right
        )
    }

    private fun JsonObject.getOptionalArray(fieldName: String): JsonArray? {
        val value = get(fieldName) ?: return null
        return if (value.isJsonArray) value.asJsonArray else null
    }

    private fun JsonObject.getOptionalString(fieldName: String): String? {
        val value = get(fieldName) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            return null
        }
        return value.asString.trim().ifBlank { null }
    }

    private fun JsonObject.getOptionalBoolean(fieldName: String): Boolean {
        val value = get(fieldName) ?: return false
        return value.isJsonPrimitive && value.asJsonPrimitive.isBoolean && value.asBoolean
    }

    private fun JsonArray.forEachJsonObject(action: (JsonObject) -> Unit) {
        forEach { element ->
            if (element != null && element.isJsonObject) {
                action(element.asJsonObject)
            }
        }
    }

    private fun JsonArray.forEachJsonString(action: (String) -> Unit) {
        forEach { element ->
            if (element != null && element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                action(element.asString.trim())
            }
        }
    }

    private fun appendPlainTextReceipt(
        builder: EscPosBuilder,
        text: String,
        profile: PrinterProfile
    ) {
        val normalizedText = text
            .normalizeReceiptPrinterText()
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        if (normalizedText.isBlank()) {
            throw IllegalArgumentException("Receipt text is empty after normalization")
        }

        normalizedText.lines().forEach { line ->
            wrapReceiptLine(line, profile.charactersPerLine).forEach { wrappedLine ->
                builder.text(wrappedLine).newLine()
            }
        }
    }

    private fun wrapReceiptLine(
        line: String,
        charactersPerLine: Int
    ): List<String> {
        val sanitizedLine = line.trimEnd()
        if (sanitizedLine.isBlank()) {
            return listOf("")
        }

        if (sanitizedLine.length <= charactersPerLine) {
            return listOf(sanitizedLine)
        }

        val words = sanitizedLine.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
        if (words.isEmpty()) {
            return listOf("")
        }

        val wrappedLines = mutableListOf<String>()
        var currentLine = ""
        words.forEach { word ->
            val candidate = if (currentLine.isBlank()) word else "$currentLine $word"
            if (candidate.length <= charactersPerLine) {
                currentLine = candidate
            } else {
                if (currentLine.isNotBlank()) {
                    wrappedLines += currentLine
                }

                if (word.length <= charactersPerLine) {
                    currentLine = word
                } else {
                    val chunks = word.chunked(charactersPerLine)
                    wrappedLines += chunks.dropLast(1)
                    currentLine = chunks.last()
                }
            }
        }

        if (currentLine.isNotBlank()) {
            wrappedLines += currentLine
        }

        return wrappedLines.ifEmpty { listOf("") }
    }

    private fun appendEscPosTwoColumnRow(
        builder: EscPosBuilder,
        left: String,
        right: String,
        charactersPerLine: Int
    ) {
        val width = charactersPerLine.coerceAtLeast(24)
        val normalizedLeft = left.normalizeReceiptPrinterText().trim()
        val normalizedRight = right.normalizeReceiptPrinterText().trim()
        if (normalizedRight.isBlank()) {
            wrapReceiptLine(normalizedLeft, width).forEach { line ->
                builder.text(line).newLine()
            }
            return
        }

        val leftWidth = (width - normalizedRight.length - 1).coerceAtLeast(width / 2)
        val leftLines = wrapReceiptLine(normalizedLeft, leftWidth)
        builder.text(formatEscPosTwoColumnLine(leftLines.firstOrNull().orEmpty(), normalizedRight, width)).newLine()
        leftLines.drop(1).forEach { line ->
            builder.text(line).newLine()
        }
    }

    private fun appendEscPosItemRow(
        builder: EscPosBuilder,
        qty: String,
        name: String,
        amount: String,
        charactersPerLine: Int
    ) {
        val width = charactersPerLine.coerceAtLeast(24)
        val qtyText = qty.normalizeReceiptPrinterText().trim()
        val nameText = name.normalizeReceiptPrinterText().trim()
        val amountText = amount.normalizeReceiptPrinterText().trim()
        val qtyWidth = if (qtyText.isBlank()) 0 else maxOf(2, qtyText.length)
        val qtyGap = if (qtyText.isBlank()) 0 else 2
        val amountGap = if (amountText.isBlank()) 0 else 1
        val nameWidth = (width - qtyWidth - qtyGap - amountText.length - amountGap).coerceAtLeast(8)
        val nameLines = wrapReceiptLine(nameText, nameWidth)
        val firstName = nameLines.firstOrNull().orEmpty()
        val firstLeft = buildString {
            if (qtyWidth > 0) {
                append(qtyText.padEnd(qtyWidth))
                append(" ".repeat(qtyGap))
            }
            append(firstName)
        }

        builder.text(formatEscPosTwoColumnLine(firstLeft, amountText, width)).newLine()

        val continuationPrefix = " ".repeat(qtyWidth + qtyGap)
        nameLines.drop(1).forEach { line ->
            builder.text((continuationPrefix + line).trimEnd()).newLine()
        }
    }

    private fun formatEscPosTwoColumnLine(left: String, right: String, charactersPerLine: Int): String {
        val normalizedLeft = left.normalizeReceiptPrinterText().trimEnd()
        val normalizedRight = right.normalizeReceiptPrinterText().trim()
        if (normalizedRight.isBlank()) {
            return normalizedLeft
        }

        val total = normalizedLeft.length + normalizedRight.length
        return if (total >= charactersPerLine) {
            "$normalizedLeft $normalizedRight".trim()
        } else {
            normalizedLeft + " ".repeat(charactersPerLine - total) + normalizedRight
        }
    }

    private fun extractReceiptTextFromHtml(html: String): String {
        if (html.isBlank()) {
            return ""
        }

        val document = Jsoup.parseBodyFragment(html)
        document.outputSettings(Document.OutputSettings().prettyPrint(false))
        document.select("script,style,noscript").remove()

        val lines = mutableListOf<String>()
        document.body().childNodes().forEach { node ->
            when (node) {
                is TextNode -> {
                    val text = node.text().normalizeWhitespace()
                    if (text.isNotBlank()) {
                        lines += text
                    }
                }
                is Element -> {
                    lines += extractLinesFromElement(node)
                }
            }
        }

        return lines
            .map { it.normalizeWhitespace() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    private fun extractLinesFromElement(element: Element): List<String> {
        if (element.tagName() == "table") {
            return element.select("tr").flatMap(::extractLinesFromTableRow)
        }

        if (element.tagName() == "tr") {
            return extractLinesFromTableRow(element)
        }

        val childLines = mutableListOf<String>()
        element.childNodes().forEach { child ->
            when (child) {
                is TextNode -> {
                    val text = child.text().normalizeWhitespace()
                    if (text.isNotBlank()) {
                        childLines += text
                    }
                }
                is Element -> {
                    if (child.tagName() == "br") {
                        childLines += ""
                    } else {
                        childLines += extractLinesFromElement(child)
                    }
                }
            }
        }

        if (childLines.isNotEmpty()) {
            return childLines
        }

        val ownText = element.text().normalizeWhitespace()
        return if (ownText.isBlank()) emptyList() else listOf(ownText)
    }

    private fun extractLinesFromTableRow(row: Element): List<String> {
        val cells = row.select("th,td")
            .map { it.text().normalizeWhitespace() }
            .filter { it.isNotBlank() }

        if (cells.isEmpty()) {
            return emptyList()
        }

        return when {
            cells.size == 1 -> listOf(cells[0])
            cells.size == 2 -> listOf("${cells[0]}    ${cells[1]}")
            else -> listOf(cells.joinToString(" | "))
        }
    }

    private fun processBlock(
        builder: EscPosBuilder,
        block: PrintBlock,
        profile: PrinterProfile
    ) {
        when (block.cmd.lowercase(Locale.US)) {
            "text" -> {
                builder.text(block.value?.toString().orEmpty()).newLine()
            }
            "title", "subtitle" -> {
                builder.text(block.value?.toString().orEmpty()).newLine()
            }
            "align" -> {
                builder.align(block.value?.toString().orEmpty())
            }
            "feed" -> {
                val lines = (block.value as? Number)?.toInt() ?: block.value?.toString()?.toIntOrNull() ?: 1
                builder.feed(lines.coerceAtLeast(1))
            }
            "cut" -> {
                builder.cut((block.value?.toString() ?: "full").equals("full", ignoreCase = true))
            }
            "charset" -> {
                builder.setEncoding(block.value?.toString() ?: "UTF-8")
            }
            "codepage" -> {
                val page = (block.value as? Number)?.toInt() ?: block.value?.toString()?.toIntOrNull() ?: 0
                builder.setCodePage(page)
            }
            "row2" -> {
                val left = block.left.orEmpty()
                val right = block.right.orEmpty()
                appendEscPosTwoColumnRow(builder, left, right, profile.charactersPerLine)
            }
            "itemrow" -> {
                val qty = block.left.orEmpty()
                val name = block.value?.toString().orEmpty()
                val amount = block.right.orEmpty()
                appendEscPosItemRow(builder, qty, name, amount, profile.charactersPerLine)
            }
            "qr" -> {
                val value = block.value?.toString().orEmpty()
                val size = block.left?.toIntOrNull()?.coerceIn(1, 16) ?: 4
                builder.qrCode(value, size)
            }
            "image" -> {
                val width = block.left?.toIntOrNull() ?: profile.resolvedPrintAreaDots()
                val height = block.right?.toIntOrNull()
                EscPosImageEncoder.decodeBase64Image(block.value?.toString().orEmpty(), width, height)?.let { image ->
                    builder.image(image.width, image.height, image.rasterBytes)
                }
            }
            "drawer" -> {
                builder.drawerOpen()
            }
            "beep" -> {
                builder.beep()
            }
            "raw" -> {
                builder.raw(hexToByteArray(block.value?.toString().orEmpty()))
            }
        }
    }

    private fun hexToByteArray(hex: String): ByteArray {
        val sanitized = hex.replace(" ", "")
        if (sanitized.length % 2 != 0) {
            return ByteArray(0)
        }
        return ByteArray(sanitized.length / 2) { index ->
            ((Character.digit(sanitized[index * 2], 16) shl 4) + Character.digit(sanitized[index * 2 + 1], 16)).toByte()
        }
    }

    private fun looksLikeCompanyDetailLine(
        line: String,
        companyName: String
    ): Boolean {
        val normalized = line.normalizeReceiptPrinterText().trim()
        if (normalized.isBlank()) {
            return false
        }

        if (companyName.isNotBlank() && normalized.equals(companyName, ignoreCase = true)) {
            return true
        }

        return normalized.contains('@') ||
            normalized.contains("www", ignoreCase = true) ||
            normalized.contains("http://", ignoreCase = true) ||
            normalized.contains("https://", ignoreCase = true) ||
            normalized.count(Char::isDigit) >= 6 ||
            normalized.contains(',')
    }

    private fun renderReceiptBlocksToBands(
        blocks: List<PrintBlock>,
        profile: PrinterProfile
    ): List<EscPosRasterImage> {
        if (blocks.isEmpty()) {
            return emptyList()
        }

        val compactUsbLayout = usesCompactUsbReceiptLayout(profile)
        val targetWidth = profile.resolvedPrintAreaDots().coerceAtLeast(320)
        val sidePadding = (targetWidth * if (compactUsbLayout) 0.052f else 0.040f)
            .roundToInt()
            .coerceIn(
                if (compactUsbLayout) 14 else 12,
                maxOf(if (compactUsbLayout) 14 else 12, targetWidth / if (compactUsbLayout) 4 else 5)
            )
        val contentWidth = (targetWidth - (sidePadding * 2))
            .coerceAtLeast((targetWidth * if (compactUsbLayout) 0.68f else 0.72f).roundToInt())

        val measureCanvas = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        val measureGraphics = measureCanvas.createGraphics()

        try {
            applyReceiptRasterHints(measureGraphics)
            val layout = createReceiptRasterLayout(
                measureGraphics = measureGraphics,
                targetWidth = targetWidth,
                compactUsbLayout = compactUsbLayout
            )
            val elements = mutableListOf<ReceiptRasterElement>()
            var currentAlign = ReceiptRasterAlign.LEFT

            blocks.forEach { block ->
                when (block.cmd.lowercase(Locale.US)) {
                    "align" -> {
                        currentAlign = parseReceiptRasterAlign(block.value?.toString())
                    }
                    "title" -> {
                        appendReceiptRasterTextElement(
                            destination = elements,
                            text = block.value?.toString().orEmpty(),
                            align = currentAlign,
                            font = layout.titleFont,
                            lineHeight = layout.titleLineHeight,
                            maxWidth = contentWidth,
                            measureGraphics = measureGraphics
                        )
                    }
                    "subtitle" -> {
                        appendReceiptRasterTextElement(
                            destination = elements,
                            text = block.value?.toString().orEmpty(),
                            align = currentAlign,
                            font = layout.footerFont,
                            lineHeight = layout.footerLineHeight,
                            maxWidth = contentWidth,
                            measureGraphics = measureGraphics
                        )
                    }
                    "text" -> {
                        val text = block.value?.toString().orEmpty()
                        val resolvedStyle = resolveReceiptRasterTextStyle(text, currentAlign, layout)
                        appendReceiptRasterTextElement(
                            destination = elements,
                            text = text,
                            align = currentAlign,
                            font = resolvedStyle.font,
                            lineHeight = resolvedStyle.lineHeight,
                            maxWidth = contentWidth,
                            measureGraphics = measureGraphics
                        )
                    }
                    "row2" -> {
                        val rightText = block.right.orEmpty()
                        val rightWidth = measureTextWidth(measureGraphics, layout.amountFont, rightText)
                        val leftWidth = (contentWidth - rightWidth - layout.rowGap)
                            .coerceAtLeast(contentWidth / 2)
                        val leftLines = wrapReceiptTextByWidth(
                            text = block.left.orEmpty(),
                            font = layout.bodyFont,
                            maxWidth = leftWidth,
                            measureGraphics = measureGraphics
                        )
                        elements += ReceiptRasterElement.Row(
                            leftLines = leftLines,
                            right = rightText,
                            leftFont = layout.bodyFont,
                            rightFont = layout.amountFont,
                            leftLineHeight = layout.bodyLineHeight,
                            rightLineHeight = layout.amountLineHeight
                        )
                    }
                    "itemrow" -> {
                        val qtyText = block.left.orEmpty()
                        val nameText = block.value?.toString().orEmpty()
                        val rightText = block.right.orEmpty()
                        val rightWidth = measureTextWidth(measureGraphics, layout.amountFont, rightText)
                        val qtyWidth = if (qtyText.isBlank()) {
                            0
                        } else {
                            maxOf(
                                measureTextWidth(measureGraphics, layout.bodyFont, qtyText),
                                (contentWidth * 0.08f).roundToInt()
                            )
                        }
                        val qtyGap = if (qtyText.isBlank()) {
                            0
                        } else {
                            (layout.rowGap * 0.65f).roundToInt().coerceAtLeast(6)
                        }
                        val amountGap = if (rightText.isBlank()) 0 else layout.rowGap
                        val maximumNameWidth = (contentWidth - qtyWidth - qtyGap - amountGap)
                            .coerceAtLeast(1)
                        val minimumNameWidth = minOf(
                            maximumNameWidth,
                            (contentWidth * 0.30f).roundToInt().coerceAtLeast(1)
                        )
                        val nameWidth = (
                            contentWidth - qtyWidth - qtyGap - rightWidth - amountGap
                            )
                            .coerceAtLeast(minimumNameWidth)
                            .coerceAtMost(maximumNameWidth)
                        val nameLines = wrapReceiptTextByWidth(
                            text = nameText,
                            font = layout.bodyFont,
                            maxWidth = nameWidth,
                            measureGraphics = measureGraphics
                        )
                        elements += ReceiptRasterElement.ItemRow(
                            qty = qtyText,
                            nameLines = nameLines,
                            right = rightText,
                            qtyFont = layout.bodyFont,
                            nameFont = layout.bodyFont,
                            rightFont = layout.amountFont,
                            qtyLineHeight = layout.bodyLineHeight,
                            nameLineHeight = layout.bodyLineHeight,
                            rightLineHeight = layout.amountLineHeight,
                            qtyWidth = qtyWidth,
                            qtyGap = qtyGap
                        )
                    }
                    "feed" -> {
                        val lines = (block.value as? Number)?.toInt()
                            ?: block.value?.toString()?.toIntOrNull()
                            ?: 1
                        elements += ReceiptRasterElement.Spacer(lines.coerceAtLeast(1) * layout.bodyLineHeight)
                    }
                    "image" -> {
                        createReceiptRasterImageElement(
                            base64Data = block.value?.toString().orEmpty(),
                            requestedWidth = block.left?.toIntOrNull(),
                            requestedHeight = block.right?.toIntOrNull(),
                            maxWidth = contentWidth,
                            align = currentAlign
                        )?.let(elements::add)
                    }
                    "qr" -> {
                        createReceiptRasterQrElement(
                            value = block.value?.toString().orEmpty(),
                            requestedSize = block.left?.toIntOrNull(),
                            maxWidth = contentWidth,
                            align = currentAlign
                        )?.let(elements::add)
                    }
                    "cut", "drawer", "beep", "charset", "codepage", "raw" -> {
                        // These affect transport behavior, not the raster receipt layout.
                    }
                    else -> return emptyList()
                }
            }

            if (elements.none { it !is ReceiptRasterElement.Spacer }) {
                return emptyList()
            }

            val totalHeight = (
                layout.topPadding +
                    elements.sumOf { element -> measureReceiptRasterElementHeight(element, layout) } +
                    layout.bottomPadding
                ).coerceAtLeast(targetWidth / 2)

            val canvas = BufferedImage(targetWidth, totalHeight, BufferedImage.TYPE_INT_RGB)
            val graphics = canvas.createGraphics()
            try {
                applyReceiptRasterHints(graphics)
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, targetWidth, totalHeight)

                var currentY = layout.topPadding
                elements.forEach { element ->
                    currentY = drawReceiptRasterElement(
                        graphics = graphics,
                        element = element,
                        layout = layout,
                        pageWidth = targetWidth,
                        sidePadding = sidePadding,
                        currentY = currentY
                    )
                }
            } finally {
                graphics.dispose()
            }

            val output = ByteArrayOutputStream()
            ImageIO.write(canvas, "png", output)
            return EscPosImageEncoder.decodeImageBytesAsBands(
                imageBytes = output.toByteArray(),
                targetWidth = targetWidth,
                allowUpscale = true,
                smartFit = false,
                options = EscPosEncodingOptions(
                    grayscaleThreshold = 212f,
                    bolden = false,
                    scaleWithFilter = false,
                    allowUpscale = true
                )
            )
        } finally {
            measureGraphics.dispose()
        }
    }

    private fun createReceiptRasterLayout(
        measureGraphics: Graphics2D,
        targetWidth: Int,
        compactUsbLayout: Boolean
    ): ReceiptRasterLayout {
        val scale = targetWidth / 576f

        fun receiptFont(pointSize: Float, bold: Boolean): Font {
            val minimumPointSize = if (compactUsbLayout) 10f else 12f
            val scaledSize = (pointSize * scale).coerceAtLeast(minimumPointSize).roundToInt()
            return Font("SansSerif", if (bold) Font.BOLD else Font.PLAIN, scaledSize)
        }

        val titleFont = receiptFont(pointSize = if (compactUsbLayout) 19f else 24f, bold = true)
        val bodyFont = receiptFont(pointSize = if (compactUsbLayout) 15f else 20f, bold = true)
        val amountFont = receiptFont(pointSize = if (compactUsbLayout) 15f else 20f, bold = true)
        val footerFont = receiptFont(pointSize = if (compactUsbLayout) 12f else 16f, bold = true)
        val smallFont = receiptFont(pointSize = if (compactUsbLayout) 10.5f else 13f, bold = false)
        val titleLineMultiplier = if (compactUsbLayout) 1.0f else 1.04f
        val bodyLineMultiplier = if (compactUsbLayout) 1.01f else 1.04f
        val amountLineMultiplier = if (compactUsbLayout) 1.01f else 1.04f
        val footerLineMultiplier = if (compactUsbLayout) 1.0f else 1.03f
        val smallLineMultiplier = if (compactUsbLayout) 1.0f else 1.02f

        return ReceiptRasterLayout(
            titleFont = titleFont,
            bodyFont = bodyFont,
            amountFont = amountFont,
            footerFont = footerFont,
            smallFont = smallFont,
            titleLineHeight = calculateReceiptRasterLineHeight(measureGraphics, titleFont, titleLineMultiplier),
            bodyLineHeight = calculateReceiptRasterLineHeight(measureGraphics, bodyFont, bodyLineMultiplier),
            amountLineHeight = calculateReceiptRasterLineHeight(measureGraphics, amountFont, amountLineMultiplier),
            footerLineHeight = calculateReceiptRasterLineHeight(measureGraphics, footerFont, footerLineMultiplier),
            smallLineHeight = calculateReceiptRasterLineHeight(measureGraphics, smallFont, smallLineMultiplier),
            blockSpacing = ((if (compactUsbLayout) 4f else 6f) * scale).roundToInt().coerceAtLeast(if (compactUsbLayout) 3 else 5),
            imageSpacing = ((if (compactUsbLayout) 10f else 16f) * scale).roundToInt().coerceAtLeast(if (compactUsbLayout) 8 else 12),
            rowGap = ((if (compactUsbLayout) 7f else 10f) * scale).roundToInt().coerceAtLeast(if (compactUsbLayout) 5 else 8),
            topPadding = ((if (compactUsbLayout) 7f else 10f) * scale).roundToInt().coerceAtLeast(if (compactUsbLayout) 5 else 8),
            bottomPadding = ((if (compactUsbLayout) 8f else 12f) * scale).roundToInt().coerceAtLeast(if (compactUsbLayout) 6 else 10)
        )
    }

    private fun usesCompactUsbReceiptLayout(profile: PrinterProfile): Boolean {
        return profile.connectionType == ConnectionType.USB &&
            (
                profile.paperWidthMm <= 58 ||
                    profile.resolvedPrintAreaDots() <= 384
                )
    }

    private fun calculateReceiptRasterLineHeight(
        measureGraphics: Graphics2D,
        font: Font,
        multiplier: Float
    ): Int {
        val metrics = measureGraphics.getFontMetrics(font)
        return (metrics.height * multiplier).roundToInt().coerceAtLeast(metrics.height)
    }

    private fun appendReceiptRasterTextElement(
        destination: MutableList<ReceiptRasterElement>,
        text: String,
        align: ReceiptRasterAlign,
        font: Font,
        lineHeight: Int,
        maxWidth: Int,
        measureGraphics: Graphics2D
    ) {
        val normalizedText = text
            .normalizeReceiptPrinterText()
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        val wrappedLines = normalizedText
            .split('\n')
            .flatMap { paragraph ->
                wrapReceiptTextByWidth(
                    text = paragraph,
                    font = font,
                    maxWidth = maxWidth,
                    measureGraphics = measureGraphics
                )
            }

        if (wrappedLines.isEmpty()) {
            destination += ReceiptRasterElement.Spacer(lineHeight)
            return
        }

        destination += ReceiptRasterElement.Text(
            lines = wrappedLines,
            align = align,
            font = font,
            lineHeight = lineHeight
        )
    }

    private fun wrapReceiptTextByWidth(
        text: String,
        font: Font,
        maxWidth: Int,
        measureGraphics: Graphics2D
    ): List<String> {
        if (text.isBlank()) {
            return listOf("")
        }

        val words = text.trim().split(WHITESPACE_REGEX).filter { it.isNotBlank() }
        if (words.isEmpty()) {
            return listOf("")
        }

        val wrappedLines = mutableListOf<String>()
        var currentLine = ""

        words.forEach { word ->
            val candidate = if (currentLine.isBlank()) word else "$currentLine $word"
            if (measureTextWidth(measureGraphics, font, candidate) <= maxWidth) {
                currentLine = candidate
            } else {
                if (currentLine.isNotBlank()) {
                    wrappedLines += currentLine
                }

                if (measureTextWidth(measureGraphics, font, word) <= maxWidth) {
                    currentLine = word
                } else {
                    val chunks = splitWordByWidth(word, font, maxWidth, measureGraphics)
                    if (chunks.isNotEmpty()) {
                        wrappedLines += chunks.dropLast(1)
                        currentLine = chunks.last()
                    } else {
                        currentLine = word
                    }
                }
            }
        }

        if (currentLine.isNotBlank()) {
            wrappedLines += currentLine
        }

        return wrappedLines.ifEmpty { listOf("") }
    }

    private fun splitWordByWidth(
        word: String,
        font: Font,
        maxWidth: Int,
        measureGraphics: Graphics2D
    ): List<String> {
        if (word.isBlank()) {
            return emptyList()
        }

        val chunks = mutableListOf<String>()
        var current = StringBuilder()

        word.forEach { character ->
            val candidate = buildString {
                append(current)
                append(character)
            }

            if (current.isNotEmpty() && measureTextWidth(measureGraphics, font, candidate) > maxWidth) {
                chunks += current.toString()
                current = StringBuilder().append(character)
            } else {
                current.append(character)
            }
        }

        if (current.isNotEmpty()) {
            chunks += current.toString()
        }

        return chunks
    }

    private fun resolveReceiptRasterTextStyle(
        text: String,
        align: ReceiptRasterAlign,
        layout: ReceiptRasterLayout
    ): ReceiptRasterTextStyle {
        val normalized = text.normalizeReceiptPrinterText().trim()
        return when {
            normalized.contains("customer signature", ignoreCase = true) -> {
                ReceiptRasterTextStyle(layout.footerFont, layout.footerLineHeight)
            }
            normalized.contains("powered by odoo", ignoreCase = true) -> {
                ReceiptRasterTextStyle(layout.smallFont, layout.smallLineHeight)
            }
            text.startsWith("  ") -> {
                ReceiptRasterTextStyle(layout.smallFont, layout.smallLineHeight)
            }
            align == ReceiptRasterAlign.CENTER &&
                (normalized.startsWith("invoice ", ignoreCase = true) ||
                    normalized.contains("served by:", ignoreCase = true) ||
                    normalized.contains("am", ignoreCase = true) ||
                    normalized.contains("pm", ignoreCase = true)) -> {
                ReceiptRasterTextStyle(layout.smallFont, layout.smallLineHeight)
            }
            align == ReceiptRasterAlign.CENTER && normalized.length <= 24 -> {
                ReceiptRasterTextStyle(layout.footerFont, layout.footerLineHeight)
            }
            else -> {
                ReceiptRasterTextStyle(layout.bodyFont, layout.bodyLineHeight)
            }
        }
    }

    private fun measureTextWidth(
        measureGraphics: Graphics2D,
        font: Font,
        text: String
    ): Int {
        return measureGraphics.getFontMetrics(font).stringWidth(text)
    }

    private fun createReceiptRasterImageElement(
        base64Data: String,
        requestedWidth: Int?,
        requestedHeight: Int?,
        maxWidth: Int,
        align: ReceiptRasterAlign
    ): ReceiptRasterElement.Image? {
        val sourceImage = decodeBufferedReceiptImage(base64Data) ?: return null
        val targetWidth = (requestedWidth ?: maxWidth).coerceIn(1, maxWidth)
        val targetHeight = requestedHeight?.coerceAtLeast(1)
            ?: ((sourceImage.height * (targetWidth.toFloat() / sourceImage.width.toFloat())).roundToInt()).coerceAtLeast(1)
        val sourceRatio = sourceImage.width.toFloat() / sourceImage.height.toFloat().coerceAtLeast(1f)
        val looksLikeBarcodeOrQr = sourceRatio in 0.72f..1.28f || sourceRatio >= 1.8f

        val scaledImage = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = scaledImage.createGraphics()
        try {
            if (looksLikeBarcodeOrQr) {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
            } else {
                applyReceiptRasterHints(graphics)
            }
            graphics.drawImage(sourceImage, 0, 0, targetWidth, targetHeight, null)
        } finally {
            graphics.dispose()
        }

        return ReceiptRasterElement.Image(
            image = scaledImage,
            align = align
        )
    }

    private fun createReceiptRasterQrElement(
        value: String,
        requestedSize: Int?,
        maxWidth: Int,
        align: ReceiptRasterAlign
    ): ReceiptRasterElement.Image? {
        val qrValue = value.trim()
        if (qrValue.isBlank()) {
            return null
        }

        val size = (requestedSize ?: maxOf(128, (maxWidth * 0.28f).roundToInt()))
            .coerceIn(96, maxWidth)
        val matrix = runCatching {
            QRCodeWriter().encode(qrValue, BarcodeFormat.QR_CODE, size, size)
        }.getOrNull() ?: return null

        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until size) {
            for (x in 0 until size) {
                image.setRGB(x, y, if (matrix[x, y]) Color.BLACK.rgb else Color.WHITE.rgb)
            }
        }

        return ReceiptRasterElement.Image(
            image = image,
            align = align
        )
    }

    private fun decodeBufferedReceiptImage(base64Data: String): BufferedImage? {
        val normalized = base64Data.substringAfter("base64,", missingDelimiterValue = base64Data)
            .removePrefix("base64:")
            .trim()
        val decoded = runCatching { Base64.getDecoder().decode(normalized) }.getOrNull() ?: return null
        return ImageIO.read(decoded.inputStream())
    }

    private fun applyReceiptRasterHints(graphics: Graphics2D) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
    }

    private fun measureReceiptRasterElementHeight(
        element: ReceiptRasterElement,
        layout: ReceiptRasterLayout
    ): Int {
        return when (element) {
            is ReceiptRasterElement.Text -> (element.lines.size * element.lineHeight) + layout.blockSpacing
            is ReceiptRasterElement.Row -> {
                maxOf(element.leftLines.size * element.leftLineHeight, element.rightLineHeight) + layout.blockSpacing
            }
            is ReceiptRasterElement.ItemRow -> {
                maxOf(
                    element.nameLines.size * element.nameLineHeight,
                    element.qtyLineHeight,
                    element.rightLineHeight
                ) + layout.blockSpacing
            }
            is ReceiptRasterElement.Image -> element.image.height + layout.imageSpacing
            is ReceiptRasterElement.Spacer -> element.height
        }
    }

    private fun drawReceiptRasterElement(
        graphics: Graphics2D,
        element: ReceiptRasterElement,
        layout: ReceiptRasterLayout,
        pageWidth: Int,
        sidePadding: Int,
        currentY: Int
    ): Int {
        return when (element) {
            is ReceiptRasterElement.Text -> {
                var nextY = currentY.toFloat()
                element.lines.forEach { line ->
                    graphics.font = element.font
                    graphics.color = Color.BLACK
                    drawReceiptRasterTextLine(
                        graphics = graphics,
                        text = line,
                        font = element.font,
                        align = element.align,
                        pageWidth = pageWidth,
                        sidePadding = sidePadding,
                        lineTopY = nextY
                    )
                    nextY += element.lineHeight
                }
                nextY.roundToInt() + layout.blockSpacing
            }
            is ReceiptRasterElement.Row -> {
                var nextY = currentY.toFloat()
                element.leftLines.forEachIndexed { index, line ->
                    graphics.font = element.leftFont
                    graphics.color = Color.BLACK
                    val bodyMetrics = graphics.getFontMetrics(element.leftFont)
                    val baselineY = nextY + bodyMetrics.ascent
                    graphics.drawString(line, sidePadding.toFloat(), baselineY)

                    if (index == 0 && element.right.isNotBlank()) {
                        graphics.font = element.rightFont
                        val rightMetrics = graphics.getFontMetrics(element.rightFont)
                        val rightX = (pageWidth - sidePadding - rightMetrics.stringWidth(element.right)).toFloat()
                        val rightBaselineY = nextY + rightMetrics.ascent
                        graphics.drawString(element.right, rightX, rightBaselineY)
                    }
                    nextY += element.leftLineHeight
                }
                maxOf((currentY + element.rightLineHeight).toFloat(), nextY).roundToInt() + layout.blockSpacing
            }
            is ReceiptRasterElement.ItemRow -> {
                var nextY = currentY.toFloat()
                val qtyX = sidePadding.toFloat()
                val nameX = (sidePadding + element.qtyWidth + element.qtyGap).toFloat()

                if (element.qty.isNotBlank()) {
                    graphics.font = element.qtyFont
                    graphics.color = Color.BLACK
                    val qtyMetrics = graphics.getFontMetrics(element.qtyFont)
                    graphics.drawString(element.qty, qtyX, nextY + qtyMetrics.ascent)
                }

                element.nameLines.forEachIndexed { index, line ->
                    graphics.font = element.nameFont
                    graphics.color = Color.BLACK
                    val nameMetrics = graphics.getFontMetrics(element.nameFont)
                    graphics.drawString(line, nameX, nextY + nameMetrics.ascent)

                    if (index == 0 && element.right.isNotBlank()) {
                        graphics.font = element.rightFont
                        val rightMetrics = graphics.getFontMetrics(element.rightFont)
                        val rightX = (pageWidth - sidePadding - rightMetrics.stringWidth(element.right)).toFloat()
                        graphics.drawString(element.right, rightX, nextY + rightMetrics.ascent)
                    }
                    nextY += element.nameLineHeight
                }

                maxOf((currentY + element.qtyLineHeight).toFloat(), (currentY + element.rightLineHeight).toFloat(), nextY)
                    .roundToInt() + layout.blockSpacing
            }
            is ReceiptRasterElement.Image -> {
                val drawX = when (element.align) {
                    ReceiptRasterAlign.LEFT -> sidePadding
                    ReceiptRasterAlign.CENTER -> ((pageWidth - element.image.width) / 2).coerceAtLeast(0)
                    ReceiptRasterAlign.RIGHT -> (pageWidth - sidePadding - element.image.width).coerceAtLeast(0)
                }
                graphics.drawImage(element.image, drawX, currentY, null)
                currentY + element.image.height + layout.imageSpacing
            }
            is ReceiptRasterElement.Spacer -> currentY + element.height
        }
    }

    private fun drawReceiptRasterTextLine(
        graphics: Graphics2D,
        text: String,
        font: Font,
        align: ReceiptRasterAlign,
        pageWidth: Int,
        sidePadding: Int,
        lineTopY: Float
    ) {
        val metrics = graphics.getFontMetrics(font)
        val x = when (align) {
            ReceiptRasterAlign.LEFT -> sidePadding
            ReceiptRasterAlign.CENTER -> ((pageWidth - metrics.stringWidth(text)) / 2).coerceAtLeast(0)
            ReceiptRasterAlign.RIGHT -> (pageWidth - sidePadding - metrics.stringWidth(text)).coerceAtLeast(0)
        }
        val baselineY = lineTopY + metrics.ascent
        graphics.drawString(text, x.toFloat(), baselineY)
    }

    private fun parseReceiptRasterAlign(value: String?): ReceiptRasterAlign {
        return when (value?.trim()?.lowercase(Locale.US)) {
            "center" -> ReceiptRasterAlign.CENTER
            "right" -> ReceiptRasterAlign.RIGHT
            else -> ReceiptRasterAlign.LEFT
        }
    }

    private data class ReceiptRasterLayout(
        val titleFont: Font,
        val bodyFont: Font,
        val amountFont: Font,
        val footerFont: Font,
        val smallFont: Font,
        val titleLineHeight: Int,
        val bodyLineHeight: Int,
        val amountLineHeight: Int,
        val footerLineHeight: Int,
        val smallLineHeight: Int,
        val blockSpacing: Int,
        val imageSpacing: Int,
        val rowGap: Int,
        val topPadding: Int,
        val bottomPadding: Int
    )

    private data class ReceiptRasterTextStyle(
        val font: Font,
        val lineHeight: Int
    )

    private sealed class ReceiptRasterElement {
        data class Text(
            val lines: List<String>,
            val align: ReceiptRasterAlign,
            val font: Font,
            val lineHeight: Int
        ) : ReceiptRasterElement()

        data class Row(
            val leftLines: List<String>,
            val right: String,
            val leftFont: Font,
            val rightFont: Font,
            val leftLineHeight: Int,
            val rightLineHeight: Int
        ) : ReceiptRasterElement()

        data class ItemRow(
            val qty: String,
            val nameLines: List<String>,
            val right: String,
            val qtyFont: Font,
            val nameFont: Font,
            val rightFont: Font,
            val qtyLineHeight: Int,
            val nameLineHeight: Int,
            val rightLineHeight: Int,
            val qtyWidth: Int,
            val qtyGap: Int
        ) : ReceiptRasterElement()

        data class Image(
            val image: BufferedImage,
            val align: ReceiptRasterAlign
        ) : ReceiptRasterElement()

        data class Spacer(
            val height: Int
        ) : ReceiptRasterElement()
    }

    private enum class ReceiptRasterAlign {
        LEFT,
        CENTER,
        RIGHT
    }

    private fun createConnection(profile: PrinterProfile): PrinterConnection {
        return when (profile.connectionType) {
            ConnectionType.NETWORK -> {
                val parts = profile.address.split(":")
                val host = parts.firstOrNull().orEmpty()
                val port = parts.getOrNull(1)?.toIntOrNull() ?: 9100
                NetworkConnection(host, port)
            }
            ConnectionType.BLUETOOTH -> error("Bluetooth printing is not implemented in the Windows port yet.")
            ConnectionType.USB -> WindowsPrinterQueueConnection(profile.address)
        }
    }
}

class JobDispatcher(
    private val jobRepository: JobRepository,
    private val printerRepository: PrinterRepository,
    private val printerDriver: PrinterDriver,
    private val bridgeEventLog: BridgeEventLog,
    private val scope: CoroutineScope
) {
    private val processingIds = ConcurrentHashMap.newKeySet<String>()
    private var started = false

    fun start() {
        if (started) {
            return
        }
        started = true

        scope.launch {
            jobRepository.pendingJobs.collectLatest { jobs ->
                jobs.forEach { job ->
                    if (processingIds.add(job.id)) {
                        launch {
                            processJob(job)
                        }
                    }
                }
            }
        }
    }

    private suspend fun processJob(job: PrintJob) {
        try {
            jobRepository.updateJobStatus(job, JobStatus.PRINTING)

            val requestedPrinterProfileId = job.printerProfileId
                ?: runCatching { PrintJobFactory.extractMetadata(job.payloadJson).printerProfileId }.getOrNull()
            val profile = if (requestedPrinterProfileId != null) {
                printerRepository.getProfileById(requestedPrinterProfileId)
            } else {
                printerRepository.getDefaultProfile()
            }

            if (profile == null) {
                throw IllegalStateException("No printer profile found for job ${job.id}")
            }

            val metadata = runCatching { PrintJobFactory.extractMetadata(job.payloadJson) }.getOrNull()
            bridgeEventLog.record(
                source = "printer",
                level = BridgeEventLevel.INFO,
                message = "Printer selected: ${profile.name} (${profile.connectionType}) for job ${job.id}; content type: ${metadata?.contentType ?: "unknown"}."
            )
            printerDriver.print(job, profile)
            jobRepository.updateJobStatus(job, JobStatus.COMPLETED)
            bridgeEventLog.record(
                source = "printer",
                level = BridgeEventLevel.INFO,
                message = "Print job completed: ${job.id}."
            )
        } catch (error: Exception) {
            jobRepository.updateJobStatus(job, JobStatus.FAILED, error.message ?: "Unknown error")
            bridgeEventLog.record(
                source = "printer",
                level = BridgeEventLevel.ERROR,
                message = "Print job failed: ${job.id}; error: ${error.message ?: "Unknown error"}"
            )
        } finally {
            processingIds.remove(job.id)
        }
    }
}

enum class ServerState {
    STOPPED,
    STARTING,
    RUNNING,
    FAILED
}

class PrintServer(
    private val jobRepository: JobRepository,
    private val printerRepository: PrinterRepository,
    private val settingsRepository: SettingsRepository,
    private val bridgeEventLog: BridgeEventLog,
    private val scope: CoroutineScope
) {
    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private var server: ApplicationEngine? = null

    fun start() {
        if (server != null || _serverState.value == ServerState.STARTING) {
            return
        }

        _serverState.value = ServerState.STARTING
        scope.launch {
            try {
                val created = embeddedServer(CIO, port = 9900) {
                    install(ContentNegotiation) {
                        gson()
                    }
                    routing {
                        options("/status") {
                            handlePreflight(call)
                        }
                        options("/integration/status") {
                            handlePreflight(call)
                        }
                        options("/print") {
                            handlePreflight(call)
                        }
                        options("/odoo/receipt") {
                            handlePreflight(call)
                        }
                        get("/status") {
                            val settings = settingsRepository.settings.value
                            if (!applyCors(call, settings)) {
                                return@get
                            }

                            call.respond(
                                mapOf(
                                    "status" to "running",
                                    "bridge" to "windows-desktop"
                                )
                            )
                        }
                        get("/integration/status") {
                            val settings = settingsRepository.settings.value
                            if (!applyCors(call, settings)) {
                                return@get
                            }

                            val allProfiles = printerRepository.allProfiles.value
                            val defaultProfile = allProfiles.firstOrNull { it.isDefault } ?: allProfiles.firstOrNull()
                            bridgeEventLog.record(
                                source = "odoo",
                                level = BridgeEventLevel.INFO,
                                message = "Integration status checked from ${call.describeIntegrationClient()}."
                            )
                            call.respond(
                                mapOf(
                                    "status" to "running",
                                    "bridge" to "windows-desktop",
                                    "token_required" to settings.requiresBridgeToken(),
                                    "accept_launch_payloads" to settings.acceptLaunchPayloads,
                                    "allowed_origins" to settings.allowedOriginRules(),
                                    "default_printer_configured" to (defaultProfile != null),
                                    "default_printer_name" to defaultProfile?.name,
                                    "saved_printer_count" to allProfiles.size,
                                    "endpoints" to mapOf(
                                        "status" to "/status",
                                        "integration_status" to "/integration/status",
                                        "print" to "/print",
                                        "odoo_receipt" to "/odoo/receipt"
                                    ),
                                    "supported_content_types" to listOf(
                                        "escpos_blocks",
                                        "odoo_structured",
                                        "receipt_image",
                                        "receipt_text",
                                        "receipt_html"
                                    )
                                )
                            )
                        }
                        post("/print") {
                            val settings = settingsRepository.settings.value
                            if (!applyCors(call, settings)) {
                                return@post
                            }
                            if (!authorizeRequest(call, settings, endpoint = "/print")) {
                                return@post
                            }

                            val body = call.receiveText()
                            val metadata = try {
                                PrintJobFactory.extractMetadata(body)
                            } catch (error: IllegalArgumentException) {
                                bridgeEventLog.record(
                                    source = "http",
                                    level = BridgeEventLevel.ERROR,
                                    message = "Rejected /print request from ${call.describeIntegrationClient()}: ${error.message}"
                                )
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf(
                                        "status" to "error",
                                        "message" to (error.message ?: "Invalid payload")
                                    )
                                )
                                return@post
                            }

                            val job = PrintJobFactory.createFromPayloadJson(body)
                            val printerAvailabilityError = resolvePrinterAvailabilityError(job)
                            if (printerAvailabilityError != null) {
                                bridgeEventLog.record(
                                    source = "http",
                                    level = BridgeEventLevel.WARNING,
                                    message = "Rejected /print request from ${call.describeIntegrationClient()}: $printerAvailabilityError"
                                )
                                call.respond(
                                    HttpStatusCode.Conflict,
                                    mapOf(
                                        "status" to "error",
                                        "message" to printerAvailabilityError
                                    )
                                )
                                return@post
                            }

                            jobRepository.createJob(job)
                            bridgeEventLog.record(
                                source = "http",
                                level = BridgeEventLevel.INFO,
                                message = "Queued ${metadata.contentType} job from ${call.describeIntegrationClient()}."
                            )
                            call.respond(
                                mapOf(
                                    "status" to "queued",
                                    "job_id" to job.id,
                                    "content_type" to metadata.contentType
                                )
                            )
                        }
                        post("/odoo/receipt") {
                            val settings = settingsRepository.settings.value
                            if (!applyCors(call, settings)) {
                                return@post
                            }
                            if (!authorizeRequest(call, settings, endpoint = "/odoo/receipt")) {
                                return@post
                            }

                            val body = call.receiveText()
                            val normalizedPayloadJson = try {
                                BridgePayloadNormalizer.normalizePayloadJson(body)
                            } catch (error: IllegalArgumentException) {
                                bridgeEventLog.record(
                                    source = "odoo",
                                    level = BridgeEventLevel.ERROR,
                                    message = "Rejected /odoo/receipt request from ${call.describeIntegrationClient()}: ${error.message}"
                                )
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf(
                                        "status" to "error",
                                        "message" to (error.message ?: "Invalid Odoo receipt payload")
                                    )
                                )
                                return@post
                            }

                            val metadata = PrintJobFactory.extractMetadata(normalizedPayloadJson)
                            val job = PrintJobFactory.createFromPayloadJson(normalizedPayloadJson)
                            val receiptLogSummary = describeOdooReceiptJobForLog(body, normalizedPayloadJson, metadata)
                            bridgeEventLog.record(
                                source = "odoo",
                                level = BridgeEventLevel.INFO,
                                message = "Print job received: $receiptLogSummary"
                            )
                            val printerAvailabilityError = resolvePrinterAvailabilityError(job)
                            if (printerAvailabilityError != null) {
                                bridgeEventLog.record(
                                    source = "odoo",
                                    level = BridgeEventLevel.WARNING,
                                    message = "Rejected /odoo/receipt request from ${call.describeIntegrationClient()}: $printerAvailabilityError"
                                )
                                call.respond(
                                    HttpStatusCode.Conflict,
                                    mapOf(
                                        "status" to "error",
                                        "message" to printerAvailabilityError
                                    )
                                )
                                return@post
                            }

                            jobRepository.createJob(job)
                            bridgeEventLog.record(
                                source = "odoo",
                                level = BridgeEventLevel.INFO,
                                message = "Queued ${metadata.contentType} receipt from ${call.describeIntegrationClient()}; job id: ${job.id}."
                            )
                            call.respond(
                                mapOf(
                                    "status" to "queued",
                                    "job_id" to job.id,
                                    "accepted_as" to metadata.contentType
                                )
                            )
                        }
                    }
                }
                created.start(wait = false)
                server = created
                bridgeEventLog.record(
                    source = "server",
                    level = BridgeEventLevel.INFO,
                    message = "Local bridge started on http://127.0.0.1:9900."
                )
                _serverState.value = ServerState.RUNNING
            } catch (error: Exception) {
                bridgeEventLog.record(
                    source = "server",
                    level = BridgeEventLevel.ERROR,
                    message = "Local bridge failed to start: ${error.message ?: "Unknown error"}"
                )
                _serverState.value = ServerState.FAILED
            }
        }
    }

    fun restart() {
        stop()
        start()
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        bridgeEventLog.record(
            source = "server",
            level = BridgeEventLevel.INFO,
            message = "Local bridge stopped."
        )
        _serverState.value = ServerState.STOPPED
    }

    private suspend fun handlePreflight(
        call: io.ktor.server.application.ApplicationCall
    ) {
        val settings = settingsRepository.settings.value
        if (!applyCors(call, settings)) {
            return
        }

        call.respond(mapOf("status" to "ok"))
    }

    private suspend fun authorizeRequest(
        call: io.ktor.server.application.ApplicationCall,
        settings: AppSettings,
        endpoint: String
    ): Boolean {
        if (!settings.requiresBridgeToken()) {
            return true
        }

        val candidateToken = call.request.headers["X-ReceiptBridge-Token"]?.trim()
            ?: call.request.headers["Authorization"]
                ?.trim()
                ?.removePrefix("Bearer ")
                ?.trim()

        if (settings.matchesBridgeToken(candidateToken)) {
            return true
        }

        bridgeEventLog.record(
            source = "auth",
            level = BridgeEventLevel.WARNING,
            message = "Rejected $endpoint request from ${call.describeIntegrationClient()}: missing or invalid bridge token."
        )
        call.respond(
            HttpStatusCode.Unauthorized,
            mapOf(
                "status" to "error",
                "message" to "Missing or invalid bridge token"
            )
        )
        return false
    }

    private suspend fun applyCors(
        call: io.ktor.server.application.ApplicationCall,
        settings: AppSettings
    ): Boolean {
        val origin = call.request.headers["Origin"]?.trim().orEmpty()
        if (origin.isBlank()) {
            return true
        }

        val allowedOriginHeader = settings.resolveAllowedOriginHeader(origin)
        if (allowedOriginHeader == null) {
            bridgeEventLog.record(
                source = "cors",
                level = BridgeEventLevel.WARNING,
                message = "Rejected browser origin $origin."
            )
            call.respond(
                HttpStatusCode.Forbidden,
                mapOf(
                    "status" to "error",
                    "message" to "Origin is not allowed by the Windows bridge"
                )
            )
            return false
        }

        call.response.headers.append("Access-Control-Allow-Origin", allowedOriginHeader)
        call.response.headers.append("Vary", "Origin")
        call.response.headers.append("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        call.response.headers.append(
            "Access-Control-Allow-Headers",
            "Content-Type, Authorization, X-ReceiptBridge-Token, X-Requested-With"
        )
        call.response.headers.append("Access-Control-Max-Age", "86400")
        call.response.headers.append("Access-Control-Allow-Private-Network", "true")
        return true
    }

    private fun describeOdooReceiptJobForLog(
        rawPayloadJson: String,
        normalizedPayloadJson: String,
        metadata: PrintJobMetadata
    ): String {
        val rawRoot = parseJsonObjectForLog(rawPayloadJson)
        val normalizedRoot = parseJsonObjectForLog(normalizedPayloadJson)
        val content = normalizedRoot
            ?.get("content")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
        val structuredReceipt = content
            ?.get("structured_receipt")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
        val printType = rawRoot.readLogString("print_type")
            ?: normalizedRoot.readLogString("print_type")
            ?: "receipt"
        val documentType = rawRoot.readLogString("document_type")
            ?: normalizedRoot.readLogString("document_type")
            ?: metadata.contentType
        val orderName = rawRoot.readLogString("order_name")
            ?: normalizedRoot.readLogString("order_name")
            ?: ""
        val qrBarcodeDetected =
            !content.readLogString("image").isNullOrBlank() ||
                !structuredReceipt.readLogString("qr_value").isNullOrBlank() ||
                !structuredReceipt.readLogString("qr_image").isNullOrBlank() ||
                !structuredReceipt.readLogString("barcode_value").isNullOrBlank() ||
                !structuredReceipt.readLogString("barcode_image").isNullOrBlank()

        return "type: $printType/$documentType; accepted as: ${metadata.contentType}; order: ${orderName.ifBlank { "n/a" }}; QR/barcode image detected: $qrBarcodeDetected."
    }

    private fun parseJsonObjectForLog(payloadJson: String): JsonObject? {
        return runCatching {
            JsonParser.parseString(payloadJson)
                .takeIf { it.isJsonObject }
                ?.asJsonObject
        }.getOrNull()
    }

    private fun JsonObject?.readLogString(fieldName: String): String? {
        val value = this?.get(fieldName) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            return null
        }
        return value.asString.trim().ifBlank { null }
    }

    private suspend fun resolvePrinterAvailabilityError(job: PrintJob): String? {
        val requestedProfileId = job.printerProfileId
        if (!requestedProfileId.isNullOrBlank()) {
            return if (printerRepository.getProfileById(requestedProfileId) == null) {
                "The selected printer profile could not be found in the Windows app."
            } else {
                null
            }
        }

        return if (printerRepository.getDefaultProfile() == null) {
            "No default printer is configured in the Windows app yet."
        } else {
            null
        }
    }
}

class NetworkScanner {
    suspend fun scanNetwork(): List<String> = withContext(Dispatchers.IO) {
        getLocalSubnets()
            .flatMap(::buildCandidateIps)
            .filter { isPortOpen(it, DEFAULT_NETWORK_PRINTER_PORT) }
            .distinct()
            .sortedBy(::ipSortKey)
    }

    suspend fun testAddress(address: String): String = withContext(Dispatchers.IO) {
        val target = parseNetworkTarget(address)
            ?: return@withContext "Enter a valid printer address like 192.168.1.50 or 192.168.1.50:9100."
        val connected = isPortOpen(target.host, target.port, NETWORK_TEST_TIMEOUT_MS)
        if (connected) {
            "Connection successful to ${target.host}:${target.port}. Save this printer and run Connection Test next."
        } else {
            "Could not reach ${target.host}:${target.port}. Make sure the printer is on the same network and that raw printing is enabled."
        }
    }

    private fun getLocalSubnets(): List<Ipv4Subnet> {
        return runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { networkInterface ->
                    networkInterface.interfaceAddresses
                        .asSequence()
                        .mapNotNull { interfaceAddress ->
                            val ipv4 = interfaceAddress.address as? Inet4Address ?: return@mapNotNull null
                            if (ipv4.isLoopbackAddress || ipv4.isLinkLocalAddress) {
                                return@mapNotNull null
                            }

                            val prefixLength = interfaceAddress.networkPrefixLength.toInt().coerceIn(0, 32)
                            if (prefixLength >= 31) {
                                return@mapNotNull null
                            }

                            val localAddress = ipv4.address.fold(0L) { result, byte ->
                                (result shl 8) or (byte.toInt() and 0xFF).toLong()
                            }
                            val mask = if (prefixLength == 0) {
                                0L
                            } else {
                                (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
                            }
                            Ipv4Subnet(localAddress and mask, prefixLength, localAddress)
                        }
                }
                .distinctBy { it.networkAddress to it.prefixLength }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun buildCandidateIps(subnet: Ipv4Subnet): List<String> {
        val hostBits = 32 - subnet.prefixLength
        if (hostBits <= 1) {
            return emptyList()
        }

        val usableHosts = (1L shl hostBits) - 2
        val rangeStart = subnet.networkAddress + 1
        val rangeEnd = subnet.networkAddress + usableHosts
        val scanStart = if (usableHosts > MAX_SCAN_HOSTS) {
            maxOf(rangeStart, subnet.localAddress - MAX_SCAN_HOSTS / 2)
        } else {
            rangeStart
        }
        val scanEnd = if (usableHosts > MAX_SCAN_HOSTS) {
            minOf(rangeEnd, scanStart + MAX_SCAN_HOSTS - 1)
        } else {
            rangeEnd
        }

        return (scanStart..scanEnd)
            .asSequence()
            .filter { it != subnet.localAddress }
            .map(::longToIpv4)
            .toList()
    }

    private fun longToIpv4(value: Long): String {
        return listOf(24, 16, 8, 0).joinToString(".") { shift ->
            ((value shr shift) and 0xFF).toString()
        }
    }

    private fun ipSortKey(ip: String): String {
        return ip.split('.').joinToString(".") { part ->
            (part.toIntOrNull() ?: Int.MAX_VALUE).toString().padStart(3, '0')
        }
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int = PORT_PROBE_TIMEOUT_MS): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun parseNetworkTarget(address: String): NetworkTarget? {
        val trimmedAddress = address.trim()
        if (trimmedAddress.isBlank()) {
            return null
        }

        val parts = trimmedAddress.split(":")
        if (parts.size > 2) {
            return null
        }

        val host = parts.first().trim()
        if (host.isBlank()) {
            return null
        }

        val port = parts.getOrNull(1)?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_NETWORK_PRINTER_PORT
        return NetworkTarget(host, port)
    }

    private data class Ipv4Subnet(
        val networkAddress: Long,
        val prefixLength: Int,
        val localAddress: Long
    )

    private data class NetworkTarget(
        val host: String,
        val port: Int
    )

    private companion object {
        const val DEFAULT_NETWORK_PRINTER_PORT = 9100
        const val PORT_PROBE_TIMEOUT_MS = 200
        const val NETWORK_TEST_TIMEOUT_MS = 1500
        const val MAX_SCAN_HOSTS = 512L
    }
}

class WindowsPrinterQueueDiscovery {
    suspend fun listQueues(): List<WindowsPrinterQueue> = withContext(Dispatchers.IO) {
        val script = """
            ${'$'}printerPorts = Get-PrinterPort | Where-Object { ${'$'}_.Name -like 'USB*' } | Select-Object Name, Description
            ${'$'}printers = Get-Printer | Select-Object Name, PortName, Default
            ${'$'}defaultPrinter = ${'$'}printers | Where-Object { ${'$'}_.Default } | Select-Object -First 1 -ExpandProperty Name
            ${'$'}results = New-Object System.Collections.Generic.List[object]

            foreach (${ '$' }printer in ${ '$' }printers) {
                if (${ '$' }printer.PortName -like 'USB*') {
                    ${ '$' }results.Add([pscustomobject]@{
                        name = ${ '$' }printer.Name
                        queue_name = ${ '$' }printer.Name
                        port_name = ${ '$' }printer.PortName
                        is_default = (${ '$' }printer.Name -eq ${ '$' }defaultPrinter)
                    })
                }
            }

            ${'$'}devices = Get-PnpDevice -PresentOnly | Where-Object {
                (${ '$' }_.Class -match '(?i)printer') -or
                ${'$'}_.InstanceId -like 'USBPRINT*' -or
                (${ '$' }_.InstanceId -like 'USB\*' -and ${ '$' }_.FriendlyName -match '(?i)receipt|printer|pos|thermal|epson|star|bixolon|xprinter|tm-')
            }

            foreach (${ '$' }device in ${ '$' }devices) {
                ${'$'}friendlyName = if ([string]::IsNullOrWhiteSpace(${ '$' }device.FriendlyName)) { ${ '$' }device.InstanceId } else { ${ '$' }device.FriendlyName.Trim() }
                ${'$'}portName = ${'$'}null

                if (${ '$' }device.InstanceId -match '(USB\d{3,})${'$'}') {
                    ${'$'}portName = ${'$'}matches[1]
                }

                if (-not ${'$'}portName) {
                    ${'$'}normalizedFriendly = (${ '$' }friendlyName -replace '[\s_]+', '').ToLowerInvariant()
                    ${'$'}matchedPort = ${'$'}printerPorts | Where-Object {
                        (([string]${ '$' }_.Description -replace '[\s_]+', '').ToLowerInvariant()) -eq ${'$'}normalizedFriendly
                    } | Select-Object -First 1
                    if (${ '$' }matchedPort) {
                        ${'$'}portName = ${'$'}matchedPort.Name
                    }
                }

                ${'$'}queueName = ${'$'}null
                if (${ '$' }portName) {
                    ${'$'}queueMatch = ${'$'}printers | Where-Object { ${'$'}_.PortName -eq ${'$'}portName } | Select-Object -First 1
                    if (${ '$' }queueMatch -and -not [string]::IsNullOrWhiteSpace(${ '$' }queueMatch.Name)) {
                        ${'$'}queueName = [string]${ '$' }queueMatch.Name
                    }
                }

                if (-not ${'$'}portName -and -not ${'$'}queueName) {
                    continue
                }

                ${'$'}alreadyIncluded = ${'$'}false
                foreach (${ '$' }existing in ${ '$' }results) {
                    if (
                        (${ '$' }queueName -and ${ '$' }existing.queue_name -eq ${ '$' }queueName) -or
                        (${ '$' }portName -and ${ '$' }existing.port_name -eq ${ '$' }portName) -or
                        (${ '$' }existing.name -eq ${ '$' }friendlyName)
                    ) {
                        ${'$'}alreadyIncluded = ${'$'}true
                        break
                    }
                }

                if (-not ${'$'}alreadyIncluded) {
                    ${'$'}results.Add([pscustomobject]@{
                        name = ${'$'}friendlyName
                        queue_name = ${'$'}queueName
                        port_name = ${'$'}portName
                        is_default = (${ '$' }queueName -and ${ '$' }queueName -eq ${ '$' }defaultPrinter)
                    })
                }
            }

            ${'$'}results | ConvertTo-Json -Compress
        """.trimIndent()

        val rawJson = runPowerShell(script)
        val parsed = parsePowerShellObjects(rawJson)
        parsed
            .map { entry ->
                WindowsPrinterQueue(
                    name = entry.name.orEmpty(),
                    isDefault = entry.isDefault == true,
                    queueName = entry.queueName?.takeIf { it.isNotBlank() },
                    portName = entry.portName?.takeIf { it.isNotBlank() }
                )
            }
            .filter { queue ->
                queue.name.isNotBlank() &&
                    (!queue.queueName.isNullOrBlank() || !queue.portName.isNullOrBlank()) &&
                    !isCommonVirtualPrinter(queue.name)
            }
            .sortedWith(
                compareByDescending<WindowsPrinterQueue> { it.isDefault }
                    .thenByDescending { !it.queueName.isNullOrBlank() }
                    .thenBy { it.name.lowercase(Locale.US) }
            )
    }

    private fun isCommonVirtualPrinter(name: String): Boolean {
        val normalized = name.trim().lowercase(Locale.US)
        return normalized.contains("print to pdf") ||
            normalized.contains("onenote") ||
            normalized.contains("xps") ||
            normalized == "fax"
    }

    private fun runPowerShell(script: String): String {
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            script
        )
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException(output.ifBlank { "PowerShell USB printer discovery failed." })
        }
        return output
    }

    private fun parsePowerShellObjects(rawJson: String): List<PowerShellUsbPrinterEntry> {
        if (rawJson.isBlank() || rawJson == "null") {
            return emptyList()
        }

        val root = runCatching { JsonParser.parseString(rawJson) }.getOrNull() ?: return emptyList()
        return when {
            root.isJsonArray -> root.asJsonArray.mapNotNull { it.toPowerShellUsbPrinterEntry() }
            root.isJsonObject -> listOfNotNull(root.toPowerShellUsbPrinterEntry())
            else -> emptyList()
        }
    }

    private data class PowerShellUsbPrinterEntry(
        val name: String? = null,
        @com.google.gson.annotations.SerializedName("queue_name")
        val queueName: String? = null,
        @com.google.gson.annotations.SerializedName("port_name")
        val portName: String? = null,
        @com.google.gson.annotations.SerializedName("is_default")
        val isDefault: Boolean? = null
    )

    private fun com.google.gson.JsonElement.toPowerShellUsbPrinterEntry(): PowerShellUsbPrinterEntry? {
        if (!isJsonObject) {
            return null
        }

        val json = asJsonObject
        return PowerShellUsbPrinterEntry(
            name = json.getPowerShellString("name"),
            queueName = json.getPowerShellString("queue_name"),
            portName = json.getPowerShellString("port_name"),
            isDefault = json.getPowerShellBoolean("is_default")
        )
    }

    private fun JsonObject.getPowerShellString(memberName: String): String? {
        val value = get(memberName) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive) {
            return null
        }
        return value.asString.trim().takeIf { it.isNotBlank() }
    }

    private fun JsonObject.getPowerShellBoolean(memberName: String): Boolean? {
        val value = get(memberName) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive) {
            return null
        }
        return runCatching { value.asBoolean }.getOrNull()
    }
}

class WindowsUsbPrinterProvisioner {
    suspend fun ensureQueueForTarget(
        address: String,
        preferredDisplayName: String
    ): String = withContext(Dispatchers.IO) {
        val trimmedAddress = address.trim()
        if (trimmedAddress.isBlank()) {
            throw IllegalArgumentException("Choose a Windows printer queue or USB printer port first.")
        }

        if (!trimmedAddress.matches(Regex("^USB\\d{3,}$", RegexOption.IGNORE_CASE))) {
            return@withContext findQueueByName(trimmedAddress)
                ?: throw IllegalArgumentException(
                    "Choose a detected USB printer, an existing Windows printer queue, or a USB port like USB001."
                )
        }

        val portName = trimmedAddress.uppercase(Locale.US)
        findQueueByPort(portName)?.let { return@withContext it }

        val driverName = findPreferredDriver()
            ?: throw IllegalStateException(
                "No compatible Windows printer driver was found for $portName. Install 'Generic / Text Only' or a receipt-printer driver in Windows first."
            )

        val queueName = buildUniqueQueueName(
            baseName = "Softbridge USB ${sanitizeQueueLabel(preferredDisplayName.ifBlank { portName })}"
        )

        runPowerShell(
            """
                Add-Printer -Name '${escapePowerShell(queueName)}' -DriverName '${escapePowerShell(driverName)}' -PortName '${escapePowerShell(portName)}' -ErrorAction Stop | Out-Null
                '${escapePowerShell(queueName)}'
            """.trimIndent()
        )

        return@withContext queueName
    }

    private fun findQueueByPort(portName: String): String? {
        val script = """
            ${'$'}printer = Get-Printer | Where-Object { ${'$'}_.PortName -eq '${escapePowerShell(portName)}' } | Select-Object -First 1 -ExpandProperty Name
            if (${ '$' }printer) { ${ '$' }printer } else { '' }
        """.trimIndent()
        return runPowerShell(script).trim().ifBlank { null }
    }

    private fun findQueueByName(queueName: String): String? {
        val script = """
            ${'$'}printer = Get-Printer -Name '${escapePowerShell(queueName)}' -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Name
            if (${ '$' }printer) { ${ '$' }printer } else { '' }
        """.trimIndent()
        return runPowerShell(script).trim().ifBlank { null }
    }

    private fun findPreferredDriver(): String? {
        val candidates = listOf(
            "Generic / Text Only",
            "Microsoft enhanced Point and Print compatibility driver"
        )

        for (candidate in candidates) {
            val script = """
                ${'$'}driver = Get-PrinterDriver -Name '${escapePowerShell(candidate)}' -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Name
                if (${ '$' }driver) { ${ '$' }driver } else { '' }
            """.trimIndent()
            val resolved = runPowerShell(script).trim().ifBlank { null }
            if (resolved != null) {
                return resolved
            }
        }

        return null
    }

    private fun buildUniqueQueueName(baseName: String): String {
        var attempt = 1
        var candidate = baseName
        while (queueExists(candidate)) {
            attempt += 1
            candidate = "$baseName $attempt"
        }
        return candidate
    }

    private fun queueExists(queueName: String): Boolean {
        val script = """
            ${'$'}printer = Get-Printer -Name '${escapePowerShell(queueName)}' -ErrorAction SilentlyContinue
            if (${ '$' }printer) { 'true' } else { 'false' }
        """.trimIndent()
        return runPowerShell(script).trim().equals("true", ignoreCase = true)
    }

    private fun sanitizeQueueLabel(value: String): String {
        val cleaned = value
            .replace(Regex("[\\\\/:*?\"<>|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.ifBlank { "Receipt Printer" }
    }

    private fun escapePowerShell(value: String): String {
        return value.replace("'", "''")
    }

    private fun runPowerShell(script: String): String {
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            script
        )
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException(output.trim().ifBlank { "Windows USB printer setup failed." })
        }
        return output
    }
}

class ReceiptBridgeDesktopController(
    private val launchArguments: List<String> = emptyList()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val storage = DesktopStorage()
    private val printerRepository = PrinterRepository(storage)
    private val jobRepository = JobRepository(storage)
    private val settingsRepository = SettingsRepository(storage)
    private val bridgeEventLog = BridgeEventLog()
    private val printerDriver = PrinterDriver(settingsRepository, bridgeEventLog)
    private val jobDispatcher = JobDispatcher(jobRepository, printerRepository, printerDriver, bridgeEventLog, scope)
    private val networkScanner = NetworkScanner()
    private val windowsPrinterQueueDiscovery = WindowsPrinterQueueDiscovery()
    private val windowsUsbPrinterProvisioner = WindowsUsbPrinterProvisioner()
    private val printServer = PrintServer(jobRepository, printerRepository, settingsRepository, bridgeEventLog, scope)

    val profiles = printerRepository.allProfiles
    val activeProfile = printerRepository.allProfiles
        .map { allProfiles -> allProfiles.firstOrNull { it.isDefault } ?: allProfiles.firstOrNull() }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = printerRepository.allProfiles.value.firstOrNull { it.isDefault }
                ?: printerRepository.allProfiles.value.firstOrNull()
        )
    val jobs = jobRepository.allJobs
    val settings = settingsRepository.settings
    val serverState = printServer.serverState
    val bridgeEvents = bridgeEventLog.events

    private val _printerActionMessage = MutableStateFlow<String?>(null)
    val printerActionMessage: StateFlow<String?> = _printerActionMessage.asStateFlow()

    private val _networkScanMessage = MutableStateFlow<String?>(null)
    val networkScanMessage: StateFlow<String?> = _networkScanMessage.asStateFlow()

    private val _networkAddressTestMessage = MutableStateFlow<String?>(null)
    val networkAddressTestMessage: StateFlow<String?> = _networkAddressTestMessage.asStateFlow()

    private val _foundIpDevices = MutableStateFlow<List<String>>(emptyList())
    val foundIpDevices: StateFlow<List<String>> = _foundIpDevices.asStateFlow()

    private val _foundUsbPrinters = MutableStateFlow<List<WindowsPrinterQueue>>(emptyList())
    val foundUsbPrinters: StateFlow<List<WindowsPrinterQueue>> = _foundUsbPrinters.asStateFlow()

    private val _isNetworkScanning = MutableStateFlow(false)
    val isNetworkScanning: StateFlow<Boolean> = _isNetworkScanning.asStateFlow()

    private val _isUsbScanning = MutableStateFlow(false)
    val isUsbScanning: StateFlow<Boolean> = _isUsbScanning.asStateFlow()

    private val _isTestingNetworkAddress = MutableStateFlow(false)
    val isTestingNetworkAddress: StateFlow<Boolean> = _isTestingNetworkAddress.asStateFlow()

    private val _systemPrintTestMessage = MutableStateFlow<String?>(null)
    val systemPrintTestMessage: StateFlow<String?> = _systemPrintTestMessage.asStateFlow()

    private val _usbDiscoveryMessage = MutableStateFlow<String?>(null)
    val usbDiscoveryMessage: StateFlow<String?> = _usbDiscoveryMessage.asStateFlow()

    private val _systemPrintTestInProgress = MutableStateFlow(false)
    val systemPrintTestInProgress: StateFlow<Boolean> = _systemPrintTestInProgress.asStateFlow()

    init {
        jobDispatcher.start()
        printServer.start()
        scope.launch {
            settingsRepository.refreshSettings()
            jobRepository.purgeHistoryOlderThan(settings.value.keepHistoryDays)
            importLaunchPayloadIfPresent()
        }
    }

    fun restartServer() {
        printServer.restart()
    }

    fun scanNetwork() {
        if (_isNetworkScanning.value) {
            return
        }

        scope.launch {
            _isNetworkScanning.value = true
            _foundIpDevices.value = emptyList()
            _networkScanMessage.value = "Scanning your local network for printers on port 9100..."
            try {
                val devices = networkScanner.scanNetwork()
                _foundIpDevices.value = devices
                _networkScanMessage.value = if (devices.isEmpty()) {
                    "No printers answered on port 9100. You can still add the printer manually using its IP address."
                } else {
                    "Found ${devices.size} reachable network printer address${if (devices.size == 1) "" else "es"}."
                }
            } catch (error: Exception) {
                _networkScanMessage.value = "Network scan failed: ${error.message ?: "Unknown error"}"
            } finally {
                _isNetworkScanning.value = false
            }
        }
    }

    fun scanUsbPrinters() {
        if (_isUsbScanning.value) {
            return
        }

        scope.launch {
            _isUsbScanning.value = true
            _foundUsbPrinters.value = emptyList()
            _usbDiscoveryMessage.value = "Checking Windows USB printers and printer queues..."
            try {
                val queues = windowsPrinterQueueDiscovery.listQueues()
                val queueCount = queues.count { !it.queueName.isNullOrBlank() }
                val rawDeviceCount = queues.count { it.queueName.isNullOrBlank() && !it.portName.isNullOrBlank() }
                _foundUsbPrinters.value = queues
                _usbDiscoveryMessage.value = if (queues.isEmpty()) {
                    "No USB receipt printers were detected. Make sure the printer is plugged in, powered on, and visible in Windows, then refresh."
                } else if (rawDeviceCount > 0 && queueCount == 0) {
                    "Detected ${rawDeviceCount} USB printer device${if (rawDeviceCount == 1) "" else "s"}. Select one below and Softbridge will create the Windows printer queue when you save."
                } else if (rawDeviceCount > 0) {
                    "Found ${queueCount} Windows USB printer queue${if (queueCount == 1) "" else "s"} and ${rawDeviceCount} plugged USB printer device${if (rawDeviceCount == 1) "" else "s"}."
                } else {
                    "Found ${queueCount} Windows USB printer queue${if (queueCount == 1) "" else "s"}. Select the printer below."
                }
            } catch (error: Exception) {
                _usbDiscoveryMessage.value = "USB printer scan failed: ${error.message ?: "Unknown error"}"
            } finally {
                _isUsbScanning.value = false
            }
        }
    }

    fun testNetworkAddress(address: String) {
        if (_isTestingNetworkAddress.value) {
            return
        }

        scope.launch {
            _isTestingNetworkAddress.value = true
            try {
                _networkAddressTestMessage.value = networkScanner.testAddress(address)
            } finally {
                _isTestingNetworkAddress.value = false
            }
        }
    }

    fun clearNetworkAddressTestMessage() {
        _networkAddressTestMessage.value = null
    }

    fun clearUsbDiscoveryMessage() {
        _usbDiscoveryMessage.value = null
    }

    fun addProfile(
        name: String,
        type: ConnectionType,
        address: String,
        isDefault: Boolean,
        paperWidthMm: Int,
        printAreaDots: Int
    ) {
        scope.launch {
            try {
                val hadDefaultBeforeSave = printerRepository.getDefaultProfile() != null
                val normalizedPaperWidth = normalizePaperWidthMm(paperWidthMm)
                val sanitizedDots = sanitizePrintAreaDots(printAreaDots)
                val requestedAddress = address.trim()
                val matchedUsbDevice = if (type == ConnectionType.USB && requestedAddress.isNotBlank()) {
                    runCatching {
                        windowsPrinterQueueDiscovery.listQueues().firstOrNull { queue ->
                            queue.name.equals(requestedAddress, ignoreCase = true) ||
                                queue.queueName.equals(requestedAddress, ignoreCase = true) ||
                                queue.portName.equals(requestedAddress, ignoreCase = true)
                        }
                    }.getOrNull()
                } else {
                    null
                }
                val resolvedAddress = if (type == ConnectionType.USB) {
                    val usbTarget = matchedUsbDevice?.queueName ?: matchedUsbDevice?.portName ?: requestedAddress
                    windowsUsbPrinterProvisioner.ensureQueueForTarget(
                        address = usbTarget,
                        preferredDisplayName = matchedUsbDevice?.name ?: name
                    )
                } else {
                    requestedAddress
                }
                val defaultOdooRenderMode = defaultOdooReceiptRenderModeFor(type)
                val profile = PrinterProfile(
                    name = name,
                    connectionType = type,
                    address = resolvedAddress,
                    paperWidthMm = normalizedPaperWidth,
                    printAreaDots = sanitizedDots,
                    charactersPerLine = defaultCharactersPerLineForPrintAreaDots(sanitizedDots),
                    odooReceiptRenderMode = defaultOdooRenderMode,
                    renderedReceiptFillPercent = defaultRenderedReceiptFillPercentFor(
                        renderMode = defaultOdooRenderMode,
                        fallbackPercent = settings.value.systemPrintContentFillPercent
                    ),
                    renderedReceiptSmartFit = true,
                    isDefault = isDefault
                )
                printerRepository.saveProfile(profile)

                val queueSummary = if (type == ConnectionType.USB && !requestedAddress.equals(resolvedAddress, ignoreCase = true)) {
                    " using Windows queue $resolvedAddress"
                } else {
                    ""
                }

                _printerActionMessage.value = if (isDefault || !hadDefaultBeforeSave) {
                    "Printer saved and ready: $name is now the active printer${queueSummary} (${normalizedPaperWidth} mm, ${sanitizedDots} dots)."
                } else {
                    "Printer saved: $name${queueSummary} (${normalizedPaperWidth} mm, ${sanitizedDots} dots)"
                }
            } catch (error: Exception) {
                _printerActionMessage.value = "Printer save failed: ${error.message ?: "Unknown error"}"
            }
        }
    }

    fun deleteProfile(profile: PrinterProfile) {
        scope.launch {
            printerRepository.deleteProfile(profile)
            _printerActionMessage.value = "Printer removed: ${profile.name}"
        }
    }

    fun setDefault(profile: PrinterProfile) {
        scope.launch {
            val updatedProfile = printerRepository.setDefaultProfile(profile.id)
            _printerActionMessage.value = if (updatedProfile != null) {
                "Default printer set to ${updatedProfile.name}"
            } else {
                "Unable to set ${profile.name} as the default printer."
            }
        }
    }

    fun updatePaperWidth(profile: PrinterProfile, paperWidthMm: Int) {
        scope.launch {
            val normalizedPaperWidth = normalizePaperWidthMm(paperWidthMm)
            if (profile.paperWidthMm == normalizedPaperWidth) {
                return@launch
            }

            val currentDefaultDots = defaultPrintAreaDotsForPaperWidthMm(profile.paperWidthMm)
            val newDefaultDots = defaultPrintAreaDotsForPaperWidthMm(normalizedPaperWidth)
            val nextPrintAreaDots = if (profile.resolvedPrintAreaDots() == currentDefaultDots) {
                newDefaultDots
            } else {
                profile.resolvedPrintAreaDots()
            }

            printerRepository.saveProfile(
                profile.copy(
                    paperWidthMm = normalizedPaperWidth,
                    printAreaDots = nextPrintAreaDots,
                    charactersPerLine = defaultCharactersPerLineForPrintAreaDots(nextPrintAreaDots)
                )
            )
            _printerActionMessage.value =
                "Receipt size for ${profile.name} set to ${normalizedPaperWidth} mm (${nextPrintAreaDots} dots)."
        }
    }

    fun updatePrintAreaDots(profile: PrinterProfile, printAreaDots: Int) {
        scope.launch {
            val sanitizedDots = sanitizePrintAreaDots(printAreaDots)
            if (profile.resolvedPrintAreaDots() == sanitizedDots) {
                return@launch
            }

            printerRepository.saveProfile(
                profile.copy(
                    printAreaDots = sanitizedDots,
                    charactersPerLine = defaultCharactersPerLineForPrintAreaDots(sanitizedDots)
                )
            )
            _printerActionMessage.value = "Print area for ${profile.name} set to ${sanitizedDots} dots."
        }
    }

    fun updateRenderedReceiptFillPercent(profile: PrinterProfile, fillPercent: Int) {
        scope.launch {
            val sanitizedFillPercent = sanitizeRenderedReceiptFillPercent(fillPercent)
            if (profile.resolvedRenderedReceiptFillPercent() == sanitizedFillPercent) {
                return@launch
            }

            printerRepository.saveProfile(
                profile.copy(renderedReceiptFillPercent = sanitizedFillPercent)
            )
            _printerActionMessage.value =
                "Rendered receipt width for ${profile.name} set to ${sanitizedFillPercent}%."
        }
    }

    fun updateRenderedReceiptSmartFit(profile: PrinterProfile, enabled: Boolean) {
        scope.launch {
            if (profile.resolvedRenderedReceiptSmartFit() == enabled) {
                return@launch
            }

            printerRepository.saveProfile(
                profile.copy(renderedReceiptSmartFit = enabled)
            )
            _printerActionMessage.value =
                if (enabled) {
                    "Smart receipt fit is on for ${profile.name}. The Windows app will ignore large outer page margins before scaling."
                } else {
                    "Smart receipt fit is off for ${profile.name}. The Windows app will print the full captured receipt page as-is."
                }
        }
    }

    fun updateOdooReceiptRenderMode(profile: PrinterProfile, mode: OdooReceiptRenderMode) {
        scope.launch {
            if (profile.resolvedOdooReceiptRenderMode() == mode) {
                return@launch
            }

            printerRepository.saveProfile(
                profile.copy(odooReceiptRenderMode = mode)
            )
            _printerActionMessage.value =
                when (mode) {
                    OdooReceiptRenderMode.EXACT_LAYOUT ->
                        "Odoo receipt mode for ${profile.name} set to Exact Layout. The Windows app will prefer the captured Odoo receipt image when available."
                    OdooReceiptRenderMode.NATIVE_THERMAL ->
                        "Odoo receipt mode for ${profile.name} set to Native Thermal. The Windows app will rebuild the receipt for thermal printing."
                }
        }
    }

    fun queueTestPrint(profile: PrinterProfile) {
        scope.launch {
            val testJob = PrintJob(
                printerProfileId = profile.id,
                payloadJson = buildConnectionTestPayload(profile)
            )
            jobRepository.createJob(testJob)
            _printerActionMessage.value = "Connection test queued for ${profile.name}."
        }
    }

    fun queueWidthCalibrationPrint(profile: PrinterProfile) {
        scope.launch {
            val calibrationJob = PrintJob(
                printerProfileId = profile.id,
                payloadJson = buildWidthCalibrationPayload(profile)
            )
            jobRepository.createJob(calibrationJob)
            _printerActionMessage.value =
                "Width calibration sheet queued for ${profile.name}. Measure the widest guide and apply the suggested dots."
        }
    }

    fun retryJob(job: PrintJob) {
        scope.launch {
            jobRepository.updateJobStatus(job, JobStatus.PENDING, null)
        }
    }

    fun clearHistory() {
        scope.launch {
            jobRepository.clearHistoryJobs()
        }
    }

    fun updateSettings(newSettings: AppSettings) {
        scope.launch {
            val sanitized = newSettings.copy(
                keepHistoryDays = sanitizeKeepHistoryDays(newSettings.keepHistoryDays)
            ).sanitized()
            settingsRepository.updateSettings(sanitized)
            jobRepository.purgeHistoryOlderThan(sanitized.keepHistoryDays)
        }
    }

    fun runSettingsPrintTest() {
        if (_systemPrintTestInProgress.value) {
            return
        }

        scope.launch {
            _systemPrintTestInProgress.value = true
            val profile = printerRepository.getDefaultProfile() ?: profiles.value.firstOrNull()
            if (profile == null) {
                _systemPrintTestMessage.value = "Add a printer first, then run the settings test."
                _systemPrintTestInProgress.value = false
                return@launch
            }

            val job = PrintJob(
                printerProfileId = profile.id,
                payloadJson = buildSettingsTestPayload(profile, settings.value)
            )
            jobRepository.createJob(job)
            _systemPrintTestMessage.value =
                "Settings test queued for ${profile.name}. Check the queue for completion status."
            _systemPrintTestInProgress.value = false
        }
    }

    fun clearSystemPrintTestMessage() {
        _systemPrintTestMessage.value = null
    }

    private suspend fun importLaunchPayloadIfPresent() {
        if (launchArguments.isEmpty()) {
            return
        }

        if (!settings.value.acceptLaunchPayloads) {
            bridgeEventLog.record(
                source = "launch",
                level = BridgeEventLevel.WARNING,
                message = "Launch payload support is disabled in the Windows app settings."
            )
            return
        }

        val launchPayloadJson = try {
            LaunchPayloadParser.extractPayloadJson(launchArguments)
        } catch (error: IllegalArgumentException) {
            bridgeEventLog.record(
                source = "launch",
                level = BridgeEventLevel.ERROR,
                message = error.message ?: "Launch payload could not be decoded."
            )
            return
        }

        if (launchPayloadJson.isNullOrBlank()) {
            return
        }

        val normalizedPayloadJson = try {
            BridgePayloadNormalizer.normalizePayloadJson(launchPayloadJson)
        } catch (_: IllegalArgumentException) {
            launchPayloadJson
        }

        val job = try {
            PrintJobFactory.createFromPayloadJson(normalizedPayloadJson)
        } catch (error: IllegalArgumentException) {
            bridgeEventLog.record(
                source = "launch",
                level = BridgeEventLevel.ERROR,
                message = "Launch payload was rejected: ${error.message ?: "Invalid payload"}"
            )
            return
        }

        jobRepository.createJob(job)
        val contentType = runCatching { PrintJobFactory.extractMetadata(normalizedPayloadJson).contentType }
            .getOrDefault("unknown")
        bridgeEventLog.record(
            source = "launch",
            level = BridgeEventLevel.INFO,
            message = "Queued launch payload as $contentType."
        )
    }

    fun close() {
        printServer.stop()
        scope.cancel()
    }

    private fun buildConnectionTestPayload(profile: PrinterProfile): String {
        val root = JsonObject().apply {
            addProperty("printer_profile_id", profile.id)
            addProperty("copies", 1)
            add("content", JsonObject().apply {
                addProperty("type", "escpos_blocks")
                add("blocks", JsonArray().apply {
                    addCommand("align", "center")
                    addCommand("text", "Softbridge Connection Test")
                    addCommand("text", profile.name)
                    addCommand("align", "left")
                    addCommand("text", "Paper: ${profile.paperWidthMm} mm")
                    addCommand("text", "Print area: ${profile.resolvedPrintAreaDots()} dots")
                    addCommand("text", "Connection: ${profile.connectionType}")
                    addCommand("text", "Address: ${profile.address}")
                    addCommand("text", "If this prints, the saved printer profile is working.")
                })
            })
        }
        return gson.toJson(root)
    }

    private fun buildSettingsTestPayload(
        profile: PrinterProfile,
        settings: AppSettings
    ): String {
        val root = JsonObject().apply {
            addProperty("printer_profile_id", profile.id)
            addProperty("copies", 1)
            add("content", JsonObject().apply {
                addProperty("type", "escpos_blocks")
                add("blocks", JsonArray().apply {
                    addCommand("align", "center")
                    addCommand("text", "Softbridge Settings Test")
                    addCommand("text", Date().toString())
                    addCommand("align", "left")
                    addCommand("text", "Printer: ${profile.name}")
                    addCommand("text", "Receipt width: ${profile.paperWidthMm} mm")
                    addCommand("text", "Print area: ${profile.resolvedPrintAreaDots()} dots")
                    addCommand("text", "History retention: ${settings.keepHistoryDays} days")
                    addCommand("text", "Rendered receipt width: ${profile.resolvedRenderedReceiptFillPercent(settings.systemPrintContentFillPercent)}%")
                    addCommand("text", "Header/footer values are applied globally if set.")
                })
            })
        }
        return gson.toJson(root)
    }

    private fun buildWidthCalibrationPayload(profile: PrinterProfile): String {
        val calibrationImage = buildCalibrationSheetImageDataUrl(profile)
        val root = JsonObject().apply {
            addProperty("printer_profile_id", profile.id)
            addProperty("copies", 1)
            add("content", JsonObject().apply {
                addProperty("type", "escpos_blocks")
                add("blocks", JsonArray().apply {
                    addCommand("align", "center")
                    addImageCommand(calibrationImage, profile.resolvedPrintAreaDots())
                    addCommand("feed", "1")
                })
            })
        }
        return gson.toJson(root)
    }

    private fun buildCalibrationSheetImageDataUrl(profile: PrinterProfile): String {
        val widthDots = profile.resolvedPrintAreaDots().coerceAtLeast(320)
        val sheetWidth = widthDots
        val sheetHeight = 1200
        val canvas = BufferedImage(sheetWidth, sheetHeight, BufferedImage.TYPE_INT_RGB)
        val graphics = canvas.createGraphics()

        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, sheetWidth, sheetHeight)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.color = Color.BLACK
        graphics.stroke = BasicStroke(2f)

        val textMargin = 16
        val textWidth = (sheetWidth - (textMargin * 2)).coerceAtLeast(160)
        val currentWidthGuide = (sheetWidth - 2).coerceAtLeast(120)
        var cursorY = 34

        graphics.font = Font("SansSerif", Font.BOLD, 24)
        cursorY = drawWrappedText(
            graphics = graphics,
            text = "Softbridge Width Calibration",
            left = textMargin,
            baselineY = cursorY,
            maxWidth = textWidth,
            lineHeight = 30
        )

        graphics.font = Font("SansSerif", Font.PLAIN, 18)
        cursorY = drawWrappedText(
            graphics = graphics,
            text = "Printer: ${profile.name}",
            left = textMargin,
            baselineY = cursorY,
            maxWidth = textWidth,
            lineHeight = 24
        )
        cursorY = drawWrappedText(
            graphics = graphics,
            text = "Paper: ${profile.paperWidthMm} mm",
            left = textMargin,
            baselineY = cursorY,
            maxWidth = textWidth,
            lineHeight = 24
        )
        cursorY = drawWrappedText(
            graphics = graphics,
            text = "Current print area: ${profile.resolvedPrintAreaDots()} dots",
            left = textMargin,
            baselineY = cursorY,
            maxWidth = textWidth,
            lineHeight = 24
        )
        cursorY = drawWrappedText(
            graphics = graphics,
            text = "Measure the outer current-width guide from edge to edge on paper, then enter that millimeter value in the desktop calibration helper.",
            left = textMargin,
            baselineY = cursorY + 6,
            maxWidth = textWidth,
            lineHeight = 24
        )

        val fullWidthLeft = ((sheetWidth - currentWidthGuide) / 2).coerceAtLeast(0)
        cursorY += 14
        drawCalibrationGuide(
            graphics = graphics,
            left = fullWidthLeft,
            top = cursorY,
            width = currentWidthGuide,
            height = 96,
            label = "Current-width guide",
            details = "${profile.paperWidthMm} mm target  |  ${profile.resolvedPrintAreaDots()} dots"
        )

        cursorY += 132
        val guide95Width = (currentWidthGuide * 0.95f).roundToInt().coerceAtLeast(100)
        drawCalibrationGuide(
            graphics = graphics,
            left = ((sheetWidth - guide95Width) / 2).coerceAtLeast(0),
            top = cursorY,
            width = guide95Width,
            height = 72,
            label = "95% guide",
            details = formatGuideWidthLabel(profile.paperWidthMm, 95)
        )

        cursorY += 106
        val guide90Width = (currentWidthGuide * 0.90f).roundToInt().coerceAtLeast(90)
        drawCalibrationGuide(
            graphics = graphics,
            left = ((sheetWidth - guide90Width) / 2).coerceAtLeast(0),
            top = cursorY,
            width = guide90Width,
            height = 72,
            label = "90% guide",
            details = formatGuideWidthLabel(profile.paperWidthMm, 90)
        )

        graphics.font = Font("SansSerif", Font.PLAIN, 16)
        cursorY = drawWrappedText(
            graphics = graphics,
            text = "If the current-width guide is smaller than the roll width, raise the dots value.",
            left = textMargin,
            baselineY = cursorY + 108,
            maxWidth = textWidth,
            lineHeight = 22
        )
        cursorY = drawWrappedText(
            graphics = graphics,
            text = "If it is too wide or clipped, lower the dots value or switch 58 mm / 80 mm.",
            left = textMargin,
            baselineY = cursorY,
            maxWidth = textWidth,
            lineHeight = 22
        )
        cursorY = drawWrappedText(
            graphics = graphics,
            text = "After dots are right, fine tune the rendered receipt width percentage.",
            left = textMargin,
            baselineY = cursorY,
            maxWidth = textWidth,
            lineHeight = 22
        )
        graphics.dispose()

        val finalHeight = (cursorY + 20).coerceIn(1, sheetHeight)
        val finalImage = canvas.getSubimage(0, 0, sheetWidth, finalHeight)
        val output = ByteArrayOutputStream()
        ImageIO.write(finalImage, "png", output)
        val encoded = Base64.getEncoder().encodeToString(output.toByteArray())
        return "data:image/png;base64,$encoded"
    }

    private fun drawCalibrationGuide(
        graphics: Graphics2D,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        label: String,
        details: String
    ) {
        graphics.drawRect(left, top, width.coerceAtLeast(1), height.coerceAtLeast(1))
        for (step in 1 until 10) {
            val x = left + ((width * (step / 10f)).roundToInt())
            graphics.drawLine(x, top, x, top + height)
        }
        graphics.font = Font("SansSerif", Font.BOLD, 18)
        graphics.drawString(label, left + 10, top + 28)
        graphics.font = Font("SansSerif", Font.PLAIN, 16)
        graphics.drawString(details, left + 10, top + 50)
        graphics.drawString("${width} dots", left + 10, top + 70)
    }

    private fun drawWrappedText(
        graphics: Graphics2D,
        text: String,
        left: Int,
        baselineY: Int,
        maxWidth: Int,
        lineHeight: Int
    ): Int {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) {
            return baselineY
        }

        var y = baselineY
        var currentLine = words.first()
        for (word in words.drop(1)) {
            val candidate = "$currentLine $word"
            if (graphics.fontMetrics.stringWidth(candidate) <= maxWidth) {
                currentLine = candidate
            } else {
                graphics.drawString(currentLine, left, y)
                y += lineHeight
                currentLine = word
            }
        }

        graphics.drawString(currentLine, left, y)
        return y + lineHeight
    }

    private fun formatGuideWidthLabel(paperWidthMm: Int, percent: Int): String {
        val widthMm = paperWidthMm * (percent / 100f)
        return "${percent}% of roll width ≈ ${"%.1f".format(Locale.US, widthMm)} mm"
    }

    private fun JsonArray.addCommand(command: String, value: String) {
        add(JsonObject().apply {
            addProperty("cmd", command)
            addProperty("value", value)
        })
    }

    private fun JsonArray.addImageCommand(base64Data: String, widthDots: Int) {
        add(JsonObject().apply {
            addProperty("cmd", "image")
            addProperty("value", base64Data)
            addProperty("left", widthDots.toString())
        })
    }
}

private val WHITESPACE_REGEX = "\\s+".toRegex()
private val NON_STANDARD_SPACES_REGEX = "[\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]".toRegex()

private fun String.normalizeWhitespace(): String {
    return replace('\u00A0', ' ')
        .replace(Regex("[\\t\\x0B\\f]+"), " ")
        .trim()
}

private fun String.normalizeReceiptPrinterText(): String {
    return replace(NON_STANDARD_SPACES_REGEX, " ")
}
