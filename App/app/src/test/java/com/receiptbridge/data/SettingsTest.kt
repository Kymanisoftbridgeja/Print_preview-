package com.receiptbridge.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {

    @Test
    fun sanitizeKeepHistoryDays_clampsToSupportedRange() {
        assertEquals(MIN_KEEP_HISTORY_DAYS, sanitizeKeepHistoryDays(-10))
        assertEquals(MAX_KEEP_HISTORY_DAYS, sanitizeKeepHistoryDays(MAX_KEEP_HISTORY_DAYS + 10))
    }

    @Test
    fun sanitizeSystemPrintContentFillPercent_clampsToSupportedRange() {
        assertEquals(
            MIN_SYSTEM_PRINT_CONTENT_FILL_PERCENT,
            sanitizeSystemPrintContentFillPercent(MIN_SYSTEM_PRINT_CONTENT_FILL_PERCENT - 10)
        )
        assertEquals(
            MAX_SYSTEM_PRINT_CONTENT_FILL_PERCENT,
            sanitizeSystemPrintContentFillPercent(MAX_SYSTEM_PRINT_CONTENT_FILL_PERCENT + 10)
        )
    }

    @Test
    fun sanitizedSettings_clampsBothNumericValues() {
        val sanitized = AppSettings(
            keepHistoryDays = -99,
            systemPrintContentFillPercent = 500
        ).sanitized()

        assertEquals(MIN_KEEP_HISTORY_DAYS, sanitized.keepHistoryDays)
        assertEquals(MAX_SYSTEM_PRINT_CONTENT_FILL_PERCENT, sanitized.systemPrintContentFillPercent)
    }
}
