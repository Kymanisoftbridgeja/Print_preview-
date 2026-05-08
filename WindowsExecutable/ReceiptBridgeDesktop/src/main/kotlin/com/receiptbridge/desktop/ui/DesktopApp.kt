package com.receiptbridge.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.receiptbridge.desktop.model.AppSettings
import com.receiptbridge.desktop.model.ConnectionType
import com.receiptbridge.desktop.model.DEFAULT_PRINT_AREA_DOTS_58_MM
import com.receiptbridge.desktop.model.DEFAULT_PRINT_AREA_DOTS_80_MM
import com.receiptbridge.desktop.model.JobStatus
import com.receiptbridge.desktop.model.MAX_KEEP_HISTORY_DAYS
import com.receiptbridge.desktop.model.MAX_PRINT_AREA_DOTS
import com.receiptbridge.desktop.model.MAX_SYSTEM_PRINT_CONTENT_FILL_PERCENT
import com.receiptbridge.desktop.model.MIN_KEEP_HISTORY_DAYS
import com.receiptbridge.desktop.model.MIN_PRINT_AREA_DOTS
import com.receiptbridge.desktop.model.MIN_SYSTEM_PRINT_CONTENT_FILL_PERCENT
import com.receiptbridge.desktop.model.OdooReceiptRenderMode
import com.receiptbridge.desktop.model.PAPER_WIDTH_58_MM
import com.receiptbridge.desktop.model.PAPER_WIDTH_80_MM
import com.receiptbridge.desktop.model.PRINT_AREA_DOTS_STEP
import com.receiptbridge.desktop.model.PrintJob
import com.receiptbridge.desktop.model.PrinterProfile
import com.receiptbridge.desktop.model.defaultPrintAreaDotsForPaperWidthMm
import com.receiptbridge.desktop.model.resolvedOdooReceiptRenderMode
import com.receiptbridge.desktop.model.resolvedPrintAreaDots
import com.receiptbridge.desktop.model.resolvedRenderedReceiptFillPercent
import com.receiptbridge.desktop.model.resolvedRenderedReceiptSmartFit
import com.receiptbridge.desktop.model.sanitizeKeepHistoryDays
import com.receiptbridge.desktop.model.sanitizePrintAreaDots
import com.receiptbridge.desktop.model.sanitizeRenderedReceiptFillPercent
import com.receiptbridge.desktop.model.sanitizeSystemPrintContentFillPercent
import com.receiptbridge.desktop.service.BridgeEvent
import com.receiptbridge.desktop.service.BridgeEventLevel
import com.receiptbridge.desktop.service.ReceiptBridgeDesktopController
import com.receiptbridge.desktop.service.ServerState
import com.receiptbridge.desktop.service.WindowsPrinterQueue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class DesktopScreen(
    val label: String
) {
    Home("Home"),
    Printers("Printers"),
    Queue("Queue"),
    Settings("Settings")
}

@Composable
fun ReceiptBridgeDesktopApp(controller: ReceiptBridgeDesktopController) {
    val colorScheme = MaterialTheme.colorScheme.copy(
        primary = Color(0xFF0F766E),
        secondary = Color(0xFF1D4ED8),
        tertiary = Color(0xFFF97316),
        surface = Color(0xFFF8FAFC),
        background = Color(0xFFF2F5F9)
    )
    var selectedScreen by remember { mutableStateOf(DesktopScreen.Home) }

    MaterialTheme(colorScheme = colorScheme) {
        Surface(color = colorScheme.background) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFFF4F8FB), Color(0xFFEFF5FA))
                        )
                    )
            ) {
                DesktopRail(
                    selectedScreen = selectedScreen,
                    onSelect = { selectedScreen = it }
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(20.dp)
                ) {
                    when (selectedScreen) {
                        DesktopScreen.Home -> HomeScreen(
                            controller = controller,
                            onNavigate = { selectedScreen = it }
                        )
                        DesktopScreen.Printers -> ProfilesScreen(controller)
                        DesktopScreen.Queue -> QueueScreen(controller)
                        DesktopScreen.Settings -> SettingsScreen(controller)
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopRail(
    selectedScreen: DesktopScreen,
    onSelect: (DesktopScreen) -> Unit
) {
    NavigationRail(
        modifier = Modifier
            .fillMaxHeight()
            .padding(start = 16.dp, top = 16.dp, bottom = 16.dp),
        containerColor = Color(0xFF0F172A),
        header = {
            Column(
                modifier = Modifier.padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .background(Color(0xFF14B8A6), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("S", color = Color.White, style = MaterialTheme.typography.headlineMedium)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("Softbridge", color = Color.White, fontWeight = FontWeight.Bold)
                Text("Windows Port", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
            }
        }
    ) {
        listOf(
            DesktopScreen.Home to Icons.Default.Home,
            DesktopScreen.Printers to Icons.Default.Print,
            DesktopScreen.Queue to Icons.Default.List,
            DesktopScreen.Settings to Icons.Default.Settings
        ).forEach { (screen, icon) ->
            NavigationRailItem(
                selected = selectedScreen == screen,
                onClick = { onSelect(screen) },
                icon = { Icon(icon, contentDescription = screen.label) },
                label = { Text(screen.label) }
            )
        }
    }
}

@Composable
private fun HomeScreen(
    controller: ReceiptBridgeDesktopController,
    onNavigate: (DesktopScreen) -> Unit
) {
    val profiles by controller.profiles.collectAsState()
    val activeProfile by controller.activeProfile.collectAsState()
    val settings by controller.settings.collectAsState()
    val serverState by controller.serverState.collectAsState()
    val printerActionMessage by controller.printerActionMessage.collectAsState()
    val bridgeEvents by controller.bridgeEvents.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            HeroHeader(
                title = "Receipt printing without the Android-only boundary",
                subtitle = "This Windows desktop port keeps the local print bridge, saved printer profiles, queue tracking, and settings flow from the Android app."
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                StatusCard(
                    modifier = Modifier.weight(1f),
                    title = "Server Status",
                    accent = Color(0xFF0F766E)
                ) {
                    Text("State: ${serverState.name.asFriendlyStateLabel()}")
                    Text("Port: 9900")
                    Text("Endpoint: POST /print")
                    Text("Odoo endpoint: POST /odoo/receipt")
                    Text(
                        if (settings.odooApiToken.isNullOrBlank()) {
                            "Bridge token: optional"
                        } else {
                            "Bridge token: required"
                        }
                    )
                    Text("Browser origins: ${summarizeOriginRules(settings.odooAllowedOrigins)}")
                    bridgeEvents.firstOrNull()?.let { event ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            "Latest bridge activity: ${event.message}",
                            color = event.level.asEventColor()
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { controller.restartServer() }) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Restart Server")
                    }
                }

                StatusCard(
                    modifier = Modifier.weight(1f),
                    title = "Desktop Port",
                    accent = Color(0xFF1D4ED8)
                ) {
                    Text("Supported today: Network ESC/POS printing")
                    Text("Also supported: Windows-installed USB printer queues")
                    Text("Saved but pending: Bluetooth profiles")
                    Text("Data path: local desktop storage + local HTTP queue")
                }
            }
        }

        item {
            StatusCard(
                modifier = Modifier.fillMaxWidth(),
                title = "Active Printer",
                accent = Color(0xFFF97316)
            ) {
                val activePrinter = activeProfile
                if (activePrinter == null) {
                    Text("No printer has been added yet.")
                    Text("Add a printer profile to enable HTTP jobs and desktop test prints.")
                } else {
                    Text(activePrinter.name, style = MaterialTheme.typography.titleLarge)
                    Text("${activePrinter.connectionType} - ${activePrinter.address}")
                    Text("Receipt width: ${activePrinter.paperWidthMm} mm")
                    Text("Print area: ${activePrinter.resolvedPrintAreaDots()} dots")
                    Text("Rendered receipt width: ${activePrinter.resolvedRenderedReceiptFillPercent(settings.systemPrintContentFillPercent)}%")
                    Text("Smart receipt fit: ${if (activePrinter.resolvedRenderedReceiptSmartFit()) "On" else "Off"}")
                    Text("Saved printers: ${profiles.size}")
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { controller.queueTestPrint(activePrinter) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Connection Test")
                        }
                        Button(
                            onClick = { controller.queueWidthCalibrationPrint(activePrinter) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Print Calibration Test")
                        }
                    }
                }

                printerActionMessage?.let { message ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(message, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                QuickActionCard(
                    modifier = Modifier.weight(1f),
                    title = "Manage Printers",
                    description = "Add and tune printer profiles.",
                    onClick = { onNavigate(DesktopScreen.Printers) }
                )
                QuickActionCard(
                    modifier = Modifier.weight(1f),
                    title = "Print Queue",
                    description = "Watch pending, completed, and failed jobs.",
                    onClick = { onNavigate(DesktopScreen.Queue) }
                )
                QuickActionCard(
                    modifier = Modifier.weight(1f),
                    title = "Settings",
                    description = "Adjust global headers, footers, and retention.",
                    onClick = { onNavigate(DesktopScreen.Settings) }
                )
            }
        }
    }
}

@Composable
private fun ProfilesScreen(controller: ReceiptBridgeDesktopController) {
    val profiles by controller.profiles.collectAsState()
    val activeProfile by controller.activeProfile.collectAsState()
    val printerActionMessage by controller.printerActionMessage.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    var selectedProfileId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(profiles, activeProfile?.id) {
        selectedProfileId = when {
            profiles.isEmpty() -> null
            selectedProfileId != null && profiles.any { it.id == selectedProfileId } -> selectedProfileId
            activeProfile != null -> activeProfile?.id
            else -> profiles.first().id
        }
    }

    val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId } ?: activeProfile ?: profiles.firstOrNull()

    Scaffold(
        floatingActionButton = {
            Button(onClick = { showDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Printer")
            }
        },
        containerColor = Color.Transparent
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            ScreenTitle(
                title = "Printer Profiles",
                subtitle = "Mirror the Android printer management flow while keeping Windows-specific constraints explicit."
            )

            printerActionMessage?.let { message ->
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE6FFFB)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(16.dp),
                        color = Color(0xFF0F766E)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            if (profiles.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("No saved printers yet.", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Add a printer to tune receipt width, thermal mode, and default printer behavior.")
                    }
                }
            } else {
                if (profiles.size > 1 && selectedProfile != null) {
                    SelectionDropdown(
                        label = "Choose Printer To Configure",
                        selectedLabel = buildProfileSelectionLabel(selectedProfile),
                        placeholder = "Select a printer profile",
                        options = profiles,
                        onSelect = { profile -> selectedProfileId = profile.id },
                        optionLabel = { profile -> buildProfileSelectionLabel(profile) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                selectedProfile?.let { profile ->
                    PrinterProfileEditorCard(
                        profile = profile,
                        controller = controller
                    )
                }
            }

            Spacer(modifier = Modifier.height(100.dp))
        }
    }

    if (showDialog) {
        AddPrinterDialog(
            controller = controller,
            hasExistingDefault = profiles.any { it.isDefault },
            onDismiss = { showDialog = false },
            onConfirm = { name, type, address, isDefault, paperWidthMm, printAreaDots ->
                controller.addProfile(name, type, address, isDefault, paperWidthMm, printAreaDots)
                showDialog = false
            }
        )
    }
}

@Composable
private fun PrinterProfileEditorCard(
    profile: PrinterProfile,
    controller: ReceiptBridgeDesktopController
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Text("${profile.connectionType} - ${profile.address}")
                    Text("Receipt: ${profile.paperWidthMm} mm - ${profile.resolvedPrintAreaDots()} dots")
                    Text("Odoo: ${profile.resolvedOdooReceiptRenderMode().asFriendlyLabel()}")
                    if (profile.isDefault) {
                        Text("DEFAULT", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
                IconButton(onClick = { controller.deleteProfile(profile) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            ReceiptSizeSelector(
                selectedPaperWidthMm = profile.paperWidthMm,
                onSelect = { controller.updatePaperWidth(profile, it) }
            )

            Spacer(modifier = Modifier.height(12.dp))
            PrintAreaDotsEditor(
                currentPrintAreaDots = profile.resolvedPrintAreaDots(),
                paperWidthMm = profile.paperWidthMm,
                onChange = { controller.updatePrintAreaDots(profile, it) }
            )

            Spacer(modifier = Modifier.height(12.dp))
            OdooReceiptRenderModeEditor(
                selectedMode = profile.resolvedOdooReceiptRenderMode(),
                onSelect = { controller.updateOdooReceiptRenderMode(profile, it) }
            )

            Spacer(modifier = Modifier.height(12.dp))
            RenderedReceiptWidthEditor(
                currentFillPercent = profile.resolvedRenderedReceiptFillPercent(),
                onChange = { controller.updateRenderedReceiptFillPercent(profile, it) }
            )

            Spacer(modifier = Modifier.height(12.dp))
            SettingToggle(
                title = "Smart Receipt Fit",
                supportingText = "Ignore the large outer white margins from the browser receipt page before scaling the Odoo receipt to paper width. Turn this off only if you want the full page exactly as captured.",
                checked = profile.resolvedRenderedReceiptSmartFit(),
                onCheckedChange = { controller.updateRenderedReceiptSmartFit(profile, it) }
            )

            Spacer(modifier = Modifier.height(12.dp))
            WidthCalibrationAssistant(
                profile = profile,
                onPrintCalibration = { controller.queueWidthCalibrationPrint(profile) },
                onApplySuggestedDots = { controller.updatePrintAreaDots(profile, it) },
                onApplySuggestedPaperWidth = { controller.updatePaperWidth(profile, it) }
            )

            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { controller.queueTestPrint(profile) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Connection Test")
                }
                if (!profile.isDefault) {
                    Button(
                        onClick = { controller.setDefault(profile) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Set As Default")
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueScreen(controller: ReceiptBridgeDesktopController) {
    val jobs by controller.jobs.collectAsState()
    val profiles by controller.profiles.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val defaultProfile = profiles.firstOrNull { it.isDefault } ?: profiles.firstOrNull()

    val activeJobs = jobs.filter { it.status == JobStatus.PENDING || it.status == JobStatus.PRINTING }
    val historyJobs = jobs.filter { it.status == JobStatus.COMPLETED || it.status == JobStatus.FAILED }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTitle(
            title = "Print Queue",
            subtitle = "The local bridge writes HTTP jobs here first, then dispatches them to the selected printer."
        )

        Spacer(modifier = Modifier.height(16.dp))
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Active (${activeJobs.size})") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("History (${historyJobs.size})") })
        }

        if (selectedTab == 1 && historyJobs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = { controller.clearHistory() }) {
                Text("Clear History")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val displayJobs = if (selectedTab == 0) activeJobs else historyJobs
            items(displayJobs, key = { it.id }) { job ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Job ${job.id.take(8)}", style = MaterialTheme.typography.titleMedium)
                            Text("Printer: ${job.resolvePrinterLabel(profiles, defaultProfile)}")
                            Text("Status: ${job.status}")
                            Text(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(job.timestamp)))
                            if (job.status == JobStatus.FAILED) {
                                Text("Error: ${job.errorMessage}", color = MaterialTheme.colorScheme.error)
                            }
                        }

                        when (job.status) {
                            JobStatus.FAILED -> {
                                Button(onClick = { controller.retryJob(job) }) {
                                    Text("Retry")
                                }
                            }
                            JobStatus.COMPLETED -> {
                                Button(
                                    onClick = { controller.retryJob(job) },
                                    enabled = job.canRetryFromHistory()
                                ) {
                                    Text(if (job.canRetryFromHistory()) "Re-print" else "Compacted")
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(controller: ReceiptBridgeDesktopController) {
    val settings by controller.settings.collectAsState()
    val profiles by controller.profiles.collectAsState()
    val bridgeEvents by controller.bridgeEvents.collectAsState()
    val systemPrintTestInProgress by controller.systemPrintTestInProgress.collectAsState()
    val systemPrintTestMessage by controller.systemPrintTestMessage.collectAsState()
    val defaultProfile = profiles.firstOrNull { it.isDefault } ?: profiles.firstOrNull()
    val systemPrintWidthDraft = remember(settings.systemPrintContentFillPercent) {
        mutableFloatStateOf(settings.systemPrintContentFillPercent.toFloat())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenTitle(
            title = "Settings",
            subtitle = "The desktop port keeps the Android settings model, while printer-specific receipt fitting now lives on each Windows printer profile."
        )

        Spacer(modifier = Modifier.height(18.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(modifier = Modifier.padding(18.dp)) {
                OutlinedTextField(
                    value = settings.globalHeader.orEmpty(),
                    onValueChange = { controller.updateSettings(settings.copy(globalHeader = it)) },
                    label = { Text("Global Header (Text or base64:...)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = settings.globalFooter.orEmpty(),
                    onValueChange = { controller.updateSettings(settings.copy(globalFooter = it)) },
                    label = { Text("Global Footer Text") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(18.dp))

                StepperSetting(
                    title = "Keep History",
                    valueText = "${settings.keepHistoryDays} days",
                    supportingText = "Completed and failed jobs older than this are removed automatically.",
                    canDecrease = settings.keepHistoryDays > MIN_KEEP_HISTORY_DAYS,
                    canIncrease = settings.keepHistoryDays < MAX_KEEP_HISTORY_DAYS,
                    onDecrease = {
                        controller.updateSettings(
                            settings.copy(
                                keepHistoryDays = sanitizeKeepHistoryDays(settings.keepHistoryDays - KEEP_HISTORY_STEP_DAYS)
                            )
                        )
                    },
                    onIncrease = {
                        controller.updateSettings(
                            settings.copy(
                                keepHistoryDays = sanitizeKeepHistoryDays(settings.keepHistoryDays + KEEP_HISTORY_STEP_DAYS)
                            )
                        )
                    }
                )

                Spacer(modifier = Modifier.height(18.dp))
                SettingToggle(
                    title = "Auto-print on USB Connect",
                    supportingText = "Saved for parity with Android. The Windows port can print to saved Windows USB printer queues, but it does not auto-attach to newly connected USB devices.",
                    checked = settings.autoPrintOnConnect,
                    onCheckedChange = { controller.updateSettings(settings.copy(autoPrintOnConnect = it)) }
                )

                Spacer(modifier = Modifier.height(22.dp))
                Text("Global Receipt Image Width Fallback", style = MaterialTheme.typography.titleMedium)
                Text(
                    "This fallback is used for older printer profiles that have not been tuned yet. For precise Odoo receipt fitting, use the per-printer controls in Printer Profiles.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("${systemPrintWidthDraft.floatValue.roundToInt()}%", style = MaterialTheme.typography.titleLarge)

                Slider(
                    value = systemPrintWidthDraft.floatValue,
                    onValueChange = { value ->
                        systemPrintWidthDraft.floatValue = value.roundToInt().toFloat()
                    },
                    onValueChangeFinished = {
                        controller.updateSettings(
                            settings.copy(
                                systemPrintContentFillPercent = sanitizeSystemPrintContentFillPercent(
                                    systemPrintWidthDraft.floatValue.roundToInt()
                                )
                            )
                        )
                    },
                    valueRange = MIN_SYSTEM_PRINT_CONTENT_FILL_PERCENT.toFloat()..MAX_SYSTEM_PRINT_CONTENT_FILL_PERCENT.toFloat(),
                    steps = (MAX_SYSTEM_PRINT_CONTENT_FILL_PERCENT - MIN_SYSTEM_PRINT_CONTENT_FILL_PERCENT) - 1,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))
                ReceiptSettingsPreview(
                    settings = settings,
                    previewFillPercent = systemPrintWidthDraft.floatValue.roundToInt(),
                    profile = defaultProfile
                )

                Spacer(modifier = Modifier.height(16.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Settings Print Test", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        if (defaultProfile == null) {
                            Text("No saved printer available. Add a printer first, then come back here to run the test.")
                        } else {
                            Text("Uses: ${defaultProfile.name} (${defaultProfile.paperWidthMm} mm / ${defaultProfile.resolvedPrintAreaDots()} dots)")
                            Text(
                                "This desktop test prints a sample receipt through the same queue and network pipeline used by incoming HTTP jobs."
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    controller.clearSystemPrintTestMessage()
                                    controller.runSettingsPrintTest()
                                },
                                enabled = !systemPrintTestInProgress
                            ) {
                                Text(if (systemPrintTestInProgress) "Queueing..." else "Print Settings Test")
                            }
                        }

                        systemPrintTestMessage?.let { message ->
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(message, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("Odoo Bridge Integration", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "The Windows bridge now exposes an Odoo-friendly receipt endpoint with browser preflight support, optional token checks, and startup payload import.",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = settings.odooAllowedOrigins,
                    onValueChange = { controller.updateSettings(settings.copy(odooAllowedOrigins = it)) },
                    label = { Text("Allowed Browser Origins") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Use `*` to allow any origin, or enter one origin per line such as `https://your-odoo-domain`.",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = settings.odooApiToken.orEmpty(),
                    onValueChange = { controller.updateSettings(settings.copy(odooApiToken = it)) },
                    label = { Text("Bridge Token (Optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "If you set a token, browser requests must send `X-ReceiptBridge-Token` or `Authorization: Bearer <token>`.",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(modifier = Modifier.height(18.dp))
                SettingToggle(
                    title = "Accept Launch Payloads",
                    supportingText = "Allows the Windows app to queue a print job when it starts with a `receiptbridge://...` URI or a Base64 payload argument.",
                    checked = settings.acceptLaunchPayloads,
                    onCheckedChange = { controller.updateSettings(settings.copy(acceptLaunchPayloads = it)) }
                )

                Spacer(modifier = Modifier.height(18.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Bridge Endpoints", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("GET /status")
                        Text("GET /integration/status")
                        Text("POST /print")
                        Text("POST /odoo/receipt")
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))
                Text("Recent Bridge Activity", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                if (bridgeEvents.isEmpty()) {
                    Text(
                        "No Odoo or browser requests have reached the Windows bridge yet.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    bridgeEvents.take(6).forEach { event ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(event.timestamp))} - ${event.source.uppercase(Locale.getDefault())}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF475569)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    event.message,
                                    color = event.level.asEventColor()
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AddPrinterDialog(
    controller: ReceiptBridgeDesktopController,
    hasExistingDefault: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, ConnectionType, String, Boolean, Int, Int) -> Unit
) {
    val foundIpDevices by controller.foundIpDevices.collectAsState()
    val foundUsbPrinters by controller.foundUsbPrinters.collectAsState()
    val isNetworkScanning by controller.isNetworkScanning.collectAsState()
    val isUsbScanning by controller.isUsbScanning.collectAsState()
    val networkScanMessage by controller.networkScanMessage.collectAsState()
    val isTestingNetworkAddress by controller.isTestingNetworkAddress.collectAsState()
    val networkAddressTestMessage by controller.networkAddressTestMessage.collectAsState()
    val usbDiscoveryMessage by controller.usbDiscoveryMessage.collectAsState()

    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("192.168.1.100") }
    var type by remember { mutableStateOf(ConnectionType.NETWORK) }
    var paperWidthMm by remember { mutableIntStateOf(PAPER_WIDTH_80_MM) }
    var printAreaDots by remember { mutableIntStateOf(defaultPrintAreaDotsForPaperWidthMm(PAPER_WIDTH_80_MM)) }
    var setAsDefault by remember(hasExistingDefault) { mutableStateOf(!hasExistingDefault) }

    val resolvedProfileName = remember(name, type, address) {
        resolveProfileName(name, type, address)
    }

    LaunchedEffect(type) {
        controller.clearNetworkAddressTestMessage()
        controller.clearUsbDiscoveryMessage()
        if (type == ConnectionType.NETWORK) {
            controller.scanNetwork()
        } else if (type == ConnectionType.USB) {
            address = ""
            paperWidthMm = PAPER_WIDTH_58_MM
            printAreaDots = defaultPrintAreaDotsForPaperWidthMm(PAPER_WIDTH_58_MM)
            controller.scanUsbPrinters()
        } else {
            address = ""
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Printer") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile Name (optional)") },
                    placeholder = { Text(defaultProfileNameHint(type, address)) },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ConnectionType.values().forEach { option ->
                        Button(
                            onClick = {
                                type = option
                                address = if (option == ConnectionType.NETWORK) "192.168.1.100" else ""
                            },
                            enabled = type != option,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                when (option) {
                                    ConnectionType.NETWORK -> "Net"
                                    ConnectionType.BLUETOOTH -> "BT"
                                    ConnectionType.USB -> "USB"
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                when (type) {
                    ConnectionType.NETWORK -> {
                        Text(
                            "Find printers on the same network or type the printer IP manually. You can also enter a custom raw-print port using IP:PORT.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { controller.scanNetwork() },
                                enabled = !isNetworkScanning,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (isNetworkScanning) "Scanning..." else "Scan Network")
                            }
                            Button(
                                onClick = { controller.testNetworkAddress(address) },
                                enabled = address.isNotBlank() && !isTestingNetworkAddress,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (isTestingNetworkAddress) "Testing..." else "Test Address")
                            }
                        }
                        networkScanMessage?.let {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (foundIpDevices.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            SelectionDropdown(
                                label = "Detected Network Printers",
                                selectedLabel = foundIpDevices.firstOrNull { it == address }.orEmpty(),
                                placeholder = "Choose a detected printer",
                                options = foundIpDevices,
                                onSelect = { ip ->
                                    address = ip
                                    controller.clearNetworkAddressTestMessage()
                                    if (name.isBlank()) {
                                        name = "Network Printer $ip"
                                    }
                                },
                                optionLabel = { it }
                            )
                        }
                        OutlinedTextField(
                            value = address,
                            onValueChange = {
                                address = it
                                controller.clearNetworkAddressTestMessage()
                            },
                            label = { Text("IP Address or IP:Port") },
                            placeholder = { Text("192.168.1.50 or 192.168.1.50:9100") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        networkAddressTestMessage?.let {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    ConnectionType.BLUETOOTH -> {
                        Text(
                            "The Windows port keeps Bluetooth profiles for parity with Android, but actual Bluetooth printing is not implemented yet.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            label = { Text("MAC Address or Device Label") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    ConnectionType.USB -> {
                        Text(
                            "Select a detected USB printer. If Windows already has a printer queue, Softbridge will use it. If the device only shows a USB port like USB001, Softbridge will create the Windows queue when you save.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { controller.scanUsbPrinters() },
                            enabled = !isUsbScanning,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isUsbScanning) "Refreshing..." else "Refresh USB Printers")
                        }
                        usbDiscoveryMessage?.let {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (foundUsbPrinters.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            SelectionDropdown(
                                label = "Detected USB Printers",
                                selectedLabel = foundUsbPrinters.firstOrNull { queue ->
                                    address.equals(queue.queueName ?: queue.portName ?: queue.name, ignoreCase = true)
                                }?.let(::formatUsbPrinterQueueLabel).orEmpty(),
                                placeholder = "Choose a USB printer",
                                options = foundUsbPrinters,
                                onSelect = { queue ->
                                    address = queue.queueName ?: queue.portName ?: queue.name
                                    if (name.isBlank()) {
                                        name = "USB Printer ${queue.name}"
                                    }
                                },
                                optionLabel = { queue -> formatUsbPrinterQueueLabel(queue) }
                            )
                        }
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            label = { Text("Windows Printer Queue or USB Port") },
                            placeholder = { Text("Select a printer above or type an exact queue name / USB001") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Receipt Width", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(8.dp))
                ReceiptSizeSelector(
                    selectedPaperWidthMm = paperWidthMm,
                    onSelect = {
                        paperWidthMm = it
                        printAreaDots = defaultPrintAreaDotsForPaperWidthMm(it)
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))
                PrintAreaDotsEditor(
                    currentPrintAreaDots = printAreaDots,
                    paperWidthMm = paperWidthMm,
                    onChange = { printAreaDots = it }
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (hasExistingDefault) "Set as default printer" else "First printer becomes default automatically",
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = setAsDefault,
                        onCheckedChange = { setAsDefault = it }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        resolvedProfileName,
                        type,
                        address.trim(),
                        setAsDefault,
                        paperWidthMm,
                        printAreaDots
                    )
                },
                enabled = resolvedProfileName.isNotBlank() && address.trim().isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun <T> SelectionDropdown(
    label: String,
    selectedLabel: String,
    placeholder: String,
    options: List<T>,
    onSelect: (T) -> Unit,
    optionLabel: (T) -> String
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (selectedLabel.isBlank()) placeholder else selectedLabel)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

private fun buildProfileSelectionLabel(profile: PrinterProfile): String {
    val defaultSuffix = if (profile.isDefault) " - Default" else ""
    return "${profile.name} (${profile.connectionType})$defaultSuffix"
}

private fun formatUsbPrinterQueueLabel(queue: WindowsPrinterQueue): String {
    return when {
        !queue.queueName.isNullOrBlank() && !queue.portName.isNullOrBlank() && queue.isDefault ->
            "${queue.queueName} (${queue.portName}, Windows default)"
        !queue.queueName.isNullOrBlank() && !queue.portName.isNullOrBlank() ->
            "${queue.queueName} (${queue.portName})"
        !queue.queueName.isNullOrBlank() && queue.isDefault ->
            "${queue.queueName} (Windows default)"
        !queue.queueName.isNullOrBlank() ->
            queue.queueName
        !queue.portName.isNullOrBlank() ->
            "${queue.name} (${queue.portName}, queue will be created on save)"
        else -> queue.name
    }
}

@Composable
private fun HeroHeader(
    title: String,
    subtitle: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF0F172A), Color(0xFF134E4A), Color(0xFF0F766E))
                    )
                )
                .padding(26.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(10.dp))
                Text(subtitle, color = Color(0xFFD7F9F4), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun ScreenTitle(
    title: String,
    subtitle: String
) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF475569))
    }
}

@Composable
private fun StatusCard(
    modifier: Modifier = Modifier,
    title: String,
    accent: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(accent, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun QuickActionCard(
    modifier: Modifier = Modifier,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF475569))
        }
    }
}

@Composable
private fun ReceiptSizeSelector(
    selectedPaperWidthMm: Int,
    onSelect: (Int) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { onSelect(PAPER_WIDTH_80_MM) },
            enabled = selectedPaperWidthMm != PAPER_WIDTH_80_MM,
            modifier = Modifier.weight(1f)
        ) {
            Text("80 mm")
        }
        Button(
            onClick = { onSelect(PAPER_WIDTH_58_MM) },
            enabled = selectedPaperWidthMm != PAPER_WIDTH_58_MM,
            modifier = Modifier.weight(1f)
        ) {
            Text("58 mm")
        }
    }
}

@Composable
private fun PrintAreaDotsEditor(
    currentPrintAreaDots: Int,
    paperWidthMm: Int,
    onChange: (Int) -> Unit
) {
    val defaultDots = defaultPrintAreaDotsForPaperWidthMm(paperWidthMm)
    val presets = listOf(
        defaultDots,
        DEFAULT_PRINT_AREA_DOTS_58_MM,
        DEFAULT_PRINT_AREA_DOTS_80_MM,
        832,
        960
    ).distinct()

    Column {
        Text("Print Area (dots)", style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(8.dp))
        StepperSetting(
            title = "Fine Tune",
            valueText = "$currentPrintAreaDots dots",
            supportingText = "Common starting points: 384 for many 58 mm printers and 576 for many 80 mm printers. This controls the printer's real printable width.",
            canDecrease = currentPrintAreaDots > MIN_PRINT_AREA_DOTS,
            canIncrease = currentPrintAreaDots < MAX_PRINT_AREA_DOTS,
            onDecrease = { onChange(sanitizePrintAreaDots(currentPrintAreaDots - PRINT_AREA_DOTS_STEP)) },
            onIncrease = { onChange(sanitizePrintAreaDots(currentPrintAreaDots + PRINT_AREA_DOTS_STEP)) }
        )
        Spacer(modifier = Modifier.height(8.dp))
        presets.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { preset ->
                    Button(
                        onClick = { onChange(preset) },
                        enabled = currentPrintAreaDots != preset,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("$preset")
                    }
                }
                repeat(3 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        TextButton(onClick = { onChange(defaultDots) }, enabled = currentPrintAreaDots != defaultDots) {
            Text("Reset to $defaultDots dots")
        }
    }
}

@Composable
private fun RenderedReceiptWidthEditor(
    currentFillPercent: Int,
    onChange: (Int) -> Unit
) {
    val presets = listOf(85, 90, 95, 100)

    Column {
        Text("Rendered Receipt Width", style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(8.dp))
        StepperSetting(
            title = "Fine Tune",
            valueText = "${currentFillPercent}%",
            supportingText = "This controls how much of the printable width the captured Odoo receipt image should use after the print area is calibrated.",
            canDecrease = currentFillPercent > MIN_SYSTEM_PRINT_CONTENT_FILL_PERCENT,
            canIncrease = currentFillPercent < MAX_SYSTEM_PRINT_CONTENT_FILL_PERCENT,
            onDecrease = { onChange(sanitizeRenderedReceiptFillPercent(currentFillPercent - 1)) },
            onIncrease = { onChange(sanitizeRenderedReceiptFillPercent(currentFillPercent + 1)) }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            presets.forEach { preset ->
                Button(
                    onClick = { onChange(preset) },
                    enabled = currentFillPercent != preset,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("$preset%")
                }
            }
        }
    }
}

@Composable
private fun OdooReceiptRenderModeEditor(
    selectedMode: OdooReceiptRenderMode,
    onSelect: (OdooReceiptRenderMode) -> Unit
) {
    Column {
        Text("Odoo Receipt Mode", style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { onSelect(OdooReceiptRenderMode.EXACT_LAYOUT) },
                enabled = selectedMode != OdooReceiptRenderMode.EXACT_LAYOUT,
                modifier = Modifier.weight(1f)
            ) {
                Text("Exact Layout")
            }
            Button(
                onClick = { onSelect(OdooReceiptRenderMode.NATIVE_THERMAL) },
                enabled = selectedMode != OdooReceiptRenderMode.NATIVE_THERMAL,
                modifier = Modifier.weight(1f)
            ) {
                Text("Native Thermal")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            when (selectedMode) {
                OdooReceiptRenderMode.EXACT_LAYOUT ->
                    "Closest to the Odoo receipt screen. The Windows app will use the captured Odoo receipt image when it is available."
                OdooReceiptRenderMode.NATIVE_THERMAL ->
                    "Rebuilds the receipt for thermal output. Usually sharper on tiny text, but spacing and font sizes can differ from Odoo."
            },
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF475569)
        )
    }
}

@Composable
private fun WidthCalibrationAssistant(
    profile: PrinterProfile,
    onPrintCalibration: () -> Unit,
    onApplySuggestedDots: (Int) -> Unit,
    onApplySuggestedPaperWidth: (Int) -> Unit
) {
    var measuredWidthText by remember(profile.id) { mutableStateOf("") }
    val measuredWidthMm = measuredWidthText.replace(',', '.').toFloatOrNull()
    val suggestedDots = measuredWidthMm?.let { suggestPrintAreaDots(profile, it) }
    val suggestedPaperWidthMm = measuredWidthMm?.let(::suggestPaperWidthMm)
    val paperWidthMismatch = suggestedPaperWidthMm != null && suggestedPaperWidthMm != profile.paperWidthMm

    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Width Calibration Assistant", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Print a guide, measure the outer current-width guide in millimeters, and let the Windows app suggest a tighter dots value for this printer.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF475569)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onPrintCalibration) {
                Text("Print Calibration Test")
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = measuredWidthText,
                onValueChange = { measuredWidthText = it },
                label = { Text("Measured current-width guide (mm)") },
                placeholder = { Text("${profile.paperWidthMm}") },
                modifier = Modifier.fillMaxWidth()
            )

            if (suggestedDots != null && measuredWidthMm > 0f) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Suggested print area: $suggestedDots dots for the selected ${profile.paperWidthMm} mm roll.",
                    color = MaterialTheme.colorScheme.primary
                )
                if (paperWidthMismatch) {
                    Text(
                        "The measured width looks closer to a ${suggestedPaperWidthMm} mm profile than ${profile.paperWidthMm} mm.",
                        color = Color(0xFFB45309),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onApplySuggestedDots(suggestedDots) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Apply Dots")
                    }
                    if (paperWidthMismatch && suggestedPaperWidthMm != null) {
                        Button(
                            onClick = { onApplySuggestedPaperWidth(suggestedPaperWidthMm) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Use ${suggestedPaperWidthMm} mm")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingToggle(
    title: String,
    supportingText: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(supportingText, style = MaterialTheme.typography.bodySmall, color = Color(0xFF475569))
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StepperSetting(
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
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onDecrease, enabled = canDecrease) { Text("-") }
                Spacer(modifier = Modifier.width(10.dp))
                Text(valueText, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.width(10.dp))
                Button(onClick = onIncrease, enabled = canIncrease) { Text("+") }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(supportingText, style = MaterialTheme.typography.bodySmall, color = Color(0xFF475569))
    }
}

@Composable
private fun ReceiptSettingsPreview(
    settings: AppSettings,
    previewFillPercent: Int,
    profile: PrinterProfile?
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Receipt Preview", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .background(Color.White, RoundedCornerShape(20.dp))
                    .padding(18.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(settings.globalHeader?.takeIf { it.isNotBlank() } ?: "Global Header", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .background(Color(0xFFE2E8F0), RoundedCornerShape(10.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(previewFillPercent / 100f)
                                .height(10.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Width hint: $previewFillPercent%", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        profile?.let { "Printer: ${it.name} (${it.paperWidthMm} mm / ${it.resolvedPrintAreaDots()} dots)" }
                            ?: "Select a printer to print this preview for real.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF475569)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(settings.globalFooter?.takeIf { it.isNotBlank() } ?: "Global Footer", style = MaterialTheme.typography.bodySmall)
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
        matchedProfile != null -> "${matchedProfile.name} (${matchedProfile.paperWidthMm} mm, ${matchedProfile.resolvedPrintAreaDots()} dots)"
        printerProfileId != null -> "Saved printer unavailable"
        defaultProfile != null -> "${defaultProfile.name} (${defaultProfile.paperWidthMm} mm, ${defaultProfile.resolvedPrintAreaDots()} dots, default)"
        else -> "Default printer not set"
    }
}

private fun PrintJob.canRetryFromHistory(): Boolean {
    return !payloadJson.contains("This completed job payload was compacted by Softbridge")
}

private fun defaultProfileNameHint(
    type: ConnectionType,
    address: String
): String {
    return defaultProfileNameFor(type, address).ifBlank {
        when (type) {
            ConnectionType.NETWORK -> "Network Printer"
            ConnectionType.BLUETOOTH -> "Bluetooth Printer"
            ConnectionType.USB -> "USB Printer"
        }
    }
}

private fun resolveProfileName(
    typedName: String,
    type: ConnectionType,
    address: String
): String {
    val trimmed = typedName.trim()
    return if (trimmed.isNotBlank()) trimmed else defaultProfileNameFor(type, address)
}

private fun defaultProfileNameFor(
    type: ConnectionType,
    address: String
): String {
    val trimmedAddress = address.trim()
    return when (type) {
        ConnectionType.NETWORK -> if (trimmedAddress.isBlank()) "Network Printer" else "Network Printer $trimmedAddress"
        ConnectionType.BLUETOOTH -> if (trimmedAddress.isBlank()) "Bluetooth Printer" else "Bluetooth Printer $trimmedAddress"
        ConnectionType.USB -> if (trimmedAddress.isBlank()) "USB Printer" else "USB Printer $trimmedAddress"
    }
}

private const val KEEP_HISTORY_STEP_DAYS = 5

private fun suggestPrintAreaDots(
    profile: PrinterProfile,
    measuredWidthMm: Float
): Int? {
    if (measuredWidthMm <= 0f) {
        return null
    }

    val recommendedDots = (profile.resolvedPrintAreaDots() * (profile.paperWidthMm / measuredWidthMm)).roundToInt()
    return sanitizePrintAreaDots(recommendedDots)
}

private fun suggestPaperWidthMm(measuredWidthMm: Float): Int {
    return if (abs(measuredWidthMm - PAPER_WIDTH_58_MM) <= abs(measuredWidthMm - PAPER_WIDTH_80_MM)) {
        PAPER_WIDTH_58_MM
    } else {
        PAPER_WIDTH_80_MM
    }
}

private fun summarizeOriginRules(rawValue: String): String {
    val rules = rawValue
        .split(Regex("[,\\r\\n]+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (rules.isEmpty()) {
        return "No browser origins allowed"
    }
    if (rules.firstOrNull() == "*") {
        return "Any origin"
    }
    return rules.take(2).joinToString(", ") + if (rules.size > 2) " +" else ""
}

private fun BridgeEventLevel.asEventColor(): Color {
    return when (this) {
        BridgeEventLevel.INFO -> Color(0xFF0F766E)
        BridgeEventLevel.WARNING -> Color(0xFFB45309)
        BridgeEventLevel.ERROR -> Color(0xFFB91C1C)
    }
}

private fun OdooReceiptRenderMode.asFriendlyLabel(): String {
    return when (this) {
        OdooReceiptRenderMode.EXACT_LAYOUT -> "Exact Layout"
        OdooReceiptRenderMode.NATIVE_THERMAL -> "Native Thermal"
    }
}

private fun String.asFriendlyStateLabel(): String {
    val lowered = lowercase()
    return lowered.replaceFirstChar {
        if (it.isLowerCase()) {
            it.titlecase()
        } else {
            it.toString()
        }
    }
}
