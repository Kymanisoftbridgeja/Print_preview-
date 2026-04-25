package com.receiptbridge.data

import androidx.room.*

const val MIN_KEEP_HISTORY_DAYS = 0
const val MAX_KEEP_HISTORY_DAYS = 365
const val DEFAULT_KEEP_HISTORY_DAYS = 30
const val MIN_SYSTEM_PRINT_CONTENT_FILL_PERCENT = 75
const val MAX_SYSTEM_PRINT_CONTENT_FILL_PERCENT = 100
const val DEFAULT_SYSTEM_PRINT_CONTENT_FILL_PERCENT = 92

@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey val id: Int = 1, // Single row instance
    val globalHeader: String? = null, // Base64 image or text
    val globalFooter: String? = null,
    val autoPrintOnConnect: Boolean = false,
    val keepHistoryDays: Int = DEFAULT_KEEP_HISTORY_DAYS,
    val systemPrintContentFillPercent: Int = DEFAULT_SYSTEM_PRINT_CONTENT_FILL_PERCENT
)

fun sanitizeKeepHistoryDays(value: Int): Int {
    return value.coerceIn(MIN_KEEP_HISTORY_DAYS, MAX_KEEP_HISTORY_DAYS)
}

fun sanitizeSystemPrintContentFillPercent(value: Int): Int {
    return value.coerceIn(
        MIN_SYSTEM_PRINT_CONTENT_FILL_PERCENT,
        MAX_SYSTEM_PRINT_CONTENT_FILL_PERCENT
    )
}

fun AppSettings.sanitized(): AppSettings {
    return copy(
        keepHistoryDays = sanitizeKeepHistoryDays(keepHistoryDays),
        systemPrintContentFillPercent = sanitizeSystemPrintContentFillPercent(systemPrintContentFillPercent)
    )
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun getSettings(): AppSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: AppSettings)
}
