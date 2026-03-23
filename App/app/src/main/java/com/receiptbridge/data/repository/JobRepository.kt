package com.receiptbridge.data.repository

import com.receiptbridge.data.JobStatus
import com.receiptbridge.data.PrintJob
import com.receiptbridge.data.PrintJobDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JobRepository @Inject constructor(
    private val printJobDao: PrintJobDao
) {
    val allJobs: Flow<List<PrintJob>> = printJobDao.getAll()
    val pendingJobs: Flow<List<PrintJob>> = printJobDao.getPendingJobs()

    suspend fun createJob(job: PrintJob) {
        printJobDao.insert(job)
    }

    suspend fun updateJobStatus(job: PrintJob, status: JobStatus, error: String? = null) {
        val updatedJob = job.copy(status = status, errorMessage = error)
        printJobDao.update(updatedJob)
    }
    
    suspend fun updateJob(job: PrintJob) {
        printJobDao.update(job)
    }

    suspend fun retryRecoverableUsbJobs(printerProfileId: String?): Int {
        return printJobDao.retryRecoverableUsbJobs(printerProfileId)
    }

    suspend fun clearHistoryJobs() {
        printJobDao.clearHistory()
    }

    suspend fun purgeHistoryOlderThan(days: Int) {
        val retentionDays = days.coerceAtLeast(0)
        val cutoffMillis = System.currentTimeMillis() - retentionDays * 24L * 60L * 60L * 1000L
        printJobDao.deleteHistoryOlderThan(cutoffMillis)
    }

    suspend fun clearAllJobs() {
        printJobDao.clearAll()
    }
}
