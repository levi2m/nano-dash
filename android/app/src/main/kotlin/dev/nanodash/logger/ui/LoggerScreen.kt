package dev.nanodash.logger.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import dev.nanodash.core.watch.WatchFormat
import dev.nanodash.logger.ui.components.BusyBar
import java.io.IOException
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import dev.nanodash.core.camera.cameraLabel
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nanodash.logger.BuildConfigInfo
import dev.nanodash.logger.CameraController
import dev.nanodash.logger.CameraUi
import dev.nanodash.logger.LoggerService
import dev.nanodash.logger.Note
import dev.nanodash.logger.RideFiles
import dev.nanodash.logger.RideStore
import dev.nanodash.core.ride.RideFolder
import dev.nanodash.core.ride.RideSummary
import dev.nanodash.core.ride.SpeedUnit
import dev.nanodash.logger.RideUiState
import dev.nanodash.logger.ui.components.ButtonKind
import dev.nanodash.logger.ui.components.Health
import dev.nanodash.logger.ui.components.Hairline
import dev.nanodash.logger.ui.components.NanoButton
import dev.nanodash.logger.ui.components.Notice
import dev.nanodash.logger.ui.components.Panel
import dev.nanodash.logger.ui.components.RouteLine
import dev.nanodash.logger.ui.components.RouteMark
import dev.nanodash.logger.ui.components.SectionLabel
import dev.nanodash.logger.ui.components.caption
import dev.nanodash.logger.ui.components.StatusRow
import dev.nanodash.logger.ui.theme.NanoText
import dev.nanodash.logger.ui.theme.Radius
import dev.nanodash.logger.ui.theme.Space
import dev.nanodash.logger.ui.theme.nanoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun LoggerScreen() {
    val context = LocalContext.current
    val c = nanoColors
    val ui by RideStore.state.collectAsStateWithLifecycle()
    val cam by CameraController.ui.collectAsStateWithLifecycle()
    var rides by remember { mutableStateOf<List<RideSummary>?>(null) }          // null while loading
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirmDelete by remember { mutableStateOf<RideSummary?>(null) }
    var showFolded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // The unit starts from the language region (miles for US, UK and a few others) but the rider can switch it; the choice is kept.
    val prefs = remember { context.getSharedPreferences("nanodash", Context.MODE_PRIVATE) }
    val region = LocalConfiguration.current.locales[0].country
    var unit by remember { mutableStateOf(SpeedUnit.saved(prefs.getString("speed_unit", null)) ?: SpeedUnit.forCountry(region)) }
    // Location can be switched off, or permission revoked, while the app is in the background, so look again every time it returns.
    var gps by remember { mutableStateOf(GpsReadiness.Ready) }
    LifecycleResumeEffect(Unit) {
        gps = gpsReadiness(context)
        onPauseOrDispose { }
    }

    fun fileFailed() {
        RideStore.update { it.copy(message = Note("Could not write the file. Is the phone full?")) }
    }

    suspend fun reload() {
        rides = withContext(Dispatchers.IO) {
            val all = RideFiles.list(context)
            // A ride cut off before its .fit was written is rebuilt from its GPS log, but never while a ride is recording or being saved.
            val missing = if (ui.recording || ui.saving) emptyList() else all.filter { !it.hasFit && it.fixes > 0 }
            missing.forEach { runCatching { RideFolder.rebuildFit(it.dir) } }
            if (missing.isEmpty()) all else RideFiles.list(context)
        }
    }
    LaunchedEffect(ui.recording, ui.message) { reload() }
    // With quick start on, the service is armed whenever the app is on screen: Android only lets it start from the foreground.
    LaunchedEffect(Unit) { LoggerService.arm(context) }
    LaunchedEffect(ui.recording) {
        while (ui.recording) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val permissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    var quickStart by remember { mutableStateOf(LoggerService.quickStartEnabled(context)) }
    var watchName by remember { mutableStateOf(LoggerService.watchName(context)) }
    var chooseWatch by remember { mutableStateOf(false) }
    var watchGuide by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    // Quick start needs location (the service type), Bluetooth (the watch link) and notifications (the Start button).
    val quickStartPermissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val quickStartLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // The switch only turns on when the watch link can work: location for the service type and, on Android 12+, Bluetooth.
        val location = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bluetooth = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (location && bluetooth) {
            quickStart = true
            LoggerService.setQuickStart(context, true)
            if (watchName == null) chooseWatch = true
        } else {
            RideStore.update { it.copy(message = Note("Quick start needs location and Bluetooth. Allow them in settings.", needsSettings = true)) }
        }
    }
    fun toggleQuickStart() {
        val on = !quickStart
        val missing = quickStartPermissions.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (on && missing) { quickStartLauncher.launch(quickStartPermissions); return }
        quickStart = on
        LoggerService.setQuickStart(context, on)
        if (on && watchName == null) chooseWatch = true
    }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) LoggerService.start(context)
        else RideStore.update { it.copy(message = Note("Precise location permission is needed to log a ride.", needsSettings = true)) }
    }
    val bluetoothPermissions = remember {
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()
    }
    var afterBluetooth by remember { mutableStateOf<(() -> Unit)?>(null) }
    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) afterBluetooth?.invoke()
        else RideStore.update { it.copy(message = Note("Bluetooth permission is needed to connect the camera.", needsSettings = true)) }
    }

    /** Runs [action] once the Bluetooth permissions are granted, asking for them first if needed. */
    fun withBluetooth(action: () -> Unit) {
        val ok = bluetoothPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (ok) action() else { afterBluetooth = action; bluetoothLauncher.launch(bluetoothPermissions) }
    }

    fun startRide() {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine) LoggerService.start(context) else locationLauncher.launch(permissions)
    }

    LazyColumn(
        Modifier.fillMaxSize().background(c.bg).systemBarsPadding(),
        contentPadding = PaddingValues(horizontal = Space.l, vertical = Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        item { Header { settings = true } }
        item { RidePanel(ui, cam, now, gps, unit) }
        item {
            ActionArea(
                ui, cam,
                onStart = ::startRide,
                onStop = { LoggerService.stop(context) },
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )
        }
        // While a ride is recording the status rows already say everything about the camera; the controls only return afterwards.
        if (!ui.recording) {
            item {
                CameraPanel(
                    cam,
                    onConnect = { withBluetooth { CameraController.connect(context) } },
                    onChoose = { withBluetooth { CameraController.choose(context) } },
                    onToggle = CameraController::toggle,
                    onConnectSelected = { withBluetooth { CameraController.connectSelected(context) } },
                    onClosePicker = CameraController::closePicker,
                    onDisconnect = { CameraController.disconnect() },
                )
            }
        }
        item { SectionLabel("Rides", Modifier.padding(top = Space.s)) }
        // The ride being recorded is the newest folder; it has no result yet, so it joins the list when it stops.
        val list = rides?.let { if (ui.recording) it.drop(1) else it }
        when {
            list == null -> item { Notice("Loading rides") }
            list.isEmpty() -> item { EmptyRides() }
            else -> {
                // Old rides with no GPS and no .fit are test runs: fold them away, but always show the newest ride,
                // because its message explains why the last ride gave no file.
                val (shown, folded) = list.withIndex().partition { (i, r) -> i == 0 || r.hasFit || r.fixes > 0 }
                val cards = if (showFolded) list else shown.map { it.value }
                items(cards, key = { it.dir.name }) { ride ->
                    RideCard(
                        ride,
                        emphasise = ride === list.first(),
                        unit = unit,
                        onOpenRoute = {
                            scope.launch {
                                val gpx = java.io.File(RideFiles.exports(context), "route.gpx")
                                try {
                                    if (withContext(Dispatchers.IO) { RideFolder.writeGpx(ride.dir, gpx) }) openRoute(context, gpx)
                                } catch (e: IOException) {
                                    fileFailed()
                                }
                            }
                        },
                        onShareFit = { share(context, ride.fits) },
                        onShareAll = { share(context, ride.files) },
                        onRebuild = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { RideFolder.rebuildFit(ride.dir) }
                                } catch (e: IOException) {
                                    fileFailed()
                                }
                                reload()
                            }
                        },
                        onDelete = { confirmDelete = ride },
                    )
                }
                if (!ui.recording) {
                    item {
                        NanoButton(
                            "Export all rides (${list.size})",
                            {
                                scope.launch {
                                    val zip = java.io.File(RideFiles.exports(context), "nano-dash-rides.zip")
                                    val ok = try {
                                        withContext(Dispatchers.IO) {
                                            try {
                                                RideFolder.zipAll(RideFiles.root(context), zip)
                                            } catch (e: IOException) {
                                                zip.delete()                  // never leave a half-written zip to be shared
                                                throw e
                                            }
                                        }
                                        true
                                    } catch (e: IOException) {
                                        fileFailed()
                                        false
                                    }
                                    if (ok) share(context, listOf(zip))
                                    reload()
                                }
                            },
                            Modifier.fillMaxWidth(),
                            ButtonKind.Secondary,
                            height = 48.dp,
                        )
                    }
                }
                if (folded.isNotEmpty()) {
                    item {
                        NanoButton(
                            if (showFolded) "Hide rides without GPS" else "Show ${folded.size} ${if (folded.size == 1) "ride" else "rides"} without GPS",
                            { showFolded = !showFolded },
                            Modifier.fillMaxWidth(),
                            ButtonKind.Quiet,
                            height = 48.dp,
                        )
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(top = Space.s), horizontalAlignment = Alignment.CenterHorizontally) {
                // The build number is the last part of the release tag (v0.1.0-24), so it can be compared with the releases page.
                Text("Nano Dash ${BuildConfigInfo.versionName(context)}, build ${BuildConfigInfo.build(context)}", style = NanoText.label, color = c.textMuted)
                // The link is underlined as well as coloured, so it does not rely on colour alone.
                val link = SpanStyle(color = c.accentText, textDecoration = TextDecoration.Underline)
                Text(
                    buildAnnotatedString {
                        append("Built by ")
                        withLink(LinkAnnotation.Url("https://github.com/levi2m", TextLinkStyles(link))) { append("levi2m") }
                        append(" and his AI agents")
                    },
                    style = NanoText.label, color = c.textMuted, textAlign = TextAlign.Center,
                )
            }
        }
    }

    if (settings) {
        AlertDialog(
            onDismissRequest = { settings = false },
            containerColor = c.card,
            title = { Text("Settings", style = NanoText.heading, color = c.text, modifier = Modifier.semantics { heading() }) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SectionLabel("Units")
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Speed", style = NanoText.body, color = c.text)
                        Row(
                            Modifier.clip(RoundedCornerShape(Radius.control)).background(c.bg).selectableGroup()
                                .semantics { contentDescription = "Speed unit" },
                        ) {
                            SpeedUnit.entries.forEach { option ->
                                val selected = option == unit
                                Box(
                                    Modifier.heightIn(min = 48.dp)
                                        .selectable(selected = selected, role = Role.RadioButton, onClick = { unit = option; prefs.edit().putString("speed_unit", option.name).apply() })
                                        .background(if (selected) c.fill else c.bg).padding(horizontal = Space.m),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    // Bold as well as filled, so the choice does not rest on colour alone.
                                    Text(
                                        option.speedLabel,
                                        style = NanoText.label.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
                                        color = if (selected) c.onFill else c.textSecondary,
                                    )
                                }
                            }
                        }
                    }
                    SectionLabel("Watch remote", Modifier.padding(top = Space.m))
                    // Off by default: a quiet notification with a Start button, so a watch can start a ride.
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(quickStart, role = Role.Switch) { toggleQuickStart() },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Quick start from the watch", style = NanoText.body, color = c.text)
                            Text("Adds one quiet notification", style = NanoText.label, color = c.textMuted)
                        }
                        Switch(quickStart, null, colors = SwitchDefaults.colors(checkedTrackColor = c.fill, checkedThumbColor = c.onFill))
                    }
                    if (quickStart) {
                        SettingRow("Watch", watchName ?: "None chosen", "Choose the watch") { chooseWatch = true }
                        SettingRow("Watch app", if (LoggerService.watchSeen(context) > 0) "Connected before" else "Not seen yet", "Show how to install the watch app") { watchGuide = true }
                    }
                    // An F-Droid install is updated by F-Droid, so the GitHub link is hidden there.
                    if (!BuildConfigInfo.fromFDroid(context)) {
                        SectionLabel("About", Modifier.padding(top = Space.m))
                        SettingRow("Check for updates", "Opens your browser", "Open the releases page in your browser") {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/levi2m/nano-dash/releases/latest".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { settings = false }) { Text("Done", style = NanoText.button, color = c.text) } },
        )
    }

    if (watchGuide) {
        AlertDialog(
            onDismissRequest = { watchGuide = false },
            containerColor = c.card,
            title = { Text("Put the watch app on your watch", style = NanoText.heading, color = c.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text("Wear OS only installs apps from a computer or the Play Store, so this phone cannot send it. Once, with a computer:", style = NanoText.body, color = c.textSecondary)
                    Text("1. Download the watch APK (nano-dash-wear) from the releases page.", style = NanoText.body, color = c.textSecondary)
                    Text("2. On the watch: Settings, About watch, Software, tap Software version 7 times; then Developer options, turn on Debug over Wi-Fi.", style = NanoText.body, color = c.textSecondary)
                    Text("3. On the computer: adb connect <watch address>, then adb install nano-dash-wear-<version>.apk", style = NanoText.body, color = c.textSecondary)
                    Text("Then open Nano Dash on the watch. This row changes to connected.", style = NanoText.body, color = c.textSecondary)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/levi2m/nano-dash/releases/latest".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Open releases page", style = NanoText.button, color = c.text) }
            },
            dismissButton = { TextButton(onClick = { watchGuide = false }) { Text("Close", style = NanoText.button, color = c.text) } },
        )
    }

    if (chooseWatch) {
        val devices = remember { pairedDevices(context) }
        AlertDialog(
            onDismissRequest = { chooseWatch = false },
            containerColor = c.card,
            title = { Text("Which is your watch?", style = NanoText.heading, color = c.text) },
            text = {
                if (devices.isEmpty()) {
                    Text("No paired devices were found. Pair your watch with this phone first.", style = NanoText.body, color = c.textSecondary)
                } else {
                    Column {
                        Text("Only this device can start and stop rides.", style = NanoText.body, color = c.textSecondary)
                        devices.forEach { (name, address) ->
                            TextButton(
                                onClick = { LoggerService.setWatch(context, address, name); watchName = name; chooseWatch = false },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text(name, style = NanoText.button, color = c.text) }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { chooseWatch = false }) { Text("Cancel", style = NanoText.button, color = c.text) } },
        )
    }

    confirmDelete?.let { ride ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = c.card,
            title = { Text("Delete this ride?", style = NanoText.heading, color = c.text) },
            text = { Text("Its GPS, motion and camera timing files are removed from this phone. Copies you already shared are not affected.", style = NanoText.body, color = c.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch { withContext(Dispatchers.IO) { RideFolder.delete(ride.dir); RideFiles.clearExports(context) }; reload() }
                }) { Text("Delete", style = NanoText.button, color = c.bad) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep", style = NanoText.button, color = c.text) } },
        )
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = Space.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        RouteMark(Modifier.size(40.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text("Nano Dash", Modifier.semantics { heading() }, style = NanoText.title, color = nanoColors.text)
            Text("Ride logger for the Osmo Nano", style = NanoText.label, color = nanoColors.textMuted)
        }
        TextButton(onSettings, Modifier.heightIn(min = 48.dp)) { Text("Settings", style = NanoText.button, color = nanoColors.text) }
    }
}

/** A tappable settings row: the name on the left, its current value on the right. */
@Composable
private fun SettingRow(title: String, value: String, actionLabel: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = actionLabel, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = NanoText.body, color = nanoColors.text)
        Text(value, style = NanoText.label, color = nanoColors.textSecondary)
    }
}

@Composable
private fun RidePanel(ui: RideUiState, cam: CameraUi, now: Long, gps: GpsReadiness, unit: SpeedUnit) {
    val c = nanoColors
    Panel {
        if (ui.recording) {
            // The speed is the one bright figure. Without a fix there is nothing honest to show at that size, so say so quietly.
            // The height is a minimum so the figure can grow with the phone's font size instead of being cut off.
            Box(Modifier.fillMaxWidth().heightIn(min = 96.dp), contentAlignment = Alignment.CenterStart) {
                val mps = ui.lastSpeedMps
                if (mps != null) {
                    val speedText = "%.0f".format(mps * unit.speedPerMps)
                    val speakSpeed = Modifier.semantics(mergeDescendants = true) { contentDescription = "Speed $speedText ${unit.speedLabel}" }
                    if (LocalDensity.current.fontScale > 1.3f) {
                        // At large font sizes the unit goes under the figure so the 88 sp number never runs off the card.
                        Column(speakSpeed) {
                            Text(speedText, style = NanoText.hero, color = c.accentText)
                            Text(unit.speedLabel, style = NanoText.heading, color = c.textSecondary)
                        }
                    } else {
                        Row(speakSpeed) {
                            Text(speedText, Modifier.alignByBaseline(), style = NanoText.hero, color = c.accentText)
                            Text(unit.speedLabel, Modifier.padding(start = Space.s).alignByBaseline(), style = NanoText.heading, color = c.textSecondary)
                        }
                    }
                } else {
                    Text("Waiting for GPS", style = NanoText.title, color = c.textSecondary)
                }
            }
            val s = ((now - ui.startedUtcMillis) / 1000).coerceAtLeast(0)
            Text(
                "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60),
                Modifier.semantics { contentDescription = "Riding for ${WatchFormat.spoken(s)}" },
                style = NanoText.figure, color = c.text,
            )
        } else {
            Text("Ready", Modifier.semantics { heading() }, style = NanoText.title, color = c.text)
            Notice("Start a ride to log GPS and motion. The camera starts with it when it is connected.")
        }
        Hairline()
        Column {
        val (gpsValue, gpsHealth) = when {
            !ui.recording -> when (gps) {
                GpsReadiness.Ready -> "Location is on" to Health.Ok
                GpsReadiness.LocationOff -> "Location is off, switch it on" to Health.Wait
                GpsReadiness.NeedsPermission -> "Asked for when you start" to Health.Off
            }
            ui.fixes == 0 -> (if (ui.satellitesVisible != null) "Searching, ${ui.satellitesVisible} in view" else "Searching for satellites") to Health.Wait
            else -> {
                val acc = ui.lastAccuracyM
                // Weak is said in words, so the amber light is not the only difference between a good fix and a poor one.
                if (acc != null && acc <= 15f) "%.0f m, ${ui.satellitesUsed ?: 0} satellites".format(acc) to Health.Ok
                else (acc?.let { "%.0f m, weak".format(it) } ?: "No accuracy, weak") to Health.Wait
            }
        }
        StatusRow("GPS", gpsValue, gpsHealth)
        val many = cam.rows.size > 1
        val (camValue, camHealth) = when {
            cam.recording == true -> ((if (many) "${cam.rows.count { it.recording == true }} of ${cam.rows.size} recording" else "Recording") +
                (if (cam.error) ", with a problem" else "")) to
                (if (cam.error) Health.Wait else Health.Ok)
            cam.error -> "Problem" to Health.Bad
            cam.busy -> "Connecting" to Health.Wait
            cam.ready -> (if (many) "${cam.rows.size} ready" else "Ready") to Health.Ok
            else -> "Not connected" to Health.Off
        }
        StatusRow(if (many) "Cameras" else "Camera", camValue, camHealth)
        val (motionValue, motionHealth) = when {
            !ui.recording -> "Starts with the ride" to Health.Off
            ui.imuSamples == 0L -> "Waiting for the first sample" to Health.Wait
            else -> "Logging, ${"%,d".format(ui.imuSamples)} samples" to Health.Ok
        }
        StatusRow("Motion sensors", motionValue, motionHealth)
        }
    }
}

@Composable
private fun ActionArea(ui: RideUiState, cam: CameraUi, onStart: () -> Unit, onStop: () -> Unit, onOpenSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        if (ui.recording) {
            NanoButton("Stop ride", onStop, Modifier.fillMaxWidth(), ButtonKind.Danger, height = 72.dp)
        } else {
            NanoButton("Start ride", onStart, Modifier.fillMaxWidth(), ButtonKind.Primary, height = 72.dp, enabled = !ui.saving)
        }
        // The camera panel below says when no camera is ready, so only the result of the last action is repeated here.
        ui.cameraNote?.let { Notice(it.text, health = if (it.problem) Health.Wait else Health.Off) }
        ui.message?.let { Notice(it.text, health = if (it.problem) Health.Wait else Health.Off) }
        if (ui.message?.needsSettings == true) {
            NanoButton("Open app settings", onOpenSettings, Modifier.fillMaxWidth(), ButtonKind.Secondary)
        }
    }
}

/** Whether the phone can give a GPS fix right now, so the rider hears about a switched-off location before the ride, not after. */
private enum class GpsReadiness { Ready, LocationOff, NeedsPermission }

private fun gpsReadiness(context: Context): GpsReadiness = when {
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED -> GpsReadiness.NeedsPermission
    !LocationManagerCompat.isLocationEnabled(context.getSystemService(Context.LOCATION_SERVICE) as LocationManager) -> GpsReadiness.LocationOff
    else -> GpsReadiness.Ready
}

@Composable
private fun CameraPanel(
    cam: CameraUi,
    onConnect: () -> Unit,
    onChoose: () -> Unit,
    onToggle: (String) -> Unit,
    onConnectSelected: () -> Unit,
    onClosePicker: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val c = nanoColors
    // Once every camera is ready with its clock set there is nothing to decide, so the panel shrinks to one line and Rides move up.
    var open by remember { mutableStateOf(false) }
    val settled = !cam.choosing && !cam.error && !cam.busy && cam.ready && cam.rows.isNotEmpty() && cam.rows.all { it.clockSet }
    LaunchedEffect(settled) { if (!settled) open = false }          // after any reconnect the panel folds up again once it is ready
    if (settled && !open) {
        Panel(bottomPadding = Space.l) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    SectionLabel(if (cam.rows.size > 1) "Cameras" else "Camera")
                    Text(
                        if (cam.rows.size > 1) "All ready, clocks set to this phone" else "Ready, clock set to this phone",
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = NanoText.body, color = c.textSecondary,
                    )
                }
                NanoButton("Manage", { open = true }, kind = ButtonKind.Quiet)
            }
        }
        return
    }
    Panel {
        SectionLabel(if (cam.rows.size > 1 || cam.choosing) "Cameras" else "Camera")
        if (cam.choosing) {
            CameraPicker(cam, onToggle, onConnectSelected, onChoose, onClosePicker)
            return@Panel
        }
        val approve = cam.text.startsWith("Approve")
        val shown = when {
            cam.ready -> if (cam.rows.size > 1) "All ready to start and stop with the ride" else "Ready to start and stop with the ride"
            cam.text == "Camera not connected" -> "No camera is connected, so a ride now would have no video. Turn the camera on, make sure wireless connection is enabled on it, close Mimo on the phone, then connect."
            else -> caption(cam.text)
        }
        Text(
            shown,
            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            style = if (approve) NanoText.heading else NanoText.body,
            color = when {
                approve -> c.accentText
                cam.error -> c.bad
                else -> c.textSecondary
            },
        )
        if (cam.rows.size > 1) {
            Column {
                cam.rows.forEach { row ->
                    StatusRow(row.label, row.text, when {
                        row.error -> Health.Bad
                        row.busy -> Health.Wait
                        row.ready -> Health.Ok
                        else -> Health.Off
                    })
                }
            }
        }
        // The app sets each camera's clock to this phone's on connect, so a phone with the wrong time gives the camera the wrong time too.
        val context = LocalContext.current
        if (Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 1) == 0) {
            Notice("Automatic date and time is off. The camera copies this phone's clock, so a wrong clock makes the .fit miss the video. Turn it on in the phone's settings.", health = Health.Wait)
        }
        if (cam.ready) {
            if (cam.rows.all { it.clockSet }) Notice("Camera clock set to this phone's time", health = Health.Off)
            else Notice("The camera did not take the phone's clock, so its time may be off and the overlay may be a second or two out", health = Health.Wait)
        }
        if (cam.busy) BusyBar(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            NanoButton(
                if (cam.rows.isNotEmpty()) "Reconnect" else "Connect camera", onConnect, Modifier.weight(1f), ButtonKind.Secondary,
                enabled = !cam.busy,
            )
            if (cam.rows.isNotEmpty()) NanoButton("Disconnect", onDisconnect, kind = ButtonKind.Quiet)
        }
        NanoButton("Choose cameras", onChoose, Modifier.fillMaxWidth(), ButtonKind.Quiet, enabled = !cam.busy)
        if (settled) NanoButton("Done", { open = false }, Modifier.fillMaxWidth(), ButtonKind.Quiet)
    }
}

/** Lists the Nanos found nearby so one or several can be ticked; each Nano is told apart by the end of its address. */
@Composable
private fun CameraPicker(cam: CameraUi, onToggle: (String) -> Unit, onConnectSelected: () -> Unit, onSearchAgain: () -> Unit, onCancel: () -> Unit) {
    val c = nanoColors
    when {
        cam.scanning -> {
            Text("Looking for cameras", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = NanoText.body, color = c.textSecondary)
            BusyBar(Modifier.fillMaxWidth())
        }
        cam.found.isEmpty() -> {
            Text(
                if (cam.error) caption(cam.text) else "No Osmo Nano found. Is it on, with wireless connection enabled, and Mimo closed?",
                style = NanoText.body, color = if (cam.error) c.bad else c.textSecondary,
            )
            NanoButton("Search again", onSearchAgain, Modifier.fillMaxWidth(), ButtonKind.Secondary)
        }
        else -> {
            Text("Tick every camera that should record with the ride.", style = NanoText.body, color = c.textSecondary)
            Column {
                cam.found.forEach { address ->
                    val checked = address in cam.selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle(address) }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        Checkbox(checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = c.fill, checkmarkColor = c.onFill, uncheckedColor = c.textMuted))
                        Text(cameraLabel(address), style = NanoText.body, color = c.text)
                    }
                }
            }
            val n = cam.selected.size
            NanoButton(
                if (n == 1) "Connect 1 camera" else "Connect $n cameras", onConnectSelected, Modifier.fillMaxWidth(), ButtonKind.Primary,
                enabled = n > 0,
            )
            NanoButton("Search again", onSearchAgain, Modifier.fillMaxWidth(), ButtonKind.Secondary)
        }
    }
    NanoButton("Cancel", onCancel, Modifier.fillMaxWidth(), ButtonKind.Quiet)
}

@Composable
private fun EmptyRides() {
    Panel {
        Text("No rides yet", Modifier.semantics { heading() }, style = NanoText.heading, color = nanoColors.text)
        Notice("1. Connect the camera if you have it with you.\n2. Start the ride before you move off.\n3. Stop it when you arrive, then share the .fit file and import it in Mimo together with the clip.")
    }
}

@Composable
private fun RideCard(ride: RideSummary, emphasise: Boolean, unit: SpeedUnit, onOpenRoute: () -> Unit, onShareFit: () -> Unit, onShareAll: () -> Unit, onRebuild: () -> Unit, onDelete: () -> Unit) {
    val c = nanoColors
    val locale = LocalConfiguration.current.locales[0]
    // The quiet Delete button is 48 dp tall with its label centred, so the card needs less bottom padding than the others.
    Panel(bottomPadding = Space.s) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(SimpleDateFormat("EEE d MMM, HH:mm", locale).format(Date(ride.startUtcMillis)), Modifier.semantics { heading() }, style = NanoText.heading, color = c.text)
                Text("${ride.sizeKb} KB", style = NanoText.label, color = c.textMuted)
            }
            Text(ride.durationSeconds?.let(::clock) ?: "–", style = NanoText.bodyNumbers, color = c.textSecondary)
        }
        // The route is the product's signature; a ride without a track shows no empty box, only the reason.
        if (ride.route.size >= 2) {
            RouteLine(
                ride.route,
                Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(Radius.small)).background(c.raised)
                    .clickable(onClickLabel = "Open the route in a maps app", role = Role.Button, onClick = onOpenRoute)
                    .semantics { contentDescription = "Route of the ride, drawn from ${ride.fixes} GPS fixes" },
            )
            Text("Tap the route to open it in a maps app", style = NanoText.label, color = c.textMuted)
        }
        Column {
            Fact("GPS fixes", ride.fixes.toString())
            ride.distanceM?.takeIf { it > 0 }?.let { Fact("Distance", "%.1f %s".format(it / unit.metersPerDistanceUnit, unit.distanceLabel)) }
            ride.topSpeedMps?.takeIf { it > 0 }?.let { Fact("Top speed", "%.0f %s".format(it * unit.speedPerMps, unit.speedLabel)) }
            // GPS is logged once a second, so a long wait means a tunnel or the phone pausing the app; 10 s keeps ordinary hiccups out.
            ride.longestGapSeconds?.takeIf { it >= 10 }?.let { Fact("Longest GPS gap", clock(it)) }
            ride.cameraClipSeconds.forEachIndexed { i, s ->
                Fact(if (ride.cameraClipSeconds.size == 1) "Camera clip" else "Camera ${i + 1} clip", "%.1f s".format(s))
            }
        }

        // Amber is spent on the newest ride only, so a list of old no-fix rides does not shout.
        val tone = if (emphasise) Health.Wait else Health.Off
        when {
            ride.hasFit -> Unit
            ride.fixes == 0 -> Notice("No GPS fix, so there is no .fit to import", health = tone)
            else -> Notice("Interrupted before the .fit was written. It can be rebuilt from the GPS log.", health = tone)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            when {
                ride.hasFit -> {
                    NanoButton(if (ride.fits.size > 1) "Share .fit files" else "Share .fit", onShareFit, Modifier.weight(1f), ButtonKind.Primary, height = 48.dp)
                    NanoButton("All files", onShareAll, Modifier.weight(1f), ButtonKind.Secondary, height = 48.dp)
                }
                ride.fixes > 0 -> {
                    NanoButton("Rebuild .fit", onRebuild, Modifier.weight(1f), ButtonKind.Primary, height = 48.dp)
                    NanoButton("All files", onShareAll, Modifier.weight(1f), ButtonKind.Secondary, height = 48.dp)
                }
                else -> NanoButton("Share raw files", onShareAll, Modifier.weight(1f), ButtonKind.Secondary, height = 48.dp)
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            NanoButton("Delete ride", onDelete, kind = ButtonKind.QuietDanger, height = 48.dp)
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = NanoText.body, color = nanoColors.textSecondary)
        Text(value, Modifier.weight(1f), style = NanoText.bodyNumbers, color = nanoColors.text, textAlign = TextAlign.End)
    }
}

/** "17 min 17 s" reads as a length, where "17:17" could be mistaken for a time of day. */
private fun clock(totalSeconds: Long): String = when {
    totalSeconds >= 3600 -> "%d h %02d min".format(totalSeconds / 3600, (totalSeconds / 60) % 60)
    totalSeconds >= 60 -> "%d min %02d s".format(totalSeconds / 60, totalSeconds % 60)
    else -> "$totalSeconds s"
}

/** Hands the GPX to whichever installed app opens routes (OsmAnd, Organic Maps, ...), so the app itself needs no network. */
private fun openRoute(context: Context, gpx: java.io.File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", gpx)
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/gpx+xml")
        .apply { clipData = ClipData.newRawUri("", uri) }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(view, "Open route in").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun share(context: Context, files: List<java.io.File>) {
    if (files.isEmpty()) return
    val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) })
    val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share ride").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** The devices already paired with this phone, by name, for choosing which one is the watch. */
@SuppressLint("MissingPermission")
private fun pairedDevices(context: Context): List<Pair<String, String>> = runCatching {
    context.getSystemService(BluetoothManager::class.java).adapter.bondedDevices
        .map { (it.name ?: it.address) to it.address }.sortedBy { it.first.lowercase() }
}.getOrDefault(emptyList())
