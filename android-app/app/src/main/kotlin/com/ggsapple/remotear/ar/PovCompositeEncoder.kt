package com.ggsapple.remotear.ar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.PixelCopy
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ggsapple.remotear.annotation.AnnotationTool
import com.ggsapple.remotear.annotation.PointerOverlay
import com.ggsapple.remotear.annotation.RenderedStroke
import livekit.org.webrtc.JavaI420Buffer
import livekit.org.webrtc.VideoFrame
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import androidx.compose.ui.graphics.toArgb

/**
 * Customer POV composite — same contract as iOS `ARCompositeFrameEncoder`.
 *
 * iOS snapshots ARView (camera + RealityKit marks) into a fixed 540x960 BGRA
 * buffer with rotation 0. Android PixelCopies the AR GLSurfaceView, paints the
 * Compose overlay strokes onto that bitmap, then aspect-fills into 540x960 I420
 * so the expert web sees the phone view instead of the raw sensor image.
 */
object PovCompositeEncoder {
    const val TARGET_WIDTH = 540
    const val TARGET_HEIGHT = 960
    const val FRAME_INTERVAL_MS = 66L

    private val snapshotInFlight = AtomicBoolean(false)
    private val loggedFirstFrame = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val strokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun capture(
        surfaceView: GLSurfaceView,
        strokes: List<RenderedStroke>,
        draftStroke: RenderedStroke?,
        pointerOverlay: PointerOverlay,
        onFrame: (VideoFrame) -> Unit,
    ) {
        val viewWidth = surfaceView.width
        val viewHeight = surfaceView.height
        if (viewWidth <= 1 || viewHeight <= 1) {
            return
        }
        if (!surfaceView.holder.surface.isValid) {
            return
        }
        if (!snapshotInFlight.compareAndSet(false, true)) {
            return
        }

        val source =
            try {
                Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
            } catch (error: Exception) {
                snapshotInFlight.set(false)
                Log.w(TAG, "POV source bitmap alloc failed", error)
                return
            }

        PixelCopy.request(
            surfaceView,
            source,
            { result ->
                try {
                    if (result != PixelCopy.SUCCESS) {
                        Log.w(TAG, "PixelCopy failed result=$result")
                        return@request
                    }
                    paintOverlays(source, strokes, draftStroke, pointerOverlay)
                    val framed = aspectFillToTarget(source) ?: return@request
                    try {
                        val videoFrame = bitmapToVideoFrame(framed) ?: return@request
                        if (loggedFirstFrame.compareAndSet(false, true)) {
                            Log.i(
                                TAG,
                                "first POV frame ${framed.width}x${framed.height} from ${source.width}x${source.height}",
                            )
                        }
                        onFrame(videoFrame)
                        videoFrame.release()
                    } finally {
                        if (framed !== source) {
                            framed.recycle()
                        }
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "POV encode failed", error)
                } finally {
                    source.recycle()
                    snapshotInFlight.set(false)
                }
            },
            mainHandler,
        )
    }

    private fun paintOverlays(
        bitmap: Bitmap,
        strokes: List<RenderedStroke>,
        draftStroke: RenderedStroke?,
        pointerOverlay: PointerOverlay,
    ) {
        val canvas = Canvas(bitmap)
        strokes.forEach { drawStroke(canvas, it) }
        draftStroke?.let { drawStroke(canvas, it) }
        if (pointerOverlay.active) {
            pointerOverlay.position?.let { pos ->
                fillPaint.color = 0x66FF4757
                canvas.drawCircle(pos.x, pos.y, 18f, fillPaint)
                fillPaint.color = 0xFFFF4757.toInt()
                canvas.drawCircle(pos.x, pos.y, 6f, fillPaint)
            }
        }
    }

    private fun drawStroke(canvas: Canvas, stroke: RenderedStroke) {
        if (stroke.points.size < 2) {
            return
        }
        strokePaint.color = stroke.color.toArgb()
        strokePaint.strokeWidth = stroke.thickness.coerceAtLeast(3f) * 2.2f
        when (stroke.tool) {
            AnnotationTool.FREEHAND, AnnotationTool.CIRCLE -> {
                val path = Path()
                val first = stroke.points.first()
                path.moveTo(first.x, first.y)
                stroke.points.drop(1).forEach { point ->
                    path.lineTo(point.x, point.y)
                }
                if (stroke.tool == AnnotationTool.CIRCLE) {
                    path.close()
                }
                canvas.drawPath(path, strokePaint)
            }
            AnnotationTool.ARROW -> {
                val start = stroke.points.first()
                val end = stroke.points.last()
                canvas.drawLine(start.x, start.y, end.x, end.y, strokePaint)
                drawArrowHead(canvas, start.x, start.y, end.x, end.y, strokePaint.color, strokePaint.strokeWidth)
            }
        }
    }

    private fun drawArrowHead(
        canvas: Canvas,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        color: Int,
        thickness: Float,
    ) {
        val dx = endX - startX
        val dy = endY - startY
        val length = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (length < 4f) {
            return
        }
        val angle = atan2(dy, dx)
        val headLength = (thickness * 3f).coerceAtLeast(14f)
        val headAngle = Math.toRadians(28.0).toFloat()
        val path = Path()
        path.moveTo(endX, endY)
        path.lineTo(
            endX - headLength * cos(angle - headAngle),
            endY - headLength * sin(angle - headAngle),
        )
        path.lineTo(
            endX - headLength * cos(angle + headAngle),
            endY - headLength * sin(angle + headAngle),
        )
        path.close()
        fillPaint.color = color
        canvas.drawPath(path, fillPaint)
    }

    /**
     * Aspect-fill the phone snapshot into the stable 9:16 LiveKit canvas,
     * matching iOS `UIGraphicsImageRenderer` cover-scale.
     */
    private fun aspectFillToTarget(source: Bitmap): Bitmap? {
        val srcW = source.width.toFloat()
        val srcH = source.height.toFloat()
        if (srcW <= 1f || srcH <= 1f) {
            return null
        }
        val fill = max(TARGET_WIDTH / srcW, TARGET_HEIGHT / srcH)
        val drawW = srcW * fill
        val drawH = srcH * fill
        val dest = Bitmap.createBitmap(TARGET_WIDTH, TARGET_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dest)
        canvas.drawColor(Color.BLACK)
        val destRect =
            RectF(
                (TARGET_WIDTH - drawW) / 2f,
                (TARGET_HEIGHT - drawH) / 2f,
                (TARGET_WIDTH + drawW) / 2f,
                (TARGET_HEIGHT + drawH) / 2f,
            )
        canvas.drawBitmap(source, Rect(0, 0, source.width, source.height), destRect, null)
        return dest
    }

    private fun bitmapToVideoFrame(bitmap: Bitmap): VideoFrame? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) {
            return null
        }
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        val i420 = JavaI420Buffer.allocate(width, height)
        argbToI420(argb, width, height, i420)
        return VideoFrame(i420, 0, System.nanoTime())
    }

    private fun argbToI420(
        argb: IntArray,
        width: Int,
        height: Int,
        buffer: JavaI420Buffer,
    ) {
        val yPlane = buffer.dataY
        val uPlane = buffer.dataU
        val vPlane = buffer.dataV
        val strideY = buffer.strideY
        val strideU = buffer.strideU
        val strideV = buffer.strideV
        yPlane.clear()
        uPlane.clear()
        vPlane.clear()

        for (row in 0 until height) {
            val yRow = row * strideY
            val uvRow = (row / 2) * strideU
            for (col in 0 until width) {
                val pixel = argb[row * width + col]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yPlane.put(yRow + col, y.coerceIn(16, 235).toByte())
                if (row % 2 == 0 && col % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val uvIndex = uvRow + (col / 2)
                    uPlane.put(uvIndex, u.coerceIn(16, 240).toByte())
                    vPlane.put(uvIndex, v.coerceIn(16, 240).toByte())
                }
            }
        }
        yPlane.position(0)
        uPlane.position(0)
        vPlane.position(0)
    }

    private const val TAG = "PovCompositeEncoder"
}
