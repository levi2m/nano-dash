package dev.nanodash.logger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.nanodash.logger.ui.LoggerScreen
import dev.nanodash.logger.ui.theme.NanoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { NanoTheme { LoggerScreen() } }
    }
}
