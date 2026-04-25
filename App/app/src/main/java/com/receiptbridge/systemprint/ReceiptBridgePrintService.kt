package com.receiptbridge.systemprint

import android.print.PrintJobId
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

            val profileId = printerId.localId
            val profile = entryPoint.printerRepository().getProfileById(profileId)
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
}
