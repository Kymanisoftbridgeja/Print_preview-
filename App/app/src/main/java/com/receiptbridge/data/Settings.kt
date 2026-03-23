package com.receiptbridge.data

import androidx.room.*

@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey val id: Int = 1, // Single row instance
    val globalHeader: String? = null, // Base64 image or text
    val globalFooter: String? = null,
    val autoPrintOnConnect: Boolean = false,
    val keepHistoryDays: Int = 30
)

@Dao
interface SettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun getSettings(): AppSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: AppSettings)
}
