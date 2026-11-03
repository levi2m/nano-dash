package dev.nanodash.logger

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import dev.nanodash.core.camera.CameraFleet
import dev.nanodash.core.camera.CameraLink
import dev.nanodash.core.camera.CameraNotes
import dev.nanodash.core.camera.CameraRow
import dev.nanodash.core.camera.CameraState
import dev.nanodash.core.camera.CameraTiming
import dev.nanodash.core.camera.Clock
import dev.nanodash.core.camera.StartResult
import dev.nanodash.core.camera.StopResult
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * What the screen shows about the cameras. The first five fields summarise all of them (so a single camera reads exactly
 * as before); [rows] has one entry per connected camera; the rest is the camera picker.
 */
data class CameraUi(
    val text: String = "Camera not connected",
    val ready: Boolean = false,
    val busy: Boolean = false,
    val recording: Boolean? = null,
    /** True when a camera could not be reached or its link was lost, so the screen can show it as a problem. */
    val error: Boolean = false,
    val rows: List<CameraRow> = emptyList(),
    val choosing: Boolean = false,
    val scanning: Boolean = false,
    val found: List<String> = emptyList(),
    val selected: Set<String> = emptySet(),
)

object SystemClocks : Clock {
    override fun utcMillis() = System.currentTimeMillis()
    override fun monotonicNanos() = SystemClock.elapsedRealtimeNanos()
}

/**
 * Process-wide owner of the camera links, so they outlive the screen and can be used by the ride service. Any number of
 * Nanos can be connected at once: they all start and stop with the ride. The phone's controller id (a 32-character hex
 * string, created once) is what every camera recognises as "this phone".
 */
object CameraController {
    /**
     * Camera trouble must never take the ride down with it: the camera code runs in the same process as the GPS logger, and an
     * uncaught exception here would kill both. Anything unexpected becomes a problem shown on the camera panel.
     */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            _ui.value = _ui.value.copy(text = "Camera error: ${e.message ?: e::class.simpleName}", error = true)
        },
    )
    private val _ui = MutableStateFlow(CameraUi())
    val ui: StateFlow<CameraUi> = _ui.asStateFlow()

    @Volatile private var fleet: CameraFleet? = null
    private val rows = LinkedHashMap<String, CameraRow>()
    private var job: Job? = null

    private fun prefs(context: Context) = context.getSharedPreferences("nanodash", Context.MODE_PRIVATE)

    private fun controllerId(context: Context): String {
        val p = prefs(context)
        p.getString("controller_id", null)?.let { return it }
        val id = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        p.edit { putString("controller_id", id) }
        return id
    }

    private fun savedCameras(context: Context): Set<String> {
        val p = prefs(context)
        return p.getStringSet("camera_addresses", null) ?: setOfNotNull(p.getString("camera_address", null))   // the older single-camera key
    }

    fun readyIds(): List<String> = fleet?.readyIds() ?: emptyList()

    /** The main button: connect the cameras chosen before, or open the picker the first time. */
    fun connect(context: Context) {
        val app = context.applicationContext
        val saved = savedCameras(app)
        if (saved.isEmpty()) choose(app) else connectTo(app, saved)
    }

    /** Looks for cameras and opens the picker. Cameras that are connected to this phone do not advertise, so this disconnects first. */
    fun choose(context: Context) {
        val app = context.applicationContext
        disconnect(keepText = true)
        job = scope.launch {
            publish { CameraUi(choosing = true, scanning = true) }
            try {
                val found = NanoScanner.scan(app, 8_000)
                val saved = savedCameras(app).filter { it in found }.toSet()
                publish { it.copy(scanning = false, found = found, selected = saved.ifEmpty { found.take(if (found.size == 1) 1 else 0).toSet() }) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                publish { it.copy(scanning = false, text = e.message ?: "Could not scan for cameras", error = true) }
            }
        }
    }

    fun toggle(address: String) = publish { ui -> ui.copy(selected = if (address in ui.selected) ui.selected - address else ui.selected + address) }

    fun closePicker() = publish { it.copy(choosing = false) }

    /** Connects the cameras ticked in the picker and remembers them for next time. */
    fun connectSelected(context: Context) {
        val app = context.applicationContext
        val chosen = _ui.value.selected
        if (chosen.isEmpty()) return
        prefs(app).edit { putStringSet("camera_addresses", chosen) }
        connectTo(app, chosen)
    }

    private fun connectTo(app: Context, addresses: Set<String>) {
        disconnect(keepText = true)
        val id = controllerId(app)
        val links = addresses.associateWith { address ->
            CameraLink(scope, SystemClocks, id) {
                BleCameraTransport(app, address).also { t -> try { t.open() } catch (e: Exception) { t.close(); throw e } }
            }
        }
        fleet = CameraFleet(links)
        synchronized(rows) { rows.clear(); addresses.forEach { rows[it] = CameraRow(it, "Waiting", busy = true) } }
        publish { CameraUi(choosing = false) }
        job = scope.launch {
            // One after another: setting up several GATT links at once is unreliable on many phones.
            for ((address, link) in links) {
                launch { link.state.collect { st -> setRow(address) { row -> rowFor(row, st) } } }
                launch { link.recording.collect { r -> setRow(address) { it.copy(recording = r) } } }
                link.connect()
            }
        }
    }

    private fun rowFor(row: CameraRow, st: CameraState): CameraRow = when (st) {
        CameraState.Pairing -> row.copy(text = "Pairing", ready = false, busy = true, error = false)
        CameraState.AwaitingApproval -> row.copy(text = "Approve this phone on the camera's screen", ready = false, busy = true, error = false)
        CameraState.Waking -> row.copy(text = "Waking the camera", ready = false, busy = true, error = false)
        CameraState.Ready -> row.copy(text = "Camera ready", ready = true, busy = false, error = false, clockSet = fleet?.clockSet(row.address) == true)
        CameraState.Reconnecting -> row.copy(text = "Camera lost, reconnecting", ready = false, busy = true, error = true)
        is CameraState.Failed -> row.copy(text = st.reason, ready = false, busy = false, error = true)
        CameraState.Disconnected -> if (row.ready) row.copy(text = "Camera disconnected", ready = false, error = true) else row
    }

    private fun setRow(address: String, change: (CameraRow) -> CameraRow) {
        synchronized(rows) { rows[address]?.let { rows[address] = change(it) } }
        publish()
    }

    /** Recomputes the summary from the rows, keeping the picker fields. */
    private fun publish(change: (CameraUi) -> CameraUi = { it }) {
        synchronized(rows) {
            val list = rows.values.toList()
            val base = change(_ui.value)
            _ui.value = if (list.isEmpty()) base.copy(rows = emptyList(), ready = false, busy = false, recording = null)
            else CameraNotes.summarize(list).let { s ->
                base.copy(rows = list, text = s.text, ready = s.ready, busy = s.busy, error = s.error, recording = s.recording)
            }
        }
    }

    fun disconnect(keepText: Boolean = false) {
        job?.cancel()
        job = null
        fleet?.close()
        fleet = null
        synchronized(rows) { rows.clear() }
        if (!keepText) _ui.value = CameraUi()
    }

    /** Starts every camera that is ready (or just those in [only]), all at the same moment. Empty when none is. */
    suspend fun startRecording(only: Collection<String>? = null): Map<String, StartResult> = fleet?.startReady(only) ?: emptyMap()

    /** What to add to this camera's .fit times: its clock was set to the phone's on connect, or it keeps the older, larger offset. */
    fun fitShiftMs(address: String): Long = if (fleet?.clockSet(address) == true) CameraTiming.FIT_SHIFT_MS else CameraTiming.LEGACY_FIT_SHIFT_MS

    suspend fun stopRecording(addresses: Collection<String>): Map<String, StopResult> = fleet?.stop(addresses) ?: emptyMap()
}
