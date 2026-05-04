package com.receiptbridge.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.receiptbridge.desktop.service.ReceiptBridgeDesktopController
import com.receiptbridge.desktop.ui.ReceiptBridgeDesktopApp

fun main(args: Array<String>) = application {
    val controller = ReceiptBridgeDesktopController(args.toList())

    Window(
        onCloseRequest = {
            controller.close()
            exitApplication()
        },
        title = "Softbridge Desktop",
        state = WindowState(size = DpSize(1260.dp, 860.dp))
    ) {
        ReceiptBridgeDesktopApp(controller)
    }
}
