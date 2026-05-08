package com.receiptbridge.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.receiptbridge.desktop.service.ReceiptBridgeDesktopController
import com.receiptbridge.desktop.ui.ReceiptBridgeDesktopApp
import java.awt.SystemTray
import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

fun main(args: Array<String>) {
    val instanceLock = SoftbridgeInstanceLock.acquire() ?: return

    application {
    val controller = remember { ReceiptBridgeDesktopController(args.toList()) }
    val traySupported = remember { SystemTray.isSupported() }
    val trayState = rememberTrayState()
    val windowState = rememberWindowState(size = DpSize(1260.dp, 860.dp))
    val trayIcon = rememberVectorPainter(Icons.Default.Print)
    var isWindowVisible by remember { mutableStateOf(true) }
    var trayNotificationShown by remember { mutableStateOf(false) }

    fun showWindow() {
        isWindowVisible = true
    }

    fun exitSoftbridge() {
        controller.close()
        instanceLock.close()
        exitApplication()
    }

    if (traySupported) {
        Tray(
            state = trayState,
            icon = trayIcon,
            tooltip = "Softbridge Desktop",
            onAction = { showWindow() },
            menu = {
                Item(
                    text = "Open Softbridge",
                    onClick = { showWindow() }
                )
                Separator()
                Item(
                    text = "Exit Softbridge",
                    onClick = { exitSoftbridge() }
                )
            }
        )
    }

    Window(
        onCloseRequest = {
            if (traySupported) {
                isWindowVisible = false
                if (!trayNotificationShown) {
                    trayState.sendNotification(
                        Notification(
                            title = "Softbridge Desktop",
                            message = "Softbridge is still running in the background. Use the tray icon to reopen or exit the app.",
                            type = Notification.Type.Info
                        )
                    )
                    trayNotificationShown = true
                }
            } else {
                exitSoftbridge()
            }
        },
        title = "Softbridge Desktop",
        state = windowState,
        visible = isWindowVisible
    ) {
        ReceiptBridgeDesktopApp(controller)
    }
    }
}

private class SoftbridgeInstanceLock(
    private val channel: FileChannel,
    private val lock: FileLock
) : Closeable {
    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        fun acquire(): SoftbridgeInstanceLock? {
            val lockPath = buildLockPath()
            Files.createDirectories(lockPath.parent)
            val channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            )

            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }

            if (lock == null) {
                channel.close()
                return null
            }

            return SoftbridgeInstanceLock(channel, lock)
        }

        private fun buildLockPath(): Path {
            val localAppData = System.getenv("LOCALAPPDATA")
            val baseDir = if (!localAppData.isNullOrBlank()) {
                Paths.get(localAppData, "ReceiptBridgeDesktop")
            } else {
                Paths.get(System.getProperty("user.home"), ".receiptbridge-desktop")
            }
            return baseDir.resolve("softbridge-desktop.lock")
        }
    }
}
