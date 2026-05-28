package com.tanjo.servicereports.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import com.tanjo.servicereports.data.local.AttachmentEntity
import com.tanjo.servicereports.data.local.AppDatabase
import com.tanjo.servicereports.data.local.JobEntity
import com.tanjo.servicereports.data.local.PartEntity
import com.tanjo.servicereports.data.local.ServiceReportEntity
import com.tanjo.servicereports.data.remote.ApiFactory
import com.tanjo.servicereports.data.remote.AttachmentDto
import com.tanjo.servicereports.data.remote.AttachmentUploadRequest
import com.tanjo.servicereports.data.remote.LoginRequest
import com.tanjo.servicereports.data.remote.PartDto
import com.tanjo.servicereports.data.remote.ReportDto
import com.tanjo.servicereports.data.remote.SyncRequest
import retrofit2.HttpException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow

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
        val api = ApiFactory.create(cleanUrl)
        val response = runCatching {
            api.login(LoginRequest(db.trim(), login.trim(), password))
        }.getOrElse { throw friendlyConnectionError(it) }
        prefs.edit()
            .putString("base_url", cleanUrl)
            .putString("token", response.accessToken)
            .putString("technician_name", response.user.name)
            .putString("device_id", prefs.getString("device_id", null) ?: UUID.randomUUID().toString())
            .apply()
        refreshJobs()
    }

    private fun friendlyConnectionError(error: Throwable): Throwable {
        val message = when (error) {
            is HttpException -> "Odoo rejected the login (${error.code()}). Check the database, user, password, and connector module."
            else -> error.message ?: "Could not reach Odoo. Check the server URL and network."
        }
        return IllegalStateException(message, error)
    }

    suspend fun refreshJobs() {
        val api = api()
        val response = api.jobs(bearer())
        dao.upsertJobs(
            response.jobs.mapNotNull {
                val jobId = it.id.asLongOrNull() ?: return@mapNotNull null
                JobEntity(
                    id = jobId,
                    jobNumber = it.jobNumber,
                    customerId = it.customerId.asLongOrNull(),
                    companyName = it.companyName.orEmpty(),
                    contactName = it.contactName.orEmpty(),
                    address = it.address.orEmpty(),
                    scheduledDate = it.scheduledDate.orEmpty(),
                    serviceType = it.serviceType.orEmpty(),
                    jobStatus = it.status.orEmpty(),
                    description = it.description.orEmpty()
                )
            }
        )
    }

    suspend fun reportForJob(job: JobEntity): ServiceReportEntity {
        return dao.reportForJob(job.id) ?: ServiceReportEntity(
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
            technicianName = prefs.getString("technician_name", "") ?: ""
        ).also { dao.upsertReport(it) }
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
    suspend fun saveReport(report: ServiceReportEntity) = dao.upsertReport(report)
    suspend fun savePart(part: PartEntity) = dao.upsertPart(part)
    suspend fun saveAttachment(attachment: AttachmentEntity) = dao.upsertAttachment(attachment)

    suspend fun startJob(report: ServiceReportEntity) {
        dao.upsertReport(report.copy(arrivalTime = Instant.now().toString(), state = "in_progress", syncStatus = "Pending Sync"))
    }

    suspend fun stopJob(report: ServiceReportEntity) {
        val departure = Instant.now()
        val labor = runCatching {
            Duration.between(Instant.parse(report.arrivalTime), departure).toMinutes() / 60.0
        }.getOrDefault(0.0)
        dao.upsertReport(report.copy(departureTime = departure.toString(), laborHours = labor, state = "completed", syncStatus = "Pending Sync"))
    }

    suspend fun markPendingSubmit(report: ServiceReportEntity) {
        dao.upsertReport(report.copy(state = "submitted", syncStatus = "Pending Sync"))
    }

    suspend fun syncPending() {
        val pending = dao.pendingReports()
        if (pending.isEmpty()) return
        pending.forEach { dao.upsertReport(it.copy(syncStatus = "Syncing")) }
        val response = api().sync(bearer(), SyncRequest(pending.map { it.toDto() }))
        response.results.forEach { result ->
            pending.firstOrNull { it.mobileExternalId == result.mobileExternalId }?.let { report ->
                if (result.status == "synced" && result.odooId != null) {
                    uploadAttachments(report, result.odooId)
                }
                dao.upsertReport(
                    report.copy(
                        odooId = result.odooId ?: report.odooId,
                        reportNumber = result.reportNumber ?: report.reportNumber,
                        syncStatus = if (result.status == "synced") "Synced" else "Sync Failed"
                    )
                )
            }
        }
    }

    private suspend fun uploadAttachments(report: ServiceReportEntity, odooId: Long) {
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
            api().uploadAttachments(bearer(), odooId, AttachmentUploadRequest(payload))
        }
    }

    private suspend fun ServiceReportEntity.toDto(): ReportDto {
        val parts = dao.partsForReport(localId).map {
            PartDto(it.partName, it.serialNumber, it.quantity, "part", it.invoiceable, it.notes)
        }
        return ReportDto(
            mobileExternalId, jobId, customerId, customerName, address, serviceDate,
            arrivalTime.ifBlank { null }, departureTime.ifBlank { null }, vehicle, poReference,
            serviceType, originalReportNumber, make, model, kva, equipmentType, serialNumber,
            load, inputVoltage, outputVoltage, systemDown, batteryManufacturer, batteryType,
            batteryRating, batteryQuantity, problemReported, defectsFound, correctiveAction,
            recommendations, statusOfService, customerSignaturePath, technicianSignaturePath,
            technicianName, state, state == "submitted", parts
        )
    }

    private fun mobileExternalId(): String = "${prefs.getString("device_id", UUID.randomUUID().toString())}-${UUID.randomUUID()}"
    private fun bearer() = "Bearer ${prefs.getString("token", "")}"
    private fun api() = ApiFactory.create(prefs.getString("base_url", "") ?: "")

    private fun Any?.asLongOrNull(): Long? = when (this) {
        is Number -> toLong()
        is String -> toLongOrNull()
        else -> null
    }
}
