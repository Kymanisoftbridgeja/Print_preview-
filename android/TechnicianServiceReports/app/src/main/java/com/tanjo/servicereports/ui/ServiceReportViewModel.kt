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
    val connectionDefaults = repository.connectionDefaults
    val jobs = repository.jobs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val serviceReports = repository.serviceReports.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val selectedReportId = MutableStateFlow<String?>(null)
    val message = MutableStateFlow("")

    init {
        if (repository.hasSavedSession()) {
            refresh()
        }
    }

    val selectedReport: StateFlow<ServiceReportEntity?> = selectedReportId.flatMapLatest {
        if (it == null) flowOf(null) else repository.observeReport(it)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val parts: StateFlow<List<PartEntity>> = selectedReportId.flatMapLatest {
        if (it == null) flowOf(emptyList()) else repository.observeParts(it)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun login(baseUrl: String, db: String, login: String, password: String) = viewModelScope.launch {
        runCatching { repository.login(baseUrl, db, login, password) }
            .onSuccess { message.value = it }
            .onFailure { message.value = it.message ?: "Login failed" }
    }

    fun refresh() = viewModelScope.launch {
        runCatching {
            val reportsMessage = repository.refreshServiceReports()
            val jobsMessage = repository.refreshJobs()
            "$reportsMessage; $jobsMessage"
        }
            .onSuccess { message.value = it }
            .onFailure { message.value = it.message ?: "Offline: showing saved Jobs" }
    }

    fun openReport(report: ServiceReportEntity) {
        selectedReportId.value = report.localId
    }

    fun openJob(job: JobEntity) = viewModelScope.launch {
        runCatching { repository.reportForJob(job) }
            .onSuccess {
                selectedReportId.value = it.localId
                if (it.syncError.isNotBlank()) message.value = it.syncError
            }
            .onFailure { message.value = it.message ?: "Could not open service report" }
    }

    fun newEmergencyReport() = viewModelScope.launch {
        selectedReportId.value = repository.newEmergencyReport().localId
    }

    fun save(report: ServiceReportEntity) = viewModelScope.launch {
        repository.saveReport(report.copy(syncStatus = "Local Draft"))
        message.value = "Draft saved locally"
    }

    fun start(report: ServiceReportEntity) = viewModelScope.launch {
        runCatching { repository.startJob(report) }
            .onSuccess { message.value = it }
            .onFailure {
                message.value = it.message ?: "Start sync failed"
                SyncWorker.enqueue(getApplication())
            }
    }

    fun stop(report: ServiceReportEntity) = viewModelScope.launch {
        runCatching { repository.stopJob(report) }
            .onSuccess { message.value = it }
            .onFailure {
                message.value = it.message ?: "Stop sync failed"
                SyncWorker.enqueue(getApplication())
            }
    }

    fun submit(report: ServiceReportEntity) = viewModelScope.launch {
        runCatching { repository.submitReport(report) }
            .onSuccess { message.value = it }
            .onFailure {
                message.value = it.message ?: "Report queued for sync"
                SyncWorker.enqueue(getApplication())
            }
    }

    fun addPart(reportId: String, partName: String, serialNumber: String, quantity: Double, invoiceable: Boolean) = viewModelScope.launch {
        repository.savePart(
            PartEntity(
                reportLocalId = reportId,
                partName = partName,
                serialNumber = serialNumber,
                quantity = quantity,
                invoiceable = invoiceable
            )
        )
    }

    fun removePart(partId: String) = viewModelScope.launch {
        repository.removePart(partId)
    }

    fun addPhoto(reportId: String, uri: String) = viewModelScope.launch {
        repository.saveAttachment(AttachmentEntity(reportLocalId = reportId, filePath = uri))
    }

    fun syncNow() = viewModelScope.launch {
        runCatching { repository.syncPending() }
            .onSuccess { message.value = "Pending reports synced" }
            .onFailure { message.value = it.message ?: "Sync failed; reports are still saved locally" }
    }
}
