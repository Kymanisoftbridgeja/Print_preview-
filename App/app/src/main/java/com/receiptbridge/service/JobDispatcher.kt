package com.receiptbridge.service

import com.receiptbridge.data.JobStatus
import com.receiptbridge.data.PrintJob
import com.receiptbridge.data.repository.JobRepository
import com.receiptbridge.data.repository.PrinterRepository
import com.receiptbridge.escpos.PrinterDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JobDispatcher @Inject constructor(
    private val jobRepository: JobRepository,
    private val printerRepository: PrinterRepository,
    private val printerDriver: PrinterDriver
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        
        scope.launch {
            jobRepository.pendingJobs.collect { jobs ->
                for (job in jobs) {
                     if (job.status == JobStatus.PENDING) {
                         processJob(job)
                     }
                }
            }
        }
    }

    private suspend fun processJob(job: PrintJob) {
        // Double check status to avoid race conditions if needed, though collect is sequential here
        
        // Update to PRINTING
        jobRepository.updateJobStatus(job, JobStatus.PRINTING)

        try {
            val profile = if (job.printerProfileId != null) {
                printerRepository.getProfileById(job.printerProfileId)
            } else {
                printerRepository.getDefaultProfile()
            }

            if (profile == null) {
                throw IllegalStateException("No printer profile found for job ${job.id}")
            }

            // Print
            printerDriver.print(job, profile)
            
            // Success
            jobRepository.updateJobStatus(job, JobStatus.COMPLETED)
            
        } catch (e: Exception) {
            e.printStackTrace()
            jobRepository.updateJobStatus(job, JobStatus.FAILED, e.message ?: "Unknown Error")
        }
    }
    
    fun stop() {
        // scope.cancel() // Don't cancel singleton scope usually
        isRunning = false
    }
}
