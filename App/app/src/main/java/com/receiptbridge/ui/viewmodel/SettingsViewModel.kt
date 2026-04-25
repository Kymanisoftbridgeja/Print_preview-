package com.receiptbridge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.receiptbridge.data.AppSettings
import com.receiptbridge.data.repository.JobRepository
import com.receiptbridge.data.repository.SettingsRepository
import com.receiptbridge.data.repository.SystemPrintRenderSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val jobRepository: JobRepository,
    private val systemPrintRenderSettingsRepository: SystemPrintRenderSettingsRepository
) : ViewModel() {
    val settings = repository.settings
    val systemPrintRenderSettings = systemPrintRenderSettingsRepository.settings

    init {
        viewModelScope.launch {
            repository.refreshSettings()
            jobRepository.purgeHistoryOlderThan(repository.settings.value.keepHistoryDays)
        }
    }

    fun updateSettings(newSettings: AppSettings) {
        viewModelScope.launch {
            val sanitized = newSettings.copy(keepHistoryDays = newSettings.keepHistoryDays.coerceAtLeast(0))
            repository.updateSettings(sanitized)
            jobRepository.purgeHistoryOlderThan(sanitized.keepHistoryDays)
        }
    }

    fun updateSystemPrintDarknessPercent(value: Int) {
        systemPrintRenderSettingsRepository.updateDarknessPercent(value)
    }
}
