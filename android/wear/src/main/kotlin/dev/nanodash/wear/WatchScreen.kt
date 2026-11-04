package dev.nanodash.wear

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nanodash.core.watch.CameraState
import dev.nanodash.core.watch.GpsState
import dev.nanodash.core.watch.RideState
import dev.nanodash.core.watch.WatchCommand
import dev.nanodash.core.watch.WatchFormat
import dev.nanodash.core.watch.WatchState
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The phone app's tokens on true black, as in docs/WEAR_UI.md. */
private object Tokens {
    val ground = Color(0xFF000000)
    val surface = Color(0xFF232325)
    val text = Color(0xFFEDEDED)
    val textSecondary = Color(0xFFA1A1A6)
    val textMuted = Color(0xFF85858B)
    val accent = Color(0xFFFF7A1A)
    val onAccent = Color(0xFF000000)
    val ok = Color(0xFF4CC38A)
    val warn = Color(0xFFE3B341)
    val bad = Color(0xFFF2575C)
}

private const val HOLD_MS = 1_200f
private const val SAVED_MS = 2_000L
private const val REARM_MS = 3_000L
private const val START_TIMEOUT_MS = 8_000L

/** A font size that follows the system font scale up to 1.3x, then stops, so the big timer and hero words never outgrow a round screen. */
@Composable
private fun cappedSp(base: Float, maxScale: Float = 1.3f): TextUnit {
    val scale = LocalDensity.current.fontScale
    return (base * minOf(scale, maxScale) / scale).sp
}

/** Picks the screen from the link and a few local facts, and turns taps into commands. */
@Composable
fun WatchApp(link: PhoneLink, haptics: Haptics) {
    val model by link.model.collectAsState()
    var starting by remember { mutableStateOf(false) }
    var refusal by remember { mutableStateOf<String?>(null) }
    var savedUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var previous by remember { mutableStateOf<RideState?>(null) }

    LaunchedEffect(Unit) {
        link.refusals.collect { reason ->
            refusal = reason
            starting = false
            haptics.refused()
        }
    }
    LaunchedEffect(model) {
        val ride = (model as? LinkModel.Connected)?.state?.ride
        if (ride == RideState.Recording) {
            if (starting) haptics.started()
            starting = false
            refusal = null
        }
        if (previous == RideState.Saving && ride == RideState.Idle) savedUntil = System.currentTimeMillis() + SAVED_MS
        previous = ride
    }
    LaunchedEffect(starting) {
        if (starting) {
            delay(START_TIMEOUT_MS)
            if (starting) { starting = false; refusal = "unknown"; haptics.refused() }
        }
    }
    LaunchedEffect(savedUntil) {
        while (System.currentTimeMillis() < savedUntil) { now = System.currentTimeMillis(); delay(250) }
        now = System.currentTimeMillis()
    }

    val state = (model as? LinkModel.Connected)?.state
    Box(Modifier.fillMaxSize().background(Tokens.ground)) {
        when {
            state == null -> Message("No phone", "Open Nano Dash on the phone", "Try again") { link.retryNow() }
            refusal != null -> {
                val (title, hint) = refusalText(refusal!!)
                Message(title, hint, "Try again", isError = true) { refusal = null }
            }
            starting -> Spinner(null, "Starting ride")
            state.ride == RideState.Saving -> Spinner("Saving ride", "Saving ride")
            now < savedUntil -> Hero("Saved", LiveRegionMode.Assertive)
            state.ride == RideState.Recording -> Recording(state, onTick = haptics::holdTick) {
                haptics.stopped()
                link.send(WatchCommand.Stop)
            }
            else -> Ready(state.camera) {
                starting = true
                refusal = null
                link.send(WatchCommand.Start)
            }
        }
    }
}

private fun refusalText(reason: String): Pair<String, String> = when (reason) {
    "gps-off" -> "Location is off" to "Switch it on in the phone's settings"
    "no-permission" -> "No location access" to "Allow it in the Nano Dash app"
    "busy" -> "Still saving" to "Try again in a moment"
    "version" -> "Update both apps" to ""
    else -> "Something went wrong" to "Try again"
}

@Composable
private fun Ready(camera: CameraState, onStart: () -> Unit) {
    Box(Modifier.fillMaxSize().semantics { paneTitle = "Ready" }) {
        Box(Modifier.align(Alignment.TopCenter).padding(top = 30.dp).padding(horizontal = 36.dp)) {
            when (camera) {
                CameraState.Ready, CameraState.Recording -> StatusDot(Tokens.ok, "Camera ready")
                CameraState.Problem -> StatusDot(Tokens.bad, "Camera problem")
                CameraState.None -> StatusDot(Tokens.textMuted, "No camera")
            }
        }
        Box(Modifier.align(Alignment.Center)) { Hero("Ready", LiveRegionMode.Polite) }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)) {
            PillButton("Start ride", Tokens.accent, Tokens.onAccent, onClick = onStart)
        }
    }
}

@Composable
private fun Recording(state: WatchState, onTick: () -> Unit, onStop: () -> Unit) {
    Box(Modifier.fillMaxSize().semantics { paneTitle = "Recording" }) {
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 26.dp).padding(horizontal = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            when (state.gps) {
                GpsState.Fix -> StatusDot(Tokens.ok, "GPS fix")
                GpsState.Searching -> StatusDot(Tokens.warn, "GPS searching")
                GpsState.Off -> StatusDot(Tokens.textMuted, "GPS off")
            }
            when (state.camera) {
                CameraState.Recording -> StatusDot(Tokens.ok, "Camera recording")
                CameraState.Ready -> StatusDot(Tokens.ok, "Camera ready")
                CameraState.Problem -> StatusDot(Tokens.bad, "Camera problem")
                CameraState.None -> StatusDot(Tokens.textMuted, "No camera")
            }
        }
        // One spoken node (merged) instead of the description plus the digits, and a heading as the landmark.
        // No live region here: it would read out every second.
        Box(Modifier.align(Alignment.Center).semantics(mergeDescendants = true) {
            heading()
            contentDescription = "Riding for ${WatchFormat.spoken(state.elapsedSeconds)}"
        }) {
            Text(
                WatchFormat.elapsed(state.elapsedSeconds),
                color = Tokens.accent,
                style = TextStyle(fontSize = cappedSp(46f), fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
            )
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)) { HoldToStop(onTick, onStop) }
    }
}

/**
 * A title, a hint and one button. The text area scrolls, so large fonts never push it under the button.
 * Errors are announced at once (assertive); title and hint are one merged node so they are read together.
 */
@Composable
internal fun Message(title: String, hint: String, button: String, isError: Boolean = false, primary: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxSize().semantics { paneTitle = title }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 30.dp).padding(top = 24.dp)
                    .semantics(mergeDescendants = true) {
                        liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    title, color = if (isError) Tokens.bad else Tokens.text, textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                    style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium),
                )
                if (hint.isNotEmpty()) Text(hint, color = Tokens.textMuted, textAlign = TextAlign.Center, style = TextStyle(fontSize = 14.sp))
            }
        }
        Box(Modifier.padding(bottom = 14.dp)) {
            if (primary) PillButton(button, Tokens.accent, Tokens.onAccent, onClick = onClick)
            else PillButton(button, Tokens.surface, Tokens.text, outlined = true, onClick = onClick)
        }
    }
}

/** A big word. "Saved" vanishes after 2 s, so it is an assertive live region: it is spoken even if focus is elsewhere. */
@Composable
private fun Hero(text: String, live: LiveRegionMode) {
    Box(Modifier.fillMaxSize().semantics { paneTitle = text }, contentAlignment = Alignment.Center) {
        Text(
            text, color = Tokens.text, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp).semantics { heading(); liveRegion = live },
            style = TextStyle(fontSize = cappedSp(38f), fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun StatusDot(color: Color, word: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(word, color = Tokens.textSecondary, modifier = Modifier.weight(1f, fill = false), style = TextStyle(fontSize = 13.sp))
    }
}

/** True when the user turned animations off (animator duration scale 0): the spinner is then a still arc. */
@Composable
private fun animationsOff(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/** The caption (if any) is visible; TalkBack gets `spoken`, announced politely, and the drawing itself is hidden from it. */
@Composable
private fun Spinner(caption: String?, spoken: String) {
    val still = animationsOff()
    val turn = if (still) 0f else rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_000, easing = LinearEasing), RepeatMode.Restart), label = "turn",
    ).value
    Box(
        Modifier.fillMaxSize().clearAndSetSemantics {
            paneTitle = spoken
            contentDescription = spoken
            liveRegion = LiveRegionMode.Polite
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Canvas(Modifier.size(44.dp)) {
                drawArc(
                    Tokens.accent, startAngle = if (still) -90f else turn, sweepAngle = if (still) 270f else 100f, useCenter = false,
                    topLeft = Offset(4f, 4f), size = Size(size.width - 8f, size.height - 8f), style = Stroke(width = 6f, cap = StrokeCap.Round),
                )
            }
            if (caption != null) Text(caption, color = Tokens.textSecondary, textAlign = TextAlign.Center, style = TextStyle(fontSize = 14.sp))
        }
    }
}

/** A wide pill at the bottom edge, at least 64 dp tall (it grows with large fonts), so a gloved thumb cannot miss it. */
@Composable
internal fun PillButton(label: String, container: Color, content: Color, outlined: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(32.dp)
    Box(
        Modifier.fillMaxWidth(0.78f).heightIn(min = 64.dp).clip(shape).background(container)
            // The secondary pill gets a 1 dp muted outline (5.73:1 on black) so it is visible against the ground.
            .then(if (outlined) Modifier.border(1.dp, Tokens.textMuted, shape) else Modifier)
            .semantics { role = Role.Button }.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = content, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
        )
    }
}

/**
 * Stopping is deliberate: the button fills from the left while it is held, and the ride stops only when it is full.
 * Letting go early cancels with no message.
 *
 * Screen readers (decision of the owner): a single TalkBack activation must NOT stop the ride. The spoken name is the visible
 * text ("Hold to stop"); the click action is a deliberate no-op whose label says where the real action is; only the Actions menu
 * entry "Stop ride" (long click) or the real press-and-hold stops it, so the accidental-stop guard stays.
 *
 * Contrast: the label is drawn twice, light on the dark pill and black on the red fill, and the red layer is clipped to the progress,
 * so every part of the text always sits on its own background (light on #232325 13.4:1, black on #F2575C 6.29:1).
 */
@Composable
private fun HoldToStop(onTick: () -> Unit, onStop: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    var fired by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // If the phone did not stop (link lost), the button re-arms after a moment instead of staying dead.
    LaunchedEffect(fired) {
        if (fired) { delay(REARM_MS); fired = false; progress = 0f }
    }
    val shape = RoundedCornerShape(32.dp)
    val labelStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium)
    Box(
        Modifier.fillMaxWidth(0.78f).heightIn(min = 64.dp).clip(shape).background(Tokens.surface)
            .border(1.dp, Tokens.textMuted, shape)
            .semantics {
                role = Role.Button
                contentDescription = "Hold to stop"
                stateDescription = "Hold for about a second"
                onClick(label = "Use the Stop ride action to stop") { true }
                onLongClick(label = "Stop ride") { if (!fired) { fired = true; onStop() }; true }
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    onTick()
                    val fill = scope.launch {
                        val start = System.nanoTime()
                        while (progress < 1f) {
                            progress = (((System.nanoTime() - start) / 1_000_000f) / HOLD_MS).coerceAtMost(1f)
                            if (progress >= 1f && !fired) { fired = true; onStop() }
                            delay(16)
                        }
                    }
                    tryAwaitRelease()
                    fill.cancel()
                    if (!fired) progress = 0f
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        // Both label copies are hidden from semantics: the Box above carries the spoken name.
        Text(
            "Hold to stop", color = Tokens.text, textAlign = TextAlign.Center, style = labelStyle,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp).clearAndSetSemantics { },
        )
        Box(
            Modifier.matchParentSize()
                .drawWithContent {
                    if (progress > 0f) clipRect(left = 0f, top = 0f, right = size.width * progress, bottom = size.height) {
                        this@drawWithContent.drawContent()
                    }
                }
                .background(Tokens.bad)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Hold to stop", color = Tokens.onAccent, textAlign = TextAlign.Center, style = labelStyle,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
