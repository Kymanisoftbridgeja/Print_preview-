package com.tanjo.servicereports.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ServiceReportDao {
    @Query("select * from jobs order by scheduledDate asc")
    fun observeJobs(): Flow<List<JobEntity>>

    @Query("select * from service_reports where localId = :localId")
    fun observeReport(localId: String): Flow<ServiceReportEntity?>

    @Query("select * from service_reports where jobId = :jobId limit 1")
    suspend fun reportForJob(jobId: Long): ServiceReportEntity?

    @Query("select * from service_reports where syncStatus in ('Pending Sync', 'Sync Failed', 'Syncing')")
    suspend fun pendingReports(): List<ServiceReportEntity>

    @Query("select * from parts where reportLocalId = :localId")
    fun observeParts(localId: String): Flow<List<PartEntity>>

    @Query("select * from parts where reportLocalId = :localId")
    suspend fun partsForReport(localId: String): List<PartEntity>

    @Query("select * from attachments where reportLocalId = :localId")
    suspend fun attachmentsForReport(localId: String): List<AttachmentEntity>

    @Query("delete from parts where reportLocalId = :localId")
    suspend fun deletePartsForReport(localId: String)

    @Query("delete from parts where id = :id")
    suspend fun deletePart(id: String)

    @Upsert
    suspend fun upsertJobs(jobs: List<JobEntity>)

    @Upsert
    suspend fun upsertReport(report: ServiceReportEntity)

    @Upsert
    suspend fun upsertPart(part: PartEntity)

    @Upsert
    suspend fun upsertAttachment(attachment: AttachmentEntity)
}
