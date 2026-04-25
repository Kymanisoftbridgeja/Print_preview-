package com.receiptbridge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.receiptbridge.data.AppSettings
import com.receiptbridge.data.sanitizeKeepHistoryDays
import com.receiptbridge.data.sanitizeSystemPrintContentFillPercent
import com.receiptbridge.data.sanitized
import com.receiptbridge.data.repository.PrinterRepository
import com.receiptbridge.data.repository.JobRepository
import com.receiptbridge.data.repository.SettingsRepository
import com.receiptbridge.escpos.PrinterDriver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val jobRepository: JobRepository,
    private val printerRepository: PrinterRepository,
    private val printerDriver: PrinterDriver
) : ViewModel() {
    val settings = repository.settings
    val printerProfiles = printerRepository.allProfiles
    private val _systemPrintTestInProgress = MutableStateFlow(false)
    val systemPrintTestInProgress: StateFlow<Boolean> = _systemPrintTestInProgress
    private val _systemPrintTestMessage = MutableStateFlow<String?>(null)
    val systemPrintTestMessage: StateFlow<String?> = _systemPrintTestMessage

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

    fun runSystemPrintSettingsTest() {
        viewModelScope.launch {
            if (_systemPrintTestInProgress.value) {
                return@launch
            }

            _systemPrintTestInProgress.value = true
            val profile = printerRepository.getDefaultProfile()
                ?: printerRepository.allProfiles.first().firstOrNull()
            if (profile == null) {
                _systemPrintTestMessage.value = "Add a printer first, then run the settings width test."
                _systemPrintTestInProgress.value = false
                return@launch
            }

            try {
                printerDriver.printSystemSettingsTest(profile)
                _systemPrintTestMessage.value =
                    "Settings test printed on ${profile.name}. This test uses the receipt width control."
            } catch (error: Exception) {
                _systemPrintTestMessage.value =
                    "Settings test failed on ${profile.name}: ${error.message ?: "Unknown error"}"
            } finally {
                _systemPrintTestInProgress.value = false
            }
        }
    }

    fun clearSystemPrintTestMessage() {
        _systemPrintTestMessage.value = null
    }
}
