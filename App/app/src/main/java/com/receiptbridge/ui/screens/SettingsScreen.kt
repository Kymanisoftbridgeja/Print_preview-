package com.receiptbridge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import com.receiptbridge.data.MAX_KEEP_HISTORY_DAYS
import com.receiptbridge.data.MAX_SYSTEM_PRINT_CONTENT_FILL_PERCENT
import com.receiptbridge.data.MIN_KEEP_HISTORY_DAYS
import com.receiptbridge.data.MIN_SYSTEM_PRINT_CONTENT_FILL_PERCENT
import com.receiptbridge.data.sanitizeKeepHistoryDays
import com.receiptbridge.data.sanitizeSystemPrintContentFillPercent
import com.receiptbridge.ui.viewmodel.SettingsViewModel
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val systemPrintWidthDraft = remember(settings.systemPrintContentFillPercent) {
        mutableFloatStateOf(settings.systemPrintContentFillPercent.toFloat())
    }

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

        StepperSetting(
            title = "Keep History",
            valueText = "${settings.keepHistoryDays} days",
            supportingText = "Completed and failed jobs older than this are removed automatically.",
            canDecrease = settings.keepHistoryDays > MIN_KEEP_HISTORY_DAYS,
            canIncrease = settings.keepHistoryDays < MAX_KEEP_HISTORY_DAYS,
            onDecrease = {
                viewModel.updateKeepHistoryDays(
                    sanitizeKeepHistoryDays(settings.keepHistoryDays - KEEP_HISTORY_STEP_DAYS)
                )
            },
            onIncrease = {
                viewModel.updateKeepHistoryDays(
                    sanitizeKeepHistoryDays(settings.keepHistoryDays + KEEP_HISTORY_STEP_DAYS)
                )
            }
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        SettingToggle(
            title = "Auto-print on USB Connect",
            checked = settings.autoPrintOnConnect,
            onCheckedChange = { viewModel.updateSettings(settings.copy(autoPrintOnConnect = it)) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text("Android Print Service", style = MaterialTheme.typography.titleMedium)

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            "Receipt Content Width",
            style = MaterialTheme.typography.bodyLarge
        )

        Text(
            "${systemPrintWidthDraft.floatValue.roundToInt()}%",
            style = MaterialTheme.typography.titleMedium
        )

        Slider(
            value = systemPrintWidthDraft.floatValue,
            onValueChange = { value ->
                systemPrintWidthDraft.floatValue = value.roundToInt().toFloat()
            },
            onValueChangeFinished = {
                viewModel.updateSystemPrintContentFillPercent(
                    sanitizeSystemPrintContentFillPercent(systemPrintWidthDraft.floatValue.roundToInt())
                )
            },
            valueRange = MIN_SYSTEM_PRINT_CONTENT_FILL_PERCENT.toFloat()..MAX_SYSTEM_PRINT_CONTENT_FILL_PERCENT.toFloat(),
            steps = (MAX_SYSTEM_PRINT_CONTENT_FILL_PERCENT - MIN_SYSTEM_PRINT_CONTENT_FILL_PERCENT) - 1,
            modifier = Modifier.fillMaxWidth()
        )

        StepperSetting(
            title = "Adjust Width",
            valueText = "${settings.systemPrintContentFillPercent}%",
            supportingText = "Higher values make Android print-service receipts use more paper width while staying centered.",
            canDecrease = settings.systemPrintContentFillPercent > MIN_SYSTEM_PRINT_CONTENT_FILL_PERCENT,
            canIncrease = settings.systemPrintContentFillPercent < MAX_SYSTEM_PRINT_CONTENT_FILL_PERCENT,
            onDecrease = {
                val updated = sanitizeSystemPrintContentFillPercent(
                    settings.systemPrintContentFillPercent - SYSTEM_PRINT_WIDTH_STEP_PERCENT
                )
                systemPrintWidthDraft.floatValue = updated.toFloat()
                viewModel.updateSystemPrintContentFillPercent(updated)
            },
            onIncrease = {
                val updated = sanitizeSystemPrintContentFillPercent(
                    settings.systemPrintContentFillPercent + SYSTEM_PRINT_WIDTH_STEP_PERCENT
                )
                systemPrintWidthDraft.floatValue = updated.toFloat()
                viewModel.updateSystemPrintContentFillPercent(updated)
            }
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

@Composable
fun StepperSetting(
    title: String,
    valueText: String,
    supportingText: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onDecrease,
                    enabled = canDecrease
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Decrease $title")
                }
                Text(
                    valueText,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                IconButton(
                    onClick = onIncrease,
                    enabled = canIncrease
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Increase $title")
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            supportingText,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private const val KEEP_HISTORY_STEP_DAYS = 5
private const val SYSTEM_PRINT_WIDTH_STEP_PERCENT = 5
