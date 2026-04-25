package com.receiptbridge.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class SystemPrintRenderSettings(
    val widthFillPercent: Int = DEFAULT_WIDTH_FILL_PERCENT,
    val darknessPercent: Int = DEFAULT_DARKNESS_PERCENT
) {
    companion object {
        const val DEFAULT_WIDTH_FILL_PERCENT = 112
        const val DEFAULT_DARKNESS_PERCENT = 108
        const val MIN_WIDTH_FILL_PERCENT = 100
        const val MAX_WIDTH_FILL_PERCENT = 150
        const val MIN_DARKNESS_PERCENT = 90
        const val MAX_DARKNESS_PERCENT = 140
    }
}

@Singleton
class SystemPrintRenderSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<SystemPrintRenderSettings> = _settings

    fun updateWidthFillPercent(value: Int) {
        val sanitized = value.coerceIn(
            SystemPrintRenderSettings.MIN_WIDTH_FILL_PERCENT,
            SystemPrintRenderSettings.MAX_WIDTH_FILL_PERCENT
        )
        saveSettings(_settings.value.copy(widthFillPercent = sanitized))
    }

    fun updateDarknessPercent(value: Int) {
        val sanitized = value.coerceIn(
            SystemPrintRenderSettings.MIN_DARKNESS_PERCENT,
            SystemPrintRenderSettings.MAX_DARKNESS_PERCENT
        )
        saveSettings(_settings.value.copy(darknessPercent = sanitized))
    }

    private fun loadSettings(): SystemPrintRenderSettings {
        return SystemPrintRenderSettings(
            widthFillPercent = prefs.getInt(
                KEY_WIDTH_FILL_PERCENT,
                SystemPrintRenderSettings.DEFAULT_WIDTH_FILL_PERCENT
            ).coerceIn(
                SystemPrintRenderSettings.MIN_WIDTH_FILL_PERCENT,
                SystemPrintRenderSettings.MAX_WIDTH_FILL_PERCENT
            ),
            darknessPercent = prefs.getInt(
                KEY_DARKNESS_PERCENT,
                SystemPrintRenderSettings.DEFAULT_DARKNESS_PERCENT
            ).coerceIn(
                SystemPrintRenderSettings.MIN_DARKNESS_PERCENT,
                SystemPrintRenderSettings.MAX_DARKNESS_PERCENT
            )
        )
    }

    private fun saveSettings(settings: SystemPrintRenderSettings) {
        prefs.edit()
            .putInt(KEY_WIDTH_FILL_PERCENT, settings.widthFillPercent)
            .putInt(KEY_DARKNESS_PERCENT, settings.darknessPercent)
            .apply()
        _settings.value = settings
    }

    private companion object {
        const val PREFS_NAME = "receiptbridge-system-print-render"
        const val KEY_WIDTH_FILL_PERCENT = "width_fill_percent"
        const val KEY_DARKNESS_PERCENT = "darkness_percent"
    }
}
