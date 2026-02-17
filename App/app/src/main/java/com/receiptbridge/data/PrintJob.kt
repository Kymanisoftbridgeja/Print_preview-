package com.receiptbridge.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class JobStatus {
    PENDING, PRINTING, COMPLETED, FAILED
}

@Entity(tableName = "print_jobs")
data class PrintJob(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val printerProfileId: String?, // Null allows picking default
    val payloadJson: String,
    val status: JobStatus = JobStatus.PENDING,
    val timestamp: Long = System.currentTimeMillis(),
    val errorMessage: String? = null,
    val copies: Int = 1
)
