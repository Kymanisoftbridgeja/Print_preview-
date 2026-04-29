package com.receiptbridge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterProfileSystemPrintIdTest {

    @Test
    fun systemPrintLocalId_roundTripsConnectionTypeAndAddress() {
        val profile = PrinterProfile(
            id = "profile-123",
            name = "WOOSIM",
            connectionType = ConnectionType.BLUETOOTH,
            address = "00:15:0E:EA:02:20"
        )

        val localId = profile.systemPrintLocalId()
        val selector = parseSystemPrintLocalId(localId)

        assertNotNull(selector)
        assertEquals(ConnectionType.BLUETOOTH, selector?.connectionType)
        assertEquals("00:15:0e:ea:02:20", selector?.normalizedAddress)
    }

    @Test
    fun parseSystemPrintLocalId_rejectsLegacyIds() {
        assertEquals(null, parseSystemPrintLocalId("2f6998f3-caf1-48fc-b4df-44e0a6f5735c"))
        assertTrue(looksLikeLegacyPrinterProfileId("2f6998f3-caf1-48fc-b4df-44e0a6f5735c"))
    }

    @Test
    fun looksLikeLegacyPrinterProfileId_rejectsSystemPrintIds() {
        val profile = PrinterProfile(
            name = "Kitchen",
            connectionType = ConnectionType.NETWORK,
            address = "192.168.1.50:9100"
        )

        assertFalse(looksLikeLegacyPrinterProfileId(profile.systemPrintLocalId()))
    }
}
