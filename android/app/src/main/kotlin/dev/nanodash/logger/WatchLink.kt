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
 * [allowedAddress] is the paired device the user chose as their watch; null accepts any paired device (spike only).
 */
class WatchLink(
    private val context: Context,
    private val allowedAddress: () -> String?,
    private val handler: WatchHandler,
) {
    @Volatile private var running = false
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var client: BluetoothSocket? = null
    private var thread: Thread? = null

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread({ listen() }, "watch-link").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }
        runCatching { client?.close() }
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun listen() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        while (running) {
            try {
                val srv = adapter.listenUsingRfcommWithServiceRecord("Nano Dash", UUID.fromString(WatchProtocol.SERVICE_UUID))
                server = srv
                val socket = srv.accept()          // blocks until a paired device connects, or stop() closes the server
                srv.close()
                client = socket
                serve(socket)
            } catch (e: IOException) {
                if (running) Thread.sleep(2_000)   // Bluetooth off or busy: try again shortly instead of spinning
            } catch (e: SecurityException) {
                return                             // the Bluetooth permission was withdrawn
            } finally {
                runCatching { client?.close() }
                client = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun serve(socket: BluetoothSocket) {
        val allowed = allowedAddress()
        if (allowed != null && socket.remoteDevice.address != allowed) return      // not the user's watch: closed at once
        val input = BoundedLineReader(BufferedInputStream(socket.inputStream))
        val out = socket.outputStream
        fun send(line: String) { out.write((line + "\n").toByteArray()); out.flush() }
        if (!WatchProtocol.isHello(input.readLine() ?: return)) { send(WatchProtocol.err("version")); return }
        send(WatchProtocol.OK)
        while (running) {
            val line = input.readLine() ?: return
            val command = WatchProtocol.parseCommand(line) ?: continue             // unknown lines are ignored
            send(handler.onCommand(command))
        }
    }
}
