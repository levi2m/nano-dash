package dev.nanodash.logger.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.nanodash.logger.ui.theme.nanoColors
import kotlin.math.cos
import kotlin.math.max

/** The product mark: the same hollow-start, solid-end route used for the app icon, drawn small beside the name. */
@Composable
fun RouteMark(modifier: Modifier = Modifier) {
    val c = nanoColors
    Canvas(modifier) {
        val u = size.width / 40f
        val path = Path().apply {
            moveTo(9 * u, 29 * u); lineTo(16 * u, 21 * u); lineTo(22 * u, 25 * u); lineTo(32 * u, 12 * u)
        }
        drawPath(path, c.accent, style = Stroke(width = 2.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(c.bg, 2.6f * u, Offset(9 * u, 29 * u))
        drawCircle(c.accent, 2.6f * u, Offset(9 * u, 29 * u), style = Stroke(width = 1.6f * u))
        drawCircle(c.accent, 3.2f * u, Offset(32 * u, 12 * u))
    }
}

/**
 * The product's signature detail: a ride drawn as the line it took, from its GPS fixes. No map, no labels, just the route
 * scaled to fit, a hollow dot where it started and a solid accent dot where it ended. With no fixes it draws a dashed line,
 * so a missing track reads as missing rather than as an empty box.
 */
@Composable
fun RouteLine(points: List<Pair<Double, Double>>, modifier: Modifier = Modifier) {
    val c = nanoColors
    Canvas(modifier) {
        val pad = 12.dp.toPx()
        if (points.size < 2) {
            val y = size.height / 2
            drawLine(
                c.textMuted, Offset(pad, y), Offset(size.width - pad, y), strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx())),
            )
            return@Canvas
        }
        val lat0 = points.map { it.first }.average()
        val k = cos(Math.toRadians(lat0))                       // shrink longitude so the shape is not stretched
        val xs = points.map { it.second * k }
        val ys = points.map { it.first }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val w = max(maxX - minX, 1e-9); val h = max(maxY - minY, 1e-9)
        val scale = minOf((size.width - 2 * pad) / w, (size.height - 2 * pad) / h)
        val ox = (size.width - w * scale) / 2
        val oy = (size.height - h * scale) / 2
        fun at(i: Int) = Offset((ox + (xs[i] - minX) * scale).toFloat(), (oy + (maxY - ys[i]) * scale).toFloat())

        val path = Path().apply {
            moveTo(at(0).x, at(0).y)
            for (i in 1 until points.size) lineTo(at(i).x, at(i).y)
        }
        drawPath(path, c.textSecondary, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(c.card, 5.dp.toPx(), at(0))
        drawCircle(c.textSecondary, 5.dp.toPx(), at(0), style = Stroke(width = 2.dp.toPx()))
        drawCircle(c.accent, 5.dp.toPx(), at(points.lastIndex))
    }
}
