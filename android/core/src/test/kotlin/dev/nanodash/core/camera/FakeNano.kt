package dev.nanodash.core.camera

import dev.nanodash.core.duml.Addr
import dev.nanodash.core.duml.Duml
import dev.nanodash.core.duml.DumlFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * A stand-in for the Nano that behaves like the real one did in the M0 trials: pairing reply 00 01 or 00 02 (and then an
 * approval request), wake reply, status pushes at 10 Hz with a recording flag, record start answered after about 550 ms
 * with the flag on at about 1400 ms, record stop answered in about 50 ms with the flag off at about 2100 ms, reply 0xD6 to a
 * start while USB is connected, 0xDF to a start while recording, and a dropped link when keepalives stop for 6 s.
 */
class FakeNano(private val scope: CoroutineScope, private val nowMs: () -> Long) : CameraTransport {
    var alreadyPaired = true
    var approvalDelayMs: Long? = 2_000       // for an unpaired phone; null = nobody ever approves
    var usbAttached = false
    var chunkSize: Int? = null               // set to split every frame into small notification payloads
    var idleDropMs = 6_000L
    var linkUp = true

    var acceptsClock = true
    /** (fake time in ms when the set-clock write arrived, unix seconds it carried) */
    val clockWrites = mutableListOf<Pair<Long, Long>>()
    var clockPayload: ByteArray? = null

    val sent = mutableListOf<DumlFrame>()
    private var flagOn = false
    private var recordingNow = false
    private var lastKeepalive = nowMs()
    private var seq = 100
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4096)
    override val incoming: Flow<ByteArray> = _incoming

    init {
        scope.launch {
            while (true) {
                delay(100)
                if (!linkUp) continue
                if (nowMs() - lastKeepalive > idleDropMs) { linkUp = false; continue }
                val payload = ByteArray(60).also { it[0] = if (flagOn) 0x81.toByte() else 0x01 }
                emit(Duml.build(Addr.CAMERA, Addr.APP, nextSeq(), 0x00, 0x02, 0x80, payload))
            }
        }
    }

    private fun nextSeq() = ++seq

    /** The phone found the camera again: the link works and the idle timer starts over. The camera's own state (recording) is kept. */
    fun reconnect() {
        linkUp = true
        lastKeepalive = nowMs()
    }

    private fun emit(frame: ByteArray) {
        val size = chunkSize
        if (size == null) _incoming.tryEmit(frame) else frame.toList().chunked(size).forEach { _incoming.tryEmit(it.toByteArray()) }
    }

    private fun reply(sender: Int, req: DumlFrame, vararg payload: Int) =
        emit(Duml.build(sender, Addr.APP, nextSeq(), 0xC0, req.cmdSet, req.cmdId, ByteArray(payload.size) { payload[it].toByte() }))

    override suspend fun send(frame: ByteArray) {
        if (!linkUp) throw IOException("link down")
        val f = Duml.parse(frame)
        sent += f
        when (f.cmdSet to f.cmdId) {
            0x00 to 0x2B -> if (f.payload.contentEquals(byteArrayOf(1, 1))) lastKeepalive = nowMs()
            0x07 to 0x45 -> {
                reply(Addr.WIFI, f, 0x00, if (alreadyPaired) 0x01 else 0x02)
                val delayMs = approvalDelayMs
                if (!alreadyPaired && delayMs != null) scope.launch {
                    delay(delayMs)
                    emit(Duml.build(Addr.WIFI, Addr.APP, 2, 0x40, 0x07, 0x46, byteArrayOf(1)))
                }
            }
            0x53 to 0x10 -> reply(Addr.SESSION_1C, f, 0x01, 0x00, 0x00, 0x00)
            0x00 to 0x6A -> {
                // like the real camera: only the clock receiver answers; sent to the camera itself it is dropped
                if (f.receiver == Addr.CLOCK && acceptsClock) {
                    var unix = 0L
                    for (i in 7 downTo 0) unix = (unix shl 8) or (f.payload[2 + i].toLong() and 0xFF)
                    clockWrites += nowMs() to unix
                    clockPayload = f.payload
                    reply(Addr.CLOCK, f, 0x00, 0x01, 0x00)
                }
            }
            0x02 to 0x02 -> handleRecord(f)
        }
    }

    private fun handleRecord(f: DumlFrame) {
        val start = f.payload.firstOrNull()?.toInt() == 1
        scope.launch {
            if (start) {
                when {
                    usbAttached -> { delay(100); reply(Addr.CAMERA, f, 0xD6) }
                    recordingNow -> { delay(50); reply(Addr.CAMERA, f, 0xDF) }
                    else -> {
                        delay(550); recordingNow = true; reply(Addr.CAMERA, f, 0x00)
                        delay(850); flagOn = true
                    }
                }
            } else {
                delay(47); reply(Addr.CAMERA, f, 0x00)
                delay(2_050); recordingNow = false; flagOn = false
            }
        }
    }
}
