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
import dev.nanodash.core.camera.CameraNotes
import dev.nanodash.core.camera.CameraTiming
import dev.nanodash.core.camera.Stamp
import dev.nanodash.core.camera.StartResult
import dev.nanodash.core.camera.StopResult
import dev.nanodash.core.camera.cameraLabel
import dev.nanodash.core.fit.CameraSpan
import dev.nanodash.core.watch.CameraState
import dev.nanodash.core.watch.GpsState
import dev.nanodash.core.watch.RideState
import dev.nanodash.core.watch.WatchCommand
import dev.nanodash.core.watch.WatchProtocol
import dev.nanodash.core.watch.WatchStartRules
import dev.nanodash.core.watch.WatchState
import dev.nanodash.core.ride.ClockSkew
import dev.nanodash.core.ride.GnssFix
import dev.nanodash.core.ride.RideFolder
import dev.nanodash.core.ride.RideRecovery
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    private class Session(val dir: File, val startUtc: Long, val startElapsedNs: Long) {
        val gnss = BufferedWriter(FileWriter(File(dir, "gnss.csv")), 1 shl 16)
        val imu = BufferedWriter(FileWriter(File(dir, "imu.csv")), 1 shl 17)
        val events = BufferedWriter(FileWriter(File(dir, "events.jsonl")))
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
            gnss.write("elapsed_ns,utc_ms,lat,lon,alt_m,speed_mps,bearing_deg,h_acc_m,speed_acc_mps,bearing_acc_deg,v_acc_m\n")
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
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRide()
            ACTION_START -> startRide()
            ACTION_ARM -> arm()
            ACTION_DISARM -> disarm()
            else -> if (!armed && session == null && !saving) stopSelf()   // nothing else is a command: never start a ride on a stray intent
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

    // ----- quick start: armed between rides -----

    /** Started while the app is on screen, so Android lets it keep the location type. A Start from the watch then needs no new foreground start. */
    private fun arm() {
        armed = true
        ensureChannel()
        if (session == null && !saving) showArmed()
        startWatchLink()
    }

    private fun disarm() {
        armed = false
        stopWatchLink()
        if (session == null && !saving) {
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
        if (watchLink != null) return
        watchLink = WatchLink(this, { watchAddress(this) }, ::onWatchCommand, onWatchSeen = { setWatchSeen(this) }).also { it.start() }
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
            if (session != null) main.post { stopRide() }
            WatchProtocol.OK
        }
    }

    private fun watchStart(): String {
        if (session != null && !saving) return WatchProtocol.OK        // already riding: starting again is not an error
        WatchStartRules.refusal(
            saving,
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER),
        )?.let { return WatchProtocol.err(it) }
        main.post { startRide() }                                      // a second Start before this runs is ignored by startRide's own guard
        return WatchProtocol.OK
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
            camera = when { cam.error -> CameraState.Problem; cam.recording == true -> CameraState.Recording; cam.ready -> CameraState.Ready; else -> CameraState.None },
        )
    }

    @SuppressLint("MissingPermission", "InlinedApi")
    private fun startRide() {
        if (session != null || saving) return
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
        val root = getExternalFilesDir("rides") ?: File(filesDir, "rides")
        val dir = File(root, "ride-" + stamp(startUtc)).apply { mkdirs() }
        val s = try {
            Session(dir, startUtc, startElapsed)
        } catch (e: IOException) {
            RideStore.update { it.copy(message = Note("Could not create the ride files: ${e.message}")) }
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

        // (type, period in microseconds). Rotation vector, accelerometers and gyroscope at 50 Hz, pressure slower.
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
        notify("Recording a ride")
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
                RideStore.update { it.copy(cameraNote = Note("Camera not connected, so this ride has no video.")) }
                s.event(JSONObject().put("event", "camera_not_ready"))
            } else {
                RideStore.update { it.copy(cameraNote = Note("Starting the camera", problem = false)) }
                withContext(NonCancellable) { recordStarts(s, CameraController.startRecording()) }
            }
            while (isActive) {
                delay(LATE_CAMERA_CHECK_MS)
                val late = CameraController.readyIds() - s.cameras.keys
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
                        .put("recording_flag", r.recordingFlagAt?.let { stampJson(it) } ?: JSONObject.NULL),
                )
                is StartResult.Rejected -> s.event(camera.put("event", "camera_start_rejected").put("code", r.code ?: JSONObject.NULL))
                else -> Unit
            }
        }
        val connected = CameraController.ui.value.rows.size.coerceAtLeast(s.cameras.size)
        RideStore.update { it.copy(cameraNote = Note(CameraNotes.startNote(connected, s.cameras), problem = !CameraNotes.startOk(connected, s.cameras))) }
    }

    private fun stampJson(s: Stamp) = JSONObject().put("utc_ms", s.utcMillis).put("elapsed_ns", s.monoNanos)

    private fun stopRide() {
        val s = session
        if (s == null) {
            if (!saving && !armed) stopSelf()   // a Stop during saving must not switch the service off under the writer
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
        RideStore.update { it.copy(recording = false, saving = true, message = Note("Saving the ride", problem = false)) }
        notify("Saving the ride")

        Thread {
            try {
                finishRide(s)
            } catch (e: Throwable) {
                RideStore.update { it.copy(message = Note("Could not finish the ride: ${e.message}. The GPS log was kept; use Rebuild .fit.")) }
            } finally {
                saving = false
                RideStore.update { it.copy(saving = false) }
                backToIdle()
            }
        }.start()
    }

    /** Stops the cameras, writes the .fit files and meta.json. Runs on its own thread because the camera stop can take seconds. */
    private fun finishRide(s: Session) {
        // Stop the cameras first so the clips end with the ride. Cancelling the start job waits for a start command in flight.
        val spans = LinkedHashMap<String, CameraSpan>()          // one clip window per camera, by address
        val clips = ArrayList<RideRecovery.CameraClip>()
        val started = runBlocking {
            withTimeoutOrNull(15_000) { s.cameraJob?.cancelAndJoin() }
            s.cameras.filterValues { it is StartResult.Started }
        }
        // The phone's clock against the GPS's: the camera's clock came from the phone, the .fit times from the GPS.
        val skewMs = ClockSkew.phoneMinusGpsMs(s.fixes, s.startUtc, s.startElapsedNs)
        s.skewMs = skewMs
        if (started.isNotEmpty()) {
            // All cameras stop at once. Long enough for a link that dropped to come back (up to 2 x 12 s) and then stop (up to 15 s).
            val stops = runBlocking { withTimeoutOrNull(45_000) { runCatching { CameraController.stopRecording(started.keys) }.getOrDefault(emptyMap()) } } ?: emptyMap()
            val unconfirmed = mutableListOf<String>()
            for ((address, start) in started) {
                val stop = stops[address]
                if (stop !is StopResult.Stopped) { unconfirmed += cameraLabel(address); continue }
                val span = CameraTiming.clipSpan(start as StartResult.Started, stop)
                val shift = CameraController.fitShiftMs(address) + (skewMs ?: 0)
                if (span != null) { spans[address] = span; clips += RideRecovery.CameraClip(cameraLabel(address), span, shift, skewMs ?: 0) }
                s.event(
                    JSONObject().put("event", "camera_stop").put("camera", cameraLabel(address))
                        .put("fit_shift_ms", shift).put("phone_minus_gps_ms", skewMs ?: 0)
                        .put("command_sent", stampJson(stop.commandSent)).put("reply", stampJson(stop.replyAt))
                        .put("flag_cleared", stop.flagClearedAt?.let { stampJson(it) } ?: JSONObject.NULL)
                        .put("clip_span_estimate_utc_ms", span?.let { listOf(it.startUtcMillis, it.endUtcMillis) }?.let { org.json.JSONArray(it) } ?: JSONObject.NULL),
                )
            }
            RideStore.update { it.copy(cameraNote = CameraNotes.stopNote(started.size, unconfirmed)?.let { text -> Note(text) }) }
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
                message = Note(if (built.size == 1) "Saved ${samples} s of ride to ride.fit" else "Saved ${samples} s of ride, one .fit for each camera's clock", problem = false)
            }
        } catch (e: Exception) {
            message = Note("Could not write the ride: ${e.message}")
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

    // ----- sensors -----
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

    // ----- location -----
    override fun onLocationChanged(location: Location) {
        val s = session ?: return
        // Android's plain altitude is height above the GPS ellipsoid, which differs from sea level by the local geoid height
        // (tens of metres in places). Phones on Android 14 and later also report sea-level altitude; use only that there.
        val alt = if (Build.VERSION.SDK_INT >= 34) {
            if (location.hasMslAltitude()) location.mslAltitudeMeters else null
        } else if (location.hasAltitude()) location.altitude else null
        val speed = if (location.hasSpeed()) location.speed else null
        val bearing = if (location.hasBearing()) location.bearing else null
        val acc = if (location.hasAccuracy()) location.accuracy else null
        val vAcc = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null
        s.fixes += GnssFix(location.time, location.latitude, location.longitude, alt, speed, bearing, acc, vAcc, location.elapsedRealtimeNanos)
        val sb = s.line
        sb.setLength(0)
        sb.append(location.elapsedRealtimeNanos).append(',').append(location.time).append(',')
            .append(location.latitude).append(',').append(location.longitude).append(',')
            .append(alt ?: "").append(',').append(speed ?: "").append(',').append(bearing ?: "").append(',').append(acc ?: "").append(',')
            .append(if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else "").append(',')
            .append(if (location.hasBearingAccuracy()) location.bearingAccuracyDegrees else "").append(',')
            .append(vAcc ?: "").append('\n')
        // Flushed every fix (once a second) so a killed app loses at most the last fix, not the last ten minutes.
        write(s.gnss, sb, flush = true)
        RideStore.update { it.copy(fixes = s.fixes.size, lastSpeedMps = speed, lastAccuracyM = acc) }
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
        super.onDestroy()
    }

    // ----- files and notification -----
    private fun writeMeta(s: Session, endUtc: Long?, fitWritten: Boolean, samples: Int, spans: Map<String, CameraSpan>) {
        val cameras = org.json.JSONArray(
            s.cameras.map { (address, r) ->
                JSONObject().put("camera", cameraLabel(address)).put("start_result", r::class.simpleName)
                    .put("fit_shift_ms", CameraController.fitShiftMs(address))
                    .put("clip_span_estimate_utc_ms", spans[address]?.let { org.json.JSONArray(listOf(it.startUtcMillis, it.endUtcMillis)) } ?: JSONObject.NULL)
            },
        )
        val json = JSONObject()
            .put("format_version", 1)   // raise when a field changes meaning, so a reader can tell old rides from new
            .put("app", packageName + " " + BuildConfigInfo.versionName(this))
            .put("altitude_reference", if (Build.VERSION.SDK_INT >= 34) "msl" else "wgs84_ellipsoid")
            .put("device", Build.MANUFACTURER + " " + Build.MODEL)
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("start_utc_ms", s.startUtc)
            .put("start_elapsed_ns", s.startElapsedNs)
            .put("end_utc_ms", endUtc ?: JSONObject.NULL)
            .put("gnss_fixes", s.fixes.size)
            .put("imu_events_total", s.imuTotal)
            .put("imu_events_by_sensor_type", JSONObject(s.imuCounts.mapKeys { it.key.toString() }))
            .put("phone_minus_gps_ms", s.skewMs ?: JSONObject.NULL)
            .put("cameras", cameras)
            .put("fit_written", fitWritten)
            .put("fit_samples", samples)
        RideFolder.writeAtomically(File(s.dir, "meta.json"), json.toString(2).toByteArray())
    }

    private fun stamp(utcMillis: Long): String =
        SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(utcMillis))

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Ride logging", NotificationManager.IMPORTANCE_LOW))
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
        return NotificationCompat.Builder(this, READY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Nano Dash is ready")
            .setContentText("Start a ride here or from your watch")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Start ride", start)
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
        private const val MAX_RIDE_MS = 8L * 60 * 60 * 1000          // wake lock safety limit: 8 hours
        private const val LATE_CAMERA_CHECK_MS = 5_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LoggerService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LoggerService::class.java).setAction(ACTION_STOP))
        }

        // ----- quick start: the service stays armed between rides, with a Start notification and a listener for the watch -----
        const val ACTION_ARM = "dev.nanodash.logger.ARM"
        const val ACTION_DISARM = "dev.nanodash.logger.DISARM"
        private const val READY_CHANNEL_ID = "ready"

        private fun prefs(context: Context) = context.getSharedPreferences("nanodash", Context.MODE_PRIVATE)

        fun quickStartEnabled(context: Context): Boolean = prefs(context).getBoolean("quick_start", false)

        /** The paired device the rider chose as their watch; the phone serves no other device. Null until one is chosen. */
        fun watchAddress(context: Context): String? = prefs(context).getString("watch_address", null)

        fun watchName(context: Context): String? = prefs(context).getString("watch_name", null)

        /** When the watch app last completed a handshake with this phone, in ms since 1970; 0 if never. */
        fun watchSeen(context: Context): Long = prefs(context).getLong("watch_seen", 0L)

        private fun setWatchSeen(context: Context) = prefs(context).edit().putLong("watch_seen", System.currentTimeMillis()).apply()

        fun setWatch(context: Context, address: String, name: String) =
            prefs(context).edit().putString("watch_address", address).putString("watch_name", name).apply()

        /** Off by default. On, the service stays running between rides (one quiet notification) so the watch can reach it. */
        fun setQuickStart(context: Context, on: Boolean) {
            prefs(context).edit().putBoolean("quick_start", on).apply()
            if (on) arm(context) else disarm(context)
        }

        /** Call while the app is on screen: Android only lets a location service be started from the foreground. */
        fun arm(context: Context) {
            if (quickStartEnabled(context)) context.startForegroundService(Intent(context, LoggerService::class.java).setAction(ACTION_ARM))
        }

        private fun disarm(context: Context) {
            context.startService(Intent(context, LoggerService::class.java).setAction(ACTION_DISARM))
        }
    }
}

internal object BuildConfigInfo {
    fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"

    /** The `versionCode`, which CI sets to the run number that is also the end of the release tag. */
    fun build(context: Context): Long =
        runCatching { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)) }.getOrDefault(0L)
}
