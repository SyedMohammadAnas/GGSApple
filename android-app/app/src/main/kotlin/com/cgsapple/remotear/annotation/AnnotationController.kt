package com.cgsapple.remotear.annotation

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cgsapple.remotear.di.ApplicationScope
import com.google.ar.core.Frame
import com.google.ar.core.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class AnnotationRole {
    TECHNICIAN,
    CUSTOMER,
}

@Singleton
class AnnotationController @Inject constructor(
    private val annotationChannel: AnnotationChannel,
    private val anchorManager: AnchorManager,
    private val projector: AnnotationProjector,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _overlayStrokes = MutableStateFlow<List<RenderedStroke>>(emptyList())
    val overlayStrokes: StateFlow<List<RenderedStroke>> = _overlayStrokes.asStateFlow()

    private val _draftStroke = MutableStateFlow<RenderedStroke?>(null)
    val draftStroke: StateFlow<RenderedStroke?> = _draftStroke.asStateFlow()

    private val _pointerOverlay = MutableStateFlow(PointerOverlay(position = null, active = false))
    val pointerOverlay: StateFlow<PointerOverlay> = _pointerOverlay.asStateFlow()

    private val _placedModels = MutableStateFlow<List<PlacedModelOverlay>>(emptyList())
    val placedModels: StateFlow<List<PlacedModelOverlay>> = _placedModels.asStateFlow()

    /** Technician: stroke IDs in commit order for undo. */
    private val strokeOrderStack = ArrayDeque<String>()

    private var sessionId: String? = null
    private var role: AnnotationRole? = null
    private var viewWidth: Int = 1
    private var viewHeight: Int = 1
    private var lastSyncMs: Long = 0L
    private var lastOverlayMs: Long = 0L

    val isRealtimeReady: Boolean
        get() = annotationChannel.isSubscribed

    private var subscribeJob: Job? = null
    private var syncJob: Job? = null
    private val lastSentSyncPoints = mutableMapOf<String, List<NormalizedPoint>>()
    private var lastTechnicianSyncOverlayMs: Long = 0L
    private var technicianOverlayRefreshPending = false
    private val technicianOverlayRefreshRunnable = Runnable {
        technicianOverlayRefreshPending = false
        lastTechnicianSyncOverlayMs = System.currentTimeMillis()
        refreshOverlay()
    }

    /** Technician: metadata from annotation events (tool/color). */
    private val strokeMetadata = mutableMapOf<String, StrokeMetadata>()

    /** Technician: optimistic display until annotation_sync replaces. */
    private val pendingStrokes = mutableMapOf<String, StoredStroke>()

    /** Technician: authoritative overlay strokes from annotation_sync (customer-origin). */
    private val syncStrokes = mutableMapOf<String, StoredStroke>()

    /** Technician: own strokes — optimistic until customer projects them back via annotation_sync. */
    private val technicianLocalStrokes = mutableMapOf<String, StoredStroke>()

    /** Customer: metadata + screen-fixed fallback until projection succeeds. */
    private val strokeCatalog = mutableMapOf<String, StoredStroke>()

    /** Customer: last stable projected frame per stroke (prevents flicker). */
    private val cachedProjectedStrokes = mutableMapOf<String, RenderedStroke>()

    /** Skip re-processing our own broadcast echoes. */
    private val locallyCommittedIds = mutableSetOf<String>()

    private val remoteAnchorJobs = mutableMapOf<String, Job>()
    private val pendingRemoteAnchorPayloads = mutableMapOf<String, AnnotationPayload>()
    private var lastTechnicianStreamMs: Long = 0L
    private var lastStreamPointCount: Int = 0
    private var lastPointerStreamMs: Long = 0L

    private data class StrokeMetadata(
        val tool: AnnotationTool,
        val color: androidx.compose.ui.graphics.Color,
    )

    fun start(sessionId: String, role: AnnotationRole) {
        this.sessionId = sessionId
        this.role = role

        AnnotationPipelineLog.stage("START", "sessionId=$sessionId role=$role")
        subscribeJob?.cancel()
        subscribeJob = annotationChannel.subscribeWithRetry(
            sessionId = sessionId,
            onAnnotation = { payload -> handleIncomingAnnotation(payload) },
            onAnnotationSync = { payload -> handleIncomingSync(payload) },
            onClear = { handleClear() },
            onPointer = { payload -> handleIncomingPointer(payload) },
            onClearSingle = { payload -> handleClearSingle(payload.id) },
            onPlaceModel = { payload -> handleIncomingPlaceModel(payload) },
        )
    }

    suspend fun stop() {
        subscribeJob?.cancel()
        subscribeJob = null
        annotationChannel.disconnect()
        clearAllState()
        sessionId = null
        role = null
    }

    fun updateViewSize(width: Int, height: Int) {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        if (w == viewWidth && h == viewHeight) {
            return
        }
        viewWidth = w
        viewHeight = h
        AnnotationPipelineLog.stage("VIEW", "size=${viewWidth}x$viewHeight role=$role")
        refreshOverlay()
    }

    fun processFrame(session: Session, frame: Frame) {
        if (role != AnnotationRole.CUSTOMER) {
            return
        }

        anchorManager.processPending(session, frame, viewWidth, viewHeight)

        val projected = anchorManager.getAnchoredStrokes().mapNotNull { stroke ->
            projector.projectStroke(stroke, frame, viewWidth, viewHeight)?.let { rendered ->
                enrichProjectedStroke(stroke.id, rendered)
            }
        }

        projected.forEach { stroke ->
            cachedProjectedStrokes[stroke.id] = stroke
        }

        val now = System.currentTimeMillis()
        if (now - lastSyncMs >= SYNC_INTERVAL_MS && projected.isNotEmpty()) {
            lastSyncMs = now
            syncProjectedStrokes(projected)
        }

        if (now - lastOverlayMs >= OVERLAY_INTERVAL_MS) {
            lastOverlayMs = now
            mainHandler.post {
                _overlayStrokes.value = buildCustomerOverlay(projected)
            }
        }
    }

    fun setDraftStroke(stroke: RenderedStroke?) {
        _draftStroke.value = stroke
    }

    /** In the technician 9:16 viewport, container-normalized == video wire coords. */
    private fun technicianWirePointsFromStroke(stroke: RenderedStroke): List<NormalizedPoint>? {
        val width = viewWidth.toFloat()
        val height = viewHeight.toFloat()
        val containerNorm = stroke.points.toNormalizedPoints(width, height)
        if (containerNorm.size < 2) {
            return null
        }
        return containerNorm
    }

    private fun storeTechnicianLocalStroke(stroke: RenderedStroke, wirePoints: List<NormalizedPoint>) {
        strokeMetadata[stroke.id] = StrokeMetadata(stroke.tool, stroke.color)
        technicianLocalStrokes[stroke.id] = StoredStroke(
            id = stroke.id,
            tool = stroke.tool,
            color = stroke.color,
            normalizedPoints = wirePoints,
            inVideoSpace = true,
        )
    }

    /** TeamViewer-style live stream: send partial stroke while technician finger is down. */
    fun streamTechnicianStroke(stroke: RenderedStroke) {
        if (role != AnnotationRole.TECHNICIAN || stroke.points.size < 2) {
            return
        }
        val sid = sessionId ?: return
        val now = System.currentTimeMillis()
        if (now - lastTechnicianStreamMs < STREAM_INTERVAL_MS &&
            stroke.points.size <= lastStreamPointCount
        ) {
            return
        }
        val wirePoints = technicianWirePointsFromStroke(stroke) ?: return
        lastTechnicianStreamMs = now
        lastStreamPointCount = stroke.points.size

        storeTechnicianLocalStroke(stroke, wirePoints)
        refreshOverlay()

        val payload = AnnotationPayload(
            id = stroke.id,
            tool = stroke.tool.name.lowercase(),
            color = composeColorToHex(stroke.color),
            points = wirePoints,
        )

        applicationScope.launch {
            runCatching {
                annotationChannel.sendAnnotation(sid, payload)
            }.onFailure { error ->
                Log.w(TAG, "streamTechnicianStroke failed id=${stroke.id}", error)
            }
        }
    }

    fun commitDraftStroke(stroke: RenderedStroke) {
        if (stroke.points.size < 2) {
            _draftStroke.value = null
            return
        }

        _draftStroke.value = null
        val sid = sessionId ?: return

        val width = viewWidth.toFloat()
        val height = viewHeight.toFloat()

        locallyCommittedIds.add(stroke.id)

        when (role) {
            AnnotationRole.CUSTOMER -> {
                val containerNorm = stroke.points.toNormalizedPoints(width, height)
                if (containerNorm.size < 2) {
                    return
                }
                AnnotationPipelineLog.coords("CUSTOMER_COMMIT", "containerNorm", containerNorm)

                val stored = StoredStroke(
                    id = stroke.id,
                    tool = stroke.tool,
                    color = stroke.color,
                    normalizedPoints = containerNorm,
                    inVideoSpace = false,
                )
                strokeCatalog[stroke.id] = stored
                strokeOrderStack.addLast(stroke.id)
                anchorManager.queueStroke(stored.toPayload())
                AnnotationPipelineLog.stage("ANCHOR", "queued stroke id=${stroke.id}")
                refreshOverlay()

                val wirePoints = containerNorm.toVideoNormalizedFromContainer(width, height)
                AnnotationPipelineLog.conversion(
                    "CUSTOMER_COMMIT",
                    "container",
                    "video",
                    containerNorm,
                    wirePoints,
                )

                applicationScope.launch {
                    runCatching {
                        annotationChannel.sendAnnotation(sid, stored.toWirePayload(wirePoints))
                        annotationChannel.sendAnnotationSync(
                            sid,
                            AnnotationSyncPayload(id = stroke.id, points = wirePoints),
                        )
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to send annotation ${stroke.id}", error)
                    }
                }
            }
            AnnotationRole.TECHNICIAN -> {
                lastStreamPointCount = 0
                val wirePoints = technicianWirePointsFromStroke(stroke) ?: return
                AnnotationPipelineLog.coords("TECH_COMMIT", "videoNorm(wire)", wirePoints)

                storeTechnicianLocalStroke(stroke, wirePoints)
                pendingStrokes.remove(stroke.id)
                strokeOrderStack.addLast(stroke.id)
                refreshOverlay()
                AnnotationPipelineLog.stage(
                    "TECH_COMMIT",
                    "local overlay id=${stroke.id} video-mapped",
                )

                val payload = AnnotationPayload(
                    id = stroke.id,
                    tool = stroke.tool.name.lowercase(),
                    color = composeColorToHex(stroke.color),
                    points = wirePoints,
                )

                applicationScope.launch {
                    runCatching {
                        annotationChannel.sendAnnotation(sid, payload)
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to send annotation ${stroke.id}", error)
                    }
                }
            }
            null -> Unit
        }
    }

    fun clearAll() {
        val sid = sessionId ?: return
        applicationScope.launch {
            annotationChannel.sendClear(sid)
            mainHandler.post { handleClear() }
        }
    }

    fun undoLastStroke() {
        val id = strokeOrderStack.removeLastOrNull() ?: return
        val sid = sessionId ?: return
        applicationScope.launch {
            annotationChannel.sendClearSingle(sid, ClearSinglePayload(id = id))
            mainHandler.post { handleClearSingle(id) }
        }
    }

    fun hasUndoableStroke(): Boolean = strokeOrderStack.isNotEmpty()

    fun placeModel(modelId: String, modelName: String, modelUrl: String) {
        if (role != AnnotationRole.TECHNICIAN) return
        val sid = sessionId ?: return
        val payload = PlaceModelPayload(
            modelId = modelId,
            modelName = modelName,
            modelUrl = modelUrl,
            x = 0.5f,
            y = 0.5f,
        )
        applicationScope.launch {
            annotationChannel.sendPlaceModel(sid, payload)
            mainHandler.post { handleIncomingPlaceModel(payload) }
        }
    }

    private fun handleIncomingPlaceModel(payload: PlaceModelPayload) {
        val overlay = PlacedModelOverlay(
            modelId = payload.modelId,
            modelName = payload.modelName,
            modelUrl = payload.modelUrl,
            x = payload.x,
            y = payload.y,
        )
        _placedModels.value = _placedModels.value.filter { it.modelId != payload.modelId } + overlay
    }

    /** Technician laser pointer — high-frequency while finger is down. */
    fun streamPointer(normalizedX: Float, normalizedY: Float, active: Boolean) {
        if (role != AnnotationRole.TECHNICIAN) return
        val sid = sessionId ?: return
        if (!active) {
            applicationScope.launch {
                runCatching {
                    annotationChannel.sendPointer(sid, PointerPayload(x = 0f, y = 0f, active = false))
                }
            }
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastPointerStreamMs < POINTER_STREAM_INTERVAL_MS) {
            return
        }
        lastPointerStreamMs = now
        val payload = PointerPayload(
            x = normalizedX.coerceIn(0f, 1f),
            y = normalizedY.coerceIn(0f, 1f),
            active = true,
        )
        applicationScope.launch {
            runCatching {
                annotationChannel.sendPointer(sid, payload)
            }.onFailure { error ->
                Log.w(TAG, "streamPointer failed", error)
            }
        }
    }

    private fun handleIncomingAnnotation(payload: AnnotationPayload) {
        if (payload.points.size < 2) {
            AnnotationPipelineLog.stage("RX_ANNOTATION", "ignored id=${payload.id} pts<2")
            return
        }

        if (locallyCommittedIds.contains(payload.id)) {
            AnnotationPipelineLog.stage("RX_ANNOTATION", "ignored local echo id=${payload.id}")
            return
        }

        when (role) {
            AnnotationRole.CUSTOMER -> {
                val width = viewWidth.toFloat()
                val height = viewHeight.toFloat()
                AnnotationPipelineLog.coords("RX_ANNOTATION", "wire.videoNorm", payload.points)

                val containerPoints = payload.points.toContainerNormalizedFromVideo(width, height)
                AnnotationPipelineLog.conversion(
                    "RX_ANNOTATION",
                    "video",
                    "container",
                    payload.points,
                    containerPoints,
                )

                if (containerPoints.size < 2) {
                    AnnotationPipelineLog.stage("RX_ANNOTATION", "ignored id=${payload.id} convert failed")
                    return
                }

                val containerPayload = payload.copy(points = containerPoints)
                val stored = containerPayload.toStoredStroke(inVideoSpace = false)
                strokeCatalog[payload.id] = stored
                applyCustomerOverlayImmediate()
                scheduleRemoteAnchor(containerPayload, wirePoints = payload.points)
                AnnotationPipelineLog.stage("RX_ANNOTATION", "preview id=${payload.id} pts=${containerPoints.size}")
            }
            AnnotationRole.TECHNICIAN -> {
                // Metadata only — overlay is driven exclusively by annotation_sync.
                strokeMetadata[payload.id] = StrokeMetadata(
                    tool = AnnotationTool.fromRaw(payload.tool),
                    color = parseComposeColor(payload.color),
                )
                AnnotationPipelineLog.stage(
                    "RX_ANNOTATION",
                    "metadata only id=${payload.id} — render awaits annotation_sync",
                )
            }
            null -> Unit
        }
    }

    private fun handleIncomingSync(payload: AnnotationSyncPayload) {
        if (payload.points.size < 2) {
            AnnotationPipelineLog.stage("RX_SYNC", "ignored id=${payload.id} pts<2")
            return
        }

        if (role != AnnotationRole.TECHNICIAN) {
            return
        }

        val existing = syncStrokes[payload.id]
        if (existing != null && !syncPointsChanged(existing.normalizedPoints, payload.points)) {
            return
        }

        mainHandler.post {
            syncStrokes[payload.id] = storedSyncStroke(payload)
            pendingStrokes.remove(payload.id)
            scheduleTechnicianOverlayRefresh()
            AnnotationPipelineLog.stage(
                "OVERLAY",
                "technician activeStrokes=${syncStrokes.size} id=${payload.id}",
            )
        }
    }

    private fun scheduleTechnicianOverlayRefresh() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastTechnicianSyncOverlayMs
        if (elapsed >= TECH_SYNC_OVERLAY_INTERVAL_MS) {
            mainHandler.removeCallbacks(technicianOverlayRefreshRunnable)
            technicianOverlayRefreshPending = false
            lastTechnicianSyncOverlayMs = now
            refreshOverlay()
            return
        }
        if (technicianOverlayRefreshPending) {
            return
        }
        technicianOverlayRefreshPending = true
        mainHandler.postDelayed(
            technicianOverlayRefreshRunnable,
            TECH_SYNC_OVERLAY_INTERVAL_MS - elapsed,
        )
    }

    private fun storedSyncStroke(payload: AnnotationSyncPayload): StoredStroke {
        val meta = strokeMetadata[payload.id]
            ?: StrokeMetadata(
                tool = AnnotationTool.FREEHAND,
                color = parseComposeColor(DEFAULT_COLOR),
            )
        return StoredStroke(
            id = payload.id,
            tool = meta.tool,
            color = meta.color,
            normalizedPoints = payload.points,
            inVideoSpace = true,
        )
    }

    private fun handleClear() {
        AnnotationPipelineLog.stage("CLEAR", "role=$role")
        clearAllState()
        refreshOverlay()
    }

    private fun handleClearSingle(id: String) {
        AnnotationPipelineLog.stage("CLEAR_SINGLE", "id=$id role=$role")
        strokeOrderStack.remove(id)
        strokeMetadata.remove(id)
        pendingStrokes.remove(id)
        syncStrokes.remove(id)
        technicianLocalStrokes.remove(id)
        strokeCatalog.remove(id)
        cachedProjectedStrokes.remove(id)
        locallyCommittedIds.remove(id)
        lastSentSyncPoints.remove(id)
        remoteAnchorJobs[id]?.cancel()
        remoteAnchorJobs.remove(id)
        pendingRemoteAnchorPayloads.remove(id)
        anchorManager.removeStroke(id)
        if (!(_pointerOverlay.value.active)) {
            _pointerOverlay.value = PointerOverlay(null, false)
        }
        refreshOverlay()
    }

    private fun handleIncomingPointer(payload: PointerPayload) {
        if (role != AnnotationRole.CUSTOMER) return
        if (!payload.active) {
            mainHandler.post {
                _pointerOverlay.value = PointerOverlay(null, false)
            }
            return
        }
        val width = viewWidth.toFloat()
        val height = viewHeight.toFloat()
        val containerPoint = payload.pointsToContainer(width, height)
        mainHandler.post {
            _pointerOverlay.value = PointerOverlay(position = containerPoint, active = true)
        }
    }

    private fun PointerPayload.pointsToContainer(viewWidth: Float, viewHeight: Float): androidx.compose.ui.geometry.Offset? {
        val wire = NormalizedPoint(x, y)
        val container = listOf(wire).toContainerNormalizedFromVideo(viewWidth, viewHeight)
        return container.firstOrNull()?.toOffset(viewWidth, viewHeight)
    }

    private fun clearAllState() {
        anchorManager.clear()
        strokeMetadata.clear()
        pendingStrokes.clear()
        syncStrokes.clear()
        technicianLocalStrokes.clear()
        strokeCatalog.clear()
        cachedProjectedStrokes.clear()
        locallyCommittedIds.clear()
        lastSentSyncPoints.clear()
        remoteAnchorJobs.values.forEach { it.cancel() }
        remoteAnchorJobs.clear()
        pendingRemoteAnchorPayloads.clear()
        lastTechnicianStreamMs = 0L
        lastStreamPointCount = 0
        strokeOrderStack.clear()
        _pointerOverlay.value = PointerOverlay(null, false)
        _placedModels.value = emptyList()
        mainHandler.removeCallbacks(technicianOverlayRefreshRunnable)
        technicianOverlayRefreshPending = false
        _draftStroke.value = null
    }

    private fun applyCustomerOverlayImmediate() {
        _overlayStrokes.value = buildCustomerOverlay(emptyList())
    }

    private fun scheduleRemoteAnchor(containerPayload: AnnotationPayload, wirePoints: List<NormalizedPoint>) {
        val id = containerPayload.id
        pendingRemoteAnchorPayloads[id] = containerPayload
        remoteAnchorJobs[id]?.cancel()
        remoteAnchorJobs[id] = applicationScope.launch {
            delay(REMOTE_ANCHOR_DEBOUNCE_MS)
            val latest = pendingRemoteAnchorPayloads.remove(id) ?: return@launch
            remoteAnchorJobs.remove(id)
            anchorManager.queueStroke(latest)
            AnnotationPipelineLog.stage("ANCHOR", "queued remote stroke id=$id (debounced)")
            mainHandler.post { applyCustomerOverlayImmediate() }
            val sid = sessionId ?: return@launch
            runCatching {
                annotationChannel.sendAnnotationSync(
                    sid,
                    AnnotationSyncPayload(id = id, points = wirePoints),
                )
            }.onFailure { error ->
                Log.w(TAG, "Immediate preview sync failed id=$id", error)
            }
        }
    }

    private fun finalizeRemoteAnchor(containerPayload: AnnotationPayload) {
        remoteAnchorJobs[containerPayload.id]?.cancel()
        remoteAnchorJobs.remove(containerPayload.id)
        pendingRemoteAnchorPayloads.remove(containerPayload.id)
        anchorManager.queueStroke(containerPayload)
        AnnotationPipelineLog.stage("ANCHOR", "queued remote stroke id=${containerPayload.id} (final)")
    }

    private fun refreshOverlay() {
        _overlayStrokes.value = when (role) {
            AnnotationRole.CUSTOMER -> buildCustomerOverlay(emptyList())
            AnnotationRole.TECHNICIAN -> buildTechnicianOverlay()
            null -> emptyList()
        }
    }

    private fun buildCustomerOverlay(liveProjected: List<RenderedStroke>): List<RenderedStroke> {
        val liveById = liveProjected.associateBy { it.id }
        val strokeIds = (strokeCatalog.keys + cachedProjectedStrokes.keys + liveById.keys).toSet()
        val width = viewWidth.toFloat()
        val height = viewHeight.toFloat()

        return strokeIds.mapNotNull { id ->
            if (anchorManager.hasAnchoredStroke(id)) {
                liveById[id]
                    ?: cachedProjectedStrokes[id]
                    ?: strokeCatalog[id]?.toRendered(width, height)
            } else {
                // TeamViewer-style: show wire-mapped 2D preview immediately until AR anchor lands.
                strokeCatalog[id]?.toRendered(width, height)
                    ?: liveById[id]
                    ?: cachedProjectedStrokes[id]
            }
        }
    }

    /** Technician overlay: customer sync (world-projected) + optimistic local until sync arrives. */
    private fun buildTechnicianOverlay(): List<RenderedStroke> {
        val width = viewWidth.toFloat()
        val height = viewHeight.toFloat()
        val merged = linkedMapOf<String, RenderedStroke>()

        pendingStrokes.values.forEach { stroke ->
            merged[stroke.id] = stroke.toRendered(width, height)
        }
        technicianLocalStrokes.values.forEach { stroke ->
            if (stroke.id !in syncStrokes) {
                merged[stroke.id] = stroke.toRendered(width, height)
            }
        }
        syncStrokes.values.forEach { stroke ->
            merged[stroke.id] = stroke.toRendered(width, height)
        }
        return merged.values.toList()
    }

    private fun enrichProjectedStroke(id: String, rendered: RenderedStroke): RenderedStroke {
        val catalog = strokeCatalog[id] ?: return rendered
        return rendered.copy(color = catalog.color, tool = catalog.tool)
    }

    private fun syncProjectedStrokes(projected: List<RenderedStroke>) {
        val sid = sessionId ?: return
        val width = viewWidth.toFloat()
        val height = viewHeight.toFloat()

        if (syncJob?.isActive == true) {
            return
        }

        syncJob = applicationScope.launch {
            runCatching {
                projected.forEach { stroke ->
                    if (stroke.points.size < 2) {
                        return@forEach
                    }
                    val videoPoints = stroke.points.mapNotNull { point ->
                        touchToVideoNorm(point.x, point.y, width, height)
                    }
                    if (videoPoints.size < 2) {
                        return@forEach
                    }
                    if (!syncPointsChanged(lastSentSyncPoints[stroke.id], videoPoints)) {
                        return@forEach
                    }
                    lastSentSyncPoints[stroke.id] = videoPoints
                    annotationChannel.sendAnnotationSync(
                        sid,
                        AnnotationSyncPayload(id = stroke.id, points = videoPoints),
                    )
                }
            }.onFailure { error ->
                Log.w(TAG, "syncProjectedStrokes failed (non-fatal)", error)
            }
        }
    }

    private fun syncPointsChanged(
        previous: List<NormalizedPoint>?,
        current: List<NormalizedPoint>,
    ): Boolean {
        if (previous == null) {
            return true
        }
        if (previous.size != current.size) {
            return true
        }
        return previous.zip(current).any { (a, b) ->
            kotlin.math.abs(a.x - b.x) > SYNC_POINT_EPSILON ||
                kotlin.math.abs(a.y - b.y) > SYNC_POINT_EPSILON
        }
    }

    private data class StoredStroke(
        val id: String,
        val tool: AnnotationTool,
        val color: androidx.compose.ui.graphics.Color,
        val normalizedPoints: List<NormalizedPoint>,
        val inVideoSpace: Boolean,
    ) {
        fun toRendered(viewWidth: Float, viewHeight: Float): RenderedStroke =
            RenderedStroke(
                id = id,
                tool = tool,
                color = color,
                points = if (inVideoSpace) {
                    normalizedPoints.toVideoMappedOffsets(viewWidth, viewHeight)
                } else {
                    normalizedPoints.toOffsets(viewWidth, viewHeight)
                },
            )

        fun toPayload(): AnnotationPayload =
            AnnotationPayload(
                id = id,
                tool = tool.name.lowercase(),
                color = composeColorToHex(color),
                points = normalizedPoints,
            )

        fun toWirePayload(wirePoints: List<NormalizedPoint>): AnnotationPayload =
            toPayload().copy(points = wirePoints)
    }

    private fun AnnotationPayload.toStoredStroke(inVideoSpace: Boolean): StoredStroke =
        StoredStroke(
            id = id,
            tool = AnnotationTool.fromRaw(tool),
            color = parseComposeColor(color),
            normalizedPoints = points,
            inVideoSpace = inVideoSpace,
        )

    companion object {
        private const val TAG = "AnnotationController"
        private const val SYNC_INTERVAL_MS = 50L
        private const val OVERLAY_INTERVAL_MS = 16L
        private const val TECH_SYNC_OVERLAY_INTERVAL_MS = 33L
        private const val STREAM_INTERVAL_MS = 40L
        private const val POINTER_STREAM_INTERVAL_MS = 33L
        private const val REMOTE_ANCHOR_DEBOUNCE_MS = 120L
        private const val SYNC_POINT_EPSILON = 0.003f
    }
}
