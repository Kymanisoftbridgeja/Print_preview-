package com.receiptbridge.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.receiptbridge.data.JobStatus
import com.receiptbridge.data.PrintJob
import com.receiptbridge.data.PrinterProfile
import com.receiptbridge.ui.viewmodel.JobsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun QueueScreen(
    onNavigateBack: () -> Unit,
    viewModel: JobsViewModel = hiltViewModel()
) {
    val jobs by viewModel.allJobs.collectAsState(initial = emptyList())
    val profiles by viewModel.profiles.collectAsState(initial = emptyList())
    var selectedTab by remember { mutableStateOf(0) }
    val defaultProfile = profiles.firstOrNull { it.isDefault } ?: profiles.firstOrNull()

    val activeJobs = jobs.filter { it.status == JobStatus.PENDING || it.status == JobStatus.PRINTING }
    val historyJobs = jobs.filter { it.status == JobStatus.COMPLETED || it.status == JobStatus.FAILED }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Text("Active (${activeJobs.size})", modifier = Modifier.padding(16.dp))
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                Text("History (${historyJobs.size})", modifier = Modifier.padding(16.dp))
            }
        }

        val displayJobs = if (selectedTab == 0) activeJobs else historyJobs

        if (selectedTab == 1 && historyJobs.isNotEmpty()) {
            Button(
                onClick = { viewModel.clearHistory() },
                modifier = Modifier.padding(16.dp).fillMaxWidth()
            ) {
                Text("Clear History")
            }
        }

        LazyColumn(modifier = Modifier.padding(16.dp)) {
            items(displayJobs) { job ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Job ID: ${job.id.take(8)}", style = MaterialTheme.typography.titleMedium)
                            Text("Printer: ${job.resolvePrinterLabel(profiles, defaultProfile)}")
                            Text("Status: ${job.status}")
                            Text(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(job.timestamp)))
                            if (job.status == JobStatus.FAILED) {
                                Text("Error: ${job.errorMessage}", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        
                        // Action buttons
                        if (job.status == JobStatus.FAILED) {
                            Button(onClick = { viewModel.retryJob(job) }) {
                                Text("Retry")
                            }
                        } else if (job.status == JobStatus.COMPLETED) {
                            Button(onClick = { viewModel.retryJob(job) }) {
                                Text("Re-print")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun PrintJob.resolvePrinterLabel(
    profiles: List<PrinterProfile>,
    defaultProfile: PrinterProfile?
): String {
    val matchedProfile = printerProfileId?.let { id ->
        profiles.firstOrNull { it.id == id }
    }
    return when {
        matchedProfile != null -> "${matchedProfile.name} (${matchedProfile.paperWidthMm} mm)"
        printerProfileId != null -> "Saved printer unavailable"
        defaultProfile != null -> "${defaultProfile.name} (${defaultProfile.paperWidthMm} mm, default)"
        else -> "Default printer not set"
    }
}
