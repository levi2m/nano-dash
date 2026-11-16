package dev.nanodash.logger.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dev.nanodash.core.ride.SpeedUnit
import dev.nanodash.core.watch.WatchFormat
import dev.nanodash.logger.CameraUi
import dev.nanodash.logger.RideUiState
import dev.nanodash.logger.ui.components.ButtonKind
import dev.nanodash.logger.ui.components.Hairline
import dev.nanodash.logger.ui.components.Health
import dev.nanodash.logger.ui.components.NanoButton
import dev.nanodash.logger.ui.components.Notice
import dev.nanodash.logger.ui.components.Panel
import dev.nanodash.logger.ui.components.StatusRow
import dev.nanodash.logger.ui.theme.NanoText
import dev.nanodash.logger.ui.theme.Space
import dev.nanodash.logger.ui.theme.nanoColors

@Composable
internal fun RidePanel(ui: RideUiState, cam: CameraUi, now: Long, gps: GpsReadiness, unit: SpeedUnit) {
    val c = nanoColors
    Panel {
        if (ui.recording) {
            // The speed is the one bright figure. Without a fix there is nothing honest to show at that size, so say so quietly.
            // The height is a minimum so the figure can grow with the phone's font size instead of being cut off.
            Box(Modifier.fillMaxWidth().heightIn(min = 96.dp), contentAlignment = Alignment.CenterStart) {
                val mps = ui.lastSpeedMps
                if (mps != null) {
                    val speedText = "%.0f".format(mps * unit.speedPerMps)
                    val speakSpeed = Modifier.semantics(mergeDescendants = true) { contentDescription = "Speed $speedText ${unit.speedLabel}" }
                    if (LocalDensity.current.fontScale > 1.3f) {
                        // At large font sizes the unit goes under the figure so the 88 sp number never runs off the card.
                        Column(speakSpeed) {
                            Text(speedText, style = NanoText.hero, color = c.accentText)
                            Text(unit.speedLabel, style = NanoText.heading, color = c.textSecondary)
                        }
                    } else {
                        Row(speakSpeed) {
                            Text(speedText, Modifier.alignByBaseline(), style = NanoText.hero, color = c.accentText)
                            Text(unit.speedLabel, Modifier.padding(start = Space.s).alignByBaseline(), style = NanoText.heading, color = c.textSecondary)
                        }
                    }
                } else {
                    Text("Waiting for GPS", style = NanoText.title, color = c.textSecondary)
                }
            }
            val s = ((now - ui.startedUtcMillis) / 1000).coerceAtLeast(0)
            Text(
                "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60),
                Modifier.semantics { contentDescription = "Recording for ${WatchFormat.spoken(s)}" },
                style = NanoText.figure, color = c.text,
            )
        } else {
            Text("Ready", Modifier.semantics { heading() }, style = NanoText.title, color = c.text)
            Notice("Start a track to log GPS and motion. The camera starts with it when it is connected.")
        }
        Hairline()
        Column {
        val (gpsValue, gpsHealth) = when {
            !ui.recording -> when (gps) {
                GpsReadiness.Ready -> "Location is on" to Health.Ok
                GpsReadiness.LocationOff -> "Location is off, switch it on" to Health.Wait
                GpsReadiness.NeedsPermission -> "Asked for when you start" to Health.Off
            }
            ui.fixes == 0 -> (if (ui.satellitesVisible != null) "Searching, ${ui.satellitesVisible} in view" else "Searching for satellites") to Health.Wait
            else -> {
                val acc = ui.lastAccuracyM
                // Weak is said in words, so the amber light is not the only difference between a good fix and a poor one.
                if (acc != null && acc <= 15f) "%.0f m, ${ui.satellitesUsed ?: 0} satellites".format(acc) to Health.Ok
                else (acc?.let { "%.0f m, weak".format(it) } ?: "No accuracy, weak") to Health.Wait
            }
        }
        StatusRow("GPS", gpsValue, gpsHealth)
        val many = cam.rows.size > 1
        val (camValue, camHealth) = when {
            cam.recording == true -> ((if (many) "${cam.rows.count { it.recording == true }} of ${cam.rows.size} recording" else "Recording") +
                (if (cam.error) ", with a problem" else "")) to
                (if (cam.error) Health.Wait else Health.Ok)
            cam.error -> "Problem" to Health.Bad
            cam.busy -> "Connecting" to Health.Wait
            cam.ready -> (if (many) "${cam.rows.size} ready" else "Ready") to Health.Ok
            else -> "Not connected" to Health.Off
        }
        StatusRow(if (many) "Cameras" else "Camera", camValue, camHealth)
        val (motionValue, motionHealth) = when {
            !ui.recording -> "Starts with the track" to Health.Off
            ui.imuSamples == 0L -> "Waiting for the first sample" to Health.Wait
            else -> "Logging, ${"%,d".format(ui.imuSamples)} samples" to Health.Ok
        }
        StatusRow("Motion sensors", motionValue, motionHealth)
        }
    }
}

@Composable
internal fun ActionArea(ui: RideUiState, onStart: () -> Unit, onStop: () -> Unit, onOpenSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        if (ui.recording) {
            NanoButton("Stop tracking", onStop, Modifier.fillMaxWidth(), ButtonKind.Danger, height = 72.dp)
        } else {
            NanoButton("Start tracking", onStart, Modifier.fillMaxWidth(), ButtonKind.Primary, height = 72.dp, enabled = !ui.saving)
        }
        // The camera panel below says when no camera is ready, so only the result of the last action is repeated here.
        ui.cameraNote?.let { Notice(it.text, health = if (it.problem) Health.Wait else Health.Off) }
        ui.message?.let { Notice(it.text, health = if (it.problem) Health.Wait else Health.Off) }
        if (ui.message?.needsSettings == true) {
            NanoButton("Open app settings", onOpenSettings, Modifier.fillMaxWidth(), ButtonKind.Secondary)
        }
    }
}

/** Whether the phone can give a GPS fix right now, so the rider hears about a switched-off location before the ride, not after. */
internal enum class GpsReadiness { Ready, LocationOff, NeedsPermission }

internal fun gpsReadiness(context: Context): GpsReadiness = when {
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED -> GpsReadiness.NeedsPermission
    !LocationManagerCompat.isLocationEnabled(context.getSystemService(Context.LOCATION_SERVICE) as LocationManager) -> GpsReadiness.LocationOff
    else -> GpsReadiness.Ready
}
