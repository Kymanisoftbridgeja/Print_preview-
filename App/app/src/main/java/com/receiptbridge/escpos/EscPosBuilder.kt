package com.receiptbridge.escpos

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

class EscPosBuilder {
    private val buffer = ByteArrayOutputStream()

    companion object {
        const val ESC: Byte = 0x1B
        const val GS: Byte = 0x1D
        const val LF: Byte = 0x0A
    }

    fun reset(): EscPosBuilder {
        buffer.write(byteArrayOf(ESC, '@'.code.toByte()))
        return this
    }

    fun text(text: String, charset: String = "UTF-8"): EscPosBuilder {
        try {
            buffer.write(text.toByteArray(Charset.forName(charset)))
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

    // Helper for bytes
    fun raw(bytes: ByteArray): EscPosBuilder {
        buffer.write(bytes)
        return this
    }

    fun build(): ByteArray {
        return buffer.toByteArray()
    }
}
