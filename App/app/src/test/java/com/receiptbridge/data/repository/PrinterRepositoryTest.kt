package com.receiptbridge.data.repository

import com.receiptbridge.data.ConnectionType
import com.receiptbridge.data.PrinterProfile
import com.receiptbridge.data.PrinterProfileDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterRepositoryTest {

    @Test
    fun saveProfile_promotesFirstProfileToDefaultWhenNoneExists() = runBlocking {
        val repository = PrinterRepository(FakePrinterProfileDao())

        repository.saveProfile(
            PrinterProfile(
                name = "Front Counter",
                connectionType = ConnectionType.NETWORK,
                address = "192.168.1.10",
                isDefault = false
            )
        )

        val defaultProfile = repository.getDefaultProfile()

        assertNotNull(defaultProfile)
        assertEquals("Front Counter", defaultProfile?.name)
        assertTrue(defaultProfile?.isDefault == true)
    }

    @Test
    fun saveProfile_explicitDefaultClearsPreviousDefault() = runBlocking {
        val dao = FakePrinterProfileDao()
        val repository = PrinterRepository(dao)

        repository.saveProfile(
            PrinterProfile(
                name = "Front Counter",
                connectionType = ConnectionType.NETWORK,
                address = "192.168.1.10"
            )
        )
        repository.saveProfile(
            PrinterProfile(
                name = "Kitchen",
                connectionType = ConnectionType.BLUETOOTH,
                address = "AA:BB:CC:DD:EE:FF",
                isDefault = true
            )
        )

        val profiles = dao.snapshot()
        val frontCounter = profiles.single { it.name == "Front Counter" }
        val kitchen = profiles.single { it.name == "Kitchen" }

        assertFalse(frontCounter.isDefault)
        assertTrue(kitchen.isDefault)
    }

    @Test
    fun deleteProfile_promotesAnotherProfileWhenRemovingDefault() = runBlocking {
        val dao = FakePrinterProfileDao()
        val repository = PrinterRepository(dao)

        repository.saveProfile(
            PrinterProfile(
                name = "Alpha",
                connectionType = ConnectionType.NETWORK,
                address = "192.168.1.11"
            )
        )
        repository.saveProfile(
            PrinterProfile(
                name = "Beta",
                connectionType = ConnectionType.NETWORK,
                address = "192.168.1.12"
            )
        )

        val defaultBeforeDelete = repository.getDefaultProfile()
        requireNotNull(defaultBeforeDelete)
        repository.deleteProfile(defaultBeforeDelete)

        val defaultAfterDelete = repository.getDefaultProfile()

        assertNotNull(defaultAfterDelete)
        assertEquals("Beta", defaultAfterDelete?.name)
        assertTrue(defaultAfterDelete?.isDefault == true)
    }

    @Test
    fun ensureUsbProfile_updatesExistingUsbProfileName() = runBlocking {
        val dao = FakePrinterProfileDao()
        val repository = PrinterRepository(dao)
        val existing = PrinterProfile(
            name = "USB Printer",
            connectionType = ConnectionType.USB,
            address = "/dev/bus/usb/001/002",
            isDefault = true
        )
        dao.insert(existing)

        val (profile, created) = repository.ensureUsbProfile(
            deviceAddress = existing.address,
            displayName = "EPSON TM-T20III"
        )

        assertFalse(created)
        assertEquals(existing.id, profile.id)
        assertEquals("EPSON TM-T20III", profile.name)
        assertEquals("EPSON TM-T20III", dao.snapshot().single().name)
    }

    private class FakePrinterProfileDao : PrinterProfileDao {
        private val profiles = MutableStateFlow<List<PrinterProfile>>(emptyList())

        override fun getAll(): Flow<List<PrinterProfile>> = profiles

        override suspend fun getById(id: String): PrinterProfile? {
            return profiles.value.firstOrNull { it.id == id }
        }

        override suspend fun getByConnectionAndAddress(
            connectionType: ConnectionType,
            address: String
        ): PrinterProfile? {
            return profiles.value.firstOrNull {
                it.connectionType == connectionType && it.address == address
            }
        }

        override suspend fun getFirstOtherProfile(id: String): PrinterProfile? {
            return profiles.value
                .filter { it.id != id }
                .sortedBy { it.name }
                .firstOrNull()
        }

        override suspend fun getDefault(): PrinterProfile? {
            return profiles.value.firstOrNull { it.isDefault }
        }

        override suspend fun insert(profile: PrinterProfile) {
            profiles.update { current ->
                current.filterNot { it.id == profile.id } + profile
            }
        }

        override suspend fun delete(profile: PrinterProfile) {
            profiles.update { current ->
                current.filterNot { it.id == profile.id }
            }
        }

        override suspend fun clearDefaults() {
            profiles.update { current ->
                current.map { it.copy(isDefault = false) }
            }
        }

        fun snapshot(): List<PrinterProfile> = profiles.value
    }
}
