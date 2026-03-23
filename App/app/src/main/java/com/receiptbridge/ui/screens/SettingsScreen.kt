package com.receiptbridge.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import com.receiptbridge.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Global Print Settings", style = MaterialTheme.typography.titleLarge)
        
        Spacer(modifier = Modifier.height(24.dp))
        
        TextField(
            value = settings.globalHeader ?: "",
            onValueChange = { viewModel.updateSettings(settings.copy(globalHeader = it)) },
            label = { Text("Global Header (Text or base64:...)") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        TextField(
            value = settings.globalFooter ?: "",
            onValueChange = { viewModel.updateSettings(settings.copy(globalFooter = it)) },
            label = { Text("Global Footer Text") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        TextField(
            value = settings.keepHistoryDays.toString(),
            onValueChange = { input ->
                if (input.all(Char::isDigit)) {
                    viewModel.updateSettings(
                        settings.copy(keepHistoryDays = input.toIntOrNull() ?: 0)
                    )
                }
            },
            label = { Text("Keep History (Days)") },
            supportingText = { Text("Completed and failed jobs older than this are removed automatically.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        SettingToggle(
            title = "Auto-print on USB Connect",
            checked = settings.autoPrintOnConnect,
            onCheckedChange = { viewModel.updateSettings(settings.copy(autoPrintOnConnect = it)) }
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text("App Version: 1.1.0", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SettingToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .toggleable(
                value = checked,
                onValueChange = { onCheckedChange(!checked) },
                role = Role.Switch
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = null // null recommended for accessibility with toggleable modifier
        )
    }
}
