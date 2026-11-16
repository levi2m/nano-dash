package dev.nanodash.logger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import dev.nanodash.logger.ui.components.NanoButton
import dev.nanodash.logger.ui.components.SectionLabel
import dev.nanodash.logger.ui.theme.NanoText
import dev.nanodash.logger.ui.theme.NanoTheme
import dev.nanodash.logger.ui.theme.Space
import dev.nanodash.logger.ui.theme.nanoColors

/**
 * Health Connect asks every app that can read heart rate for a screen that says why. It is opened by the system (Health Connect's
 * permission screen and settings), so it is an exported component; it shows text only and takes nothing from the intent.
 */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            NanoTheme {
                Column(
                    Modifier.fillMaxSize().background(nanoColors.bg).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Space.l),
                ) {
                    SectionLabel("Heart rate from Health Connect")
                    Text(
                        "When you tap \"Add health data\" on a track, Nano Dash reads the heart rate your watch recorded between the start and the end of that track " +
                            "and writes it into that track's .fit file, so DJI Mimo can show it with the clip.\n\n" +
                            "It reads heart rate only, and it never writes to Health Connect. The readings are saved in the track's folder on this phone as health.csv. " +
                            "Nano Dash has no internet permission, so nothing is sent anywhere unless you share the track yourself. " +
                            "Delete track removes them. You can take the permission back in Health Connect at any time.",
                        Modifier.padding(top = Space.s, bottom = Space.l),
                        style = NanoText.body,
                        color = nanoColors.textSecondary,
                    )
                    NanoButton("Close", { finish() })
                }
            }
        }
    }
}
