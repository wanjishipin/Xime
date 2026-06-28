package com.kingzcheung.xime.handwriting

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.sqrt

private const val MIN_WIDTH = 12f
private const val MAX_WIDTH = 36f
private const val MIN_SPEED = 0.3f
private const val MAX_SPEED = 3.0f

private fun computeWidth(speed: Float, lastWidth: Float): Float {
    val raw = when {
        speed >= MAX_SPEED -> MIN_WIDTH
        speed <= MIN_SPEED -> MAX_WIDTH
        else -> MAX_WIDTH - (speed / MAX_SPEED) * MAX_WIDTH
    }
    return raw * 0.35f + lastWidth * 0.65f
}

fun DrawScope.renderStrokes(
    strokes: List<List<StrokePoint>>,
    currentStroke: List<StrokePoint>,
    color: Color,
) {
    for (stroke in strokes) {
        if (stroke.size == 1) {
            drawCircle(color, radius = MAX_WIDTH / 2, center = Offset(stroke[0].x, stroke[0].y))
        } else {
            var lastWidth = MAX_WIDTH
            for (j in 1 until stroke.size) {
                val p0 = stroke[j - 1]
                val p1 = stroke[j]
                val dx = p1.x - p0.x
                val dy = p1.y - p0.y
                val dist = sqrt(dx * dx + dy * dy)
                val dt = (p1.timeMs - p0.timeMs).coerceAtLeast(1L).toFloat() / 1000f
                val speed = dist / dt / 100f
                val w = computeWidth(speed, lastWidth)
                lastWidth = w
                drawLine(color, start = Offset(p0.x, p0.y), end = Offset(p1.x, p1.y), strokeWidth = w, cap = StrokeCap.Round)
            }
        }
    }
    if (currentStroke.size >= 2) {
        var lastWidth = MAX_WIDTH
        for (j in 1 until currentStroke.size) {
            val p0 = currentStroke[j - 1]
            val p1 = currentStroke[j]
            val dx = p1.x - p0.x
            val dy = p1.y - p0.y
            val dist = sqrt(dx * dx + dy * dy)
            val dt = (p1.timeMs - p0.timeMs).coerceAtLeast(1L).toFloat() / 1000f
            val speed = dist / dt / 100f
            val w = computeWidth(speed, lastWidth)
            lastWidth = w
            drawLine(color, start = Offset(p0.x, p0.y), end = Offset(p1.x, p1.y), strokeWidth = w, cap = StrokeCap.Round)
        }
    }
}
