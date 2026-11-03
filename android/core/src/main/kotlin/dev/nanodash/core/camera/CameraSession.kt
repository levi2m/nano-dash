package dev.nanodash.core.camera

import dev.nanodash.core.duml.Addr
import dev.nanodash.core.duml.CameraFrames
import dev.nanodash.core.duml.CameraReplies
import dev.nanodash.core.duml.Duml
import dev.nanodash.core.duml.DumlFrame
import dev.nanodash.core.duml.DumlReassembler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.TimeZone

/** The BLE link to the camera, as the session sees it. The Android layer implements this over GATT characteristics FFF4 and FFF5. */
interface CameraTransport {
    /** Raw notification payloads from the camera, in order. Frames may be split or merged across payloads. */
    val incoming: Flow<ByteArray>

    /** Writes one complete frame to the camera (write without response). Throws if the link is gone. */
    suspend fun send(frame: ByteArray)

    /** Drops the link and frees what it holds. Safe to call twice. */
    fun close() {}
}

interface Clock {
    fun utcMillis(): Long
    fun monotonicNanos(): Long
}

/** A moment on both clocks, so camera events can be placed in the phone's sensor timeline and in UTC. */
data class Stamp(val utcMillis: Long, val monoNanos: Long)

sealed interface CameraState {
    data object Disconnected : CameraState
    data object Pairing : CameraState
    /** The camera is showing an approval prompt for this phone. */
    data object AwaitingApproval : CameraState
    data object Waking : CameraState
    data object Ready : CameraState
    /** The link dropped after the camera was ready, and a [CameraLink] is trying to bring it back. */
    data object Reconnecting : CameraState
    data class Failed(val reason: String) : CameraState
}

sealed interface StartResult {
    /** [recordingFlagAt] is when the camera's status first reported recording; null if it did not within the timeout. */
    data class Started(val commandSent: Stamp, val replyAt: Stamp, val recordingFlagAt: Stamp?) : StartResult
    /** The camera answered with a non-zero code. 0xD6 was seen with USB connected; 0xDF means already recording (per Osmosis). */
    data class Rejected(val code: Int?, val commandSent: Stamp) : StartResult
    data object NoReply : StartResult
    data object NotReady : StartResult
}

sealed interface StopResult {
    data class Stopped(val commandSent: Stamp, val replyAt: Stamp, val flagClearedAt: Stamp?) : StopResult
    data class Rejected(val code: Int?, val commandSent: Stamp) : StopResult
    data object NoReply : StopResult
    data object NotReady : StopResult
}

/**
 * One paired session with a Nano. Behaviour is the one observed on a real camera in the M0 trials (2026-11-02):
 * pair (the camera may ask for approval on its screen), wake, a keepalive every second (the Nano drops an idle link after
 * about 5 to 6 s), status pushes about 10 times a second with a recording flag, and record start and stop commands
 * answered with a code (0 = accepted).
 */
class CameraSession(
    private val transport: CameraTransport,
    private val clock: Clock,
    private val controllerId: String,
    private val scope: CoroutineScope,
    private val timeZone: TimeZone = TimeZone.getDefault(),
) {
    /** True when this connection set the camera's clock, so clip times follow the phone's within [CameraTiming.CLOCK_BEHIND_PHONE_MS]. */
    @Volatile var clockSet = false
        private set

    private val _state = MutableStateFlow<CameraState>(CameraState.Disconnected)
    val state: StateFlow<CameraState> = _state.asStateFlow()

    private val _recording = MutableStateFlow<Boolean?>(null)
    /** The camera's own recording flag from its status pushes; null until the first push. */
    val recording: StateFlow<Boolean?> = _recording.asStateFlow()

    private val frames = MutableSharedFlow<DumlFrame>(extraBufferCapacity = 256)
    private val reassembler = DumlReassembler()
    private var seq = 0
    private var readerJob: Job? = null
    private var keepaliveJob: Job? = null

    private fun nextSeq(): Int { seq = (seq + 1) and 0xFFFF; return seq }
    private fun stamp() = Stamp(clock.utcMillis(), clock.monotonicNanos())

    /** Runs the whole handshake. Returns true when the camera is ready; otherwise [state] holds the reason. */
    suspend fun connect(): Boolean {
        close()
        _state.value = CameraState.Pairing
        _recording.value = null
        clockSet = false
        readerJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {      // subscribed before the first frame is sent
            transport.incoming.collect { chunk ->
                for (raw in reassembler.feed(chunk)) {
                    val f = runCatching { Duml.parse(raw) }.getOrNull() ?: continue
                    val flag = CameraReplies.isRecording(f)
                    if (flag != null) _recording.value = flag else frames.tryEmit(f)
                }
            }
        }
        try {
            transport.send(CameraFrames.prePair(nextSeq()))
            delay(300)

            val reply = request(3_000, { CameraReplies.isPairingReply(it) }) { transport.send(CameraFrames.pair(controllerId, nextSeq())) }
            when (reply?.let { CameraReplies.pairing(it) }) {
                null -> return fail("The camera did not answer the pairing request.")
                CameraReplies.Pairing.ALREADY_PAIRED -> Unit
                CameraReplies.Pairing.APPROVAL_REQUIRED -> {
                    _state.value = CameraState.AwaitingApproval
                    val req = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { frames.first { CameraReplies.isApprovalRequest(it) } }
                        ?: return fail("The pairing was not approved on the camera.")
                    // Reply shape is an assumption that held on the real camera: response flag, same set/id and sequence, code 0.
                    transport.send(Duml.build(Addr.APP, req.sender, req.seq, 0x80, 0x07, 0x46, byteArrayOf(0)))
                }
                CameraReplies.Pairing.UNKNOWN -> return fail("Unexpected pairing reply from the camera.")
            }

            _state.value = CameraState.Waking
            keepaliveJob = scope.launch {
                try {
                    while (true) {
                        transport.send(CameraFrames.keepalive(nextSeq()))
                        delay(KEEPALIVE_MS)
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _state.value = CameraState.Disconnected
                }
            }
            val wake = request(3_000, { CameraReplies.isWakeReply(it) }) { transport.send(CameraFrames.wake(nextSeq())) }
            if (wake == null) return fail("The camera did not answer the wake command.")
            if (withTimeoutOrNull(5_000) { _recording.first { it != null } } == null) return fail("The camera sent no status.")
            setClock()
            _state.value = CameraState.Ready
            return true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return fail("Bluetooth error: ${e.message}")
        }
    }

    /**
     * Sets the camera's clock to the phone's. The camera takes whole seconds, so the write is timed to arrive just after a
     * second boundary of the phone's clock. A camera that does not answer is left as it is: [clockSet] stays false and the
     * ride falls back to the older offset.
     */
    private suspend fun setClock() {
        // A camera that is already recording (a link that dropped mid-ride) keeps its clock: changing it under a running clip is not worth the risk.
        if (_recording.value == true) return
        val now = clock.utcMillis()
        val boundary = (now / 1000 + 2) * 1000
        delay(boundary - now - CameraTiming.SET_CLOCK_LEAD_MS)
        val offsetMinutes = timeZone.getOffset(boundary) / 60_000
        val reply = request(2_000, { CameraReplies.isClockReply(it) }) {
            transport.send(CameraFrames.setClock(nextSeq(), boundary / 1000, offsetMinutes, timeZone.id))
        }
        clockSet = reply != null && CameraReplies.clockAccepted(reply)
    }

    suspend fun startRecording(): StartResult {
        if (_state.value != CameraState.Ready) return StartResult.NotReady
        val sent = stamp()
        val reply = request(5_000, { CameraReplies.isRecordReply(it) }) { transport.send(CameraFrames.recordStart(nextSeq())) }
            ?: return StartResult.NoReply
        val replyAt = stamp()
        if (!CameraReplies.recordAccepted(reply)) return StartResult.Rejected(reply.payload.firstOrNull()?.toInt()?.and(0xFF), sent)
        var flagAt: Stamp? = null
        if (withTimeoutOrNull(8_000) { _recording.first { it == true } } != null) flagAt = stamp()
        return StartResult.Started(sent, replyAt, flagAt)
    }

    suspend fun stopRecording(): StopResult {
        if (_state.value != CameraState.Ready) return StopResult.NotReady
        val sent = stamp()
        val reply = request(5_000, { CameraReplies.isRecordReply(it) }) { transport.send(CameraFrames.recordStop(nextSeq())) }
            ?: return StopResult.NoReply
        val replyAt = stamp()
        if (!CameraReplies.recordAccepted(reply)) return StopResult.Rejected(reply.payload.firstOrNull()?.toInt()?.and(0xFF), sent)
        var clearedAt: Stamp? = null
        if (withTimeoutOrNull(10_000) { _recording.first { it == false } } != null) clearedAt = stamp()
        return StopResult.Stopped(sent, replyAt, clearedAt)
    }

    fun close() {
        keepaliveJob?.cancel()
        readerJob?.cancel()
        keepaliveJob = null
        readerJob = null
        _state.value = CameraState.Disconnected
    }

    /**
     * Subscribes for the reply first, then sends, so a fast reply cannot be missed. A write that fails because the link is
     * gone marks the session disconnected and counts as no reply, instead of throwing into the caller.
     */
    private suspend fun request(timeoutMs: Long, match: (DumlFrame) -> Boolean, send: suspend () -> Unit): DumlFrame? = try {
        coroutineScope {
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(timeoutMs) { frames.first(match) } }
            send()
            waiting.await()
        }
    } catch (e: IOException) {
        if (_state.value == CameraState.Ready) {
            keepaliveJob?.cancel()
            _state.value = CameraState.Disconnected
        }
        null
    }

    private fun fail(reason: String): Boolean {
        keepaliveJob?.cancel()
        _state.value = CameraState.Failed(reason)
        return false
    }

    companion object {
        const val KEEPALIVE_MS = 1_000L
        const val APPROVAL_TIMEOUT_MS = 40_000L
    }
}
