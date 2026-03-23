package com.receiptbridge.data.repository

import com.receiptbridge.data.ConnectionType
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

    suspend fun getProfileByConnectionAndAddress(
        connectionType: ConnectionType,
        address: String
    ): PrinterProfile? {
        return printerProfileDao.getByConnectionAndAddress(connectionType, address)
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

    suspend fun ensureUsbProfile(deviceAddress: String, displayName: String): Pair<PrinterProfile, Boolean> {
        val existing = getProfileByConnectionAndAddress(ConnectionType.USB, deviceAddress)
        if (existing != null) {
            val updated = if (displayName.isNotBlank() && existing.name != displayName) {
                existing.copy(name = displayName)
            } else {
                existing
            }

            if (updated != existing) {
                printerProfileDao.insert(updated)
            }

            return updated to false
        }

        val profile = PrinterProfile(
            name = displayName,
            connectionType = ConnectionType.USB,
            address = deviceAddress,
            isDefault = getDefaultProfile() == null
        )
        saveProfile(profile)
        return profile to true
    }
}
