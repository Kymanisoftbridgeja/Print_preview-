package com.receiptbridge.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PrinterProfileDao {
    @Query("SELECT * FROM printer_profiles")
    fun getAll(): Flow<List<PrinterProfile>>

    @Query("SELECT * FROM printer_profiles WHERE id = :id")
    suspend fun getById(id: String): PrinterProfile?

    @Query("SELECT * FROM printer_profiles WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): PrinterProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: PrinterProfile)

    @Delete
    suspend fun delete(profile: PrinterProfile)
    
    // Helper to ensure only one default
    @Query("UPDATE printer_profiles SET isDefault = 0")
    suspend fun clearDefaults()
}

@Dao
interface PrintJobDao {
    @Query("SELECT * FROM print_jobs ORDER BY timestamp DESC")
    fun getAll(): Flow<List<PrintJob>>

    @Query("SELECT * FROM print_jobs WHERE status = 'PENDING' ORDER BY timestamp ASC")
    fun getPendingJobs(): Flow<List<PrintJob>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: PrintJob)

    @Update
    suspend fun update(job: PrintJob)
    
    @Query("DELETE FROM print_jobs")
    suspend fun clearAll()
}

class Converters {
    @TypeConverter
    fun fromConnectionType(value: ConnectionType): String = value.name
    @TypeConverter
    fun toConnectionType(value: String): ConnectionType = ConnectionType.valueOf(value)

    @TypeConverter
    fun fromJobStatus(value: JobStatus): String = value.name
    @TypeConverter
    fun toJobStatus(value: String): JobStatus = JobStatus.valueOf(value)
}

@Database(entities = [PrinterProfile::class, PrintJob::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun printerProfileDao(): PrinterProfileDao
    abstract fun printJobDao(): PrintJobDao
}
