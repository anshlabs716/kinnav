package org.geonav.app.ui.screens

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CompassCalibration
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.geonav.app.GeoNavApplication
import org.geonav.app.BuildConfig
import org.geonav.app.R
import org.geonav.app.data.model.MapStyleType
import org.geonav.app.data.repository.AppThemeMode
import org.geonav.app.data.repository.SpeedUnit
import org.geonav.app.data.storage.ExchangeResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToSharing: () -> Unit = {},
    onNavigateToSharingDiagnostics: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = GeoNavApplication.instance
    val coroutineScope = rememberCoroutineScope()

    val currentTheme by app.settingsRepository.themeMode.collectAsState()
    val currentStyle by app.settingsRepository.currentStyle.collectAsState()
    val satelliteUrl by app.settingsRepository.satelliteProviderUrl.collectAsState()
    val speedUnit by app.settingsRepository.speedUnit.collectAsState()
    val autoZoom by app.settingsRepository.autoZoom.collectAsState()
    val speedBasedCamera by app.settingsRepository.speedBasedCamera.collectAsState()
    val trueNorth by app.settingsRepository.trueNorth.collectAsState()
    val voiceGuidance by app.settingsRepository.voiceGuidance.collectAsState()
    val fusionState by app.positioningRepository.fusionState.collectAsState()

    var showSatelliteDialog by remember { mutableStateOf(false) }
    var satelliteInputText by remember { mutableStateOf(satelliteUrl) }
    var showExportDialog by remember { mutableStateOf(false) }

    // Export: the user picks a location with the system document creator, so the file
    // lands somewhere they can actually reach — the old path wrote into app-private
    // storage that no file manager could open.
    var pendingExportFormat by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val format = pendingExportFormat
        pendingExportFormat = null
        if (uri != null && format != null) {
            coroutineScope.launch {
                val places = app.placesRepository.savedPlaces.first()
                val output = context.contentResolver.openOutputStream(uri)
                if (output == null) {
                    Toast.makeText(context, "Couldn't open the chosen file for writing", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val result = output.use { stream ->
                    when (format) {
                        "GPX" -> app.dataExchangeManager.exportGpx(places, stream)
                        "KML" -> app.dataExchangeManager.exportKml(places, stream)
                        "GeoJSON" -> app.dataExchangeManager.exportGeoJson(places, stream)
                        else -> app.dataExchangeManager.exportCsv(places, stream)
                    }
                }
                when (result) {
                    is ExchangeResult.Success ->
                        Toast.makeText(context, "Exported ${places.size} place(s)", Toast.LENGTH_LONG).show()
                    is ExchangeResult.Error ->
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Import: pick a GPX/KML/GeoJSON/CSV file, parse it with the existing data-exchange
    // parser, and save every waypoint. Same-name, same-coordinate waypoints are skipped
    // so re-importing a file is idempotent.
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val displayName = context.contentResolver
                    .query(uri, null, null, null, null)
                    ?.use { cursor ->
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                    }
                val format = when {
                    displayName == null -> null
                    displayName.endsWith(".gpx", ignoreCase = true) -> "GPX"
                    displayName.endsWith(".kml", ignoreCase = true) -> "KML"
                    displayName.endsWith(".geojson", ignoreCase = true) ||
                        displayName.endsWith(".json", ignoreCase = true) -> "GEOJSON"
                    displayName.endsWith(".csv", ignoreCase = true) -> "CSV"
                    else -> null
                }
                if (format == null) {
                    Toast.makeText(
                        context,
                        "Unsupported file — use GPX, KML, GeoJSON or CSV",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                val input = context.contentResolver.openInputStream(uri)
                if (input == null) {
                    Toast.makeText(context, "Couldn't open the selected file", Toast.LENGTH_LONG).show()
                    return@launch
                }
                when (val result = app.dataExchangeManager.importPlaces(input, format)) {
                    is ExchangeResult.Error ->
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                    is ExchangeResult.Success -> {
                        val existing = app.placesRepository.savedPlaces.first()
                        var added = 0
                        for (place in result.data) {
                            val duplicate = existing.any { kept ->
                                kept.name == place.name &&
                                    Math.abs(kept.location.latitude - place.location.latitude) < 1e-6 &&
                                    Math.abs(kept.location.longitude - place.location.longitude) < 1e-6
                            }
                            if (!duplicate) {
                                app.placesRepository.savePlace(place)
                                added++
                            }
                        }
                        val message = when {
                            result.data.isEmpty() -> "No waypoints found in the file"
                            added == 0 -> "All ${result.data.size} waypoint(s) already saved"
                            else -> "Imported $added place(s), skipped ${result.data.size - added} duplicate(s)"
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        modifier = modifier.testTag("settings_screen")
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Section 1: Appearance & Theme
            item {
                SettingsSectionHeader("APPEARANCE & THEME")
            }

            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Theme Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            AppThemeMode.entries.forEach { mode ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { app.settingsRepository.setThemeMode(mode) }
                                ) {
                                    RadioButton(
                                        selected = currentTheme == mode,
                                        onClick = { app.settingsRepository.setThemeMode(mode) }
                                    )
                                    Text(mode.title, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }

            // Section 2: Speedometer & Navigation
            item {
                SettingsSectionHeader("SPEEDOMETER & UNITS")
            }

            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Speed Unit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            SpeedUnit.entries.forEach { unit ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { app.settingsRepository.setSpeedUnit(unit) }
                                ) {
                                    RadioButton(
                                        selected = speedUnit == unit,
                                        onClick = { app.settingsRepository.setSpeedUnit(unit) }
                                    )
                                    Text(unit.label, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        SettingsSwitchRow(
                            icon = Icons.Default.VolumeUp,
                            title = "Voice Turn Guidance",
                            subtitle = "Speak upcoming maneuvers and navigation alerts",
                            checked = voiceGuidance,
                            onCheckedChange = { app.settingsRepository.setVoiceGuidance(it) }
                        )
                    }
                }
            }

            // Section 3: Map Style & Satellite Architecture
            item {
                SettingsSectionHeader("MAP LAYERS & SATELLITE PROVIDER")
            }

            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    satelliteInputText = satelliteUrl
                                    showSatelliteDialog = true
                                }
                        ) {
                            Icon(Icons.Default.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Satellite Imagery Provider", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = if (satelliteUrl.isBlank()) "Provider-independent (Not configured)" else satelliteUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "Configure",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Section 4: Camera & Sensors
            item {
                SettingsSectionHeader("CAMERA & POSITION FUSION")
            }

            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SettingsSwitchRow(
                            icon = Icons.Default.CameraAlt,
                            title = "Speed-Based Auto Zoom",
                            subtitle = "Adjust camera distance smoothly as velocity increases",
                            checked = autoZoom,
                            onCheckedChange = { app.settingsRepository.setAutoZoom(it) }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        SettingsSwitchRow(
                            icon = Icons.Default.CompassCalibration,
                            title = "True North Orientation",
                            subtitle = "Compensate for geomagnetic declination automatically",
                            checked = trueNorth,
                            onCheckedChange = { app.settingsRepository.setTrueNorth(it) }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Sensors, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Detected Hardware Sensors", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                val sensorsStr = if (fusionState.availableSensors.isNotEmpty()) {
                                    fusionState.availableSensors.joinToString(", ")
                                } else {
                                    "Scanning device sensors..."
                                }
                                Text(
                                    text = sensorsStr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Section 5: Data Exchange (Import / Export)
            item {
                SettingsSectionHeader("DATA MANAGEMENT (IMPORT / EXPORT)")
            }

            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showExportDialog = true }
                                .padding(vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Export Saved Places & Waypoints", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("Supports GPX, KML, GeoJSON, CSV formats", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { importLauncher.launch(arrayOf("*/*")) }
                                .padding(vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Import Saved Places & Waypoints", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("Reads GPX, KML, GeoJSON and CSV files", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    coroutineScope.launch {
                                        app.searchRepository.clearHistory()
                                        Toast.makeText(context, "Search history cleared", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Clear Search History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("Wipes local query logs from device storage", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // Section: Location Sharing (ported from Luogo)
            item {
                SettingsSectionHeader("LOCATION SHARING")
            }

            item {
                val familyRepo = remember { org.geonav.family.Family.repository }
                val sharingProfile by (familyRepo?.userProfile
                    ?: kotlinx.coroutines.flow.MutableStateFlow<org.geonav.family.domain.UserProfile?>(null))
                    .collectAsState()
                val sharingOn = sharingProfile?.sharingEnabled == true

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToSharing() }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            Icons.Default.People,
                            contentDescription = null,
                            tint = if (sharingOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("People & sharing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (sharingOn) "Sharing your location" else "Sharing is off",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = sharingOn, onCheckedChange = null)
                    }
                }
            }

            if (org.geonav.family.Family.repository != null && BuildConfig.DEBUG) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToSharingDiagnostics() }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Sharing diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("NERDY readout of the sharing engine (debug builds only)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // Section 6: About
            item {
                SettingsSectionHeader("ABOUT & SYSTEM")
            }

            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToAbout() }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("About KinNav", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("FOSS • Zero telemetry • MapLibre & OpenStreetMap", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        // Dialog: Satellite URL Configuration
        if (showSatelliteDialog) {
            AlertDialog(
                onDismissRequest = { showSatelliteDialog = false },
                title = { Text("Configure Satellite Provider") },
                text = {
                    Column {
                        Text(
                            text = "KinNav does not scrape unauthorized tiles. Enter your legitimate raster or vector tile endpoint (e.g., WMTS/XYZ URL template with {z}/{x}/{y}):",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = satelliteInputText,
                            onValueChange = { satelliteInputText = it },
                            label = { Text("Tile Provider URL") },
                            placeholder = { Text("https://example.com/tiles/{z}/{x}/{y}.png") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            app.settingsRepository.setSatelliteProviderUrl(satelliteInputText)
                            showSatelliteDialog = false
                            Toast.makeText(context, "Provider URL saved", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSatelliteDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Dialog: Export Format Selector
        if (showExportDialog) {
            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                title = { Text("Export Format") },
                text = {
                    Column {
                        Text("Select format for saved waypoints and places:")
                        Spacer(modifier = Modifier.height(8.dp))
                        listOf("GPX", "KML", "GeoJSON", "CSV").forEach { format ->
                            TextButton(
                                onClick = {
                                    showExportDialog = false
                                    // SAF document creator: the user chooses where the
                                    // file lands; we only fill in its content.
                                    val ext = format.lowercase()
                                    pendingExportFormat = format
                                    exportLauncher.launch("kinnav_places.$ext")
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(format, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showExportDialog = false }) {
                        Text("Close")
                    }
                }
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
