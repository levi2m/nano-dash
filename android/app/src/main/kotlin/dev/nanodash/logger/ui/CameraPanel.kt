package dev.nanodash.logger.ui

import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.nanodash.core.camera.cameraLabel
import dev.nanodash.logger.CameraUi
import dev.nanodash.logger.ui.components.BusyBar
import dev.nanodash.logger.ui.components.ButtonKind
import dev.nanodash.logger.ui.components.Health
import dev.nanodash.logger.ui.components.NanoButton
import dev.nanodash.logger.ui.components.Notice
import dev.nanodash.logger.ui.components.Panel
import dev.nanodash.logger.ui.components.SectionLabel
import dev.nanodash.logger.ui.components.StatusRow
import dev.nanodash.logger.ui.components.caption
import dev.nanodash.logger.ui.theme.NanoText
import dev.nanodash.logger.ui.theme.Space
import dev.nanodash.logger.ui.theme.nanoColors

@Composable
internal fun CameraPanel(
    cam: CameraUi,
    onConnect: () -> Unit,
    onChoose: () -> Unit,
    onToggle: (String) -> Unit,
    onConnectSelected: () -> Unit,
    onClosePicker: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val c = nanoColors
    // Once every camera is ready with its clock set there is nothing to decide, so the panel shrinks to one line and Rides move up.
    var open by remember { mutableStateOf(false) }
    val settled = !cam.choosing && !cam.error && !cam.busy && cam.ready && cam.rows.isNotEmpty() && cam.rows.all { it.clockSet }
    LaunchedEffect(settled) { if (!settled) open = false }
    if (settled && !open) {
        Panel(bottomPadding = Space.l) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    SectionLabel(if (cam.rows.size > 1) "Cameras" else "Camera")
                    Text(
                        if (cam.rows.size > 1) "All ready, clocks set to this phone" else "Ready, clock set to this phone",
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = NanoText.body, color = c.textSecondary,
                    )
                }
                NanoButton("Manage", { open = true }, kind = ButtonKind.Quiet)
            }
        }
        return
    }
    Panel {
        SectionLabel(if (cam.rows.size > 1 || cam.choosing) "Cameras" else "Camera")
        if (cam.choosing) {
            CameraPicker(cam, onToggle, onConnectSelected, onChoose, onClosePicker)
            return@Panel
        }
        val approve = cam.awaitingApproval
        val shown = when {
            cam.ready -> if (cam.rows.size > 1) "All ready to start and stop with the track" else "Ready to start and stop with the track"
            cam.rows.isEmpty() && !cam.error -> "No camera is connected, so a track now would have no video. Turn the camera on, make sure wireless connection is enabled on it, close Mimo on the phone, then connect."
            else -> caption(cam.text)
        }
        Text(
            shown,
            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            style = if (approve) NanoText.heading else NanoText.body,
            color = when {
                approve -> c.accentText
                cam.error -> c.bad
                else -> c.textSecondary
            },
        )
        if (cam.rows.size > 1) {
            Column {
                cam.rows.forEach { row ->
                    StatusRow(row.label, row.text, when {
                        row.error -> Health.Bad
                        row.busy -> Health.Wait
                        row.ready -> Health.Ok
                        else -> Health.Off
                    })
                }
            }
        }
        // The app sets each camera's clock to this phone's on connect, so a phone with the wrong time gives the camera the wrong time too.
        val context = LocalContext.current
        if (Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 1) == 0) {
            Notice("Automatic date and time is off. The camera copies this phone's clock, so a wrong clock makes the .fit miss the video. Turn it on in the phone's settings.", health = Health.Wait)
        }
        if (cam.ready) {
            if (cam.rows.all { it.clockSet }) Notice("Camera clock set to this phone's time", health = Health.Off)
            else Notice("The camera did not take the phone's clock, so its time may be off and the overlay may be a second or two out", health = Health.Wait)
        }
        if (cam.busy) BusyBar(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            NanoButton(
                if (cam.rows.isNotEmpty()) "Reconnect" else "Connect camera", onConnect, Modifier.weight(1f), ButtonKind.Secondary,
                enabled = !cam.busy,
            )
            if (cam.rows.isNotEmpty()) NanoButton("Disconnect", onDisconnect, kind = ButtonKind.Quiet)
        }
        NanoButton("Choose cameras", onChoose, Modifier.fillMaxWidth(), ButtonKind.Quiet, enabled = !cam.busy)
        if (settled) NanoButton("Done", { open = false }, Modifier.fillMaxWidth(), ButtonKind.Quiet)
    }
}

/** Lists the Nanos found nearby so one or several can be ticked; each Nano is told apart by the end of its address. */
@Composable
private fun CameraPicker(cam: CameraUi, onToggle: (String) -> Unit, onConnectSelected: () -> Unit, onSearchAgain: () -> Unit, onCancel: () -> Unit) {
    val c = nanoColors
    when {
        cam.scanning -> {
            Text("Looking for cameras", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = NanoText.body, color = c.textSecondary)
            BusyBar(Modifier.fillMaxWidth())
        }
        cam.found.isEmpty() -> {
            Text(
                if (cam.error) caption(cam.text) else "No Osmo Nano found. Is it on, with wireless connection enabled, and Mimo closed?",
                style = NanoText.body, color = if (cam.error) c.bad else c.textSecondary,
            )
            NanoButton("Search again", onSearchAgain, Modifier.fillMaxWidth(), ButtonKind.Secondary)
        }
        else -> {
            Text("Tick every camera that should record with the track.", style = NanoText.body, color = c.textSecondary)
            Column {
                cam.found.forEach { address ->
                    val checked = address in cam.selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle(address) }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        Checkbox(checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = c.fill, checkmarkColor = c.onFill, uncheckedColor = c.textMuted))
                        Text(cameraLabel(address), style = NanoText.body, color = c.text)
                    }
                }
            }
            val n = cam.selected.size
            NanoButton(
                if (n == 1) "Connect 1 camera" else "Connect $n cameras", onConnectSelected, Modifier.fillMaxWidth(), ButtonKind.Primary,
                enabled = n > 0,
            )
            NanoButton("Search again", onSearchAgain, Modifier.fillMaxWidth(), ButtonKind.Secondary)
        }
    }
    NanoButton("Cancel", onCancel, Modifier.fillMaxWidth(), ButtonKind.Quiet)
}
