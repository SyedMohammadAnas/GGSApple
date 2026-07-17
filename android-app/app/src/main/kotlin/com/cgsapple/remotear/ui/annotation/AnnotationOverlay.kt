package com.cgsapple.remotear.ui.annotation

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import com.cgsapple.remotear.annotation.AnnotationTool
import com.cgsapple.remotear.annotation.PointerOverlay
import com.cgsapple.remotear.annotation.RenderedStroke
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

@Composable
fun AnnotationOverlay(
    strokes: List<RenderedStroke>,
    draftStroke: RenderedStroke? = null,
    pointerOverlay: PointerOverlay = PointerOverlay(null, false),
    modifier: Modifier = Modifier,
    showDebugProbe: Boolean = false,
) {
    val allStrokes = if (draftStroke != null) strokes + draftStroke else strokes

    LaunchedEffect(strokes.size, draftStroke?.id, showDebugProbe) {
        Log.i(
            TAG,
            "compose strokes=${strokes.size} draft=${draftStroke != null} debugProbe=$showDebugProbe",
        )
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        if (showDebugProbe) {
            drawDebugProbe()
        }
        allStrokes.forEach { stroke ->
            drawStroke(stroke)
        }
        if (pointerOverlay.active) {
            pointerOverlay.position?.let { pos ->
                drawCircle(
                    color = Color(0xFFFF4757),
                    radius = 14f,
                    center = pos,
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.85f),
                    radius = 6f,
                    center = pos,
                )
            }
        }
    }
}

/** Hardcoded shape to verify Canvas renders above LiveKit video (ignore networking). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDebugProbe() {
    val cx = size.width * 0.5f
    val cy = size.height * 0.12f
    val radius = min(size.width, size.height) * 0.08f
    drawCircle(
        color = Color.Red,
        radius = radius,
        center = Offset(cx, cy),
        style = Stroke(width = 6f),
    )
    drawLine(
        color = Color.Yellow,
        start = Offset(cx - radius * 1.5f, cy),
        end = Offset(cx + radius * 1.5f, cy),
        strokeWidth = 4f,
        cap = StrokeCap.Round,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStroke(stroke: RenderedStroke) {
    if (stroke.points.size < 2) {
        return
    }

    when (stroke.tool) {
        AnnotationTool.FREEHAND, AnnotationTool.CIRCLE -> {
            val path = Path().apply {
                moveTo(stroke.points.first().x, stroke.points.first().y)
                stroke.points.drop(1).forEach { point ->
                    lineTo(point.x, point.y)
                }
                if (stroke.tool == AnnotationTool.CIRCLE) {
                    close()
                }
            }
            drawPath(
                path = path,
                color = stroke.color,
                style = Stroke(
                    width = stroke.thickness,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
        AnnotationTool.ARROW -> {
            val start = stroke.points.first()
            val end = stroke.points.last()
            drawLine(
                color = stroke.color,
                start = start,
                end = end,
                strokeWidth = stroke.thickness,
                cap = StrokeCap.Round,
            )
            drawArrowHead(start, end, stroke.color, stroke.thickness)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrowHead(
    start: Offset,
    end: Offset,
    color: Color,
    thickness: Float,
) {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val length = hypot(dx.toDouble(), dy.toDouble()).toFloat()
    if (length < 4f) {
        return
    }

    val angle = atan2(dy, dx)
    val headLength = (thickness * 3f).coerceAtLeast(12f)
    val headAngle = Math.toRadians(28.0).toFloat()

    val left = Offset(
        x = end.x - headLength * cos(angle - headAngle),
        y = end.y - headLength * sin(angle - headAngle),
    )
    val right = Offset(
        x = end.x - headLength * cos(angle + headAngle),
        y = end.y - headLength * sin(angle + headAngle),
    )

    val path = Path().apply {
        moveTo(end.x, end.y)
        lineTo(left.x, left.y)
        lineTo(right.x, right.y)
        close()
    }
    drawPath(path, color)
}

private const val TAG = "AnnotationOverlay"
