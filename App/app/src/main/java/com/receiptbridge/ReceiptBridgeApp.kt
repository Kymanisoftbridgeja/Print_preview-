package com.receiptbridge

import android.app.Application
import com.receiptbridge.data.repository.JobRepository
import com.receiptbridge.data.repository.SettingsRepository
import com.receiptbridge.service.JobDispatcher
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ReceiptBridgeApp : Application() {

    @Inject
    lateinit var jobDispatcher: JobDispatcher

    @Inject
    lateinit var jobRepository: JobRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        jobDispatcher.start()
        applicationScope.launch {
            while (isActive) {
                settingsRepository.refreshSettings()
                jobRepository.purgeHistoryOlderThan(settingsRepository.settings.value.keepHistoryDays)
                delay(HISTORY_CLEANUP_INTERVAL_MS)
            }
        }
    }

    private companion object {
        const val HISTORY_CLEANUP_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}

