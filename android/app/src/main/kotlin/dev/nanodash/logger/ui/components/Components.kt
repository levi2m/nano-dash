package dev.nanodash.logger.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nanodash.logger.ui.theme.NanoText
import dev.nanodash.logger.ui.theme.Radius
import dev.nanodash.logger.ui.theme.Space
import dev.nanodash.logger.ui.theme.nanoColors

/** How a status row is doing. The colour is never the only signal: every row also says it in words. */
enum class Health { Ok, Wait, Bad, Off }

@Composable
private fun Health.color(): Color = when (this) {
    Health.Ok -> nanoColors.ok
    Health.Wait -> nanoColors.warn
    Health.Bad -> nanoColors.bad
    Health.Off -> nanoColors.textMuted
}

/** A flat surface with a hairline edge. No shadow: depth comes from the surface step. */
@Composable
fun Panel(modifier: Modifier = Modifier, bottomPadding: Dp = Space.xl, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(nanoColors.card)
            .border(BorderStroke(1.dp, nanoColors.hairline), RoundedCornerShape(Radius.card))
            .padding(start = Space.xl, top = Space.xl, end = Space.xl, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(Space.m),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = NanoText.heading, color = nanoColors.text)
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(nanoColors.hairline))
}

/** One line of a checklist: a small status light, a label, and the value in words on the right. */
@Composable
fun StatusRow(label: String, value: String, health: Health, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // One easing curve for the whole app; the light changes colour over 160 ms instead of snapping.
        val light by animateColorAsState(health.color(), tween(160, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)), label = "status light")
        Box(Modifier.size(12.dp).clip(CircleShape).background(light))
        Text(label, Modifier.padding(start = Space.m), style = NanoText.body, color = nanoColors.textSecondary)
        Text(
            value,
            Modifier.weight(1f).padding(start = Space.m),
            style = NanoText.bodyNumbers,
            color = nanoColors.text,
            textAlign = TextAlign.End,
        )
    }
}

/** Short captions end without a period; only text of several sentences keeps its full stops. */
fun caption(text: String): String =
    if (text.endsWith(".") && !text.contains(". ") && !text.contains("\n")) text.dropLast(1) else text

/** A plain sentence under a control, with a severity colour only when it matters. */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, health: Health = Health.Off) {
    val color = when (health) {
        Health.Off, Health.Ok -> nanoColors.textSecondary
        Health.Wait -> nanoColors.warn
        Health.Bad -> nanoColors.bad
    }
    Text(caption(text), modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, style = NanoText.body, color = color)
}

enum class ButtonKind { Primary, Danger, Secondary, Quiet }

/** Touch targets are at least 48 dp, the main action 72 dp so it can be pressed with a glove. */
@Composable
fun NanoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Secondary,
    enabled: Boolean = true,
    height: Dp = 52.dp,
) {
    val c = nanoColors
    val shape = RoundedCornerShape(Radius.control)
    val sized = modifier.heightIn(min = maxOf(height, 48.dp))
    when (kind) {
        ButtonKind.Primary -> Button(
            onClick, sized, enabled, shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent, disabledContainerColor = c.raised, disabledContentColor = c.textSecondary),
        ) { Text(text, style = NanoText.button) }

        ButtonKind.Danger -> Button(
            onClick, sized, enabled, shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = c.danger, contentColor = c.onDanger, disabledContainerColor = c.raised, disabledContentColor = c.textSecondary),
        ) { Text(text, style = NanoText.button) }

        ButtonKind.Secondary -> OutlinedButton(
            onClick, sized, enabled, shape = shape,
            border = BorderStroke(1.dp, if (enabled) c.textMuted else c.hairline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text, disabledContentColor = c.textMuted),
        ) { Text(text, style = NanoText.button) }

        ButtonKind.Quiet -> TextButton(
            onClick, sized, enabled, shape = shape,
            colors = ButtonDefaults.textButtonColors(contentColor = c.textSecondary, disabledContentColor = c.textMuted),
        ) { Text(text, style = NanoText.button) }
    }
}
