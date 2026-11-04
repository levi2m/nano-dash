package dev.nanodash.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/** The watch remote for Nano Dash. The link to the phone only runs while the screen is on, to spare the battery. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Root() } }
    }
}

@Composable
private fun Root() {
    val context = LocalContext.current
    val link = remember { PhoneLink(context.applicationContext) }
    val haptics = remember { Haptics(context) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    if (!granted) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 30.dp).padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Allow Bluetooth", color = Color(0xFFEDEDED), textAlign = TextAlign.Center, style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium))
                Text("To reach your phone", color = Color(0xFF85858B), textAlign = TextAlign.Center, style = TextStyle(fontSize = 14.sp))
            }
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).fillMaxWidth(0.78f).height(64.dp)
                    .clip(RoundedCornerShape(32.dp)).background(Color(0xFFFF7A1A))
                    .clickable { permission.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                contentAlignment = Alignment.Center,
            ) { Text("Allow", color = Color.Black, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium)) }
        }
        return
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { link.run() }
    }
    WatchApp(link, haptics)
}
