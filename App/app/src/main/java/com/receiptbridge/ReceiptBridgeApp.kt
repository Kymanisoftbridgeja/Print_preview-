package com.receiptbridge

import android.app.Application
import com.receiptbridge.data.repository.JobRepository
import com.receiptbridge.data.repository.SettingsRepository
import com.receiptbridge.service.JobDispatcher
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
            settingsRepository.refreshSettings()
            jobRepository.purgeHistoryOlderThan(settingsRepository.settings.value.keepHistoryDays)
        }
    }
}

