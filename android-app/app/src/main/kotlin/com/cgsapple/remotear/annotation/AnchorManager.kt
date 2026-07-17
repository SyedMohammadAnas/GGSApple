package com.cgsapple.remotear.annotation

import android.opengl.Matrix
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnchorManager @Inject constructor() {
    private val pendingStrokes = ConcurrentLinkedQueue<AnnotationPayload>()
    private val pendingById = mutableMapOf<String, AnnotationPayload>()
    private val anchoredStrokes = mutableListOf<AnchoredStroke>()

    fun hasAnchoredStroke(id: String): Boolean =
        anchoredStrokes.any { it.id == id }

    fun queueStroke(payload: AnnotationPayload) {
        if (hasAnchoredStroke(payload.id)) {
            return
        }
        pendingById[payload.id] = payload
        pendingStrokes.add(payload)
    }

    fun processPending(session: Session, frame: Frame, viewWidth: Int, viewHeight: Int) {
        while (true) {
            val payload = pendingStrokes.poll() ?: break
            anchorStroke(session, frame, payload, viewWidth, viewHeight)
        }
    }

    fun clear() {
        pendingStrokes.clear()
        pendingById.clear()
        anchoredStrokes.forEach { stroke ->
            stroke.points.forEach { point ->
                point.anchor?.detach()
            }
        }
        anchoredStrokes.clear()
    }

    fun removeStroke(id: String) {
        pendingById.remove(id)
        pendingStrokes.removeIf { it.id == id }
        val stroke = anchoredStrokes.find { it.id == id } ?: return
        stroke.points.forEach { point -> point.anchor?.detach() }
        anchoredStrokes.removeAll { it.id == id }
        AnnotationPipelineLog.stage("ANCHOR", "removed stroke=$id")
    }

    fun getAnchoredStrokes(): List<AnchoredStroke> = anchoredStrokes

    private fun anchorStroke(
        session: Session,
        frame: Frame,
        payload: AnnotationPayload,
        viewWidth: Int,
        viewHeight: Int,
    ) {
        if (payload.points.isEmpty()) {
            return
        }

        val step = maxOf(1, payload.points.size / MAX_ANCHORS_PER_STROKE)
        val anchoredPoints = mutableListOf<AnchoredPoint>()

        for (index in payload.points.indices step step) {
            val point = payload.points[index]
            val anchored = anchorPoint(session, frame, payload.id, point, viewWidth, viewHeight)
            anchoredPoints.add(anchored)
        }

        if (anchoredPoints.isEmpty()) {
            Log.w(TAG, "No anchors created for stroke ${payload.id}")
            return
        }

        anchoredStrokes.add(
            AnchoredStroke(
                id = payload.id,
                tool = AnnotationTool.fromRaw(payload.tool),
                color = payload.color,
                points = anchoredPoints,
            ),
        )
        AnnotationPipelineLog.stage(
            "ANCHOR",
            "created stroke=${payload.id} anchors=${anchoredPoints.size} total=${anchoredStrokes.size}",
        )
    }

    private fun anchorPoint(
        session: Session,
        frame: Frame,
        strokeId: String,
        point: NormalizedPoint,
        viewWidth: Int,
        viewHeight: Int,
    ): AnchoredPoint {
        val xPx = point.x * viewWidth
        val yPx = point.y * viewHeight
        val hits = frame.hitTest(xPx, yPx)

        val planeHit = hits.firstOrNull { hit ->
            val trackable = hit.trackable
            trackable is Plane &&
                trackable.trackingState == TrackingState.TRACKING &&
                trackable.isPoseInPolygon(hit.hitPose)
        }

        if (planeHit != null) {
            val anchor = planeHit.createAnchor()
            Log.i(TAG, "hitTest PLANE stroke=$strokeId px=($xPx,$yPx) norm=(${point.x},${point.y})")
            return AnchoredPoint(
                anchor = anchor,
                fallbackNorm = null,
            )
        }

        val featureHit = hits.firstOrNull { hit ->
            hit.trackable.trackingState == TrackingState.TRACKING
        }

        if (featureHit != null) {
            val anchor = featureHit.createAnchor()
            Log.i(TAG, "hitTest FEATURE stroke=$strokeId px=($xPx,$yPx) norm=(${point.x},${point.y})")
            return AnchoredPoint(
                anchor = anchor,
                fallbackNorm = null,
            )
        }

        val fallbackPose = createFallbackPose(frame, xPx, yPx)
        val anchor = session.createAnchor(fallbackPose)
        Log.w(TAG, "hitTest FALLBACK stroke=$strokeId px=($xPx,$yPx) norm=(${point.x},${point.y}) depth=${FALLBACK_DEPTH_METERS}m")
        return AnchoredPoint(
            anchor = anchor,
            fallbackNorm = point,
        )
    }

    private fun createFallbackPose(
        frame: Frame,
        xPx: Float,
        yPx: Float,
    ): Pose {
        val camera = frame.camera
        val rayOrigin = FloatArray(3)
        camera.pose.getTranslation(rayOrigin, 0)
        val rayDirection = camera.pose.getZAxis()

        val translation = FloatArray(3)
        translation[0] = rayOrigin[0] + rayDirection[0] * FALLBACK_DEPTH_METERS
        translation[1] = rayOrigin[1] + rayDirection[1] * FALLBACK_DEPTH_METERS
        translation[2] = rayOrigin[2] + rayDirection[2] * FALLBACK_DEPTH_METERS
        return Pose(translation, floatArrayOf(0f, 0f, 0f, 1f))
    }

    data class AnchoredStroke(
        val id: String,
        val tool: AnnotationTool,
        val color: String,
        val points: List<AnchoredPoint>,
    )

    data class AnchoredPoint(
        val anchor: Anchor?,
        val fallbackNorm: NormalizedPoint?,
    )

    companion object {
        private const val TAG = "AnchorManager"
        private const val MAX_ANCHORS_PER_STROKE = 30
        private const val FALLBACK_DEPTH_METERS = 0.5f
    }
}

@Singleton
class AnnotationProjector @Inject constructor() {
    fun projectStroke(
        stroke: AnchorManager.AnchoredStroke,
        frame: Frame,
        viewWidth: Int,
        viewHeight: Int,
    ): RenderedStroke? {
        val projected = stroke.points.mapNotNull { point ->
            projectPoint(point, frame, viewWidth, viewHeight)
        }
        if (projected.size < 2) {
            return null
        }
        return RenderedStroke(
            id = stroke.id,
            tool = stroke.tool,
            color = parseComposeColor(stroke.color),
            points = projected,
        )
    }

    private fun projectPoint(
        point: AnchorManager.AnchoredPoint,
        frame: Frame,
        viewWidth: Int,
        viewHeight: Int,
    ): Offset? {
        point.fallbackNorm?.let { norm ->
            return norm.toOffset(viewWidth.toFloat(), viewHeight.toFloat())
        }

        val anchor = point.anchor ?: return null
        if (anchor.trackingState != TrackingState.TRACKING) {
            return null
        }

        val worldPos = FloatArray(4)
        anchor.pose.getTranslation(worldPos, 0)
        worldPos[3] = 1f

        val viewMatrix = FloatArray(16)
        val projMatrix = FloatArray(16)
        frame.camera.getViewMatrix(viewMatrix, 0)
        frame.camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100f)

        val viewPos = FloatArray(4)
        Matrix.multiplyMV(viewPos, 0, viewMatrix, 0, worldPos, 0)

        val clipPos = FloatArray(4)
        Matrix.multiplyMV(clipPos, 0, projMatrix, 0, viewPos, 0)

        if (clipPos[3] <= 0f) {
            return null
        }

        val ndcX = clipPos[0] / clipPos[3]
        val ndcY = clipPos[1] / clipPos[3]
        if (ndcX < -1f || ndcX > 1f || ndcY < -1f || ndcY > 1f) {
            return null
        }

        val x = (ndcX + 1f) * 0.5f * viewWidth
        val y = (1f - ndcY) * 0.5f * viewHeight
        return Offset(x, y)
    }
}
