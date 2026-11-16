package dev.nanodash.logger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.nanodash.core.ride.RideSummary
import dev.nanodash.core.ride.SpeedUnit
import dev.nanodash.logger.ui.components.ButtonKind
import dev.nanodash.logger.ui.components.Health
import dev.nanodash.logger.ui.components.NanoButton
import dev.nanodash.logger.ui.components.Notice
import dev.nanodash.logger.ui.components.Panel
import dev.nanodash.logger.ui.components.RouteLine
import dev.nanodash.logger.ui.theme.NanoText
import dev.nanodash.logger.ui.theme.Radius
import dev.nanodash.logger.ui.theme.Space
import dev.nanodash.logger.ui.theme.nanoColors
import java.text.SimpleDateFormat
import java.util.Date

@Composable
internal fun EmptyRides() {
    Panel {
        Text("No tracks yet", Modifier.semantics { heading() }, style = NanoText.heading, color = nanoColors.text)
        Notice("1. Connect the camera if you have it with you.\n2. Start the track before you move off.\n3. Stop it when you arrive, then share the .fit file and import it in Mimo together with the clip.")
    }
}

@Composable
internal fun RideCard(ride: RideSummary, emphasise: Boolean, unit: SpeedUnit, onOpenRoute: () -> Unit, onShareFit: () -> Unit, onShareAll: () -> Unit, onRebuild: () -> Unit, onDelete: () -> Unit, onAddHeartRate: (() -> Unit)?, healthNote: String?) {
    val c = nanoColors
    val locale = LocalConfiguration.current.locales[0]
    // The quiet Delete button is 48 dp tall with its label centred, so the card needs less bottom padding than the others.
    Panel(bottomPadding = Space.s) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(SimpleDateFormat("EEE d MMM, HH:mm", locale).format(Date(ride.startUtcMillis)), Modifier.semantics { heading() }, style = NanoText.heading, color = c.text)
                Text("${ride.sizeKb} KB", style = NanoText.label, color = c.textMuted)
            }
            Text(ride.durationSeconds?.let(::clock) ?: "–", style = NanoText.bodyNumbers, color = c.textSecondary)
        }
        // The route is the product's signature; a ride without a track shows no empty box, only the reason.
        if (ride.route.size >= 2) {
            RouteLine(
                ride.route,
                Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(Radius.small)).background(c.raised)
                    .clickable(onClickLabel = "Open the route in a maps app", role = Role.Button, onClick = onOpenRoute)
                    .semantics { contentDescription = "Route of the track, drawn from ${ride.fixes} GPS fixes" },
            )
            Text("Tap the route to open it in a maps app", style = NanoText.label, color = c.textMuted)
        }
        Column {
            Fact("GPS fixes", ride.fixes.toString())
            ride.distanceM?.takeIf { it > 0 }?.let { Fact("Distance", "%.1f %s".format(it / unit.metersPerDistanceUnit, unit.distanceLabel)) }
            ride.topSpeedMps?.takeIf { it > 0 }?.let { Fact("Top speed", "%.0f %s".format(it * unit.speedPerMps, unit.speedLabel)) }
            // GPS is logged once a second, so a long wait means a tunnel or the phone pausing the app; 10 s keeps ordinary hiccups out.
            ride.longestGapSeconds?.takeIf { it >= 10 }?.let { Fact("Longest GPS gap", clock(it)) }
            ride.cameraClipSeconds.forEachIndexed { i, s ->
                Fact(if (ride.cameraClipSeconds.size == 1) "Camera clip" else "Camera ${i + 1} clip", "%.1f s".format(s))
            }
            if (ride.heartRateReadings > 0) {
                Fact("Heart rate readings", ride.heartRateReadings.toString())
            }
        }

        // Amber is spent on the newest ride only, so a list of old no-fix rides does not shout.
        val tone = if (emphasise) Health.Wait else Health.Off
        when {
            ride.hasFit -> Unit
            ride.fixes == 0 -> Notice("No GPS fix, so there is no .fit to import", health = tone)
            else -> Notice("Interrupted before the .fit was written. It can be rebuilt from the GPS log.", health = tone)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            when {
                ride.hasFit -> {
                    NanoButton(if (ride.fits.size > 1) "Share .fit files" else "Share .fit", onShareFit, Modifier.weight(1f), ButtonKind.Primary, height = 48.dp)
                    NanoButton("All files", onShareAll, Modifier.weight(1f), ButtonKind.Secondary, height = 48.dp)
                }
                ride.fixes > 0 -> {
                    NanoButton("Rebuild .fit", onRebuild, Modifier.weight(1f), ButtonKind.Primary, height = 48.dp)
                    NanoButton("All files", onShareAll, Modifier.weight(1f), ButtonKind.Secondary, height = 48.dp)
                }
                else -> NanoButton("Share raw files", onShareAll, Modifier.weight(1f), ButtonKind.Secondary, height = 48.dp)
            }
        }
        healthNote?.let { Notice(it) }
        if (onAddHeartRate != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                NanoButton(if (ride.heartRateReadings > 0) "Update health data" else "Add health data", onAddHeartRate, Modifier.weight(1f), ButtonKind.Secondary, height = 48.dp)
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            NanoButton("Delete track", onDelete, kind = ButtonKind.QuietDanger, height = 48.dp)
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = NanoText.body, color = nanoColors.textSecondary)
        Text(value, Modifier.weight(1f), style = NanoText.bodyNumbers, color = nanoColors.text, textAlign = TextAlign.End)
    }
}

/** "17 min 17 s" reads as a length, where "17:17" could be mistaken for a time of day. */
private fun clock(totalSeconds: Long): String = when {
    totalSeconds >= 3600 -> "%d h %02d min".format(totalSeconds / 3600, (totalSeconds / 60) % 60)
    totalSeconds >= 60 -> "%d min %02d s".format(totalSeconds / 60, totalSeconds % 60)
    else -> "$totalSeconds s"
}
