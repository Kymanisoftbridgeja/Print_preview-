package com.receiptbridge.systemprint

import com.receiptbridge.data.repository.PrinterRepository
import com.receiptbridge.escpos.PrinterDriver
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReceiptBridgePrintServiceEntryPoint {
    fun printerRepository(): PrinterRepository
    fun printerDriver(): PrinterDriver
}
