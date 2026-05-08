package com.receiptbridge.desktop.data

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.receiptbridge.desktop.model.AppSettings
import com.receiptbridge.desktop.model.ConnectionType
import com.receiptbridge.desktop.model.JobStatus
import com.receiptbridge.desktop.model.PrintJob
import com.receiptbridge.desktop.model.PrinterProfile
import com.receiptbridge.desktop.model.defaultCharactersPerLineForPrintAreaDots
import com.receiptbridge.desktop.model.defaultPrintAreaDotsForPaperWidthMm
import com.receiptbridge.desktop.model.normalizePaperWidthMm
import com.receiptbridge.desktop.model.resolvedOdooReceiptRenderMode
import com.receiptbridge.desktop.model.resolvedRenderedReceiptFillPercent
import com.receiptbridge.desktop.model.resolvedRenderedReceiptSmartFit
import com.receiptbridge.desktop.model.sanitizePrintAreaDots
import com.receiptbridge.desktop.model.sanitized
import java.io.Reader
import java.io.Writer
import java.lang.reflect.Type
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DesktopStorage {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val baseDir: Path = buildBaseDirectory()
    private val profilesPath = baseDir.resolve("profiles.json")
    private val jobsPath = baseDir.resolve("jobs.json")
    private val settingsPath = baseDir.resolve("settings.json")

    init {
        Files.createDirectories(baseDir)
    }

    fun loadProfiles(): List<PrinterProfile> {
        val type = object : TypeToken<List<PrinterProfile>>() {}.type
        return readJson(profilesPath, type, emptyList())
    }

    fun saveProfiles(profiles: List<PrinterProfile>) {
        writeJson(profilesPath, profiles)
    }

    fun loadJobs(): List<PrintJob> {
        val type = object : TypeToken<List<PrintJob>>() {}.type
        return readJson(jobsPath, type, emptyList())
    }

    fun saveJobs(jobs: List<PrintJob>) {
        writeJson(jobsPath, jobs)
    }

    fun loadSettings(): AppSettings {
        return readJson(settingsPath, AppSettings::class.java, AppSettings())
    }

    fun saveSettings(settings: AppSettings) {
        writeJson(settingsPath, settings)
    }

    private fun <T> readJson(path: Path, type: Type, defaultValue: T): T {
        if (!Files.exists(path)) {
            return defaultValue
        }

        return runCatching {
            newReader(path).use { reader ->
                gson.fromJson<T>(reader, type) ?: defaultValue
            }
        }.getOrDefault(defaultValue)
    }

    private fun writeJson(path: Path, value: Any) {
        newWriter(path).use { writer ->
            gson.toJson(value, writer)
        }
    }

    private fun newReader(path: Path): Reader {
        return Files.newBufferedReader(path, StandardCharsets.UTF_8)
    }

    private fun newWriter(path: Path): Writer {
        return Files.newBufferedWriter(
            path,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
    }

    private fun buildBaseDirectory(): Path {
        val localAppData = System.getenv("LOCALAPPDATA")
        return if (!localAppData.isNullOrBlank()) {
            Paths.get(localAppData, "ReceiptBridgeDesktop")
        } else {
            Paths.get(System.getProperty("user.home"), ".receiptbridge-desktop")
        }
    }
}

class PrinterRepository(
    private val storage: DesktopStorage
) {
    private val mutex = Mutex()
    private val _profiles = MutableStateFlow(normalizeProfiles(storage.loadProfiles()))
    val allProfiles: StateFlow<List<PrinterProfile>> = _profiles.asStateFlow()

    init {
        storage.saveProfiles(_profiles.value)
    }

    suspend fun getProfileById(id: String): PrinterProfile? {
        return _profiles.value.firstOrNull { it.id == id }
    }

    suspend fun getProfileByConnectionAndAddress(
        connectionType: ConnectionType,
        address: String
    ): PrinterProfile? {
        return _profiles.value.firstOrNull {
            it.connectionType == connectionType && it.address.equals(address, ignoreCase = true)
        }
    }

    suspend fun getDefaultProfile(): PrinterProfile? {
        return _profiles.value.firstOrNull { it.isDefault }
    }

    suspend fun setDefaultProfile(profileId: String): PrinterProfile? {
        return mutex.withLock {
            val current = _profiles.value
            if (current.isEmpty()) {
                return@withLock null
            }

            if (current.none { it.id == profileId }) {
                return@withLock null
            }

            val normalized = normalizeProfiles(
                current.map { profile ->
                    profile.copy(isDefault = profile.id == profileId)
                }
            )
            _profiles.value = normalized
            storage.saveProfiles(normalized)
            normalized.firstOrNull { it.id == profileId }
        }
    }

    suspend fun saveProfile(profile: PrinterProfile) {
        mutex.withLock {
            val current = _profiles.value.toMutableList()
            val existingIndex = current.indexOfFirst { it.id == profile.id }
            val hadDefault = current.any { it.isDefault && it.id != profile.id }
            val profileToSave = when {
                profile.isDefault -> profile
                !hadDefault && current.none { it.isDefault && it.id == profile.id } -> profile.copy(isDefault = true)
                else -> profile
            }

            if (profileToSave.isDefault) {
                for (index in current.indices) {
                    current[index] = current[index].copy(isDefault = false)
                }
            }

            if (existingIndex >= 0) {
                current[existingIndex] = profileToSave
            } else {
                current += profileToSave
            }

            val normalized = normalizeProfiles(current)
            _profiles.value = normalized
            storage.saveProfiles(normalized)
        }
    }

    suspend fun deleteProfile(profile: PrinterProfile) {
        mutex.withLock {
            val remaining = _profiles.value
                .filterNot { it.id == profile.id }
                .sortedBy { it.name.lowercase() }
                .toMutableList()

            if (profile.isDefault && remaining.isNotEmpty()) {
                remaining[0] = remaining[0].copy(isDefault = true)
            }

            val normalized = normalizeProfiles(remaining)
            _profiles.value = normalized
            storage.saveProfiles(normalized)
        }
    }

    private fun normalizeProfiles(profiles: List<PrinterProfile>): List<PrinterProfile> {
        if (profiles.isEmpty()) {
            return emptyList()
        }

        val sorted = profiles.sortedBy { it.name.lowercase() }
        val defaultId = sorted.firstOrNull { it.isDefault }?.id ?: sorted.first().id
        return sorted.map { profile ->
            val normalizedPaperWidth = normalizePaperWidthMm(profile.paperWidthMm)
            val normalizedPrintAreaDots = if (profile.printAreaDots <= 0) {
                defaultPrintAreaDotsForPaperWidthMm(normalizedPaperWidth)
            } else {
                sanitizePrintAreaDots(profile.printAreaDots)
            }

            profile.copy(
                isDefault = profile.id == defaultId,
                paperWidthMm = normalizedPaperWidth,
                printAreaDots = normalizedPrintAreaDots,
                charactersPerLine = defaultCharactersPerLineForPrintAreaDots(normalizedPrintAreaDots),
                odooReceiptRenderMode = profile.resolvedOdooReceiptRenderMode(),
                renderedReceiptFillPercent = profile.resolvedRenderedReceiptFillPercent(),
                renderedReceiptSmartFit = profile.resolvedRenderedReceiptSmartFit()
            )
        }
    }
}

class JobRepository(
    private val storage: DesktopStorage
) {
    private val mutex = Mutex()
    private val _allJobs = MutableStateFlow(
        normalizeStoredJobs(markInterruptedPrintingJobs(storage.loadJobs()))
    )
    val allJobs: StateFlow<List<PrintJob>> = _allJobs.asStateFlow()
    val pendingJobs: Flow<List<PrintJob>> = allJobs.map { jobs ->
        jobs.filter { it.status == JobStatus.PENDING }.sortedBy { it.timestamp }
    }

    suspend fun createJob(job: PrintJob) {
        mutex.withLock {
            val updated = normalizeStoredJobs(_allJobs.value + job)
            _allJobs.value = updated
            storage.saveJobs(updated)
        }
    }

    suspend fun updateJobStatus(job: PrintJob, status: JobStatus, error: String? = null) {
        updateJob(job.copy(status = status, errorMessage = error))
    }

    suspend fun updateJob(job: PrintJob) {
        mutex.withLock {
            val updated = normalizeStoredJobs(
                _allJobs.value.map { existing ->
                    if (existing.id == job.id) job else existing
                }
            )
            _allJobs.value = updated
            storage.saveJobs(updated)
        }
    }

    suspend fun clearHistoryJobs() {
        mutex.withLock {
            val updated = normalizeStoredJobs(
                _allJobs.value.filter { it.status == JobStatus.PENDING || it.status == JobStatus.PRINTING }
            )
            _allJobs.value = updated
            storage.saveJobs(_allJobs.value)
        }
    }

    suspend fun purgeHistoryOlderThan(days: Int) {
        val cutoffMillis = System.currentTimeMillis() - days.coerceAtLeast(0) * 24L * 60L * 60L * 1000L
        mutex.withLock {
            val updated = _allJobs.value.filter { job ->
                job.status == JobStatus.PENDING ||
                    job.status == JobStatus.PRINTING ||
                    job.timestamp >= cutoffMillis
            }
            _allJobs.value = normalizeStoredJobs(updated)
            storage.saveJobs(_allJobs.value)
        }
    }

    private fun markInterruptedPrintingJobs(jobs: List<PrintJob>): List<PrintJob> {
        return jobs.map { job ->
            if (job.status == JobStatus.PRINTING) {
                job.copy(
                    status = JobStatus.FAILED,
                    errorMessage = job.errorMessage ?: "Print was interrupted before the desktop app restarted."
                )
            } else {
                job
            }
        }
    }

    private fun normalizeStoredJobs(jobs: List<PrintJob>): List<PrintJob> {
        val activeJobs = jobs.filter { it.status == JobStatus.PENDING || it.status == JobStatus.PRINTING }
        val historyJobs = jobs
            .filter { it.status == JobStatus.COMPLETED || it.status == JobStatus.FAILED }
            .sortedByDescending { it.timestamp }
            .take(MAX_STORED_HISTORY_JOBS)
            .map(::compactHistoryPayloadIfNeeded)

        return sortAllJobs(activeJobs + historyJobs)
    }

    private fun compactHistoryPayloadIfNeeded(job: PrintJob): PrintJob {
        if (job.payloadJson.length <= MAX_HISTORY_PAYLOAD_CHARS) {
            return job
        }

        return job.copy(
            payloadJson = COMPACTED_HISTORY_PAYLOAD,
            errorMessage = job.errorMessage ?: "Receipt payload was compacted after printing because it was too large to keep in history."
        )
    }

    private fun sortAllJobs(jobs: List<PrintJob>): List<PrintJob> {
        return jobs.sortedByDescending { it.timestamp }
    }

    private companion object {
        const val MAX_STORED_HISTORY_JOBS = 60
        const val MAX_HISTORY_PAYLOAD_CHARS = 250_000
        const val COMPACTED_HISTORY_PAYLOAD =
            "{\"content\":{\"type\":\"receipt_text\",\"text\":\"This completed job payload was compacted by Softbridge to keep the local bridge responsive.\"}}"
    }
}

class SettingsRepository(
    private val storage: DesktopStorage
) {
    private val mutex = Mutex()
    private val _settings = MutableStateFlow(storage.loadSettings().sanitized())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        storage.saveSettings(_settings.value)
    }

    suspend fun refreshSettings() {
        mutex.withLock {
            val sanitized = storage.loadSettings().sanitized()
            _settings.value = sanitized
            storage.saveSettings(sanitized)
        }
    }

    suspend fun updateSettings(newSettings: AppSettings) {
        mutex.withLock {
            val sanitized = newSettings.sanitized()
            _settings.value = sanitized
            storage.saveSettings(sanitized)
        }
    }
}
