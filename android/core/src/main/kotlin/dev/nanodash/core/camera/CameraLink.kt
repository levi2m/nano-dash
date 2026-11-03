package dev.nanodash.core.camera

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps one camera session alive for as long as it is wanted. If the link drops after the camera was ready, it opens a new
 * transport and session, waiting 1 s, 2 s, 4 s and so on up to 10 s between tries. The camera keeps recording while the
 * phone is away, so a ride that lost the link still ends with a stop command once it is back.
 *
 * [open] returns a fresh transport (the camera is already paired, so the handshake is short) or throws if it cannot be reached.
 */
class CameraLink(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val controllerId: String,
    private val open: suspend () -> CameraTransport,
) {
    private val _state = MutableStateFlow<CameraState>(CameraState.Disconnected)
    val state: StateFlow<CameraState> = _state.asStateFlow()

    private val _recording = MutableStateFlow<Boolean?>(null)
    val recording: StateFlow<Boolean?> = _recording.asStateFlow()

    @Volatile private var session: CameraSession? = null
    @Volatile private var transport: CameraTransport? = null
    @Volatile private var wanted = false
    @Volatile private var clockEverSet = false
    private var forwarders: Job? = null
    private var supervisor: Job? = null

    fun isReady() = session?.state?.value == CameraState.Ready

    /** Whether any connection since [connect] set the camera's clock, so a reconnect that skips it does not lose the fact. */
    fun clockSet() = clockEverSet

    /** Connects for the first time. On failure [state] holds the reason and nothing keeps running. */
    suspend fun connect(): Boolean {
        wanted = true
        clockEverSet = false
        if (!attach()) {
            wanted = false
            return false
        }
        supervisor = scope.launch { supervise() }
        return true
    }

    suspend fun startRecording(): StartResult = session?.startRecording() ?: StartResult.NotReady

    /** Waits up to [RECONNECT_WAIT_MS] for a link that dropped, then stops the camera; tries a second time if the link broke again. */
    suspend fun stopRecording(): StopResult {
        var result: StopResult = StopResult.NotReady
        repeat(2) {
            withTimeoutOrNull(RECONNECT_WAIT_MS) { while (!isReady()) delay(200) }
            result = session?.stopRecording() ?: StopResult.NotReady
            if (result is StopResult.Stopped || result is StopResult.Rejected) return result
        }
        return result
    }

    fun close() {
        wanted = false
        supervisor?.cancel()
        forwarders?.cancel()
        session?.close()
        transport?.close()
        session = null
        transport = null
        _state.value = CameraState.Disconnected
    }

    private suspend fun attach(): Boolean {
        val t = try {
            open()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _state.value = CameraState.Failed(e.message ?: "Could not connect to the camera")
            return false
        }
        transport = t
        val s = CameraSession(t, clock, controllerId, scope)
        session = s
        forwarders?.cancel()
        // A drop while the link is wanted is reported as Reconnecting by [supervise], so it is not forwarded as Disconnected.
        forwarders = scope.launch {
            launch { s.state.collect { if (it != CameraState.Disconnected || !wanted) _state.value = it } }
            launch { s.recording.collect { _recording.value = it } }
        }
        val ok = s.connect()
        if (!ok) t.close()
        if (s.clockSet) clockEverSet = true
        return ok
    }

    private suspend fun supervise() {
        var s = session ?: return
        var wait = FIRST_RETRY_MS
        while (wanted) {
            s.state.first { it == CameraState.Disconnected }
            if (!wanted) return
            _state.value = CameraState.Reconnecting
            s.close()
            transport?.close()
            while (wanted) {
                delay(wait)
                wait = minOf(wait * 2, MAX_RETRY_MS)
                if (wanted && attach()) {
                    wait = FIRST_RETRY_MS
                    break
                }
                _state.value = CameraState.Reconnecting
            }
            s = session ?: return
        }
    }

    companion object {
        const val FIRST_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 10_000L
        const val RECONNECT_WAIT_MS = 12_000L
    }
}
