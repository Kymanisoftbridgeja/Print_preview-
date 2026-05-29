package com.tanjo.servicereports.ui

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tanjo.servicereports.data.local.JobEntity
import com.tanjo.servicereports.data.local.PartEntity
import com.tanjo.servicereports.data.local.ServiceReportEntity
import com.tanjo.servicereports.data.repository.ConnectionDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceReportApp(vm: ServiceReportViewModel = viewModel()) {
    val jobs by vm.jobs.collectAsState()
    val serviceReports by vm.serviceReports.collectAsState()
    val report by vm.selectedReport.collectAsState()
    val parts by vm.parts.collectAsState()
    val message by vm.message.collectAsState()

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Field Service Jobs") },
                    actions = {
                        OutlinedButton(onClick = vm::refresh) { Text("Refresh") }
                        OutlinedButton(onClick = vm::syncNow) {
                            Icon(Icons.Default.CloudSync, contentDescription = null)
                            Text("Sync")
                        }
                    }
                )
            }
        ) { padding ->
            Surface(Modifier.fillMaxSize().padding(padding)) {
                    if (report == null) {
                        HomeScreen(
                            jobs,
                            serviceReports,
                            message,
                            vm.connectionDefaults,
                            vm::login,
                            vm::openJob,
                            vm::openReport,
                            vm::newEmergencyReport
                        )
                    } else {
                    ReportScreen(report!!, parts, message, vm::save, vm::start, vm::stop, vm::submit, vm::addPart, vm::removePart, vm::addPhoto) {
                        vm.selectedReportId.value = null
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    jobs: List<JobEntity>,
    reports: List<ServiceReportEntity>,
    message: String,
    connectionDefaults: ConnectionDefaults,
    onLogin: (String, String, String, String) -> Unit,
    onOpenJob: (JobEntity) -> Unit,
    onOpenReport: (ServiceReportEntity) -> Unit,
    onEmergency: () -> Unit
) {
    var baseUrl by remember(connectionDefaults) { mutableStateOf(connectionDefaults.baseUrl) }
    var db by remember(connectionDefaults) { mutableStateOf(connectionDefaults.db) }
    var login by remember(connectionDefaults) { mutableStateOf(connectionDefaults.login) }
    var password by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Technician Login", fontWeight = FontWeight.Bold)
                    OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Odoo Server URL") }, placeholder = { Text("https://your-odoo-server") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(db, { db = it }, label = { Text("Database (optional if only one)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(login, { login = it }, label = { Text("User") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { onLogin(baseUrl, db, login, password) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Log In")
                    }
                    if (message.isNotBlank()) Text(message)
                }
            }
        }
        item {
            Button(onClick = onEmergency, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("New Service Report")
            }
        }
        item { SectionTitle("Assigned Jobs") }
        if (jobs.isEmpty()) item { Text("No assigned Jobs synced yet.") }
        items(jobs) { job ->
            JobCard(job, onOpenJob)
        }
        item { SectionTitle("My Service Reports") }
        val myReports = reports.filter { it.syncStatus == "Synced" && it.state !in setOf("submitted", "approved", "quotation_created") }
        if (myReports.isEmpty()) item { Text("No active Service Reports synced yet.") }
        items(myReports) { report -> ReportCard(report, onOpenReport) }
        item { SectionTitle("Draft Reports") }
        val draftReports = reports.filter { it.state in setOf("draft", "assigned", "in_progress", "completed", "rejected") && it.syncStatus != "Pending Sync" }
        if (draftReports.isEmpty()) item { Text("No draft reports.") }
        items(draftReports) { report -> ReportCard(report, onOpenReport) }
        item { SectionTitle("Pending Sync") }
        val pendingReports = reports.filter { it.syncStatus in setOf("Pending Sync", "Sync Failed", "Syncing") }
        if (pendingReports.isEmpty()) item { Text("No reports waiting to sync.") }
        items(pendingReports) { report -> ReportCard(report, onOpenReport) }
        item { SectionTitle("Submitted Reports") }
        val submittedReports = reports.filter { it.state == "submitted" }
        if (submittedReports.isEmpty()) item { Text("No submitted reports.") }
        items(submittedReports) { report -> ReportCard(report, onOpenReport) }
        item { SectionTitle("Emergency Reports") }
        val emergencyReports = reports.filter { it.jobId == null }
        if (emergencyReports.isEmpty()) item { Text("No emergency reports.") }
        items(emergencyReports) { report -> ReportCard(report, onOpenReport) }
    }
}

@Composable
private fun JobCard(job: JobEntity, onOpen: (JobEntity) -> Unit) {
    Card(onClick = { onOpen(job) }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(job.jobNumber, fontWeight = FontWeight.Bold)
            Text(job.companyName)
            if (job.contactName.isNotBlank()) Text("Contact: ${job.contactName}")
            Text(job.address)
            Text("Scheduled: ${job.scheduledDate}")
            Text("Service: ${job.serviceType}")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(job.jobStatus)
                Text(job.syncStatus)
                Text(job.reportStatus)
            }
        }
    }
}

@Composable
private fun ReportCard(report: ServiceReportEntity, onOpen: (ServiceReportEntity) -> Unit) {
    Card(onClick = { onOpen(report) }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(report.reportNumber.ifBlank { "Service Report" }, fontWeight = FontWeight.Bold)
            Text(report.companyName.ifBlank { report.customerName })
            if (report.contactName.isNotBlank()) Text("Contact: ${report.contactName}")
            Text(report.address)
            Text("Service Date: ${report.serviceDate}")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(report.state)
                Text(report.syncStatus)
                Text(if (report.jobId == null) "Emergency" else "Linked Job #${report.jobId}")
            }
        }
    }
}

@Composable
private fun ReportScreen(
    report: ServiceReportEntity,
    parts: List<PartEntity>,
    message: String,
    onSave: (ServiceReportEntity) -> Unit,
    onStart: (ServiceReportEntity) -> Unit,
    onStop: (ServiceReportEntity) -> Unit,
    onSubmit: (ServiceReportEntity) -> Unit,
    onAddPart: (String, String, String, Double, Boolean) -> Unit,
    onRemovePart: (String) -> Unit,
    onAddPhoto: (String, String) -> Unit,
    onBack: () -> Unit
) {
    var draft by remember(report.localId) { mutableStateOf(report) }
    var newPart by remember { mutableStateOf("") }
    var newSerial by remember { mutableStateOf("") }
    var newQty by remember { mutableStateOf("1") }
    var newInvoiceable by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            onAddPhoto(report.localId, uri.toString())
        }
    }
    val lockedSyncedReport = report.state in setOf("submitted", "approved", "quotation_created") &&
        report.syncStatus == "Synced"
    val canEdit = !lockedSyncedReport
    val canStartStop = report.state !in setOf("submitted", "approved", "quotation_created") &&
        report.syncStatus != "Syncing"
    val submitLabel = if (report.state == "submitted" && report.syncStatus != "Synced") {
        "Retry Submit"
    } else {
        "Submit Report"
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("Jobs") }
                Button(onClick = { onStart(draft) }, enabled = canStartStop) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text("Start Job")
                }
                Button(onClick = { onStop(draft) }, enabled = canStartStop) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Text("Stop Job")
                }
            }
            Text("Status: ${report.state} | Sync: ${report.syncStatus}", fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onSave(draft) }, enabled = canEdit, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Text("Save Draft")
                }
                Button(onClick = { onSubmit(draft) }, enabled = canEdit, modifier = Modifier.weight(1f)) {
                    Text(submitLabel)
                }
            }
            if (lockedSyncedReport) {
                Text("Submitted reports are waiting for backend review. A reviewer must send it back before technicians can edit it.")
            }
            if (report.syncError.isNotBlank()) Text(report.syncError, color = MaterialTheme.colorScheme.error)
            if (message.isNotBlank()) Text(message)
        }
        item { SectionTitle("Header Information") }
        item { Field(draft.reportNumber, { draft = draft.copy(reportNumber = it) }, "Report Number") }
        item { Field(draft.jobId?.toString().orEmpty(), {}, "Job Reference") }
        item { Field(draft.customerName, { draft = draft.copy(customerName = it) }, "Contact / Customer Name") }
        item { Field(draft.companyName, { draft = draft.copy(companyName = it) }, "Company Name") }
        item { Field(draft.address, { draft = draft.copy(address = it) }, "Address") }
        item { Field(draft.serviceDate, { draft = draft.copy(serviceDate = it) }, "Service Date") }
        item { Field(draft.arrivalTime, { draft = draft.copy(arrivalTime = it) }, "Arrival Time") }
        item { Field(draft.departureTime, { draft = draft.copy(departureTime = it) }, "Departure Time") }
        item { Field(draft.technicianName, { draft = draft.copy(technicianName = it) }, "Technician") }
        item { Field(draft.vehicle, { draft = draft.copy(vehicle = it) }, "Vehicle") }
        item { Field(draft.poReference, { draft = draft.copy(poReference = it) }, "PO / Reference") }
        item { Field(draft.serviceType, { draft = draft.copy(serviceType = it) }, "Service Type") }
        item { Field(draft.originalReportNumber, { draft = draft.copy(originalReportNumber = it) }, "Original Report Number") }

        item { SectionTitle("Equipment Information") }
        item { Field(draft.make, { draft = draft.copy(make = it) }, "Make") }
        item { Field(draft.model, { draft = draft.copy(model = it) }, "Model") }
        item { Field(draft.kva, { draft = draft.copy(kva = it) }, "KVA / Capacity") }
        item { Field(draft.equipmentType, { draft = draft.copy(equipmentType = it) }, "Equipment Type") }
        item { Field(draft.serialNumber, { draft = draft.copy(serialNumber = it) }, "Serial Number") }
        item { Field(draft.load, { draft = draft.copy(load = it) }, "Load") }
        item { Field(draft.inputVoltage, { draft = draft.copy(inputVoltage = it) }, "Input Voltage") }
        item { Field(draft.outputVoltage, { draft = draft.copy(outputVoltage = it) }, "Output Voltage") }
        item {
            Row { Checkbox(draft.systemDown, { draft = draft.copy(systemDown = it) }); Text("UPS / System Down") }
        }
        item { Field(draft.batteryManufacturer, { draft = draft.copy(batteryManufacturer = it) }, "Battery Manufacturer") }
        item { Field(draft.batteryType, { draft = draft.copy(batteryType = it) }, "Battery Type") }
        item { Field(draft.batteryRating, { draft = draft.copy(batteryRating = it) }, "Battery Rating") }
        item { Field(draft.batteryQuantity.toString(), { draft = draft.copy(batteryQuantity = it.toIntOrNull() ?: 0) }, "Battery Quantity") }

        item { SectionTitle("Service Information") }
        item { Field(draft.problemReported, { draft = draft.copy(problemReported = it) }, "Problem Reported / Service Rendered", true) }
        item { Field(draft.defectsFound, { draft = draft.copy(defectsFound = it) }, "Defects Found", true) }
        item { Field(draft.correctiveAction, { draft = draft.copy(correctiveAction = it) }, "Corrective Action Taken", true) }
        item { Field(draft.recommendations, { draft = draft.copy(recommendations = it) }, "Recommendations", true) }
        item { Field(draft.techniciansOnSite, { draft = draft.copy(techniciansOnSite = it) }, "Technicians On-Site") }
        item { Field(draft.statusOfService, { draft = draft.copy(statusOfService = it) }, "Status of Service") }

        item { SectionTitle("Parts Used") }
        items(parts) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${it.partName}  |  ${it.serialNumber}  |  Qty ${it.quantity}")
                OutlinedButton(onClick = { onRemovePart(it.id) }) { Text("Remove") }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newPart, { newPart = it }, label = { Text("Part Name / Product") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(newSerial, { newSerial = it }, label = { Text("Serial Number") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(newQty, { newQty = it }, label = { Text("Quantity") }, modifier = Modifier.weight(1f))
                    Row(Modifier.weight(1f)) {
                        Checkbox(newInvoiceable, { newInvoiceable = it })
                        Text("Invoiceable")
                    }
                }
                Button(onClick = {
                    onAddPart(report.localId, newPart, newSerial, newQty.toDoubleOrNull() ?: 1.0, newInvoiceable)
                    newPart = ""
                    newSerial = ""
                    newQty = "1"
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("Add Part")
                }
            }
        }

        item { SectionTitle("Signatures") }
        item { Field(draft.customerName, { draft = draft.copy(customerName = it) }, "Customer Name") }
        item { SignaturePad("Customer Signature") { draft = draft.copy(customerSignaturePath = it) } }
        item { Field(draft.technicianName, { draft = draft.copy(technicianName = it) }, "Technician Name") }
        item { SignaturePad("Technician Signature") { draft = draft.copy(technicianSignaturePath = it) } }
        item { Field(draft.signatureDateTime, { draft = draft.copy(signatureDateTime = it) }, "Signature Date/Time") }

        item { SectionTitle("Photos / Attachments") }
        item {
            OutlinedButton(onClick = { photoLauncher.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("Add Photo")
            }
        }

        item {
            Divider()
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(draft) }, enabled = canEdit) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Text("Save Draft")
                }
                Button(onClick = { onSubmit(draft) }, enabled = canEdit) { Text(submitLabel) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, multiline: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        minLines = if (multiline) 3 else 1
    )
}

@Composable
private fun SignaturePad(label: String, onSignatureChanged: (String) -> Unit) {
    var paths by remember { mutableStateOf(listOf<List<Offset>>()) }
    var activePath by remember { mutableStateOf(listOf<Offset>()) }

    Card(Modifier.fillMaxWidth().height(160.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = {
                    paths = emptyList()
                    activePath = emptyList()
                    onSignatureChanged("")
                }) { Text("Clear") }
            }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .background(Color(0xFFF7F7F7))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { activePath = listOf(it) },
                            onDrag = { change, _ -> activePath = activePath + change.position },
                            onDragEnd = {
                                paths = paths + listOf(activePath)
                                onSignatureChanged(signatureSvgBase64(paths + listOf(activePath)))
                                activePath = emptyList()
                            }
                        )
                    }
            ) {
                (paths + listOf(activePath)).forEach { points ->
                    if (points.size > 1) {
                        val path = Path().apply {
                            moveTo(points.first().x, points.first().y)
                            points.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        drawPath(path, Color.Black, style = Stroke(width = 4f, cap = StrokeCap.Round))
                    }
                }
            }
        }
    }
}

private fun signatureSvgBase64(paths: List<List<Offset>>): String {
    val pathData = paths.filter { it.size > 1 }.joinToString(" ") { points ->
        "M ${points.first().x} ${points.first().y} " +
            points.drop(1).joinToString(" ") { "L ${it.x} ${it.y}" }
    }
    val svg = """
        <svg xmlns="http://www.w3.org/2000/svg" width="900" height="300" viewBox="0 0 900 300">
          <path d="$pathData" fill="none" stroke="black" stroke-width="6" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
    """.trimIndent()
    return Base64.encodeToString(svg.toByteArray(), Base64.NO_WRAP)
}
