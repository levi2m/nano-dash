package dev.nanodash.logger.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.core.net.toUri
import dev.nanodash.core.ride.SpeedUnit
import dev.nanodash.logger.BuildConfigInfo
import dev.nanodash.logger.ui.components.SectionLabel
import dev.nanodash.logger.ui.theme.NanoText
import dev.nanodash.logger.ui.theme.Radius
import dev.nanodash.logger.ui.theme.Space
import dev.nanodash.logger.ui.theme.nanoColors
import androidx.compose.ui.unit.dp

private const val RELEASES_URL = "https://github.com/levi2m/nano-dash/releases/latest"

private fun openReleases(context: Context) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, RELEASES_URL.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
internal fun SettingsDialog(
    unit: SpeedUnit,
    onUnit: (SpeedUnit) -> Unit,
    quickStart: Boolean,
    onToggleQuickStart: () -> Unit,
    watchName: String?,
    watchSeen: Boolean,
    onChooseWatch: () -> Unit,
    onWatchGuide: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = nanoColors
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text("Settings", style = NanoText.heading, color = c.text, modifier = Modifier.semantics { heading() }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionLabel("Units")
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Speed", style = NanoText.body, color = c.text)
                    Row(
                        Modifier.clip(RoundedCornerShape(Radius.control)).background(c.bg).selectableGroup()
                            .semantics { contentDescription = "Speed unit" },
                    ) {
                        SpeedUnit.entries.forEach { option ->
                            val selected = option == unit
                            Box(
                                Modifier.heightIn(min = 48.dp)
                                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onUnit(option) })
                                    .background(if (selected) c.fill else c.bg).padding(horizontal = Space.m),
                                contentAlignment = Alignment.Center,
                            ) {
                                // Bold as well as filled, so the choice does not rest on colour alone.
                                Text(
                                    option.speedLabel,
                                    style = NanoText.label.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
                                    color = if (selected) c.onFill else c.textSecondary,
                                )
                            }
                        }
                    }
                }
                SectionLabel("Watch remote", Modifier.padding(top = Space.m))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(quickStart, role = Role.Switch) { onToggleQuickStart() },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Quick start from the watch", style = NanoText.body, color = c.text)
                        Text("Adds one quiet notification", style = NanoText.label, color = c.textMuted)
                    }
                    Switch(quickStart, null, colors = SwitchDefaults.colors(checkedTrackColor = c.fill, checkedThumbColor = c.onFill))
                }
                if (quickStart) {
                    SettingRow("Watch", watchName ?: "None chosen", "Choose the watch", onChooseWatch)
                    SettingRow("Watch app", if (watchSeen) "Connected before" else "Not seen yet", "Show how to install the watch app", onWatchGuide)
                }
                // An F-Droid install is updated by F-Droid, so the GitHub link is hidden there.
                if (!BuildConfigInfo.fromFDroid(context)) {
                    SectionLabel("About", Modifier.padding(top = Space.m))
                    SettingRow("Check for updates", "Opens your browser", "Open the releases page in your browser") { openReleases(context) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", style = NanoText.button, color = c.text) } },
    )
}

/** A tappable settings row: the name on the left, its current value on the right. */
@Composable
private fun SettingRow(title: String, value: String, actionLabel: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = actionLabel, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = NanoText.body, color = nanoColors.text)
        Text(value, style = NanoText.label, color = nanoColors.textSecondary)
    }
}

@Composable
internal fun WatchGuideDialog(onDismiss: () -> Unit) {
    val c = nanoColors
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text("Put the watch app on your watch", style = NanoText.heading, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text("Wear OS only installs apps from a computer or the Play Store, so this phone cannot send it. Once, with a computer:", style = NanoText.body, color = c.textSecondary)
                Text("1. Download the watch APK (nano-dash-wear) from the releases page.", style = NanoText.body, color = c.textSecondary)
                Text("2. On the watch: Settings, About watch, Software, tap Software version 7 times; then Developer options, turn on Debug over Wi-Fi.", style = NanoText.body, color = c.textSecondary)
                Text("3. On the computer: adb connect <watch address>, then adb install nano-dash-wear-<version>.apk", style = NanoText.body, color = c.textSecondary)
                Text("Then open Nano Dash on the watch. This row changes to connected.", style = NanoText.body, color = c.textSecondary)
            }
        },
        confirmButton = { TextButton(onClick = { openReleases(context) }) { Text("Open releases page", style = NanoText.button, color = c.text) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", style = NanoText.button, color = c.text) } },
    )
}

@Composable
internal fun ChooseWatchDialog(onPick: (name: String, address: String) -> Unit, onDismiss: () -> Unit) {
    val c = nanoColors
    val context = LocalContext.current
    val devices = remember { pairedDevices(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text("Which is your watch?", style = NanoText.heading, color = c.text) },
        text = {
            if (devices.isEmpty()) {
                Text("No paired devices were found. Pair your watch with this phone first.", style = NanoText.body, color = c.textSecondary)
            } else {
                Column {
                    Text("Only this device can start and stop tracks.", style = NanoText.body, color = c.textSecondary)
                    devices.forEach { (name, address) ->
                        TextButton(
                            onClick = { onPick(name, address) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(name, style = NanoText.button, color = c.text) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = NanoText.button, color = c.text) } },
    )
}

/** The devices already paired with this phone, by name, for choosing which one is the watch. */
@SuppressLint("MissingPermission")
private fun pairedDevices(context: Context): List<Pair<String, String>> = runCatching {
    context.getSystemService(BluetoothManager::class.java).adapter.bondedDevices
        .map { (it.name ?: it.address) to it.address }.sortedBy { it.first.lowercase() }
}.getOrDefault(emptyList())

@Composable
internal fun DeleteTrackDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = nanoColors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text("Delete this track?", style = NanoText.heading, color = c.text) },
        text = { Text("Its GPS, motion and camera timing files are removed from this phone. Copies you already shared are not affected.", style = NanoText.body, color = c.textSecondary) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", style = NanoText.button, color = c.bad) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep", style = NanoText.button, color = c.text) } },
    )
}
