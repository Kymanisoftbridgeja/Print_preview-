package com.receiptbridge.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.receiptbridge.server.WebServer
import com.receiptbridge.data.resolvedPrintAreaDots
import com.receiptbridge.ui.viewmodel.PrinterViewModel

@Composable
fun HomeScreen(
    onNavigateToPrinters: () -> Unit,
    onNavigateToQueue: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: PrinterViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val profiles by viewModel.profiles.collectAsState(initial = emptyList())
    val printerActionMessage by viewModel.printerActionMessage.collectAsState()
    val defaultProfile = profiles.firstOrNull { it.isDefault } ?: profiles.firstOrNull()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Text("ReceiptBridge", style = MaterialTheme.typography.headlineMedium)
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Server Status", style = MaterialTheme.typography.titleMedium)
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                    Text("Port: 9900")
                    Text("Endpoint: POST /print")

                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        val intent = Intent(context, WebServer::class.java)
                        context.startForegroundService(intent)
                    }) {
                        Text("Restart Server")
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Active Printer", style = MaterialTheme.typography.titleMedium)
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                    if (defaultProfile == null) {
                        Text("No printer has been added yet.")
                        Text("Add a printer to make it available for print jobs and test prints.")
                    } else {
                        Text(defaultProfile.name, style = MaterialTheme.typography.titleMedium)
                        Text("${defaultProfile.connectionType} - ${defaultProfile.address}")
                        Text("Receipt width: ${defaultProfile.paperWidthMm} mm")
                        Text("Print area: ${defaultProfile.resolvedPrintAreaDots()} dots")
                        Text("Saved printers: ${profiles.size}")
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.queueTestPrint(defaultProfile) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Connection Test")
                        }
                    }
                    printerActionMessage?.let { message ->
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "To use these printers from Android's print dialog, enable ReceiptBridge Print Service in your device's print settings.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            Button(
                onClick = onNavigateToPrinters,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Manage Printers")
            }
        }

        item {
            Button(
                onClick = onNavigateToQueue,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Print Queue")
            }
        }

        item {
            Button(
                onClick = onNavigateToSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Settings")
            }
        }
    }
}
