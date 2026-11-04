package dev.nanodash.wear

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.nanodash.core.watch.BoundedLineReader
import dev.nanodash.core.watch.WatchProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.util.UUID

/** Spike: proves the watch can open the Bluetooth link to the phone app and get a reply. The real screen comes after this works. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Spike() } }
    }
}

@Composable
private fun Spike() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("Tap to ask the phone") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        text = if (granted) "Allowed. Tap again." else "Bluetooth permission is needed."
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, textAlign = TextAlign.Center)
        Button(onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                text = "Asking..."
                scope.launch { text = withContext(Dispatchers.IO) { askPhone(context) } }
            }
        }) { Text("Ask phone") }
    }
}

@SuppressLint("MissingPermission")
private fun askPhone(context: Context): String = try {
    val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: return "No Bluetooth"
    val phone = adapter.bondedDevices.firstOrNull { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }
        ?: return "No paired phone found"
    adapter.cancelDiscovery()
    phone.createRfcommSocketToServiceRecord(UUID.fromString(WatchProtocol.SERVICE_UUID)).use { socket ->
        socket.connect()
        socket.outputStream.write("${WatchProtocol.HELLO}\nSTATUS\n".toByteArray())
        socket.outputStream.flush()
        val input = BoundedLineReader(BufferedInputStream(socket.inputStream))
        "Phone says:\n${input.readLine()}\n${input.readLine()}"
    }
} catch (e: Exception) {
    "Failed: ${e.javaClass.simpleName}: ${e.message}"
}
