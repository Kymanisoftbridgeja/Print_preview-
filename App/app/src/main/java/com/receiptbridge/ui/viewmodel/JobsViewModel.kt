package com.receiptbridge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.receiptbridge.data.JobStatus
import com.receiptbridge.data.PrintJob
import com.receiptbridge.data.repository.JobRepository
import com.receiptbridge.data.repository.PrinterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class JobsViewModel @Inject constructor(
    private val repository: JobRepository,
    printerRepository: PrinterRepository
) : ViewModel() {

    val allJobs = repository.allJobs
    val profiles = printerRepository.allProfiles
    
    fun retryJob(job: PrintJob) {
        viewModelScope.launch {
            // Reset to PENDING
            repository.updateJobStatus(job, JobStatus.PENDING, null)
        }
    }
    
    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistoryJobs()
        }
    }
}
