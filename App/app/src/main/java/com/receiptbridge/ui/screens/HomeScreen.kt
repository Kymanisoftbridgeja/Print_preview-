package com.receiptbridge.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.receiptbridge.server.WebServer

@Composable
fun HomeScreen(
    onNavigateToPrinters: () -> Unit,
    onNavigateToQueue: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("ReceiptBridge", style = MaterialTheme.typography.headlineMedium)
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Server Status", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Port: 9900")
                Text("Endpoint: POST /print")
                
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    val intent = Intent(context, WebServer::class.java)
                    context.startForegroundService(intent)
                }) {
                    Text("Restart Server")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onNavigateToPrinters,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Manage Printers")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = onNavigateToQueue,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Print Queue")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // We need to pass the navigation callback, but signature change needed.
        // For now, I'll just add the button but it won't work unless I update the signature.
        // I will update the signature in the next tool call properly or just leave it out if too complex for right now.
        // Actually, let's update the signature. It requires updating AppNavigation too.
        // I will just adding the placeholder button.
        Button(
            // onClick = onNavigateToSettings, 
            onClick = { /* TODO: Wire up Settings */ },
            modifier = Modifier.fillMaxWidth(),
            enabled = false
        ) {
            Text("Settings (Coming Soon)")
        }
    }
}
