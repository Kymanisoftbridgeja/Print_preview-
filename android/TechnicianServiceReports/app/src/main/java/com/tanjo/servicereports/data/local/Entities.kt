package com.tanjo.servicereports.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey val id: Long,
    val reportId: Long? = null,
    val jobNumber: String,
    val customerId: Long?,
    val companyName: String,
    val contactName: String,
    val address: String,
    val scheduledDate: String,
    val serviceType: String,
    val jobStatus: String,
    val syncStatus: String = "Synced",
    val reportStatus: String = "New",
    val description: String = ""
)

@Entity(tableName = "service_reports")
data class ServiceReportEntity(
    @PrimaryKey val localId: String = UUID.randomUUID().toString(),
    val odooId: Long? = null,
    val mobileExternalId: String,
    val jobId: Long? = null,
    val reportNumber: String = "",
    val customerId: Long? = null,
    val customerName: String = "",
    val companyName: String = "",
    val contactName: String = "",
    val address: String = "",
    val serviceDate: String = "",
    val arrivalTime: String = "",
    val departureTime: String = "",
    val laborHours: Double = 0.0,
    val vehicle: String = "",
    val poReference: String = "",
    val serviceType: String = "",
    val originalReportNumber: String = "",
    val make: String = "",
    val model: String = "",
    val kva: String = "",
    val equipmentType: String = "",
    val serialNumber: String = "",
    val load: String = "",
    val inputVoltage: String = "",
    val outputVoltage: String = "",
    val systemDown: Boolean = false,
    val batteryManufacturer: String = "",
    val batteryType: String = "",
    val batteryRating: String = "",
    val batteryQuantity: Int = 0,
    val problemReported: String = "",
    val defectsFound: String = "",
    val correctiveAction: String = "",
    val recommendations: String = "",
    val techniciansOnSite: String = "",
    val statusOfService: String = "",
    val customerSignaturePath: String = "",
    val technicianSignaturePath: String = "",
    val technicianName: String = "",
    val signatureDateTime: String = "",
    val state: String = "assigned",
    val syncStatus: String = "Local Draft",
    val syncError: String = ""
)

@Entity(tableName = "parts")
data class PartEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val reportLocalId: String,
    val partName: String,
    val serialNumber: String = "",
    val quantity: Double = 1.0,
    val conditionType: String = "",
    val invoiceable: Boolean = true,
    val notes: String = ""
)

@Entity(tableName = "attachments")
data class AttachmentEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val reportLocalId: String,
    val filePath: String,
    val mimeType: String = "image/jpeg",
    val category: String = "work_photo"
)
