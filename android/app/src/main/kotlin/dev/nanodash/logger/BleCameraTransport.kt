package dev.nanodash.logger

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import dev.nanodash.core.camera.CameraTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

private fun uuid16(short: Int) = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))

/** The Osmo Nano's control channel: service FFF0, notifications on FFF4 and FFF5, writes to FFF5 (confirmed on a real Nano). */
private val SERVICE = uuid16(0xFFF0)
private val NOTIFY_CHAR = uuid16(0xFFF4)
private val WRITE_CHAR = uuid16(0xFFF5)
private val CCCD = uuid16(0x2902)

/** DJI's Bluetooth company id (advert bytes AA 08) and the Nano's model id 0x0019 (bytes 19 00), as seen in the M0 scan. */
private const val DJI_COMPANY_ID = 0x08AA

object NanoScanner {
    /** Scans for [timeoutMs] and returns the address of every Osmo Nano that advertised, in the order first seen. */
    @SuppressLint("MissingPermission")
    suspend fun scan(context: Context, timeoutMs: Long): List<String> {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return emptyList()
        val scanner = adapter.bluetoothLeScanner ?: return emptyList()
        val seen = LinkedHashSet<String>()
        var failure: Int? = null
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                synchronized(seen) { seen.add(result.device.address) }
            }

            override fun onScanFailed(errorCode: Int) { failure = errorCode }
        }
        val filter = ScanFilter.Builder()
            .setManufacturerData(DJI_COMPANY_ID, byteArrayOf(0x19, 0x00), byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
            .build()
        scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        try {
            delay(timeoutMs)
        } finally {
            scanner.stopScan(callback)
        }
        val found = synchronized(seen) { seen.toList() }
        failure?.let { if (found.isEmpty()) throw IOException("Bluetooth scan failed ($it)") }
        return found
    }
}

/** GATT connection to the camera, exposed as the session's [CameraTransport]. */
@SuppressLint("MissingPermission")
class BleCameraTransport(private val context: Context, val address: String) : CameraTransport {
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512)
    override val incoming: Flow<ByteArray> = _incoming

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val writeMutex = Mutex()
    private var writeDone: CompletableDeferred<Int>? = null
    private var step: CompletableDeferred<Int>? = null
    @Volatile var closed = false
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                closed = true
                val err = IOException("Camera disconnected (status $status)")
                step?.completeExceptionally(err)
                writeDone?.completeExceptionally(err)
                g.close()
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                step?.complete(status)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { step?.complete(status) }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { step?.complete(status) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { step?.complete(status) }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone?.complete(status)
            step?.complete(status)
        }

        // API 33 and later
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            _incoming.tryEmit(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) @Suppress("DEPRECATION") c.value?.let { _incoming.tryEmit(it.copyOf()) }
        }
    }

    private suspend fun awaitStep(what: String, timeoutMs: Long, start: () -> Boolean): Int {
        val d = CompletableDeferred<Int>()
        step = d
        if (!start()) throw IOException("$what could not be started")
        return withTimeoutOrNull(timeoutMs) { d.await() } ?: throw IOException("$what timed out")
    }

    /** Connects and prepares the characteristics. Throws [IOException] with a readable reason if any step fails. */
    suspend fun open() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: throw IOException("This phone has no Bluetooth.")
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        var g: BluetoothGatt? = null
        awaitStep("Connecting to the camera", 15_000) {
            g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            g != null
        }
        val gatt = g ?: throw IOException("Could not connect to the camera.")
        this.gatt = gatt

        // Osmosis: use MTU 500; 517 makes the camera stop answering. A failed MTU request is not fatal.
        runCatching { awaitStep("MTU request", 5_000) { gatt.requestMtu(500) } }

        if (awaitStep("Service discovery", 10_000) { gatt.discoverServices() } != BluetoothGatt.GATT_SUCCESS) {
            throw IOException("Could not read the camera's services.")
        }
        val service = gatt.getService(SERVICE) ?: throw IOException("This device does not expose the DJI control service (FFF0).")
        val notify = service.getCharacteristic(NOTIFY_CHAR) ?: throw IOException("Missing characteristic FFF4.")
        val write = service.getCharacteristic(WRITE_CHAR) ?: throw IOException("Missing characteristic FFF5.")
        writeChar = write

        for (c in listOf(notify, write)) {
            gatt.setCharacteristicNotification(c, true)
            val cccd = c.getDescriptor(CCCD) ?: throw IOException("Characteristic has no notification descriptor.")
            awaitStep("Enabling notifications", 5_000) {
                if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; gatt.writeDescriptor(cccd) }
                }
            }
        }

        // Per Osmosis: write 01 00 to FFF4 (with response) about 200 ms before any traffic on FFF5.
        delay(200)
        runCatching {
            awaitStep("Opening the control channel", 3_000) {
                if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeCharacteristic(notify, byteArrayOf(0x01, 0x00), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run { notify.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; notify.value = byteArrayOf(0x01, 0x00); gatt.writeCharacteristic(notify) }
                }
            }
        }
        delay(300)
    }

    override suspend fun send(frame: ByteArray) = writeMutex.withLock {
        if (closed) throw IOException("Camera disconnected")
        val g = gatt ?: throw IOException("Not connected")
        val c = writeChar ?: throw IOException("Not connected")
        val done = CompletableDeferred<Int>()
        writeDone = done
        val accepted = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; c.value = frame; g.writeCharacteristic(c) }
        }
        if (!accepted) throw IOException("The phone rejected the Bluetooth write")
        val status = withTimeoutOrNull(2_000) { done.await() } ?: throw IOException("Bluetooth write timed out")
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("Bluetooth write failed ($status)")
    }

    override fun close() {
        closed = true
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
    }
}
