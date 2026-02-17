package com.receiptbridge.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.receiptbridge.data.JobStatus
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Print Queue", style = MaterialTheme.typography.titleLarge)
        
        Button(
            onClick = { viewModel.clearAll() },
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Text("Clear All History")
        }

        LazyColumn {
            items(jobs) { job ->
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
                            Text("Status: ${job.status}")
                            Text(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(job.timestamp)))
                            if (job.status == JobStatus.FAILED) {
                                Text("Error: ${job.errorMessage}", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        
                        if (job.status == JobStatus.FAILED) {
                            Button(onClick = { viewModel.retryJob(job) }) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }
        }
    }
}
