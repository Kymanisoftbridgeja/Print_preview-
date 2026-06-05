package com.tanjo.servicereports.data.remote

import com.squareup.moshi.Json
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface MobileApi {
    @POST("/api/mobile/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @GET("/api/mobile/jobs")
    suspend fun jobs(@Header("Authorization") bearer: String): JobsResponse

    @GET("/api/mobile/service-reports")
    suspend fun serviceReports(@Header("Authorization") bearer: String): ServiceReportsResponse

    @GET("/api/mobile/jobs/{jobId}/service-report")
    suspend fun serviceReport(
        @Header("Authorization") bearer: String,
        @Path("jobId") jobId: Long
    ): ServiceReportResponse

    @POST("/api/mobile/jobs/{jobId}/start")
    suspend fun startJob(
        @Header("Authorization") bearer: String,
        @Path("jobId") jobId: Long
    ): JobActionResponse

    @POST("/api/mobile/jobs/{jobId}/stop")
    suspend fun stopJob(
        @Header("Authorization") bearer: String,
        @Path("jobId") jobId: Long
    ): JobActionResponse

    @POST("/api/mobile/service-reports/{reportId}/start")
    suspend fun startReport(
        @Header("Authorization") bearer: String,
        @Path("reportId") reportId: Long
    ): ServiceReportResponse

    @POST("/api/mobile/service-reports/{reportId}/stop")
    suspend fun stopReport(
        @Header("Authorization") bearer: String,
        @Path("reportId") reportId: Long
    ): ServiceReportResponse

    @POST("/api/mobile/service-reports")
    suspend fun upsertReport(
        @Header("Authorization") bearer: String,
        @Body request: ReportDto
    ): ServiceReportResponse

    @POST("/api/mobile/service-reports/{reportId}/submit")
    suspend fun submitReport(
        @Header("Authorization") bearer: String,
        @Path("reportId") reportId: Long,
        @Body request: ReportDto
    ): ServiceReportResponse

    @POST("/api/mobile/sync")
    suspend fun sync(@Header("Authorization") bearer: String, @Body request: SyncRequest): SyncResponse

    @POST("/api/mobile/service-reports/{reportId}/attachments")
    suspend fun uploadAttachments(
        @Header("Authorization") bearer: String,
        @Path("reportId") reportId: Long,
        @Body request: AttachmentUploadRequest
    )
}

data class LoginRequest(val db: String, val login: String, val password: String)
data class LoginResponse(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "expires_at") val expiresAt: String,
    val user: UserDto
)
data class UserDto(val id: Long, val name: String, val login: String)
data class JobsResponse(
    val success: Boolean? = null,
    val error: String? = null,
    val message: String? = null,
    val jobs: List<JobDto> = emptyList()
)
data class JobDto(
    val id: Any?,
    @Json(name = "report_id") val reportId: Any? = null,
    @Json(name = "job_number") val jobNumber: String,
    @Json(name = "customer_id") val customerId: Any?,
    @Json(name = "company_name") val companyName: String?,
    @Json(name = "contact_name") val contactName: String?,
    val address: String?,
    @Json(name = "scheduled_date") val scheduledDate: String?,
    @Json(name = "service_type") val serviceType: String?,
    val status: String?,
    val description: String?
)

data class JobActionResponse(
    val success: Boolean = false,
    val error: String? = null,
    @Json(name = "job_id") val jobId: Long? = null,
    @Json(name = "report_id") val reportId: Long? = null,
    val state: String? = null,
    @Json(name = "sync_status") val syncStatus: String? = null,
    val message: String? = null,
    val report: ServiceReportDto? = null
)

data class ServiceReportResponse(
    val success: Boolean = false,
    val error: String? = null,
    val report: ServiceReportDto? = null,
    @Json(name = "sync_status") val syncStatus: String? = null,
    val message: String? = null
)

data class ServiceReportsResponse(
    val success: Boolean? = null,
    val error: String? = null,
    val message: String? = null,
    val reports: List<ServiceReportDto> = emptyList()
)

data class ServiceReportDto(
    val id: Any? = null,
    val name: String? = null,
    @Json(name = "report_number") val reportNumber: String? = null,
    @Json(name = "job_id") val jobId: Any? = null,
    @Json(name = "field_service_job_id") val fieldServiceJobId: Any? = null,
    @Json(name = "mobile_external_id") val mobileExternalId: String? = null,
    val source: String? = null,
    @Json(name = "submitted_from_mobile") val submittedFromMobile: Boolean? = null,
    @Json(name = "mobile_sync_status") val mobileSyncStatus: String? = null,
    @Json(name = "is_emergency_report") val isEmergencyReport: Boolean? = null,
    @Json(name = "customer_id") val customerId: Any? = null,
    @Json(name = "company_name") val companyName: String? = null,
    @Json(name = "contact_name") val contactName: String? = null,
    @Json(name = "customer_name") val customerName: String? = null,
    val address: String? = null,
    @Json(name = "service_date") val serviceDate: String? = null,
    @Json(name = "arrival_time") val arrivalTime: String? = null,
    @Json(name = "departure_time") val departureTime: String? = null,
    val technician: String? = null,
    @Json(name = "technician_name") val technicianName: String? = null,
    val vehicle: String? = null,
    @Json(name = "po_reference") val poReference: String? = null,
    @Json(name = "service_type") val serviceType: String? = null,
    @Json(name = "original_report_number") val originalReportNumber: String? = null,
    val make: String? = null,
    val model: String? = null,
    val kva: String? = null,
    @Json(name = "equipment_type") val equipmentType: String? = null,
    @Json(name = "serial_number") val serialNumber: String? = null,
    val load: String? = null,
    @Json(name = "input_voltage") val inputVoltage: String? = null,
    @Json(name = "output_voltage") val outputVoltage: String? = null,
    @Json(name = "ups_system_down") val systemDown: Boolean? = null,
    @Json(name = "battery_manufacturer") val batteryManufacturer: String? = null,
    @Json(name = "battery_type") val batteryType: String? = null,
    @Json(name = "battery_rating") val batteryRating: String? = null,
    @Json(name = "battery_quantity") val batteryQuantity: Any? = null,
    @Json(name = "problem_reported") val problemReported: String? = null,
    @Json(name = "defects_found") val defectsFound: String? = null,
    @Json(name = "corrective_action") val correctiveAction: String? = null,
    val recommendations: String? = null,
    @Json(name = "technicians_on_site") val techniciansOnSite: String? = null,
    @Json(name = "status_of_service") val statusOfService: String? = null,
    val state: String? = null,
    @Json(name = "labor_hours") val laborHours: Double? = null,
    @Json(name = "planned_lines") val plannedLines: List<PartDto> = emptyList(),
    val lines: List<PartDto> = emptyList()
)

data class SyncRequest(val reports: List<ReportDto>)
data class SyncResponse(val success: Boolean? = null, val error: String? = null, val results: List<SyncResultDto> = emptyList())
data class SyncResultDto(
    @Json(name = "mobile_external_id") val mobileExternalId: String,
    @Json(name = "odoo_id") val odooId: Long?,
    @Json(name = "report_number") val reportNumber: String?,
    val state: String? = null,
    val status: String,
    val error: String?
)

data class ReportDto(
    val id: Long?,
    @Json(name = "mobile_external_id") val mobileExternalId: String,
    @Json(name = "job_id") val jobId: Long?,
    @Json(name = "field_service_job_id") val fieldServiceJobId: Long?,
    val source: String = "mobile",
    @Json(name = "submitted_from_mobile") val submittedFromMobile: Boolean,
    @Json(name = "customer_id") val customerId: Long?,
    @Json(name = "company_name") val companyName: String,
    @Json(name = "contact_name") val contactName: String,
    @Json(name = "customer_name") val customerName: String,
    val address: String,
    @Json(name = "service_date") val serviceDate: String,
    @Json(name = "arrival_time") val arrivalTime: String?,
    @Json(name = "departure_time") val departureTime: String?,
    val vehicle: String,
    @Json(name = "po_reference") val poReference: String,
    @Json(name = "service_type") val serviceType: String,
    @Json(name = "original_report_number") val originalReportNumber: String,
    val make: String,
    val model: String,
    val kva: String,
    @Json(name = "equipment_type") val equipmentType: String,
    @Json(name = "serial_number") val serialNumber: String,
    val load: String,
    @Json(name = "input_voltage") val inputVoltage: String,
    @Json(name = "output_voltage") val outputVoltage: String,
    @Json(name = "ups_system_down") val systemDown: Boolean,
    @Json(name = "battery_manufacturer") val batteryManufacturer: String,
    @Json(name = "battery_type") val batteryType: String,
    @Json(name = "battery_rating") val batteryRating: String,
    @Json(name = "battery_quantity") val batteryQuantity: Int,
    @Json(name = "problem_reported") val problemReported: String,
    @Json(name = "defects_found") val defectsFound: String,
    @Json(name = "corrective_action") val correctiveAction: String,
    val recommendations: String,
    @Json(name = "technicians_on_site") val techniciansOnSite: String,
    @Json(name = "status_of_service") val statusOfService: String,
    @Json(name = "customer_signature_base64") val customerSignatureBase64: String,
    @Json(name = "technician_signature_base64") val technicianSignatureBase64: String,
    @Json(name = "technician_name") val technicianName: String,
    val state: String,
    val submit: Boolean,
    val parts: List<PartDto>
)

data class PartDto(
    @Json(name = "part_name") val partName: String = "",
    @Json(name = "serial_number") val serialNumber: String = "",
    val quantity: Double = 1.0,
    @Json(name = "line_type") val lineType: String = "actual",
    val invoiceable: Boolean = true,
    val notes: String = ""
)

data class AttachmentUploadRequest(val attachments: List<AttachmentDto>)
data class AttachmentDto(
    val filename: String,
    @Json(name = "mime_type") val mimeType: String,
    @Json(name = "content_base64") val contentBase64: String
)
