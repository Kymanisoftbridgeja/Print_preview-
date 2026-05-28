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
data class JobsResponse(val jobs: List<JobDto>)
data class JobDto(
    val id: Long,
    @Json(name = "job_number") val jobNumber: String,
    @Json(name = "customer_id") val customerId: Long?,
    @Json(name = "company_name") val companyName: String,
    @Json(name = "contact_name") val contactName: String,
    val address: String,
    @Json(name = "scheduled_date") val scheduledDate: String,
    @Json(name = "service_type") val serviceType: String,
    val status: String,
    val description: String
)

data class SyncRequest(val reports: List<ReportDto>)
data class SyncResponse(val results: List<SyncResultDto>)
data class SyncResultDto(
    @Json(name = "mobile_external_id") val mobileExternalId: String,
    @Json(name = "odoo_id") val odooId: Long?,
    @Json(name = "report_number") val reportNumber: String?,
    val status: String,
    val error: String?
)

data class ReportDto(
    @Json(name = "mobile_external_id") val mobileExternalId: String,
    @Json(name = "job_id") val jobId: Long?,
    @Json(name = "customer_id") val customerId: Long?,
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
    @Json(name = "system_down") val systemDown: Boolean,
    @Json(name = "battery_manufacturer") val batteryManufacturer: String,
    @Json(name = "battery_type") val batteryType: String,
    @Json(name = "battery_rating") val batteryRating: String,
    @Json(name = "battery_quantity") val batteryQuantity: Int,
    @Json(name = "problem_reported") val problemReported: String,
    @Json(name = "defects_found") val defectsFound: String,
    @Json(name = "corrective_action") val correctiveAction: String,
    val recommendations: String,
    @Json(name = "status_of_service") val statusOfService: String,
    @Json(name = "customer_signature_base64") val customerSignatureBase64: String,
    @Json(name = "technician_signature_base64") val technicianSignatureBase64: String,
    @Json(name = "technician_name") val technicianName: String,
    val state: String,
    val submit: Boolean,
    val parts: List<PartDto>
)

data class PartDto(
    @Json(name = "part_name") val partName: String,
    @Json(name = "serial_number") val serialNumber: String,
    val quantity: Double,
    @Json(name = "line_type") val lineType: String = "part",
    val invoiceable: Boolean,
    val notes: String
)

data class AttachmentUploadRequest(val attachments: List<AttachmentDto>)
data class AttachmentDto(
    val filename: String,
    @Json(name = "mime_type") val mimeType: String,
    @Json(name = "content_base64") val contentBase64: String
)
