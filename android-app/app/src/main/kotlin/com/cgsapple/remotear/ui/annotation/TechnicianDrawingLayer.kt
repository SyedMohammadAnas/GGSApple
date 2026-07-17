package com.cgsapple.remotear.ui.annotation

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.cgsapple.remotear.annotation.AnnotationTool
import com.cgsapple.remotear.annotation.DEFAULT_THICKNESS
import com.cgsapple.remotear.annotation.RenderedStroke
import com.cgsapple.remotear.annotation.parseComposeColor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Transparent touch layer — must sit **above** video and overlay (highest zIndex) so
 * TextureView/AndroidView does not steal gestures.
 */
@Composable
fun DrawingTouchLayer(
    enabled: Boolean,
    activeTool: AnnotationTool,
    activeColor: String,
    onViewSizeChanged: (Float, Float) -> Unit,
    onDraftChanged: (RenderedStroke?) -> Unit,
    onStrokeStreaming: (RenderedStroke) -> Unit,
    onStrokeCommitted: (RenderedStroke) -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewWidth by remember { mutableStateOf(1f) }
    var viewHeight by remember { mutableStateOf(1f) }
    var strokePoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var strokeStart by remember { mutableStateOf<Offset?>(null) }
    var activeStrokeId by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                viewWidth = size.width.toFloat().coerceAtLeast(1f)
                viewHeight = size.height.toFloat().coerceAtLeast(1f)
                onViewSizeChanged(viewWidth, viewHeight)
            }
            .pointerInput(enabled, activeTool, activeColor) {
                if (!enabled) {
                    return@pointerInput
                }

                detectDragGestures(
                    onDragStart = { offset ->
                        val strokeId = createStrokeId()
                        activeStrokeId = strokeId
                        strokeStart = offset
                        strokePoints = listOf(offset)
                        val draft = buildDraftStroke(
                            id = strokeId,
                            points = strokePoints,
                            tool = activeTool,
                            color = activeColor,
                        )
                        onDraftChanged(draft)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val point = change.position
                        val strokeId = activeStrokeId ?: return@detectDragGestures
                        strokePoints = when (activeTool) {
                            AnnotationTool.FREEHAND -> strokePoints + point
                            AnnotationTool.CIRCLE -> {
                                val start = strokeStart ?: point
                                makeCirclePoints(start, point)
                            }
                            AnnotationTool.ARROW -> {
                                val start = strokeStart ?: point
                                listOf(start, point)
                            }
                        }
                        val draft = buildDraftStroke(
                            id = strokeId,
                            points = strokePoints,
                            tool = activeTool,
                            color = activeColor,
                        )
                        onDraftChanged(draft)
                        if (draft != null) {
                            onStrokeStreaming(draft)
                        }
                    },
                    onDragEnd = {
                        val points = strokePoints
                        val strokeId = activeStrokeId
                        strokePoints = emptyList()
                        strokeStart = null
                        activeStrokeId = null
                        onDraftChanged(null)
                        if (points.size >= 2 && strokeId != null) {
                            onStrokeCommitted(
                                buildDraftStroke(
                                    id = strokeId,
                                    points = points,
                                    tool = activeTool,
                                    color = activeColor,
                                )!!,
                            )
                        }
                    },
                    onDragCancel = {
                        strokePoints = emptyList()
                        strokeStart = null
                        activeStrokeId = null
                        onDraftChanged(null)
                    },
                )
            },
    )
}

private fun buildDraftStroke(
    points: List<Offset>,
    tool: AnnotationTool,
    color: String,
    id: String = "draft",
): RenderedStroke? {
    if (points.size < 2) {
        return null
    }
    return RenderedStroke(
        id = id,
        tool = tool,
        color = parseComposeColor(color),
        points = points,
        thickness = DEFAULT_THICKNESS,
    )
}

private fun makeCirclePoints(center: Offset, edge: Offset, segments: Int = 32): List<Offset> {
    val radius = hypot((edge.x - center.x).toDouble(), (edge.y - center.y).toDouble()).toFloat()
    if (radius < 4f) {
        return listOf(center, edge)
    }
    return List(segments) { index ->
        val theta = (2f * PI.toFloat() * index) / segments
        Offset(
            x = center.x + radius * cos(theta),
            y = center.y + radius * sin(theta),
        )
    }
}

private fun createStrokeId(): String =
    "${System.currentTimeMillis()}-${Random.nextInt(1_000_000)}"
