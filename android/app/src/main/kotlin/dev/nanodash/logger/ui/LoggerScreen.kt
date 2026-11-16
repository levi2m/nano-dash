package dev.nanodash.logger.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nanodash.core.ride.HealthImport
import dev.nanodash.core.ride.RideFolder
import dev.nanodash.core.ride.RideSummary
import dev.nanodash.core.ride.SpeedUnit
import dev.nanodash.logger.AppPrefs
import dev.nanodash.logger.BuildConfigInfo
import dev.nanodash.logger.CameraController
import dev.nanodash.logger.HealthConnectReader
import dev.nanodash.logger.LoggerService
import dev.nanodash.logger.Note
import dev.nanodash.logger.RideFiles
import dev.nanodash.logger.RideStore
import dev.nanodash.logger.ui.components.ButtonKind
import dev.nanodash.logger.ui.components.Health
import dev.nanodash.logger.ui.components.NanoButton
import dev.nanodash.logger.ui.components.Notice
import dev.nanodash.logger.ui.components.RouteMark
import dev.nanodash.logger.ui.components.SectionLabel
import dev.nanodash.logger.ui.theme.NanoText
import dev.nanodash.logger.ui.theme.Space
import dev.nanodash.logger.ui.theme.nanoColors
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val region = LocalConfiguration.current.locales[0].country
    var unit by remember { mutableStateOf(AppPrefs.speedUnit(context) ?: SpeedUnit.forCountry(region)) }
    // Location can be switched off, or permission revoked, while the app is in the background, so look again every time it returns.
    var gps by remember { mutableStateOf(GpsReadiness.Ready) }
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        gps = gpsReadiness(context)
        resumes++
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
    LaunchedEffect(ui.recording, ui.saving) { reload() }

    val healthAvailable = remember { HealthConnectReader.available(context) }
    // What happened to the health data of one ride, shown on that ride's card (a message at the top of the screen is out of sight when the rider taps a button further down).
    var healthNote by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun note(ride: RideSummary, text: String) { healthNote = ride.dir.name to text }
    suspend fun importHeartRate(ride: RideSummary, manual: Boolean = true): Boolean = HeartRateImport.run(context, ride, manual) { note(ride, it) }
    // The watch may sync late, so a ride that found nothing is tried again on the next return to the app and after the next ride, until it is older than HealthImport.DAYS.
    val ridesLoaded = rides != null
    LaunchedEffect(resumes, ui.recording, ui.saving, ridesLoaded) {
        val list = rides ?: return@LaunchedEffect
        if (!healthAvailable || ui.recording || ui.saving || !HealthConnectReader.granted(context)) return@LaunchedEffect
        val due = HealthImport.due(list, System.currentTimeMillis())
        var added = false
        for (ride in due) if (importHeartRate(ride, manual = false)) added = true
        if (added) reload()
    }
    var healthRide by remember { mutableStateOf<RideSummary?>(null) }
    val healthPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val ride = healthRide
        healthRide = null
        if (ride == null) return@rememberLauncherForActivityResult
        if (ok) scope.launch { importHeartRate(ride); reload() }
        else {
            // Android's own request can be skipped without a word (for example after two refusals), so go to the app's page in Health Connect, where it can always be allowed.
            note(ride, "Allow Nano Dash to read heart rate in Health Connect, then come back and tap again.")
            HealthConnectReader.openSettings(context)
        }
    }
    fun addHeartRate(ride: RideSummary) {
        note(ride, "Looking in Health Connect…")
        if (HealthConnectReader.granted(context)) scope.launch { importHeartRate(ride); reload() }
        else { healthRide = ride; healthPermission.launch(HealthConnectReader.PERMISSION) }
    }
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
    var quickStart by remember { mutableStateOf(AppPrefs.quickStart(context)) }
    var watchName by remember { mutableStateOf(AppPrefs.watchName(context)) }
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
    val quickStartLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
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
        else RideStore.update { it.copy(message = Note("Precise location permission is needed to log a track.", needsSettings = true)) }
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
                ui,
                onStart = ::startRide,
                onStop = { LoggerService.stop(context) },
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )
        }
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
        item { SectionLabel("Tracks", Modifier.padding(top = Space.s)) }
        // The ride being recorded is the newest folder; it has no result yet, so it joins the list when it stops.
        val list = rides?.let { if (ui.recording) it.drop(1) else it }
        when {
            list == null -> item { Notice("Loading tracks") }
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
                        onAddHeartRate = if (healthAvailable && ride.hasFit && !ui.recording) ({ addHeartRate(ride) }) else null,
                        healthNote = healthNote?.takeIf { it.first == ride.dir.name }?.second,
                    )
                }
                if (!ui.recording) {
                    item {
                        NanoButton(
                            "Export all tracks (${list.size})",
                            {
                                scope.launch {
                                    val zip = java.io.File(RideFiles.exports(context), "nano-dash-tracks.zip")
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
                            if (showFolded) "Hide tracks without GPS" else "Show ${folded.size} ${if (folded.size == 1) "track" else "tracks"} without GPS",
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
        SettingsDialog(
            unit = unit,
            onUnit = { unit = it; AppPrefs.setSpeedUnit(context, it) },
            quickStart = quickStart,
            onToggleQuickStart = ::toggleQuickStart,
            watchName = watchName,
            watchSeen = AppPrefs.watchSeen(context) > 0,
            onChooseWatch = { chooseWatch = true },
            onWatchGuide = { watchGuide = true },
            onDismiss = { settings = false },
        )
    }
    if (watchGuide) WatchGuideDialog { watchGuide = false }
    if (chooseWatch) {
        ChooseWatchDialog(
            onPick = { name, address -> LoggerService.setWatch(context, address, name); watchName = name; chooseWatch = false },
            onDismiss = { chooseWatch = false },
        )
    }
    confirmDelete?.let { ride ->
        DeleteTrackDialog(
            onConfirm = {
                confirmDelete = null
                scope.launch { withContext(Dispatchers.IO) { RideFolder.delete(ride.dir); RideFiles.clearExports(context) }; reload() }
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = Space.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        RouteMark(Modifier.size(40.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text("Nano Dash", Modifier.semantics { heading() }, style = NanoText.title, color = nanoColors.text)
            Text("Track logger for the Osmo Nano", style = NanoText.label, color = nanoColors.textMuted)
        }
        TextButton(onSettings, Modifier.heightIn(min = 48.dp)) { Text("Settings", style = NanoText.button, color = nanoColors.text) }
    }
}
