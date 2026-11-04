package dev.nanodash.logger

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import dev.nanodash.core.watch.BoundedLineReader
import dev.nanodash.core.watch.WatchCommand
import dev.nanodash.core.watch.WatchProtocol
import java.io.BufferedInputStream
import java.io.IOException
import java.util.UUID

/** What the phone answers to the watch. */
fun interface WatchHandler {
    /** The one line to send back for [command]. */
    fun onCommand(command: WatchCommand): String
}

/**
 * Listens for the watch on a secure Bluetooth serial channel (Android encrypts and authenticates it with the watch's
 * existing pairing) and answers its commands one line at a time. One watch is served at a time.
 * [allowedAddress] is the paired device the rider chose as their watch; while none is chosen nobody is served.
 */
class WatchLink(
    private val context: Context,
    private val allowedAddress: () -> String?,
    private val handler: WatchHandler,
    /** Called each time the chosen watch completes the handshake, so the app can show that the watch app has been seen. */
    private val onWatchSeen: () -> Unit = {},
) {
    @Volatile private var running = false
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var client: BluetoothSocket? = null
    private var thread: Thread? = null

    /** False once [stop] was called, or when the listener ended by itself (no Bluetooth adapter, or the permission was refused). */
    val isRunning: Boolean get() = running

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread({ listen() }, "watch-link").apply { isDaemon = true; start() }
    }

    /** Waits briefly for the listener to end, so a quick stop then start never leaves two of them. */
    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }          // unblocks accept(); listen() also closes a server socket that was still being created
        runCatching { client?.close() }
        thread?.interrupt()
        thread?.join(1_500)
        thread = null
    }

    /** Cuts the watch that is connected now, if any, and goes on listening. Used when the rider chooses a different watch. */
    fun dropClient() {
        runCatching { client?.close() }
    }

    @SuppressLint("MissingPermission")
    private fun listen() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { running = false; return }
        while (running) {
            try {
                val srv = adapter.listenUsingRfcommWithServiceRecord("Nano Dash", UUID.fromString(WatchProtocol.SERVICE_UUID))
                server = srv
                if (!running) { srv.close(); return }   // stop() ran before the server was visible to it, so it could not close it
                val socket = srv.accept()          // blocks until a paired device connects, or stop() closes the server
                srv.close()
                client = socket
                if (!running) return                // stop() ran while the connection was being accepted
                serve(socket)
            } catch (e: IOException) {
                if (running) {
                    try { Thread.sleep(2_000) }    // Bluetooth off or busy: try again shortly instead of spinning
                    catch (ie: InterruptedException) { return }   // stop() asked us to end
                }
            } catch (e: InterruptedException) {
                return                             // stop() asked us to end
            } catch (e: SecurityException) {
                running = false                    // the Bluetooth permission was refused: LoggerService starts a new link when it is back
                return
            } finally {
                runCatching { server?.close() }
                server = null
                runCatching { client?.close() }
                client = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun serve(socket: BluetoothSocket) {
        val address = socket.remoteDevice.address
        if (address != allowedAddress()) return                                    // no watch chosen, or not the rider's watch: closed at once
        val input = BoundedLineReader(BufferedInputStream(socket.inputStream))
        val out = socket.outputStream
        fun send(line: String) { out.write((line + "\n").toByteArray()); out.flush() }
        // A Bluetooth read has no timeout of its own: close the socket if no HELLO arrives, so a silent device cannot hold the one connection.
        val watchdog = Thread({
            try { Thread.sleep(HANDSHAKE_MS); runCatching { socket.close() } } catch (e: InterruptedException) { /* the hello came */ }
        }, "watch-handshake").apply { isDaemon = true; start() }
        val hello = try { input.readLine() } finally { watchdog.interrupt() }
        if (!WatchProtocol.isHello(hello ?: return)) { send(WatchProtocol.err("version")); return }
        send(WatchProtocol.OK)
        onWatchSeen()
        while (running) {
            val line = input.readLine() ?: return
            if (address != allowedAddress()) return                                // the rider chose another watch meanwhile: cut this one
            val command = WatchProtocol.parseCommand(line) ?: continue             // unknown lines are ignored
            send(handler.onCommand(command))
        }
    }

    private companion object {
        const val HANDSHAKE_MS = 5_000L
    }
}
