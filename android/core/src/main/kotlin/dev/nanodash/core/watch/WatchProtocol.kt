package dev.nanodash.core.watch

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** What the watch can ask the phone to do. */
enum class WatchCommand { Status, Start, Stop }

enum class RideState { Idle, Recording, Saving }
enum class GpsState { Off, Searching, Fix }
enum class CameraState { None, Ready, Recording, Problem }

/** What the phone tells the watch: enough for the watch screen and tile, nothing about where the rider is. */
data class WatchState(val ride: RideState, val elapsedSeconds: Long, val gps: GpsState, val camera: CameraState)

/**
 * The text protocol between the watch and the phone, one line per message, at most [MAX_LINE_BYTES] bytes.
 * Watch to phone: `HELLO 1`, `STATUS`, `START`, `STOP`. Phone to watch: `OK`, `ERR <reason>` and `STATE <ride> <seconds> <gps> <camera>`.
 */
object WatchProtocol {
    const val VERSION = 1
    const val MAX_LINE_BYTES = 128

    /** The Bluetooth service id of the phone's listener. */
    const val SERVICE_UUID = "a3286e7a-3551-4008-abcb-d4c7c9fbf582"

    const val HELLO = "HELLO $VERSION"
    const val OK = "OK"

    /** The command in [line], or null for anything else: unknown lines are ignored, never guessed at. */
    fun parseCommand(line: String): WatchCommand? = when (line.trim()) {
        "STATUS" -> WatchCommand.Status
        "START" -> WatchCommand.Start
        "STOP" -> WatchCommand.Stop
        else -> null
    }

    /** True only for a hello of exactly this protocol version. */
    fun isHello(line: String): Boolean = line.trim() == HELLO

    fun err(reason: String): String = "ERR " + reason.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(40)

    fun encode(state: WatchState): String =
        "STATE ${state.ride.name.lowercase()} ${state.elapsedSeconds} ${state.gps.name.lowercase()} ${state.camera.name.lowercase()}"

    /** The state in [line], or null when it is not a well-formed `STATE` line. */
    fun decode(line: String): WatchState? {
        val p = line.trim().split(' ')
        if (p.size != 5 || p[0] != "STATE") return null
        val ride = RideState.entries.firstOrNull { it.name.lowercase() == p[1] } ?: return null
        val seconds = p[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val gps = GpsState.entries.firstOrNull { it.name.lowercase() == p[3] } ?: return null
        val camera = CameraState.entries.firstOrNull { it.name.lowercase() == p[4] } ?: return null
        return WatchState(ride, seconds, gps, camera)
    }
}

/**
 * Reads newline-ended lines of at most [max] bytes. A longer line is an [IOException], so a misbehaving peer is cut off
 * instead of filling memory. Wrap a raw socket stream in a `BufferedInputStream` first; this reads one byte at a time.
 */
class BoundedLineReader(private val input: InputStream, private val max: Int = WatchProtocol.MAX_LINE_BYTES) {
    /** The next line without its line ending, or null when the stream ends. */
    fun readLine(): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return null                       // the stream ended, with or without a half line: nothing complete to return
            if (b == '\n'.code) return line.toString(Charsets.UTF_8).trimEnd('\r')
            if (line.size() >= max) throw IOException("line longer than $max bytes")
            line.write(b)
        }
    }
}
