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

class ServiceRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        context.getSharedPreferences("service_reports", Context.MODE_PRIVATE)
    private val dao = AppDatabase.get(context).dao()

    val jobs: Flow<List<JobEntity>> = dao.observeJobs()

    suspend fun login(baseUrl: String, db: String, login: String, password: String) {
        val cleanUrl = baseUrl.trim()
        require(cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
            "Enter the Odoo server URL starting with http:// or https://"
        }
        val response = runRemote("login") {
            ApiFactory.create(cleanUrl).login(LoginRequest(db.trim(), login.trim(), password))
        }
        prefs.edit()
            .putString("base_url", cleanUrl)
            .putString("token", response.accessToken)
            .putString("technician_name", response.user.name)
            .putString("device_id", prefs.getString("device_id", null) ?: UUID.randomUUID().toString())
            .apply()
        refreshJobs()
    }

    suspend fun refreshJobs() {
        val response = runRemote("jobs") { api().jobs(bearer()) }
        if (response.success == false) throw IllegalStateException(response.error ?: "Odoo rejected the Job sync.")
        val jobs = response.jobs.mapNotNull {
                val jobId = it.id.asLongOrNull() ?: return@mapNotNull null
                JobEntity(
                    id = jobId,
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
    }

    suspend fun reportForJob(job: JobEntity): ServiceReportEntity {
        val existing = dao.reportForJob(job.id)
        return try {
            val response = runRemote("service-report") { api().serviceReport(bearer(), job.id) }
            if (!response.success || response.report == null) {
                throw IllegalStateException(response.error ?: "Service report not found.")
            }
            val local = response.report.toEntity(existing, job)
            dao.upsertReport(local)
            replaceParts(local.localId, response.report.lines)
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
        val jobId = localStart.jobId ?: return "Start saved locally for emergency report"
        return try {
            val response = runRemote("start-job") { api().startJob(bearer(), jobId) }
            applyJobAction(localStart, response)
            response.message ?: "Job started successfully"
        } catch (error: Throwable) {
            val status = if (error is IOException) "Pending Sync" else "Sync Failed"
            dao.upsertReport(localStart.copy(syncStatus = status, syncError = readableError(error)))
            throw IllegalStateException(readableError(error), error)
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
        val jobId = localStop.jobId ?: return "Stop saved locally for emergency report"
        return try {
            val response = runRemote("stop-job") { api().stopJob(bearer(), jobId) }
            applyJobAction(localStop, response)
            response.message ?: "Job stopped successfully"
        } catch (error: Throwable) {
            val status = if (error is IOException) "Pending Sync" else "Sync Failed"
            dao.upsertReport(localStop.copy(syncStatus = status, syncError = readableError(error)))
            throw IllegalStateException(readableError(error), error)
        }
    }

    suspend fun submitReport(report: ServiceReportEntity): String {
        validateForSubmit(report)
        val pending = report.copy(state = "submitted", syncStatus = "Pending Sync", syncError = "")
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
            response.message ?: "Service report submitted successfully"
        } catch (error: Throwable) {
            val status = if (error is IOException) "Pending Sync" else "Sync Failed"
            dao.upsertReport(pending.copy(syncStatus = status, syncError = readableError(error)))
            throw IllegalStateException(readableError(error), error)
        }
    }

    suspend fun syncPending() {
        val pending = dao.pendingReports()
        if (pending.isEmpty()) return
        pending.forEach { dao.upsertReport(it.copy(syncStatus = "Syncing", syncError = "")) }
        val response = runRemote("bulk-sync") { api().sync(bearer(), SyncRequest(pending.map { it.toDto(submit = it.state == "submitted") })) }
        if (response.success == false) throw IllegalStateException(response.error ?: "Odoo rejected the sync request.")
        response.results.forEach { result ->
            pending.firstOrNull { it.mobileExternalId == result.mobileExternalId }?.let { report ->
                val synced = result.status == "synced"
                if (synced && result.odooId != null) uploadAttachments(report, result.odooId)
                dao.upsertReport(
                    report.copy(
                        odooId = result.odooId ?: report.odooId,
                        reportNumber = result.reportNumber ?: report.reportNumber,
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

    private suspend fun replaceParts(localId: String, lines: List<PartDto>) {
        dao.deletePartsForReport(localId)
        lines.forEach {
            dao.upsertPart(
                PartEntity(
                    reportLocalId = localId,
                    partName = it.partName,
                    serialNumber = it.serialNumber,
                    quantity = it.quantity,
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
        val parts = dao.partsForReport(localId).map {
            PartDto(it.partName, it.serialNumber, it.quantity, "part", it.invoiceable, it.notes)
        }
        return ReportDto(
            id = odooId,
            mobileExternalId = mobileExternalId,
            jobId = jobId,
            customerId = customerId,
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
            state = if (submit) "submitted" else state,
            submit = submit,
            parts = parts
        )
    }

    private fun ServiceReportDto.toEntity(existing: ServiceReportEntity?, job: JobEntity?): ServiceReportEntity {
        return ServiceReportEntity(
            localId = existing?.localId ?: UUID.randomUUID().toString(),
            odooId = id.asLongOrNull(),
            mobileExternalId = mobileExternalId ?: existing?.mobileExternalId ?: this@ServiceRepository.mobileExternalId(),
            jobId = jobId.asLongOrNull() ?: existing?.jobId ?: job?.id,
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
            state = state ?: existing?.state ?: "draft",
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
            Log.e(TAG, "$label failed: ${readableError(error)}", error)
            throw error
        }
    }

    private fun readableError(error: Throwable): String {
        if (error is HttpException) {
            val body = error.response()?.errorBody()?.string()
            val odooError = body?.let {
                runCatching { JSONObject(it).optString("error") }.getOrNull()
            }.orEmpty()
            return odooError.ifBlank { "Odoo endpoint returned ${error.code()}" }
        }
        return error.message ?: "Server unavailable"
    }

    private fun mobileExternalId(): String =
        "${prefs.getString("device_id", UUID.randomUUID().toString())}-${UUID.randomUUID()}"

    private fun bearer() = "Bearer ${prefs.getString("token", "")}"
    private fun api() = ApiFactory.create(prefs.getString("base_url", "") ?: "")

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
