package com.receiptbridge.systemprint

import android.print.PrintJobId
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import com.receiptbridge.data.PrinterProfile
import com.receiptbridge.data.looksLikeLegacyPrinterProfileId
import com.receiptbridge.data.normalizedSystemPrintAddress
import com.receiptbridge.data.parseSystemPrintLocalId
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class ReceiptBridgePrintService : PrintService() {

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            ReceiptBridgePrintServiceEntryPoint::class.java
        )
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val activePrintJobs = ConcurrentHashMap<PrintJobId, kotlinx.coroutines.Job>()

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        return ReceiptBridgePrinterDiscoverySession(
            context = applicationContext,
            printerIdFactory = ::generatePrinterId
        )
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        val processingJob = serviceScope.launch {
            val printerId = printJob.info.printerId
            if (printerId == null) {
                printJob.fail("Android did not provide a target printer.")
                activePrintJobs.remove(printJob.id)
                return@launch
            }

            val localId = printerId.localId
            val profiles = entryPoint.printerRepository().allProfiles.first()
            val profile = resolveProfile(localId, profiles)
            if (profile == null) {
                printJob.fail("Saved printer profile was not found.")
                activePrintJobs.remove(printJob.id)
                return@launch
            }

            val documentData = printJob.document.data
            if (documentData == null) {
                printJob.fail("Android did not provide printable document data.")
                activePrintJobs.remove(printJob.id)
                return@launch
            }

            try {
                printJob.start()
                entryPoint.printerDriver().printPdfDocument(
                    documentData = documentData,
                    profile = profile,
                    copies = printJob.info.copies.coerceAtLeast(1)
                )
                printJob.complete()
            } catch (cancelled: CancellationException) {
                printJob.cancel()
            } catch (error: Exception) {
                error.printStackTrace()
                printJob.fail(error.message ?: "Failed to print from Android print service.")
            } finally {
                activePrintJobs.remove(printJob.id)
            }
        }

        activePrintJobs[printJob.id] = processingJob
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        activePrintJobs.remove(printJob.id)?.cancel()
        printJob.cancel()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun resolveProfile(
        localId: String,
        profiles: List<PrinterProfile>
    ): PrinterProfile? {
        profiles.firstOrNull { it.id == localId }?.let { return it }

        parseSystemPrintLocalId(localId)?.let { selector ->
            profiles.firstOrNull { profile ->
                profile.connectionType == selector.connectionType &&
                    profile.normalizedSystemPrintAddress() == selector.normalizedAddress
            }?.let { return it }
        }

        if (profiles.size == 1) {
            Log.w(TAG, "Falling back to the only saved printer for unresolved localId=$localId")
            return profiles.single()
        }

        if (looksLikeLegacyPrinterProfileId(localId)) {
            val fallbackProfile = profiles.firstOrNull { it.isDefault } ?: profiles.firstOrNull()
            if (fallbackProfile != null) {
                Log.w(TAG, "Falling back to default printer for legacy localId=$localId")
            }
            return fallbackProfile
        }

        Log.w(TAG, "No saved printer matched localId=$localId")
        return null
    }

    private companion object {
        const val TAG = "ReceiptBridgePrintSvc"
    }
}
