package dev.nanodash.logger

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.nanodash.core.camera.CameraLink
import dev.nanodash.core.camera.CameraNotes
import dev.nanodash.core.camera.CameraTiming
import dev.nanodash.core.camera.Stamp
import dev.nanodash.core.camera.StartResult
import dev.nanodash.core.camera.StopResult
import dev.nanodash.core.camera.cameraLabel
import dev.nanodash.core.fit.CameraSpan
import dev.nanodash.core.service.ServiceRules
import dev.nanodash.core.service.ServiceState
import dev.nanodash.core.service.WatchStartDecision
import dev.nanodash.core.watch.GpsState
import dev.nanodash.core.watch.RideState
import dev.nanodash.core.watch.WatchCamera
import dev.nanodash.core.watch.WatchCommand
import dev.nanodash.core.watch.WatchProtocol
import dev.nanodash.core.watch.WatchState
import dev.nanodash.core.ride.ClockSkew
import dev.nanodash.core.ride.GnssCsv
import dev.nanodash.core.ride.GnssFix
import dev.nanodash.core.ride.LogKey
import dev.nanodash.core.ride.RideFolder
import dev.nanodash.core.ride.RideLog
import dev.nanodash.core.ride.RideRecovery
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Foreground service that logs one ride: GNSS fixes, phone motion sensors and pressure, all stamped on the monotonic
 * clock (elapsedRealtimeNanos) so they line up, and at the end writes `ride.fit`. If the camera is connected it also
 * starts and stops the camera's recording with the ride and marks the clip's time window in the .fit.
 *
 * Files go to `Android/data/<application id>/files/rides/ride-<UTC time>/` (removed with the app, so share a ride to keep it):
 *  - gnss.csv     one line per fix
 *  - imu.csv      one line per sensor event: elapsed_ns, sensor type, values
 *  - events.jsonl camera commands with their timestamps on both clocks
 *  - meta.json    clock anchors (monotonic <-> UTC), device, counts, camera result
 *  - ride.fit     the activity file, written when the ride stops (only if GNSS fixes were received)
 */
class LoggerService : Service(), SensorEventListener, LocationListener {
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var thread: HandlerThread? = null
    @Volatile private var session: Session? = null
    private var wakeLock: PowerManager.WakeLock? = null
    /** Quick start: the service stays up between rides, showing the Start notification and listening for the watch. */
    @Volatile private var armed = false
    private var watchLink: WatchLink? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var writeFailed = false
    /** True from Stop until the files are written and the service is switched off. */
    @Volatile private var saving = false
    /** A Stop from the watch was accepted and is queued on the main thread; a Start now would be undone by it (see [ServiceRules.watchStart]). */
    @Volatile private var stopPending = false
    /** A Start from the watch was accepted and is queued; a Stop that arrives now is queued behind it instead of being lost. */
    @Volatile private var startPending = false

    private fun serviceState() = ServiceState(armed = armed, riding = session != null, saving = saving)

    private class Session(val dir: File, val startUtc: Long, val startElapsedNs: Long) {
        val gnss = BufferedWriter(FileWriter(File(dir, RideLog.GNSS)), 1 shl 16)
        val imu = BufferedWriter(FileWriter(File(dir, RideLog.IMU)), 1 shl 17)
        val events = BufferedWriter(FileWriter(File(dir, RideLog.EVENTS)))
        val fixes = java.util.concurrent.CopyOnWriteArrayList<GnssFix>()   // the finisher thread reads it while the logger thread may still add a last fix
        val imuCounts = HashMap<Int, Long>()
        var imuTotal = 0L
        val line = StringBuilder(96)
        /** What each camera answered to the start command, by address. Empty if no camera was ready. */
        @Volatile var cameras: Map<String, StartResult> = emptyMap()
        var cameraJob: Job? = null
        /** Phone UTC clock minus GPS time over this ride, in ms; null when it cannot be told. */
        @Volatile var skewMs: Long? = null

        init {
            gnss.write(GnssCsv.HEADER + "\n")
            imu.write("elapsed_ns,sensor_type,v0,v1,v2,v3\n")
        }

        fun event(json: JSONObject) = synchronized(events) { events.write(json.toString()); events.write("\n"); events.flush() }

        fun close() {
            gnss.flush(); gnss.close()
            imu.flush(); imu.close()
            synchronized(events) { events.flush(); events.close() }
        }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            RideStore.update { it.copy(satellitesUsed = used, satellitesVisible = status.satelliteCount) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRide()
            ACTION_START -> startRide()
            ACTION_ARM -> arm()
            ACTION_DISARM -> disarm()
            else -> if (ServiceRules.switchOffOnStrayIntent(serviceState())) stopSelf()
        }
        return START_NOT_STICKY
    }

    /** The foreground types this service may use now. Connected-device needs the Bluetooth connect permission. */
    @SuppressLint("InlinedApi")
    private fun foregroundTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        return types
    }


    /** Started while the app is on screen, so Android lets it keep the location type. A Start from the watch then needs no new foreground start. */
    private fun arm() {
        armed = true
        ensureChannel()
        if (serviceState().idle) showArmed()
        startWatchLink()   // also the retry point: the app calls arm on every launch, so a link that died for a missing permission is replaced
    }

    private fun disarm() {
        armed = false
        stopWatchLink()
        if (serviceState().idle) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun showArmed() {
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, readyNotification(), foregroundTypes())
        } catch (e: SecurityException) {   // the location or notification permission was withdrawn: nothing to arm
            armed = false
            stopWatchLink()
            stopSelf()
        }
    }

    /** Between rides: back to the Start notification if armed, otherwise the service switches itself off. */
    private fun backToIdle() {
        if (armed) showArmed() else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startWatchLink() {
        watchLink?.let { if (it.isRunning) return; it.stop() }   // a link that ended by itself (no Bluetooth permission) is replaced
        watchLink = WatchLink(this, { AppPrefs.watchAddress(this) }, ::onWatchCommand, onWatchSeen = { AppPrefs.markWatchSeen(this) }).also { it.start() }
    }

    private fun stopWatchLink() {
        watchLink?.stop()
        watchLink = null
    }

    /** Runs on the watch link's thread: answers at once, and does the real work on the main thread. */
    private fun onWatchCommand(command: WatchCommand): String = when (command) {
        WatchCommand.Status -> WatchProtocol.encode(watchState())
        WatchCommand.Start -> watchStart()
        WatchCommand.Stop -> {
            if (session != null || startPending) {
                stopPending = true
                main.post { stopRide() }       // queued behind a Start that is still waiting, so that Start is not left running
            }
            WatchProtocol.OK
        }
    }

    // The watch Start is deliberately allowed while the phone is locked (the rider's phone is in a pocket); the notification's Start is not.
    private fun watchStart(): String {
        val decision = ServiceRules.watchStart(
            serviceState(),
            stopPending,
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER),
        )
        return when (decision) {
            WatchStartDecision.AlreadyRiding -> WatchProtocol.OK
            is WatchStartDecision.Refuse -> WatchProtocol.err(decision.reason)
            WatchStartDecision.Go -> {
                startPending = true
                main.post { startRide() }                               // a second Start before this runs is ignored by startRide's own guard
                WatchProtocol.OK
            }
        }
    }

    private fun watchState(): WatchState {
        val s = session
        val ui = RideStore.state.value
        val cam = CameraController.ui.value
        val ride = when {
            saving -> RideState.Saving
            s != null -> RideState.Recording
            else -> RideState.Idle
        }
        return WatchState(
            ride = ride,
            elapsedSeconds = if (s != null) ((System.currentTimeMillis() - s.startUtc) / 1000).coerceAtLeast(0) else 0,
            gps = when { ride != RideState.Recording -> GpsState.Off; ui.fixes == 0 -> GpsState.Searching; else -> GpsState.Fix },
            camera = when { cam.error -> WatchCamera.Problem; cam.recording == true -> WatchCamera.Recording; cam.ready -> WatchCamera.Ready; else -> WatchCamera.None },
        )
    }

    @SuppressLint("MissingPermission", "InlinedApi")
    private fun startRide() {
        startPending = false
        if (!serviceState().idle) return
        writeFailed = false
        ensureChannel()
        // Must follow startForegroundService() within a few seconds, so do it before anything else.
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Starting"), foregroundTypes())
        } catch (e: SecurityException) {   // Android 14 and later refuse the location type without the permission
            RideStore.update { it.copy(message = Note("Location permission is missing.", needsSettings = true)) }
            armed = false
            stopSelf()
            return
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            RideStore.update { it.copy(message = Note("Location permission is missing.", needsSettings = true)) }
            backToIdle()
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            RideStore.update { it.copy(message = Note("Location is switched off in the phone's settings.")) }
            backToIdle()
            return
        }

        val startUtc = System.currentTimeMillis()
        val startElapsed = SystemClock.elapsedRealtimeNanos()
        val dir = File(RideFiles.root(this), RideLog.FOLDER_PREFIX + stamp(startUtc)).apply { mkdirs() }
        val s = try {
            Session(dir, startUtc, startElapsed)
        } catch (e: IOException) {
            RideStore.update { it.copy(message = Note("Could not create the track files: ${e.message}")) }
            backToIdle()
            return
        }
        session = s
        writeMeta(s, endUtc = null, fitWritten = false, samples = 0, spans = emptyMap())

        val t = HandlerThread("ride-logger").also { it.start() }
        thread = t
        val handler = Handler(t.looper)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nanodash:ride").apply { acquire(MAX_RIDE_MS) }

        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, t.looper)
        @Suppress("DEPRECATION")
        locationManager.registerGnssStatusCallback(gnssCallback, handler)

        val wanted = listOf(
            Sensor.TYPE_ROTATION_VECTOR to 20_000,
            Sensor.TYPE_LINEAR_ACCELERATION to 20_000,
            Sensor.TYPE_ACCELEROMETER to 20_000,
            Sensor.TYPE_GYROSCOPE to 20_000,
            Sensor.TYPE_PRESSURE to 200_000,
        )
        for ((type, period) in wanted) {
            sensorManager.getDefaultSensor(type)?.let { sensorManager.registerListener(this, it, period, handler) }
        }

        RideStore.update { RideUiState(recording = true, startedUtcMillis = startUtc) }
        notify("Recording a track")
        startCamera(s)
    }

    /**
     * Starts every connected camera with the ride, all at once, and then any camera that becomes ready later (one that was
     * still reconnecting, or was switched on after the start): its clip simply begins later. Runs off the main thread;
     * failures never stop the ride. The start commands themselves are not cancellable, so stopping the ride never leaves a
     * camera that was told to start but is not remembered.
     */
    private fun startCamera(s: Session) {
        s.cameraJob = CameraController.scope.launch {
            if (CameraController.readyIds().isEmpty()) {
                RideStore.update { it.copy(cameraNote = Note("Camera not connected, so this track has no video.")) }
                s.event(JSONObject().put("event", "camera_not_ready"))
            } else {
                RideStore.update { it.copy(cameraNote = Note("Starting the camera", problem = false)) }
                withContext(NonCancellable) { recordStarts(s, CameraController.startRecording()) }
            }
            CameraController.ui.map { ui -> ui.rows.filter { it.ready }.map { it.address }.toSet() }.distinctUntilChanged().collect { ready ->
                val late = ready - s.cameras.keys
                if (late.isNotEmpty()) withContext(NonCancellable) { recordStarts(s, CameraController.startRecording(late)) }
            }
        }
    }

    /** Remembers and logs what the cameras answered, and tells the rider. */
    private fun recordStarts(s: Session, results: Map<String, StartResult>) {
        s.cameras = s.cameras + results
        for ((address, r) in results) {
            val camera = JSONObject().put("camera", cameraLabel(address))
            when (r) {
                is StartResult.Started -> s.event(
                    camera.put("event", "camera_start")
                        .put("command_sent", stampJson(r.commandSent)).put("reply", stampJson(r.replyAt))
                        .put("recording_flag", stampJson(r.recordingFlagAt)),
                )
                is StartResult.Rejected -> s.event(camera.put("event", "camera_start_rejected").put("code", r.code ?: JSONObject.NULL))
                else -> Unit
            }
        }
        val connected = CameraController.ui.value.rows.size.coerceAtLeast(s.cameras.size)
        RideStore.update { it.copy(cameraNote = Note(CameraNotes.startNote(connected, s.cameras), problem = !CameraNotes.startOk(connected, s.cameras))) }
    }

    private fun stampJson(s: Stamp?): Any = if (s == null) JSONObject.NULL else JSONObject().put("utc_ms", s.utcMillis).put("elapsed_ns", s.monoNanos)

    private fun spanJson(span: CameraSpan?): Any = if (span == null) JSONObject.NULL else JSONArray(listOf(span.startUtcMillis, span.endUtcMillis))

    private fun stopRide() {
        stopPending = false
        val s = session
        if (s == null) {
            if (ServiceRules.switchOffOnStopWithoutRide(serviceState())) stopSelf()
            return
        }
        session = null
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
        locationManager.unregisterGnssStatusCallback(gnssCallback)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        thread?.quitSafely()
        thread = null
        // Until the thread below is done, a new ride must not start: its end would switch this service off under the new ride.
        saving = true
        RideStore.update { it.copy(recording = false, saving = true, message = Note("Saving the track", problem = false)) }
        notify("Saving the track")

        Thread {
            try {
                finishRide(s)
            } catch (e: Throwable) {
                RideStore.update { it.copy(message = Note("Could not finish the track: ${e.message}. The GPS log was kept; use Rebuild .fit.")) }
            } finally {
                // On the main thread, in one step: a Start there between clearing `saving` and backToIdle() would begin a ride that backToIdle then kills.
                main.post {
                    saving = false
                    RideStore.update { it.copy(saving = false) }
                    backToIdle()
                }
            }
        }.start()
    }

    /** Stops the cameras, writes the .fit files and meta.json. Runs on its own thread because the camera stop can take seconds. */
    private fun finishRide(s: Session) {
        // Stop the cameras first so the clips end with the ride. Cancelling the start job waits for a start command in flight.
        val spans = LinkedHashMap<String, CameraSpan>()
        val clips = ArrayList<RideRecovery.CameraClip>()
        val toStop = runBlocking {
            withTimeoutOrNull(CameraLink.START_BUDGET_MS) { s.cameraJob?.cancelAndJoin() }
            ServiceRules.camerasToStop(s.cameras, CameraController.ui.value.rows.associate { it.address to it.recording })
        }
        // The phone's clock against the GPS's: the camera's clock came from the phone, the .fit times from the GPS.
        val skewMs = ClockSkew.phoneMinusGpsMs(s.fixes, s.startUtc, s.startElapsedNs)
        s.skewMs = skewMs
        if (toStop.isNotEmpty()) {
            val stops = runBlocking { withTimeoutOrNull(CameraLink.STOP_BUDGET_MS) { runCatching { CameraController.stopRecording(toStop) }.getOrDefault(emptyMap()) } } ?: emptyMap()
            val unconfirmed = mutableListOf<String>()
            val untimed = mutableListOf<String>()
            for (address in toStop) {
                val start = s.cameras[address] as? StartResult.Started
                val stop = stops[address]
                if (stop !is StopResult.Stopped) { unconfirmed += cameraLabel(address); continue }
                if (start == null) {
                    untimed += cameraLabel(address)
                    s.event(
                        JSONObject().put("event", "camera_stop_untimed").put(LogKey.CAMERA, cameraLabel(address))
                            .put("command_sent", stampJson(stop.commandSent)).put("reply", stampJson(stop.replyAt))
                            .put("flag_cleared", stampJson(stop.flagClearedAt)),
                    )
                    continue
                }
                val span = CameraTiming.clipSpan(start, stop)
                val shift = CameraTiming.fitShiftMs(CameraController.clockSet(address)) + (skewMs ?: 0)
                if (span != null) { spans[address] = span; clips += RideRecovery.CameraClip(cameraLabel(address), span, shift, skewMs ?: 0) }
                s.event(
                    JSONObject().put("event", "camera_stop").put(LogKey.CAMERA, cameraLabel(address))
                        .put(LogKey.FIT_SHIFT_MS, shift).put(LogKey.PHONE_MINUS_GPS_MS, skewMs ?: 0)
                        .put("command_sent", stampJson(stop.commandSent)).put("reply", stampJson(stop.replyAt))
                        .put("flag_cleared", stampJson(stop.flagClearedAt))
                        .put(LogKey.CLIP_SPAN, spanJson(span)),
                )
            }
            val note = listOfNotNull(CameraNotes.stopNote(toStop.size, unconfirmed), ServiceRules.strayStopNote(untimed)).joinToString(" ")
            RideStore.update { ui -> ui.copy(cameraNote = note.takeIf { n -> n.isNotEmpty() }?.let { text -> Note(text) }) }
        }

        val endUtc = System.currentTimeMillis()
        var written = false
        var samples = 0
        var message: Note
        try {
            s.close()
            // Times are moved onto the camera's own clock (see CameraTiming) so Mimo finds the clip inside the file.
            val built = RideRecovery.buildFits(s.fixes, clips)
            if (built == null) {
                message = Note("No GPS fixes were received, so no .fit was written. Motion data was saved.")
            } else {
                built.forEach { RideFolder.writeAtomically(File(s.dir, it.fileName), it.bytes) }
                samples = built.first().samples
                written = true
                message = Note(if (built.size == 1) "Saved ${samples} s of track to ride.fit" else "Saved ${samples} s of track, one .fit for each camera's clock", problem = false)
            }
        } catch (e: Exception) {
            message = Note("Could not write the track: ${e.message}")
        }
        writeMeta(s, endUtc, written, samples, spans)
        RideStore.update { it.copy(message = message) }
    }

    /** A full disk must not crash the ride: say so once and keep going with what still works. */
    private fun write(w: BufferedWriter, line: CharSequence, flush: Boolean = false) {
        try {
            w.append(line)
            if (flush) w.flush()
        } catch (e: IOException) {
            if (!writeFailed) {
                writeFailed = true
                RideStore.update { it.copy(message = Note("Could not write to storage. Is the phone full?")) }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val s = session ?: return
        val v = event.values
        val sb = s.line
        sb.setLength(0)
        sb.append(event.timestamp).append(',').append(event.sensor.type)
        for (i in 0 until minOf(v.size, 4)) sb.append(',').append(v[i])
        sb.append('\n')
        write(s.imu, sb)
        s.imuTotal++
        s.imuCounts.merge(event.sensor.type, 1L, Long::plus)
        if (s.imuTotal % 50L == 0L) RideStore.update { it.copy(imuSamples = s.imuTotal) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        val s = session ?: return
        // Android's plain altitude is height above the GPS ellipsoid, which differs from sea level by the local geoid height
        // (tens of metres in places). Phones on Android 14 and later also report sea-level altitude; use only that there.
        val alt = if (Build.VERSION.SDK_INT >= 34) {
            if (location.hasMslAltitude()) location.mslAltitudeMeters else null
        } else if (location.hasAltitude()) location.altitude else null
        val fix = GnssFix(
            utcMillis = location.time, latitudeDeg = location.latitude, longitudeDeg = location.longitude, altitudeM = alt,
            speedMps = if (location.hasSpeed()) location.speed else null,
            bearingDeg = if (location.hasBearing()) location.bearing else null,
            horizontalAccuracyM = if (location.hasAccuracy()) location.accuracy else null,
            verticalAccuracyM = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null,
            elapsedNs = location.elapsedRealtimeNanos,
            speedAccuracyMps = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null,
            bearingAccuracyDeg = if (location.hasBearingAccuracy()) location.bearingAccuracyDegrees else null,
        )
        s.fixes += fix
        // Flushed every fix (once a second) so a killed app loses at most the last fix, not the last ten minutes.
        write(s.gnss, GnssCsv.line(fix), flush = true)
        RideStore.update { it.copy(fixes = s.fixes.size, lastSpeedMps = fix.speedMps, lastAccuracyM = fix.horizontalAccuracyM) }
        if (s.fixes.size % 10 == 0) notify("Recording: ${s.fixes.size} GPS fixes")
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    override fun onDestroy() {
        // The system can kill us mid-ride: make sure the sensors and the wake lock are released.
        sensorManager.unregisterListener(this)
        runCatching { locationManager.removeUpdates(this) }
        wakeLock?.let { if (it.isHeld) it.release() }
        session?.let { runCatching { it.close() } }
        stopWatchLink()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun writeMeta(s: Session, endUtc: Long?, fitWritten: Boolean, samples: Int, spans: Map<String, CameraSpan>) {
        val cameras = JSONArray(
            s.cameras.map { (address, r) ->
                JSONObject().put(LogKey.CAMERA, cameraLabel(address)).put("start_result", r::class.simpleName)
                    .put(LogKey.FIT_SHIFT_MS, CameraTiming.fitShiftMs(CameraController.clockSet(address)))
                    .put(LogKey.CLIP_SPAN, spanJson(spans[address]))
            },
        )
        val json = JSONObject()
            .put("format_version", 1)   // raise when a field changes meaning, so a reader can tell old rides from new
            .put("app", packageName + " " + BuildConfigInfo.versionName(this))
            .put("altitude_reference", if (Build.VERSION.SDK_INT >= 34) "msl" else "wgs84_ellipsoid")
            .put("device", Build.MANUFACTURER + " " + Build.MODEL)
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put(LogKey.START_UTC_MS, s.startUtc)
            .put("start_elapsed_ns", s.startElapsedNs)
            .put(LogKey.END_UTC_MS, endUtc ?: JSONObject.NULL)
            .put(LogKey.GNSS_FIXES, s.fixes.size)
            .put("imu_events_total", s.imuTotal)
            .put("imu_events_by_sensor_type", JSONObject(s.imuCounts.mapKeys { it.key.toString() }))
            .put(LogKey.PHONE_MINUS_GPS_MS, s.skewMs ?: JSONObject.NULL)
            .put("cameras", cameras)
            .put("fit_written", fitWritten)
            .put("fit_samples", samples)
        RideFolder.writeAtomically(File(s.dir, RideLog.META), json.toString(2).toByteArray())
    }

    private fun stamp(utcMillis: Long): String =
        SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(utcMillis))

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Track logging", NotificationManager.IMPORTANCE_LOW))
        }
        if (nm.getNotificationChannel(READY_CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(READY_CHANNEL_ID, "Quick start", NotificationManager.IMPORTANCE_LOW))
        }
    }

    /** The notification while armed: the service is running, no ride yet. The Start button goes to this same service. */
    private fun readyNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val start = PendingIntent.getService(
            this, 2, Intent(this, LoggerService::class.java).setAction(ACTION_START), PendingIntent.FLAG_IMMUTABLE,
        )
        // A locked phone must not start a ride from the notification shade: Android asks for the unlock first (API 31 and later, where Android offers this;
        // older versions cannot require it). The watch's Start is different on purpose: the rider's
        // phone is locked in a pocket, so LoggerService.watchStart does not require an unlock.
        val startAction = NotificationCompat.Action.Builder(0, "Start tracking", start)
            .apply { if (Build.VERSION.SDK_INT >= 31) setAuthenticationRequired(true) }
            .build()
        return NotificationCompat.Builder(this, READY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Nano Dash is ready")
            .setContentText("Start a track here or from your watch")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(startAction)
            .build()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LoggerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Nano Dash Logger")
            .setContentText(text)
            .setOngoing(true)
            .setLocalOnly(false)   // a Wear OS watch mirrors this notification with its Stop button
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun notify(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        const val ACTION_START = "dev.nanodash.logger.START"
        const val ACTION_STOP = "dev.nanodash.logger.STOP"
        private const val CHANNEL_ID = "ride"
        private const val NOTIFICATION_ID = 1
        private const val MAX_RIDE_MS = 8L * 60 * 60 * 1000

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LoggerService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LoggerService::class.java).setAction(ACTION_STOP))
        }

        const val ACTION_ARM = "dev.nanodash.logger.ARM"
        const val ACTION_DISARM = "dev.nanodash.logger.DISARM"
        private const val READY_CHANNEL_ID = "ready"

        /** The running service, if any, so choosing a watch can cut the connection of the one chosen before. Set and cleared on the main thread. */
        @Volatile private var instance: LoggerService? = null

        fun setWatch(context: Context, address: String, name: String) {
            AppPrefs.setWatch(context, address, name)
            instance?.main?.post { instance?.let { it.watchLink?.dropClient(); if (it.armed) it.startWatchLink() } }
        }

        fun setQuickStart(context: Context, on: Boolean) {
            AppPrefs.setQuickStart(context, on)
            if (on) arm(context) else disarm(context)
        }

        /** Call while the app is on screen: Android only lets a location service be started from the foreground. */
        fun arm(context: Context) {
            if (AppPrefs.quickStart(context)) context.startForegroundService(Intent(context, LoggerService::class.java).setAction(ACTION_ARM))
        }

        private fun disarm(context: Context) {
            context.startService(Intent(context, LoggerService::class.java).setAction(ACTION_DISARM))
        }
    }
}

internal object BuildConfigInfo {
    fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"

    /** True when an F-Droid client installed the app: that client updates it, so the GitHub update link is not needed. */
    @Suppress("DEPRECATION") // getInstallSourceInfo needs API 30, minSdk is 26
    fun fromFDroid(context: Context): Boolean {
        val installer = runCatching { context.packageManager.getInstallerPackageName(context.packageName) }.getOrNull() ?: return false
        return installer.startsWith("org.fdroid.") || installer == "com.looker.droidify" || installer == "com.machiav3lli.fdroid"
    }

    /** The `versionCode`, which CI sets to the run number that is also the end of the release tag. */
    fun build(context: Context): Long =
        runCatching { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)) }.getOrDefault(0L)
}
