package com.tanjo.servicereports.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tanjo.servicereports.data.local.JobEntity
import com.tanjo.servicereports.data.local.AttachmentEntity
import com.tanjo.servicereports.data.local.PartEntity
import com.tanjo.servicereports.data.local.ServiceReportEntity
import com.tanjo.servicereports.data.repository.ServiceRepository
import com.tanjo.servicereports.sync.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceReportViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = ServiceRepository(app)
    val jobs = repository.jobs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val selectedReportId = MutableStateFlow<String?>(null)
    val message = MutableStateFlow("")

    val selectedReport: StateFlow<ServiceReportEntity?> = selectedReportId.flatMapLatest {
        if (it == null) flowOf(null) else repository.observeReport(it)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val parts: StateFlow<List<PartEntity>> = selectedReportId.flatMapLatest {
        if (it == null) flowOf(emptyList()) else repository.observeParts(it)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun login(baseUrl: String, db: String, login: String, password: String) = viewModelScope.launch {
        runCatching { repository.login(baseUrl, db, login, password) }
            .onSuccess { message.value = "Jobs synced" }
            .onFailure { message.value = it.message ?: "Login failed" }
    }

    fun refresh() = viewModelScope.launch {
        runCatching { repository.refreshJobs() }
            .onSuccess { message.value = "Jobs refreshed" }
            .onFailure { message.value = "Offline: showing saved Jobs" }
    }

    fun openJob(job: JobEntity) = viewModelScope.launch {
        selectedReportId.value = repository.reportForJob(job).localId
    }

    fun newEmergencyReport() = viewModelScope.launch {
        selectedReportId.value = repository.newEmergencyReport().localId
    }

    fun save(report: ServiceReportEntity) = viewModelScope.launch {
        repository.saveReport(report.copy(syncStatus = "Local Draft"))
    }

    fun start(report: ServiceReportEntity) = viewModelScope.launch {
        repository.startJob(report)
        SyncWorker.enqueue(getApplication())
    }

    fun stop(report: ServiceReportEntity) = viewModelScope.launch {
        repository.stopJob(report)
        SyncWorker.enqueue(getApplication())
    }

    fun submit(report: ServiceReportEntity) = viewModelScope.launch {
        repository.markPendingSubmit(report)
        SyncWorker.enqueue(getApplication())
        message.value = "Report queued for sync"
    }

    fun addPart(reportId: String, partName: String, quantity: Double) = viewModelScope.launch {
        repository.savePart(PartEntity(reportLocalId = reportId, partName = partName, quantity = quantity))
    }

    fun addPhoto(reportId: String, uri: String) = viewModelScope.launch {
        repository.saveAttachment(AttachmentEntity(reportLocalId = reportId, filePath = uri))
    }

    fun syncNow() = viewModelScope.launch {
        runCatching { repository.syncPending() }
            .onSuccess { message.value = "Pending reports synced" }
            .onFailure { message.value = "Sync failed; reports are still saved locally" }
    }
}
