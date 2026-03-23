package com.receiptbridge.data.repository

import com.receiptbridge.data.AppSettings
import com.receiptbridge.data.SettingsDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val settingsDao: SettingsDao
) {
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings

    suspend fun refreshSettings() {
        val dbSettings = settingsDao.getSettings()
        if (dbSettings != null) {
            _settings.emit(dbSettings)
        } else {
            // Initialize with defaults if empty
            val default = AppSettings()
            settingsDao.saveSettings(default)
            _settings.emit(default)
        }
    }

    suspend fun updateSettings(newSettings: AppSettings) {
        settingsDao.saveSettings(newSettings)
        _settings.emit(newSettings)
    }
}
