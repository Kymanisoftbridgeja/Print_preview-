package com.receiptbridge.escpos

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

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
        try {
            buffer.write(text.toByteArray(currentCharset))
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return this
    }

    fun setCodePage(page: Int): EscPosBuilder {
        // ESC t n
        buffer.write(byteArrayOf(ESC, 't'.code.toByte(), page.toByte()))
        return this
    }

    fun setEncoding(charset: String): EscPosBuilder {
        try {
            currentCharset = Charset.forName(charset)
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
        // 0: Left, 1: Center, 2: Right
        val n = when (align.lowercase()) {
            "center" -> 1
            "right" -> 2
            else -> 0
        }
        buffer.write(byteArrayOf(ESC, 'a'.code.toByte(), n.toByte()))
        return this
    }

    fun bold(enable: Boolean): EscPosBuilder {
        val n = if (enable) 1 else 0
        buffer.write(byteArrayOf(ESC, 'E'.code.toByte(), n.toByte()))
        return this
    }

    fun underline(enable: Boolean): EscPosBuilder {
        // 0: Off, 1: 1-dot, 2: 2-dot
        val n = if (enable) 1 else 0
        buffer.write(byteArrayOf(ESC, '-'.code.toByte(), n.toByte()))
        return this
    }

    // Double Height / Width
    fun size(doubleWidth: Boolean, doubleHeight: Boolean): EscPosBuilder {
        var n = 0
        if (doubleWidth) n = n or 0x20
        if (doubleHeight) n = n or 0x10
        buffer.write(byteArrayOf(GS, '!'.code.toByte(), n.toByte()))
        return this
    }

    fun cut(full: Boolean = true): EscPosBuilder {
        // GS V m
        // m = 66 (partial), 65 (full) usually, but basic is GS V 66 0
        // More standard: GS V m n (Feeds n lines then cuts)
        // Function A: GS V m (0: full, 1: partial)
        val m = if (full) 0 else 1
        buffer.write(byteArrayOf(GS, 'V'.code.toByte(), m.toByte()))
        return this
    }
    
    fun feedAndCut(feedLines: Int = 3, full: Boolean = true): EscPosBuilder {
         // Function B: GS V m n
         // m = 65 (full), 66 (partial)
         val m = if (full) 65 else 66
         buffer.write(byteArrayOf(GS, 'V'.code.toByte(), m.toByte(), feedLines.toByte()))
         return this
    }

    fun qrCode(data: String, size: Int = 3): EscPosBuilder {
        val bytes = data.toByteArray(Charset.forName("UTF-8"))
        val pL = (bytes.size + 3) % 256
        val pH = (bytes.size + 3) / 256

        // 1. Model selection (Model 2)
        buffer.write(byteArrayOf(GS, '('.code.toByte(), 'k'.code.toByte(), 4, 0, 49, 65, 50, 0))
        // 2. Module size
        buffer.write(byteArrayOf(GS, '('.code.toByte(), 'k'.code.toByte(), 3, 0, 49, 67, size.toByte()))
        // 3. Error correction (Level M)
        buffer.write(byteArrayOf(GS, '('.code.toByte(), 'k'.code.toByte(), 3, 0, 49, 69, 49))
        // 4. Store data
        buffer.write(byteArrayOf(GS, '('.code.toByte(), 'k'.code.toByte(), pL.toByte(), pH.toByte(), 49, 80, 48))
        buffer.write(bytes)
        // 5. Print
        buffer.write(byteArrayOf(GS, '('.code.toByte(), 'k'.code.toByte(), 3, 0, 49, 81, 48))

        return this
    }

    fun image(width: Int, height: Int, data: ByteArray): EscPosBuilder {
        // GS v 0 m xL xH yL yH d1...dk
        // xL xH is width in bytes (width dots / 8)
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
        // ESC p m t1 t2
        buffer.write(byteArrayOf(ESC, 'p'.code.toByte(), 0, 25, 250.toByte()))
        return this
    }

    fun beep(): EscPosBuilder {
        buffer.write(byteArrayOf(ESC, 'B'.code.toByte(), 2, 1))
        return this
    }

    // Helper for bytes
    fun raw(bytes: ByteArray): EscPosBuilder {
        buffer.write(bytes)
        return this
    }

    fun build(): ByteArray {
        return buffer.toByteArray()
    }
}
