package com.receiptbridge.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PrinterProfileTest {

    @Test
    fun defaultPrintAreaDots_matchPaperDefaults() {
        assertEquals(DEFAULT_PRINT_AREA_DOTS_58_MM, defaultPrintAreaDotsForPaperWidthMm(PAPER_WIDTH_58_MM))
        assertEquals(DEFAULT_PRINT_AREA_DOTS_80_MM, defaultPrintAreaDotsForPaperWidthMm(PAPER_WIDTH_80_MM))
    }

    @Test
    fun sanitizePrintAreaDots_clampsToSupportedRange() {
        assertEquals(MIN_PRINT_AREA_DOTS, sanitizePrintAreaDots(MIN_PRINT_AREA_DOTS - 100))
        assertEquals(MAX_PRINT_AREA_DOTS, sanitizePrintAreaDots(MAX_PRINT_AREA_DOTS + 100))
    }

    @Test
    fun defaultCharactersPerLineForPrintAreaDots_scalesWithDots() {
        assertEquals(32, defaultCharactersPerLineForPrintAreaDots(DEFAULT_PRINT_AREA_DOTS_58_MM))
        assertEquals(48, defaultCharactersPerLineForPrintAreaDots(DEFAULT_PRINT_AREA_DOTS_80_MM))
    }
}
