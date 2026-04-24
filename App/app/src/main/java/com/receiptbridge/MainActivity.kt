package com.receiptbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.content.ContextCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.receiptbridge.data.PrintJob
import com.receiptbridge.data.PrintJobFactory
import com.receiptbridge.data.repository.JobRepository
import com.receiptbridge.data.repository.PrinterRepository
import com.receiptbridge.data.repository.SettingsRepository
import com.receiptbridge.server.WebServer
import com.receiptbridge.ui.AppNavigation
import com.receiptbridge.ui.theme.ReceiptBridgeTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var jobRepository: JobRepository

    @Inject
    lateinit var printerRepository: PrinterRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permission results
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
             Toast.makeText(this, "Permissions are required for printing", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkPermissions()
        ensurePrintServerRunning()
        handleIncomingIntent(intent)

        setContent {
            ReceiptBridgeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        // Filter out already granted
        val toRequest = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (toRequest.isNotEmpty()) {
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    private fun ensurePrintServerRunning() {
        val serviceIntent = Intent(this, WebServer::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        handleUsbAttach(intent)
        handleDeepLink(intent)
    }

    private fun handleUsbAttach(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            return
        }

        val device = intent.getUsbDevice() ?: return
        lifecycleScope.launch {
            settingsRepository.refreshSettings()
            val settings = settingsRepository.settings.value
            val (profile, created) = printerRepository.ensureUsbProfile(
                deviceAddress = device.deviceName,
                displayName = buildUsbPrinterName(device)
            )

            if (settings.autoPrintOnConnect) {
                jobRepository.retryRecoverableUsbJobs(profile.id)
            }

            val status = if (created) "added" else "ready"
            Toast.makeText(
                this@MainActivity,
                "USB printer $status: ${profile.name}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun handleDeepLink(intent: Intent?) {
        intent?.data?.let { uri ->
            if (uri.scheme == "receiptbridge" && uri.host == "print") {
                val payloadBase64 = uri.getQueryParameter("payload")
                if (payloadBase64 != null) {
                    try {
                        val decodedBytes = Base64.decode(payloadBase64, Base64.DEFAULT)
                        val json = String(decodedBytes)
                        
                        lifecycleScope.launch {
                            val job = PrintJobFactory.createFromPayloadJson(json)
                            jobRepository.createJob(job)
                            Toast.makeText(this@MainActivity, "Print Job Queued", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, "Invalid Deep Link Payload", Toast.LENGTH_SHORT).show()
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private fun buildUsbPrinterName(device: UsbDevice): String {
        val parts = listOfNotNull(device.manufacturerName, device.productName)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        return parts.joinToString(" ").ifBlank { "USB Printer ${device.deviceId}" }
    }

    private fun Intent.getUsbDevice(): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }
}
