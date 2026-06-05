package com.tanjo.servicereports.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.tanjo.servicereports.data.local.AttachmentEntity
import com.tanjo.servicereports.data.local.AppDatabase
import com.tanjo.servicereports.data.local.JobEntity
import com.tanjo.servicereports.data.local.PartEntity
import com.tanjo.servicereports.data.local.ServiceReportEntity
import com.tanjo.servicereports.data.remote.ApiFactory
import com.tanjo.servicereports.data.remote.AttachmentDto
import com.tanjo.servicereports.data.remote.AttachmentUploadRequest
import com.tanjo.servicereports.data.remote.JobActionResponse
import com.tanjo.servicereports.data.remote.LoginRequest
import com.tanjo.servicereports.data.remote.PartDto
import com.tanjo.servicereports.data.remote.ReportDto
import com.tanjo.servicereports.data.remote.ServiceReportDto
import com.tanjo.servicereports.data.remote.SyncRequest
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import retrofit2.HttpException

data class ConnectionDefaults(
    val baseUrl: String = "",
    val db: String = "",
    val login: String = ""
)

class ServiceRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        context.getSharedPreferences("service_reports", Context.MODE_PRIVATE)
    private val dao = AppDatabase.get(context).dao()

    val jobs: Flow<List<JobEntity>> = dao.observeJobs()
    val serviceReports: Flow<List<ServiceReportEntity>> = dao.observeReports()

    val connectionDefaults: ConnectionDefaults
        get() = ConnectionDefaults(
            baseUrl = prefs.getString("base_url", "").orEmpty(),
            db = prefs.getString("db", "").orEmpty(),
            login = prefs.getString("login", "").orEmpty()
        )

    fun hasSavedSession(): Boolean =
        prefs.getString("base_url", "").orEmpty().isNotBlank() &&
            prefs.getString("token", "").orEmpty().isNotBlank()

    suspend fun login(baseUrl: String, db: String, login: String, password: String): String {
        val cleanUrl = baseUrl.trim()
        require(cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
            "Enter the Odoo server URL starting with http:// or https://"
        }
        val response = runRemote("login") {
            ApiFactory.create(cleanUrl).login(LoginRequest(db.trim(), login.trim(), password))
        }
        prefs.edit()
            .putString("base_url", cleanUrl)
            .putString("db", db.trim())
            .putString("login", login.trim())
            .putString("token", response.accessToken)
            .putString("technician_name", response.user.name)
            .putString("device_id", prefs.getString("device_id", null) ?: UUID.randomUUID().toString())
            .apply()
        refreshServiceReports()
        return refreshJobs()
    }

    suspend fun refreshJobs(): String {
        val response = runRemote("jobs") { api().jobs(bearer()) }
        if (response.success == false) throw IllegalStateException(response.error ?: "Odoo rejected the Job sync.")
        val jobs = response.jobs.mapNotNull {
                val jobId = it.id.asLongOrNull() ?: return@mapNotNull null
                JobEntity(
                    id = jobId,
                    reportId = it.reportId.asLongOrNull(),
                    jobNumber = it.jobNumber.ifBlank { "Job #$jobId" },
                    customerId = it.customerId.asLongOrNull(),
                    companyName = it.companyName.orEmpty(),
                    contactName = it.contactName.orEmpty(),
                    address = it.address.orEmpty(),
                    scheduledDate = it.scheduledDate.orEmpty(),
                    serviceType = it.serviceType.orEmpty(),
                    jobStatus = it.status.orEmpty(),
                    syncStatus = "Synced",
                    reportStatus = it.status.orEmpty(),
                    description = it.description.orEmpty()
                )
            }
        dao.deleteJobs()
        dao.upsertJobs(jobs)
        return response.message?.takeIf { it.isNotBlank() } ?: "${jobs.size} Jobs synced"
    }

    suspend fun refreshServiceReports(): String {
        val response = runRemote("service-reports") { api().serviceReports(bearer()) }
        if (response.success == false) throw IllegalStateException(response.error ?: "Odoo rejected the Service Report sync.")
        response.reports.forEach { remote ->
            val existing = remote.id.asLongOrNull()?.let { dao.reportForOdooId(it) }
            val local = remote.toEntity(existing, null)
            dao.upsertReport(local)
            replaceParts(local.localId, remote.plannedLines, remote.lines)
        }
        return response.message?.takeIf { it.isNotBlank() } ?: "${response.reports.size} Service Reports synced"
    }

    suspend fun reportForJob(job: JobEntity): ServiceReportEntity {
        val existing = job.reportId?.let { dao.reportForOdooId(it) } ?: dao.reportForJob(job.id)
        return try {
            val response = runRemote("service-report") { api().serviceReport(bearer(), job.reportId ?: job.id) }
            if (!response.success || response.report == null) {
                throw IllegalStateException(response.error ?: "Service report not found.")
            }
            val local = response.report.toEntity(existing, job)
            dao.upsertReport(local)
            replaceParts(local.localId, response.report.plannedLines, response.report.lines)
            local
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to load report from Odoo; using local copy", error)
            existing ?: ServiceReportEntity(
                mobileExternalId = mobileExternalId(),
                jobId = job.id,
                customerId = job.customerId,
                customerName = job.contactName.ifBlank { job.companyName },
                companyName = job.companyName,
                contactName = job.contactName,
                address = job.address,
                serviceDate = job.scheduledDate.take(10),
                serviceType = job.serviceType,
                problemReported = job.description,
                technicianName = prefs.getString("technician_name", "") ?: "",
                syncStatus = "Local Draft",
                syncError = readableError(error)
            ).also { dao.upsertReport(it) }
        }
    }

    suspend fun newEmergencyReport(): ServiceReportEntity {
        return ServiceReportEntity(
            mobileExternalId = mobileExternalId(),
            technicianName = prefs.getString("technician_name", "") ?: "",
            syncStatus = "Local Draft"
        ).also { dao.upsertReport(it) }
    }

    fun observeReport(localId: String) = dao.observeReport(localId)
    fun observeParts(localId: String) = dao.observeParts(localId)

    suspend fun saveReport(report: ServiceReportEntity) {
        dao.upsertReport(report.copy(syncStatus = "Local Draft", syncError = ""))
    }

    suspend fun savePart(part: PartEntity) = dao.upsertPart(part)
    suspend fun removePart(partId: String) = dao.deletePart(partId)
    suspend fun saveAttachment(attachment: AttachmentEntity) = dao.upsertAttachment(attachment)

    suspend fun startJob(report: ServiceReportEntity): String {
        val localStart = report.copy(
            serviceDate = report.serviceDate.ifBlank { todayString() },
            arrivalTime = report.arrivalTime.ifBlank { nowTimeString() },
            state = "in_progress",
            syncStatus = "Pending Sync",
            syncError = ""
        )
        dao.upsertReport(localStart)
        val reportId = localStart.odooId
        val remoteId = reportId ?: localStart.jobId ?: return "Start saved locally for emergency report"
        return try {
            if (reportId != null) {
                val response = runRemote("start-report") { api().startReport(bearer(), reportId) }
                applyReportResponse(localStart, response)
                response.message ?: "Service report started successfully"
            } else {
                val response = runRemote("start-job") { api().startJob(bearer(), remoteId) }
                applyJobAction(localStart, response)
                response.message ?: "Service report started successfully"
            }
        } catch (error: Throwable) {
            val status = if (error is IOException) "Pending Sync" else "Sync Failed"
            val errorText = readableError(error)
            val failed = if (error is IOException) {
                localStart.copy(syncStatus = status, syncError = errorText)
            } else {
                report.copy(syncStatus = status, syncError = errorText)
            }
            dao.upsertReport(failed)
            throw IllegalStateException(errorText, error)
        }
    }

    suspend fun stopJob(report: ServiceReportEntity): String {
        val localStop = report.copy(
            serviceDate = report.serviceDate.ifBlank { todayString() },
            departureTime = report.departureTime.ifBlank { nowTimeString() },
            state = "completed",
            syncStatus = "Pending Sync",
            syncError = ""
        )
        dao.upsertReport(localStop)
        val reportId = localStop.odooId
        val remoteId = reportId ?: localStop.jobId ?: return "Stop saved locally for emergency report"
        return try {
            if (reportId != null) {
                val response = runRemote("stop-report") { api().stopReport(bearer(), reportId) }
                applyReportResponse(localStop, response)
                response.message ?: "Service report stopped successfully"
            } else {
                val response = runRemote("stop-job") { api().stopJob(bearer(), remoteId) }
                applyJobAction(localStop, response)
                response.message ?: "Service report stopped successfully"
            }
        } catch (error: Throwable) {
            val status = if (error is IOException) "Pending Sync" else "Sync Failed"
            val errorText = readableError(error)
            val failed = if (error is IOException) {
                localStop.copy(syncStatus = status, syncError = errorText)
            } else {
                report.copy(syncStatus = status, syncError = errorText)
            }
            dao.upsertReport(failed)
            throw IllegalStateException(errorText, error)
        }
    }

    suspend fun submitReport(report: ServiceReportEntity): String {
        validateForSubmit(report)
        val pending = report.copy(state = "completed", syncStatus = "Pending Sync", syncError = "")
        dao.upsertReport(pending)
        return try {
            val dto = pending.toDto(submit = true)
            val response = if (pending.odooId != null) {
                runRemote("submit-report") { api().submitReport(bearer(), pending.odooId, dto) }
            } else {
                runRemote("upsert-report") { api().upsertReport(bearer(), dto) }
            }
            if (!response.success || response.report == null) {
                throw IllegalStateException(response.error ?: "Odoo rejected the service report.")
            }
            val synced = response.report.toEntity(pending, null).copy(syncStatus = "Synced", syncError = "")
            dao.upsertReport(synced)
            uploadAttachments(synced, synced.odooId ?: response.report.id.asLongOrNull() ?: 0L)
            response.message ?: "Service report completed successfully"
        } catch (error: Throwable) {
            val status = if (error is IOException) "Pending Sync" else "Sync Failed"
            val errorText = readableError(error)
            dao.upsertReport(pending.copy(syncStatus = status, syncError = errorText))
            "Report saved locally. Sync failed: $errorText"
        }
    }

    suspend fun syncPending() {
        val pending = dao.pendingReports()
        if (pending.isEmpty()) return
        pending.forEach { dao.upsertReport(it.copy(syncStatus = "Syncing", syncError = "")) }
        val response = runRemote("bulk-sync") { api().sync(bearer(), SyncRequest(pending.map { it.toDto(submit = it.state == "completed") })) }
        if (response.success == false) throw IllegalStateException(response.error ?: "Odoo rejected the sync request.")
        response.results.forEach { result ->
            pending.firstOrNull { it.mobileExternalId == result.mobileExternalId }?.let { report ->
                val synced = result.status == "synced"
                if (synced && result.odooId != null) uploadAttachments(report, result.odooId)
                dao.upsertReport(
                    report.copy(
                        odooId = result.odooId ?: report.odooId,
                        reportNumber = result.reportNumber ?: report.reportNumber,
                        state = result.state ?: report.state,
                        syncStatus = if (synced) "Synced" else "Sync Failed",
                        syncError = result.error.orEmpty()
                    )
                )
            }
        }
    }

    private suspend fun applyJobAction(local: ServiceReportEntity, response: JobActionResponse) {
        if (!response.success) throw IllegalStateException(response.error ?: "Odoo rejected the Job update.")
        val updated = response.report?.toEntity(local, null) ?: local.copy(
            odooId = response.reportId ?: local.odooId,
            state = response.state ?: local.state
        )
        dao.upsertReport(updated.copy(syncStatus = "Synced", syncError = ""))
    }

    private suspend fun applyReportResponse(local: ServiceReportEntity, response: com.tanjo.servicereports.data.remote.ServiceReportResponse) {
        if (!response.success) throw IllegalStateException(response.error ?: "Odoo rejected the Service Report update.")
        val updated = response.report?.toEntity(local, null) ?: local
        dao.upsertReport(updated.copy(syncStatus = "Synced", syncError = ""))
    }

    private suspend fun uploadAttachments(report: ServiceReportEntity, odooId: Long) {
        if (odooId <= 0) return
        val attachments = dao.attachmentsForReport(report.localId)
        if (attachments.isEmpty()) return
        val payload = attachments.mapNotNull { attachment ->
            runCatching {
                val uri = Uri.parse(attachment.filePath)
                val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@mapNotNull null
                AttachmentDto(
                    filename = uri.lastPathSegment ?: "service-photo.jpg",
                    mimeType = attachment.mimeType,
                    contentBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                )
            }.getOrNull()
        }
        if (payload.isNotEmpty()) {
            runRemote("upload-attachments") {
                api().uploadAttachments(bearer(), odooId, AttachmentUploadRequest(payload))
            }
        }
    }

    private suspend fun replaceParts(localId: String, plannedLines: List<PartDto>, actualLines: List<PartDto>) {
        dao.deletePartsForReport(localId)
        plannedLines.forEach {
            dao.upsertPart(
                PartEntity(
                    reportLocalId = localId,
                    partName = it.partName,
                    serialNumber = it.serialNumber,
                    quantity = it.quantity,
                    conditionType = "planned",
                    invoiceable = it.invoiceable,
                    notes = it.notes
                )
            )
        }
        actualLines.forEach {
            dao.upsertPart(
                PartEntity(
                    reportLocalId = localId,
                    partName = it.partName,
                    serialNumber = it.serialNumber,
                    quantity = it.quantity,
                    conditionType = "actual",
                    invoiceable = it.invoiceable,
                    notes = it.notes
                )
            )
        }
    }

    private fun validateForSubmit(report: ServiceReportEntity) {
        if (report.customerId == null && report.customerName.isBlank() && report.companyName.isBlank()) {
            throw IllegalStateException("Missing required field: customer_id or customer name")
        }
        if (report.problemReported.isBlank()) {
            throw IllegalStateException("Missing required field: Problem / Service Rendered")
        }
    }

    private suspend fun ServiceReportEntity.toDto(submit: Boolean): ReportDto {
        val parts = dao.partsForReport(localId).filter { it.conditionType != "planned" }.map {
            PartDto(it.partName, it.serialNumber, it.quantity, "actual", it.invoiceable, it.notes)
        }
        return ReportDto(
            id = odooId,
            mobileExternalId = mobileExternalId,
            jobId = jobId,
            fieldServiceJobId = jobId,
            source = "mobile",
            submittedFromMobile = submit,
            customerId = customerId,
            companyName = companyName,
            contactName = contactName,
            customerName = customerName.ifBlank { contactName.ifBlank { companyName } },
            address = address,
            serviceDate = normalizeDateForOdoo(serviceDate),
            arrivalTime = normalizeTimeForOdoo(arrivalTime),
            departureTime = normalizeTimeForOdoo(departureTime),
            vehicle = vehicle,
            poReference = poReference,
            serviceType = serviceType,
            originalReportNumber = originalReportNumber,
            make = make,
            model = model,
            kva = kva,
            equipmentType = equipmentType,
            serialNumber = serialNumber,
            load = load,
            inputVoltage = inputVoltage,
            outputVoltage = outputVoltage,
            systemDown = systemDown,
            batteryManufacturer = batteryManufacturer,
            batteryType = batteryType,
            batteryRating = batteryRating,
            batteryQuantity = batteryQuantity,
            problemReported = problemReported,
            defectsFound = defectsFound,
            correctiveAction = correctiveAction,
            recommendations = recommendations,
            techniciansOnSite = techniciansOnSite,
            statusOfService = statusOfService,
            customerSignatureBase64 = customerSignaturePath,
            technicianSignatureBase64 = technicianSignaturePath,
            technicianName = technicianName,
            state = if (submit) "completed" else state,
            submit = submit,
            parts = parts
        )
    }

    private fun ServiceReportDto.toEntity(existing: ServiceReportEntity?, job: JobEntity?): ServiceReportEntity {
        return ServiceReportEntity(
            localId = existing?.localId ?: UUID.randomUUID().toString(),
            odooId = id.asLongOrNull(),
            mobileExternalId = mobileExternalId ?: existing?.mobileExternalId ?: this@ServiceRepository.mobileExternalId(),
            jobId = fieldServiceJobId.asLongOrNull() ?: jobId.asLongOrNull() ?: existing?.jobId ?: job?.id,
            reportNumber = reportNumber ?: name ?: existing?.reportNumber.orEmpty(),
            customerId = customerId.asLongOrNull() ?: existing?.customerId ?: job?.customerId,
            customerName = customerName ?: existing?.customerName ?: job?.contactName.orEmpty(),
            companyName = companyName ?: existing?.companyName ?: job?.companyName.orEmpty(),
            contactName = contactName ?: existing?.contactName ?: job?.contactName.orEmpty(),
            address = address ?: existing?.address ?: job?.address.orEmpty(),
            serviceDate = serviceDate ?: existing?.serviceDate ?: job?.scheduledDate?.take(10).orEmpty(),
            arrivalTime = arrivalTime ?: existing?.arrivalTime.orEmpty(),
            departureTime = departureTime ?: existing?.departureTime.orEmpty(),
            laborHours = laborHours ?: existing?.laborHours ?: 0.0,
            vehicle = vehicle ?: existing?.vehicle.orEmpty(),
            poReference = poReference ?: existing?.poReference.orEmpty(),
            serviceType = serviceType ?: existing?.serviceType ?: job?.serviceType.orEmpty(),
            originalReportNumber = originalReportNumber ?: existing?.originalReportNumber.orEmpty(),
            make = make ?: existing?.make.orEmpty(),
            model = model ?: existing?.model.orEmpty(),
            kva = kva ?: existing?.kva.orEmpty(),
            equipmentType = equipmentType ?: existing?.equipmentType.orEmpty(),
            serialNumber = serialNumber ?: existing?.serialNumber.orEmpty(),
            load = load ?: existing?.load.orEmpty(),
            inputVoltage = inputVoltage ?: existing?.inputVoltage.orEmpty(),
            outputVoltage = outputVoltage ?: existing?.outputVoltage.orEmpty(),
            systemDown = systemDown ?: existing?.systemDown ?: false,
            batteryManufacturer = batteryManufacturer ?: existing?.batteryManufacturer.orEmpty(),
            batteryType = batteryType ?: existing?.batteryType.orEmpty(),
            batteryRating = batteryRating ?: existing?.batteryRating.orEmpty(),
            batteryQuantity = batteryQuantity.asIntOrNull() ?: existing?.batteryQuantity ?: 0,
            problemReported = problemReported ?: existing?.problemReported ?: job?.description.orEmpty(),
            defectsFound = defectsFound ?: existing?.defectsFound.orEmpty(),
            correctiveAction = correctiveAction ?: existing?.correctiveAction.orEmpty(),
            recommendations = recommendations ?: existing?.recommendations.orEmpty(),
            techniciansOnSite = techniciansOnSite ?: existing?.techniciansOnSite.orEmpty(),
            statusOfService = statusOfService ?: existing?.statusOfService.orEmpty(),
            customerSignaturePath = existing?.customerSignaturePath.orEmpty(),
            technicianSignaturePath = existing?.technicianSignaturePath.orEmpty(),
            technicianName = technicianName ?: technician ?: existing?.technicianName ?: prefs.getString("technician_name", "").orEmpty(),
            signatureDateTime = existing?.signatureDateTime.orEmpty(),
            state = state ?: existing?.state ?: "assigned",
            syncStatus = "Synced",
            syncError = ""
        )
    }

    private suspend fun <T> runRemote(label: String, block: suspend () -> T): T {
        return try {
            val result = block()
            Log.d(TAG, "$label response: $result")
            result
        } catch (error: Throwable) {
            val message = readableError(error)
            Log.e(TAG, "$label failed: $message", error)
            if (error is IOException) {
                throw error
            }
            throw IllegalStateException(message, error)
        }
    }

    private fun readableError(error: Throwable): String {
        if (error is HttpException) {
            val body = error.response()?.errorBody()?.string().orEmpty()
            val odooError = runCatching {
                val json = JSONObject(body)
                json.optString("error")
                    .ifBlank { json.optString("message") }
                    .ifBlank { json.optJSONObject("data")?.optString("message").orEmpty() }
            }.getOrDefault("")
            return odooError
                .ifBlank { body.trim().take(500) }
                .ifBlank { "Odoo endpoint returned ${error.code()}" }
        }
        return error.message ?: "Server unavailable"
    }

    private fun mobileExternalId(): String =
        "${prefs.getString("device_id", UUID.randomUUID().toString())}-${UUID.randomUUID()}"

    private fun bearer(): String {
        val token = prefs.getString("token", "").orEmpty()
        require(token.isNotBlank()) { "Log in before syncing Jobs." }
        return "Bearer $token"
    }

    private fun api(): com.tanjo.servicereports.data.remote.MobileApi {
        val baseUrl = prefs.getString("base_url", "").orEmpty()
        require(baseUrl.isNotBlank()) { "Log in before syncing Jobs." }
        return ApiFactory.create(baseUrl)
    }

    private fun Any?.asLongOrNull(): Long? = when (this) {
        is Number -> toLong()
        is String -> toLongOrNull()
        else -> null
    }

    private fun Any?.asIntOrNull(): Int? = when (this) {
        is Number -> toInt()
        is String -> toIntOrNull()
        else -> null
    }

    private fun nowTimeString(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

    private fun todayString(): String = LocalDate.now().toString()

    private fun normalizeDateForOdoo(value: String): String =
        when {
            value.isBlank() -> todayString()
            value.length >= 10 -> value.take(10)
            else -> value
        }

    private fun normalizeTimeForOdoo(value: String): String? {
        if (value.isBlank()) return null
        if (value.length >= 16 && value[10] == 'T') return value.substring(11, 16)
        if (value.length >= 16 && value[10] == ' ') return value.substring(11, 16)
        return value.take(5)
    }

    companion object {
        private const val TAG = "ServiceRepository"
    }
}
