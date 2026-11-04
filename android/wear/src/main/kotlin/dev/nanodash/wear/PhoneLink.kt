package dev.nanodash.wear

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import dev.nanodash.core.watch.BoundedLineReader
import dev.nanodash.core.watch.WatchCommand
import dev.nanodash.core.watch.WatchProtocol
import dev.nanodash.core.watch.WatchState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.IOException
import java.util.UUID

/** What the watch knows about the phone: out of reach, or its latest state. */
sealed interface LinkModel {
    data object NoPhone : LinkModel
    data class Connected(val state: WatchState) : LinkModel
}

/**
 * The watch's end of the Bluetooth link to the phone app. One coroutine owns the connection: it sends the latest
 * command, asks for the phone's state about every 1.5 seconds, and reconnects when the link drops. A dead link
 * can never hang it, because each exchange has a watchdog that closes the socket.
 */
class PhoneLink(private val context: Context) {
    private val _model = MutableStateFlow<LinkModel>(LinkModel.NoPhone)
    val model: StateFlow<LinkModel> = _model

    /** Why the phone refused a command (`gps-off`, `no-permission`, `busy`, `version`), for the screen to explain. */
    private val _refusals = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val refusals: SharedFlow<String> = _refusals

    private val commands = Channel<WatchCommand>(Channel.CONFLATED)

    fun send(command: WatchCommand) { commands.trySend(command) }

    private val retry = Channel<Unit>(Channel.CONFLATED)

    /** Skips the wait before the next connection attempt. */
    fun retryNow() { retry.trySend(Unit) }

    /** Keeps the link up until the caller's coroutine is cancelled, which also closes the socket. */
    suspend fun run() {
        while (true) {
            try {
                session()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // out of range, Bluetooth off, the phone app closed: show "No phone" and try again shortly
            }
            _model.value = LinkModel.NoPhone
            withTimeoutOrNull(RETRY_MS) { retry.receive() }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun session() = withContext(Dispatchers.IO) {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: throw IOException("no Bluetooth")
        val phone = adapter.bondedDevices.firstOrNull { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }
            ?: throw IOException("no paired phone")
        phone.createRfcommSocketToServiceRecord(UUID.fromString(WatchProtocol.SERVICE_UUID)).use { socket ->
            socket.connect()
            val out = socket.outputStream
            val input = BoundedLineReader(BufferedInputStream(socket.inputStream))
            val scope: CoroutineScope = this

            /** One line out, one line back, with a watchdog that closes the socket if the phone goes quiet. */
            fun ask(line: String): String {
                val watchdog = scope.launch { delay(EXCHANGE_TIMEOUT_MS); runCatching { socket.close() } }
                try {
                    out.write((line + "\n").toByteArray())
                    out.flush()
                    return input.readLine() ?: throw IOException("the phone closed the link")
                } finally {
                    watchdog.cancel()
                }
            }

            if (ask(WatchProtocol.HELLO) != WatchProtocol.OK) throw IOException("the phone does not speak this version")
            while (isActive) {
                val command = commands.tryReceive().getOrNull()
                if (command != null && command != WatchCommand.Status) {
                    val reply = ask(command.name.uppercase())
                    if (reply.startsWith("ERR")) _refusals.tryEmit(reply.removePrefix("ERR").trim())
                }
                WatchProtocol.decode(ask("STATUS"))?.let { _model.value = LinkModel.Connected(it) }
                delay(POLL_MS)
            }
        }
    }

    private companion object {
        const val POLL_MS = 1_500L
        const val RETRY_MS = 3_000L
        const val EXCHANGE_TIMEOUT_MS = 5_000L
    }
}
