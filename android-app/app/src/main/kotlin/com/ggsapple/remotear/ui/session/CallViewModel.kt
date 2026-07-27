package com.ggsapple.remotear.ui.session

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ggsapple.remotear.annotation.AnnotationController
import com.ggsapple.remotear.annotation.AnnotationRole
import com.ggsapple.remotear.annotation.AnnotationTool
import com.ggsapple.remotear.annotation.DEFAULT_COLOR
import com.ggsapple.remotear.annotation.PointerOverlay
import com.ggsapple.remotear.annotation.RenderedStroke
import com.ggsapple.remotear.ar.ARCoreManager
import com.ggsapple.remotear.ar.ArTrackingUiState
import com.ggsapple.remotear.data.audio.AudioOutputManager
import com.ggsapple.remotear.data.livekit.CallConnectionStatus
import com.ggsapple.remotear.data.livekit.LiveKitManager
import com.ggsapple.remotear.data.local.RecentModelsStore
import com.ggsapple.remotear.data.model.ModelItem
import com.ggsapple.remotear.data.model.SessionParticipantRole
import com.ggsapple.remotear.data.model.SessionStatus
import com.ggsapple.remotear.data.realtime.ChatChannel
import com.ggsapple.remotear.data.realtime.ChatMessage
import com.ggsapple.remotear.data.realtime.FileShareChannel
import com.ggsapple.remotear.data.realtime.FileSharePayload
import com.ggsapple.remotear.data.realtime.SharedFileNotice
import com.ggsapple.remotear.data.recording.ScreenRecordingManager
import com.ggsapple.remotear.data.repository.AuthRepository
import com.ggsapple.remotear.data.repository.ModelRepository
import com.ggsapple.remotear.data.repository.RuntimeConfigRepository
import com.ggsapple.remotear.data.repository.SessionRepository
import com.ggsapple.remotear.data.storage.FileUploadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.SupabaseClient
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

data class CallUiState(
    val joinCode: String,
    val isCustomer: Boolean,
    val elapsedSeconds: Int = 0,
    val connectionStatus: CallConnectionStatus = CallConnectionStatus.DISCONNECTED,
    val hasRemoteVideo: Boolean = false,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = true,
    val isVideoPaused: Boolean = false,
    val isEnding: Boolean = false,
    val permissionsGranted: Boolean = false,
    val liveKitStarted: Boolean = false,
    val errorMessage: String? = null,
    val navigateToSessionEnded: Boolean = false,
    val remoteParticipantUnstable: Boolean = false,
    val arActive: Boolean = false,
    val arFallbackActive: Boolean = false,
    val trackingUiState: ArTrackingUiState = ArTrackingUiState.SCANNING,
    val planeCount: Int = 0,
    val sidebarTool: SidebarTool = SidebarTool.DRAW,
    val bottomSheetExpanded: Boolean = false,
    val sessionPanel: SessionPanel = SessionPanel.NONE,
    val modelsLoading: Boolean = false,
    val models: List<ModelItem> = emptyList(),
    val recentModelIds: List<String> = emptyList(),
    val assetSearchQuery: String = "",
    val selectedModel: ModelItem? = null,
    val chatInput: String = "",
    val isUploadingFile: Boolean = false,
    val isRecording: Boolean = false,
    val recordingSeconds: Int = 0,
    val toastMessage: String? = null,
    val pendingRecordingIntent: Boolean = false,
)

@HiltViewModel
class CallViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: SessionRepository,
    private val liveKitManager: LiveKitManager,
    private val arCoreManager: ARCoreManager,
    private val annotationController: AnnotationController,
    private val audioOutputManager: AudioOutputManager,
    private val modelRepository: ModelRepository,
    private val recentModelsStore: RecentModelsStore,
    private val chatChannel: ChatChannel,
    private val fileShareChannel: FileShareChannel,
    private val fileUploadManager: FileUploadManager,
    private val screenRecordingManager: ScreenRecordingManager,
    private val authRepository: AuthRepository,
    private val supabase: SupabaseClient,
    private val runtimeConfigRepository: RuntimeConfigRepository,
) : ViewModel() {

    val sessionId: String = checkNotNull(savedStateHandle["sessionId"])
    val joinCode: String = checkNotNull(savedStateHandle["joinCode"])

    private val activeSession = sessionRepository.getCachedSession(sessionId)
    val isCustomer: Boolean = activeSession?.role == SessionParticipantRole.CUSTOMER

    private val _uiState = MutableStateFlow(
        CallUiState(
            joinCode = joinCode,
            isCustomer = isCustomer,
            bottomSheetExpanded = false,
        ),
    )
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    val roomState = liveKitManager.roomState
    val remoteVideoTrack: StateFlow<VideoTrack?> = liveKitManager.remoteVideoTrack
    val arCoreManagerRef: ARCoreManager = arCoreManager
    val annotationStrokes = annotationController.overlayStrokes
    val draftStroke = annotationController.draftStroke
    val pointerOverlay = annotationController.pointerOverlay

    private val _activeTool = MutableStateFlow(AnnotationTool.FREEHAND)
    val activeTool: StateFlow<AnnotationTool> = _activeTool.asStateFlow()

    private val _activeColor = MutableStateFlow(DEFAULT_COLOR)
    val activeColor: StateFlow<String> = _activeColor.asStateFlow()

    val chatMessages: StateFlow<List<ChatMessage>> = chatChannel.messages
    val sharedFiles: StateFlow<List<SharedFileNotice>> = fileShareChannel.sharedFiles

    private var observeJob: Job? = null
    private var timerJob: Job? = null
    private var chatJob: Job? = null
    private var fileJob: Job? = null
    private var recordingTimerJob: Job? = null
    private var displayName: String = "User"
    private var userId: String = ""

    init {
        if (activeSession == null) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Session token missing. Return home and try again.",
            )
        }

        viewModelScope.launch {
            val uid = supabase.auth.currentUserOrNull()?.id
            userId = uid.orEmpty()
            displayName = uid?.let { authRepository.fetchProfile(it)?.displayName }
                ?: supabase.auth.currentUserOrNull()?.email?.substringBefore("@")
                ?: "User"
        }

        viewModelScope.launch {
            liveKitManager.connectionStatus.collect { status ->
                _uiState.update { it.copy(connectionStatus = status) }
            }
        }
        viewModelScope.launch {
            liveKitManager.connectionError.collect { message ->
                if (message != null) {
                    _uiState.update { it.copy(errorMessage = message) }
                }
            }
        }
        viewModelScope.launch {
            liveKitManager.remoteVideoTrack.collect { track ->
                _uiState.update { it.copy(hasRemoteVideo = track != null) }
            }
        }
        viewModelScope.launch {
            liveKitManager.isMuted.collect { muted ->
                _uiState.update { it.copy(isMuted = muted) }
            }
        }
        viewModelScope.launch {
            liveKitManager.isVideoPaused.collect { paused ->
                _uiState.update { it.copy(isVideoPaused = paused) }
            }
        }
        viewModelScope.launch {
            liveKitManager.remoteParticipantUnstable.collect { unstable ->
                _uiState.update { it.copy(remoteParticipantUnstable = unstable) }
            }
        }
        viewModelScope.launch {
            audioOutputManager.speakerOn.collect { on ->
                _uiState.update { it.copy(isSpeakerOn = on) }
            }
        }
        viewModelScope.launch {
            recentModelsStore.recentModelIds.collect { ids ->
                _uiState.update { it.copy(recentModelIds = ids) }
            }
        }
        viewModelScope.launch {
            screenRecordingManager.isRecording.collect { recording ->
                _uiState.update { it.copy(isRecording = recording) }
            }
        }
        viewModelScope.launch {
            screenRecordingManager.recordingSeconds.collect { seconds ->
                _uiState.update { it.copy(recordingSeconds = seconds) }
            }
        }

        if (isCustomer) {
            viewModelScope.launch {
                arCoreManager.arcoreActiveState.collect { active ->
                    _uiState.update { it.copy(arActive = active) }
                }
            }
            viewModelScope.launch {
                arCoreManager.fallbackActive.collect { fallback ->
                    _uiState.update { it.copy(arFallbackActive = fallback) }
                }
            }
            viewModelScope.launch {
                arCoreManager.planeCount.collect { count ->
                    _uiState.update { it.copy(planeCount = count) }
                }
            }
            viewModelScope.launch {
                arCoreManager.trackingUiState.collect { state ->
                    _uiState.update { it.copy(trackingUiState = state) }
                    if (state == ArTrackingUiState.SURFACE_FOUND) {
                        delay(SURFACE_FOUND_DISPLAY_MS)
                        _uiState.update { current ->
                            if (current.trackingUiState == ArTrackingUiState.SURFACE_FOUND) {
                                current.copy(trackingUiState = ArTrackingUiState.STABLE)
                            } else {
                                current
                            }
                        }
                    }
                }
            }
            viewModelScope.launch {
                arCoreManager.streamingReady.collect { ready ->
                    if (ready && _uiState.value.permissionsGranted) {
                        startLiveKitIfReady()
                    }
                }
            }
        }

        observeJob = viewModelScope.launch {
            sessionRepository.observeSessionStatus(sessionId).collect { status ->
                if (status == SessionStatus.ENDED) {
                    disconnectLiveKit()
                    sessionRepository.clearCachedSession()
                    _uiState.value = _uiState.value.copy(navigateToSessionEnded = true)
                }
            }
        }

        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(TIMER_INTERVAL_MS)
                _uiState.update { it.copy(elapsedSeconds = it.elapsedSeconds + 1) }
            }
        }

        annotationController.start(
            sessionId = sessionId,
            role = if (isCustomer) AnnotationRole.CUSTOMER else AnnotationRole.TECHNICIAN,
        )

        if (userId.isBlank()) {
            viewModelScope.launch {
                delay(500)
                userId = supabase.auth.currentUserOrNull()?.id.orEmpty()
                startRealtimeExtras()
            }
        } else {
            startRealtimeExtras()
        }
    }

    private fun startRealtimeExtras() {
        if (userId.isBlank()) return
        chatJob?.cancel()
        fileJob?.cancel()
        // Instant customer app always enables chat + file channels.
        chatJob = chatChannel.subscribeWithRetry(sessionId, userId)
        fileJob = fileShareChannel.subscribeWithRetry(sessionId, userId)
    }

    fun onPermissionsResult(granted: Boolean) {
        _uiState.update { it.copy(permissionsGranted = granted) }
        if (granted) {
            if (isCustomer) {
                arCoreManager.onCameraPermissionGranted()
                if (arCoreManager.streamingReady.value) {
                    startLiveKitIfReady()
                }
            } else {
                startLiveKitIfReady()
            }
        } else {
            val message = if (isCustomer) {
                "Camera and microphone permissions are required for the call."
            } else {
                "Microphone permission is required for the call."
            }
            _uiState.update { it.copy(errorMessage = message) }
        }
    }

    fun startLiveKitIfReady() {
        val session = sessionRepository.getCachedSession(sessionId) ?: return
        if (_uiState.value.liveKitStarted) return

        _uiState.update { it.copy(liveKitStarted = true, errorMessage = null) }

        val captureSize = arCoreManager.getCaptureSize()
        val useFallback = arCoreManager.fallbackActive.value

        liveKitManager.connect(
            url = runtimeConfigRepository.livekitUrlBlocking(),
            token = session.livekitToken,
            isCustomer = session.role == SessionParticipantRole.CUSTOMER,
            arCapturer = if (!useFallback) arCoreManager.frameCapturer else null,
            captureWidth = captureSize.width,
            captureHeight = captureSize.height,
            useCameraFallback = useFallback,
        )
    }

    fun toggleMute() {
        Log.i(TAG, "toggleMute() requested isMuted=${_uiState.value.isMuted}")
        liveKitManager.toggleMute()
    }

    fun toggleSpeaker() {
        Log.i(TAG, "toggleSpeaker() requested isSpeakerOn=${_uiState.value.isSpeakerOn}")
        audioOutputManager.toggleSpeakerphone()
    }

    fun toggleVideoPaused() {
        Log.i(TAG, "toggleVideoPaused() requested isVideoPaused=${_uiState.value.isVideoPaused}")
        liveKitManager.toggleVideoPaused()
    }

    fun toggleBottomSheetExpanded() {
        _uiState.update { it.copy(bottomSheetExpanded = !it.bottomSheetExpanded) }
        if (_uiState.value.bottomSheetExpanded && _uiState.value.models.isEmpty()) {
            loadModels()
        }
    }

    fun setAssetSearchQuery(query: String) {
        _uiState.update { it.copy(assetSearchQuery = query) }
    }

    fun openSessionMenu() {
        _uiState.update { it.copy(sessionPanel = SessionPanel.MENU) }
    }

    fun openChat() {
        _uiState.update { it.copy(sessionPanel = SessionPanel.CHAT) }
    }

    fun openFiles() {
        _uiState.update { it.copy(sessionPanel = SessionPanel.FILES) }
    }

    fun dismissSessionPanel() {
        _uiState.update { it.copy(sessionPanel = SessionPanel.NONE) }
    }

    fun setChatInput(text: String) {
        _uiState.update { it.copy(chatInput = text) }
    }

    fun sendChatMessage() {
        val text = _uiState.value.chatInput
        if (text.isBlank() || userId.isBlank()) return
        viewModelScope.launch {
            chatChannel.sendMessage(text, userId, displayName)
            _uiState.update { it.copy(chatInput = "") }
        }
    }

    fun loadModels(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(modelsLoading = true) }
            modelRepository.getModels(forceRefresh)
                .onSuccess { models ->
                    _uiState.update { it.copy(models = models, modelsLoading = false) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            modelsLoading = false,
                            toastMessage = error.message ?: "Failed to load models",
                        )
                    }
                }
        }
    }

    fun selectModel(model: ModelItem) {
        _uiState.update { it.copy(selectedModel = model, bottomSheetExpanded = false) }
    }

    fun dismissModelDetail() {
        _uiState.update { it.copy(selectedModel = null) }
    }

    fun placeSelectedModel() {
        val model = _uiState.value.selectedModel ?: return
        viewModelScope.launch {
            recentModelsStore.recordModelUsed(model.id)
            annotationController.placeModel(
                modelId = model.id,
                modelName = model.name,
                modelUrl = model.url,
            )
            showToast("Placed ${model.name} in session")
            _uiState.update { it.copy(selectedModel = null) }
        }
    }

    fun setSidebarTool(tool: SidebarTool) {
        Log.i(TAG, "setSidebarTool($tool) current=${_uiState.value.sidebarTool}")
        when (tool) {
            SidebarTool.UNDO -> {
                undoLastStroke()
                return
            }
            SidebarTool.DELETE -> {
                clearAnnotations()
                return
            }
            else -> Unit
        }
        _uiState.update { it.copy(sidebarTool = tool) }
        tool.toAnnotationTool()?.let { _activeTool.value = it }
    }

    fun undoLastStroke() {
        val hadStroke = annotationController.hasUndoableStroke()
        Log.i(TAG, "undoLastStroke() hadStroke=$hadStroke")
        annotationController.undoLastStroke()
        if (!hadStroke) {
            showToast("Nothing to undo")
        }
    }

    fun requestStartRecording() {
        _uiState.update { it.copy(pendingRecordingIntent = true) }
    }

    fun onRecordingPermissionResult(resultCode: Int, data: Intent?) {
        _uiState.update { it.copy(pendingRecordingIntent = false) }
        if (data == null) {
            showToast("Recording permission denied")
            return
        }
        screenRecordingManager.startRecording(resultCode, data)
            .onSuccess { path ->
                showToast("Recording started")
                startRecordingTimer()
            }
            .onFailure { error ->
                showToast(error.message ?: "Could not start recording")
            }
    }

    fun stopRecording() {
        screenRecordingManager.stopRecording()
            .onSuccess { path ->
                showToast("Recording saved: $path")
            }
            .onFailure { error ->
                showToast(error.message ?: "Could not stop recording")
            }
        recordingTimerJob?.cancel()
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch {
            while (isActive && screenRecordingManager.isRecording.value) {
                delay(1_000)
                screenRecordingManager.tickRecordingTimer()
            }
        }
    }

    fun shareFile(uri: Uri, contentResolver: (Uri) -> java.io.InputStream?) {
        if (userId.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isUploadingFile = true) }
            val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "shared_file"
            val mime = when {
                fileName.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
                fileName.endsWith(".png", ignoreCase = true) -> "image/png"
                else -> "image/jpeg"
            }
            val stream = contentResolver(uri)
            if (stream == null) {
                _uiState.update { it.copy(isUploadingFile = false, toastMessage = "Could not read file") }
                return@launch
            }
            fileUploadManager.uploadSessionFile(sessionId, fileName, stream, mime)
                .onSuccess { url ->
                    val payload = FileSharePayload(
                        senderId = userId,
                        senderName = displayName,
                        fileUrl = url,
                        fileName = fileName,
                        fileSizeBytes = 0L,
                        timestamp = System.currentTimeMillis(),
                    )
                    fileShareChannel.broadcastFile(payload)
                    showToast("File shared")
                }
                .onFailure { error ->
                    showToast(error.message ?: "Upload failed")
                }
            _uiState.update { it.copy(isUploadingFile = false) }
        }
    }

    fun showToast(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
        viewModelScope.launch {
            delay(3_000)
            _uiState.update { current ->
                if (current.toastMessage == message) current.copy(toastMessage = null) else current
            }
        }
    }

    fun endSession() {
        if (_uiState.value.isEnding) {
            Log.w(TAG, "endSession() ignored — already ending")
            return
        }

        Log.i(TAG, "endSession() sessionId=$sessionId isCustomer=$isCustomer")
        viewModelScope.launch {
            _uiState.update { it.copy(isEnding = true, errorMessage = null) }
            screenRecordingManager.reset()
            disconnectLiveKit()
            sessionRepository.endSession(sessionId)
                .onSuccess {
                    Log.i(TAG, "endSession() success — navigating to session ended")
                    sessionRepository.clearCachedSession()
                    _uiState.update {
                        it.copy(isEnding = false, navigateToSessionEnded = true)
                    }
                }
                .onFailure { error ->
                    Log.e(TAG, "endSession() failed: ${error.message}", error)
                    _uiState.update {
                        it.copy(
                            isEnding = false,
                            errorMessage = error.message ?: "Failed to end session",
                        )
                    }
                }
        }
    }

    fun onNavigatedToSessionEnded() {
        _uiState.update { it.copy(navigateToSessionEnded = false) }
    }

    fun setActiveTool(tool: AnnotationTool) {
        _activeTool.value = tool
    }

    fun setActiveColor(color: String) {
        _activeColor.value = color
    }

    fun onAnnotationViewSize(width: Float, height: Float) {
        annotationController.updateViewSize(width.toInt(), height.toInt())
    }

    fun setDraftStroke(stroke: RenderedStroke?) {
        annotationController.setDraftStroke(stroke)
    }

    fun streamAnnotationStroke(stroke: RenderedStroke) {
        annotationController.streamTechnicianStroke(stroke)
    }

    fun commitAnnotationStroke(stroke: RenderedStroke) {
        annotationController.commitDraftStroke(stroke)
    }

    fun streamPointer(normalizedX: Float, normalizedY: Float, active: Boolean) {
        annotationController.streamPointer(normalizedX, normalizedY, active)
    }

    fun clearAnnotations() {
        Log.i(TAG, "clearAnnotations() requested")
        annotationController.clearAll()
    }

    fun createRecordingIntent(): Intent = screenRecordingManager.createCaptureIntent()

    private suspend fun disconnectLiveKit() {
        chatJob?.cancel()
        fileJob?.cancel()
        chatChannel.disconnect()
        fileShareChannel.disconnect()
        annotationController.stop()
        audioOutputManager.reset()
        liveKitManager.disconnect()
    }

    override fun onCleared() {
        observeJob?.cancel()
        timerJob?.cancel()
        recordingTimerJob?.cancel()
        runBlocking {
            annotationController.stop()
            chatChannel.disconnect()
            fileShareChannel.disconnect()
        }
        screenRecordingManager.reset()
        audioOutputManager.reset()
        liveKitManager.disconnect()
        super.onCleared()
    }

    companion object {
        private const val TAG = "CallViewModel"
        private const val TIMER_INTERVAL_MS = 1_000L
        private const val SURFACE_FOUND_DISPLAY_MS = 2_000L
    }
}
