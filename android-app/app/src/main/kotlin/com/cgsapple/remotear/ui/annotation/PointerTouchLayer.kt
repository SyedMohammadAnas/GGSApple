package com.cgsapple.remotear.ui.annotation

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.cgsapple.remotear.annotation.toVideoNormalizedPoints

/**
 * Laser pointer — sends normalized video coords while finger is down.
 * [detectDragGestures] fires onDragStart for taps, so pointer works without dragging.
 */
@Composable
fun PointerTouchLayer(
    enabled: Boolean,
    onViewSizeChanged: (Float, Float) -> Unit,
    onPointerEvent: (normalizedX: Float, normalizedY: Float, active: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewWidth by remember { mutableStateOf(1f) }
    var viewHeight by remember { mutableStateOf(1f) }
    var localDot by remember { mutableStateOf<Offset?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                viewWidth = size.width.toFloat().coerceAtLeast(1f)
                viewHeight = size.height.toFloat().coerceAtLeast(1f)
                onViewSizeChanged(viewWidth, viewHeight)
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput

                detectDragGestures(
                    onDragStart = { offset ->
                        localDot = offset
                        offset.toVideoNorm(viewWidth, viewHeight)?.let { norm ->
                            onPointerEvent(norm.x, norm.y, true)
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        localDot = change.position
                        change.position.toVideoNorm(viewWidth, viewHeight)?.let { norm ->
                            onPointerEvent(norm.x, norm.y, true)
                        }
                    },
                    onDragEnd = {
                        localDot = null
                        onPointerEvent(0f, 0f, false)
                    },
                    onDragCancel = {
                        localDot = null
                        onPointerEvent(0f, 0f, false)
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            localDot?.let { pos ->
                drawCircle(color = Color(0xFFFF4757), radius = 14f, center = pos)
                drawCircle(color = Color.White.copy(alpha = 0.85f), radius = 6f, center = pos)
            }
        }
    }
}

private fun Offset.toVideoNorm(viewWidth: Float, viewHeight: Float): com.cgsapple.remotear.annotation.NormalizedPoint? =
    listOf(this).toVideoNormalizedPoints(viewWidth, viewHeight).firstOrNull()
