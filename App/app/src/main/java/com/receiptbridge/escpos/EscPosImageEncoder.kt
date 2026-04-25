package com.receiptbridge.escpos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.roundToInt

data class EscPosRasterImage(
    val width: Int,
    val height: Int,
    val rasterBytes: ByteArray
)

object EscPosImageEncoder {
    fun encodeBitmap(
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int? = null
    ): EscPosRasterImage {
        val scaledBitmap = bitmap.scaleForEscPos(targetWidth, targetHeight)
        return try {
            scaledBitmap.toRasterImage()
        } finally {
            if (scaledBitmap !== bitmap) {
                scaledBitmap.recycle()
            }
        }
    }

    fun decodeBase64Image(
        base64Data: String,
        targetWidth: Int,
        targetHeight: Int? = null
    ): EscPosRasterImage? {
        val normalized = base64Data.substringAfter("base64,", missingDelimiterValue = base64Data)
            .removePrefix("base64:")
            .trim()
        val decoded = try {
            android.util.Base64.decode(normalized, android.util.Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return null
        }

        return decodeImageBytes(decoded, targetWidth, targetHeight)
    }

    fun decodeImageBytes(
        imageBytes: ByteArray,
        targetWidth: Int,
        targetHeight: Int? = null
    ): EscPosRasterImage? {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
        return try {
            encodeBitmap(bitmap, targetWidth, targetHeight)
        } finally {
            bitmap.recycle()
        }
    }

    private fun Bitmap.scaleForEscPos(targetWidth: Int, targetHeight: Int?): Bitmap {
        val safeTargetWidth = targetWidth.coerceAtLeast(1)
        val desiredWidth = if (targetHeight != null) {
            safeTargetWidth
        } else {
            width.coerceAtMost(safeTargetWidth)
        }
        val desiredHeight = targetHeight?.coerceAtLeast(1)
            ?: ((height * (desiredWidth.toFloat() / width.toFloat())).roundToInt()).coerceAtLeast(1)

        if (desiredWidth == width && desiredHeight == height) {
            return this
        }

        return Bitmap.createScaledBitmap(this, desiredWidth, desiredHeight, true)
    }

    private fun Bitmap.toRasterImage(): EscPosRasterImage {
        val widthBytes = (width + 7) / 8
        val raster = ByteArray(widthBytes * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = getPixel(x, y)
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                val alpha = (pixel ushr 24) and 0xFF
                val grayscale = (red * 0.299f) + (green * 0.587f) + (blue * 0.114f)
                val shouldPrintBlack = alpha > 127 && grayscale < 180f
                if (shouldPrintBlack) {
                    val index = y * widthBytes + (x / 8)
                    raster[index] = (raster[index].toInt() or (0x80 shr (x % 8))).toByte()
                }
            }
        }

        return EscPosRasterImage(
            width = width,
            height = height,
            rasterBytes = raster
        )
    }
}
