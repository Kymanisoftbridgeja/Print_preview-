package com.receiptbridge

import android.app.Application
import com.receiptbridge.service.JobDispatcher
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ReceiptBridgeApp : Application() {

    @Inject
    lateinit var jobDispatcher: JobDispatcher

    override fun onCreate() {
        super.onCreate()
        jobDispatcher.start()
    }
}

