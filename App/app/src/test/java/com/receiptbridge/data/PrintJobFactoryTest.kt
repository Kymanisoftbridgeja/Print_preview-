package com.receiptbridge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrintJobFactoryTest {

    @Test
    fun createFromPayloadJson_extractsPrinterAndCopies() {
        val payload = """
            {
              "printer_profile_id": "printer-123",
              "copies": 3,
              "content": {
                "type": "escpos_blocks",
                "blocks": [
                  {"cmd": "text", "value": "Hello"}
                ]
              }
            }
        """.trimIndent()

        val job = PrintJobFactory.createFromPayloadJson(payload)

        assertEquals("printer-123", job.printerProfileId)
        assertEquals(3, job.copies)
        assertEquals(payload, job.payloadJson)
    }

    @Test
    fun extractMetadata_defaultsCopiesToOne() {
        val payload = """
            {
              "copies": 0,
              "content": {
                "type": "escpos_blocks",
                "blocks": []
              }
            }
        """.trimIndent()

        val metadata = PrintJobFactory.extractMetadata(payload)

        assertNull(metadata.printerProfileId)
        assertEquals(1, metadata.copies)
    }

    @Test(expected = IllegalArgumentException::class)
    fun extractMetadata_requiresContentBlocks() {
        PrintJobFactory.extractMetadata("""{"content": {}}""")
    }
}
