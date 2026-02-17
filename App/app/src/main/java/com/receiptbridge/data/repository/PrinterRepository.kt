package com.receiptbridge.data.repository

import com.receiptbridge.data.PrinterProfile
import com.receiptbridge.data.PrinterProfileDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrinterRepository @Inject constructor(
    private val printerProfileDao: PrinterProfileDao
) {
    val allProfiles: Flow<List<PrinterProfile>> = printerProfileDao.getAll()

    suspend fun getProfileById(id: String): PrinterProfile? {
        return printerProfileDao.getById(id)
    }

    suspend fun getDefaultProfile(): PrinterProfile? {
        return printerProfileDao.getDefault()
    }

    suspend fun saveProfile(profile: PrinterProfile) {
        if (profile.isDefault) {
            printerProfileDao.clearDefaults()
        }
        printerProfileDao.insert(profile)
    }

    suspend fun deleteProfile(profile: PrinterProfile) {
        printerProfileDao.delete(profile)
    }
}
