package com.receiptbridge.escpos

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class EscPosBuilderTest {

    @Test
    fun testReset() {
        val bytes = EscPosBuilder().reset().build()
        assertArrayEquals(byteArrayOf(0x1B, 0x40), bytes)
    }

    @Test
    fun testText() {
        val bytes = EscPosBuilder().text("Hello").build()
        assertArrayEquals("Hello".toByteArray(), bytes)
    }

    @Test
    fun testQrCode() {
        val data = "https://kymanisoft.com"
        val bytes = EscPosBuilder().qrCode(data, 4).build()
        
        // Expected sequence:
        // Model select: 1D 28 6B 04 00 31 41 32 00
        // Module size: 1D 28 6B 03 00 31 43 04
        // Error correction: 1D 28 6B 03 00 31 45 31
        // Store data: 1D 28 6B pL pH 31 50 30 <data>
        // Print: 1D 28 6B 03 00 31 51 30
        
        val dataBytes = data.toByteArray()
        val pL = (dataBytes.size + 3) % 256
        val pH = (dataBytes.size + 3) / 256
        
        val expectedStart = byteArrayOf(
            0x1D, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00, // Model
            0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, 0x04,       // Size
            0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, 0x31,       // EC
            0x1D, 0x28, 0x6B, pL.toByte(), pH.toByte(), 0x31, 0x50, 0x30 // Store
        )
        
        val expectedEnd = byteArrayOf(
            0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30 // Print
        )
        
        val expected = expectedStart + dataBytes + expectedEnd
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun testDrawerOpen() {
        val bytes = EscPosBuilder().drawerOpen().build()
        assertArrayEquals(byteArrayOf(0x1B, 0x70, 0x00, 25, 0xFA.toByte()), bytes)
    }
}
