package com.receiptbridge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.receiptbridge.data.AppSettings
import com.receiptbridge.data.sanitizeKeepHistoryDays
import com.receiptbridge.data.sanitizeSystemPrintContentFillPercent
import com.receiptbridge.data.sanitized
import com.receiptbridge.data.repository.JobRepository
import com.receiptbridge.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val jobRepository: JobRepository
) : ViewModel() {
    val settings = repository.settings

    init {
        viewModelScope.launch {
            repository.refreshSettings()
            jobRepository.purgeHistoryOlderThan(repository.settings.value.keepHistoryDays)
        }
    }

    fun updateSettings(newSettings: AppSettings) {
        viewModelScope.launch {
            val sanitized = newSettings.sanitized()
            repository.updateSettings(sanitized)
            jobRepository.purgeHistoryOlderThan(sanitized.keepHistoryDays)
        }
    }

    fun updateKeepHistoryDays(value: Int) {
        updateSettings(settings.value.copy(keepHistoryDays = sanitizeKeepHistoryDays(value)))
    }

    fun updateSystemPrintContentFillPercent(value: Int) {
        updateSettings(
            settings.value.copy(
                systemPrintContentFillPercent = sanitizeSystemPrintContentFillPercent(value)
            )
        )
    }
}
