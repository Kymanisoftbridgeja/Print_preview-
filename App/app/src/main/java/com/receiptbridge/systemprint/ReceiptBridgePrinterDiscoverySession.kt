package com.receiptbridge.systemprint

import android.content.Context
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrinterDiscoverySession
import dagger.hilt.android.EntryPointAccessors
import com.receiptbridge.data.PAPER_WIDTH_58_MM
import com.receiptbridge.data.PrinterProfile
import com.receiptbridge.data.systemPrintLocalId
import com.receiptbridge.data.resolvedPrintAreaDots
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ReceiptBridgePrinterDiscoverySession(
    context: Context,
    private val printerIdFactory: (String) -> PrinterId
) : PrinterDiscoverySession() {

    private val appContext = context.applicationContext
    private val entryPoint = EntryPointAccessors.fromApplication(
        appContext,
        ReceiptBridgePrintServiceEntryPoint::class.java
    )
    private val printerRepository = entryPoint.printerRepository()
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var discoveryStarted = false
    private var discoveryJob: Job? = null
    private var knownPrinterIds: Set<PrinterId> = emptySet()

    override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
        if (discoveryStarted) {
            return
        }
        discoveryStarted = true
        discoveryJob = sessionScope.launch {
            printerRepository.allProfiles.collect { profiles ->
                publishProfiles(profiles)
            }
        }
    }

    override fun onStopPrinterDiscovery() {
        discoveryStarted = false
        discoveryJob?.cancel()
        discoveryJob = null
    }

    override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
        sessionScope.launch {
            val profiles = printerRepository.allProfiles.first()
            val printersToUpdate = profiles
                .filter { profile -> printerIds.contains(printerIdFactory(profile.systemPrintLocalId())) }
                .map(::buildPrinterInfo)
            if (printersToUpdate.isNotEmpty()) {
                addPrinters(printersToUpdate)
            }
        }
    }

    override fun onStartPrinterStateTracking(printerId: PrinterId) {
    }

    override fun onStopPrinterStateTracking(printerId: PrinterId) {
    }

    override fun onDestroy() {
        discoveryJob?.cancel()
        sessionScope.cancel()
    }

    private fun publishProfiles(profiles: List<PrinterProfile>) {
        val printers = profiles.map(::buildPrinterInfo)
        val currentPrinterIds = printers.map { it.id }.toSet()
        val removedPrinterIds = knownPrinterIds - currentPrinterIds
        if (printers.isNotEmpty()) {
            addPrinters(printers)
        }
        if (removedPrinterIds.isNotEmpty()) {
            removePrinters(removedPrinterIds.toList())
        }
        knownPrinterIds = currentPrinterIds
    }

    private fun buildPrinterInfo(profile: PrinterProfile): PrinterInfo {
        val printerId = printerIdFactory(profile.systemPrintLocalId())
        val description = buildString {
            append(profile.connectionType.name)
            append(" - ")
            append(profile.paperWidthMm)
            append(" mm")
            append(" - ")
            append(profile.resolvedPrintAreaDots())
            append(" dots")
            append(" - ")
            append(profile.address)
        }

        return PrinterInfo.Builder(printerId, profile.name, PrinterInfo.STATUS_IDLE)
            .setDescription(description)
            .setCapabilities(buildCapabilities(printerId, profile))
            .build()
    }

    private fun buildCapabilities(
        printerId: PrinterId,
        profile: PrinterProfile
    ): PrinterCapabilitiesInfo {
        val mediaSize = if (profile.paperWidthMm == PAPER_WIDTH_58_MM) {
            PrintAttributes.MediaSize(
                "receiptbridge_58mm",
                "58 mm Receipt",
                2280,
                11000
            )
        } else {
            PrintAttributes.MediaSize(
                "receiptbridge_80mm",
                "80 mm Receipt",
                3150,
                11000
            )
        }

        return PrinterCapabilitiesInfo.Builder(printerId)
            .addMediaSize(mediaSize, true)
            .addResolution(
                PrintAttributes.Resolution(
                    "receiptbridge_600dpi",
                    "Receipt 600 DPI",
                    600,
                    600
                ),
                true
            )
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .setColorModes(
                PrintAttributes.COLOR_MODE_MONOCHROME,
                PrintAttributes.COLOR_MODE_MONOCHROME
            )
            .build()
    }
}
