package com.cgsapple.remotear.ui.session

import android.opengl.GLSurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cgsapple.remotear.BuildConfig
import com.cgsapple.remotear.annotation.AnnotationTool
import com.cgsapple.remotear.annotation.HOST_VIDEO_ASPECT
import com.cgsapple.remotear.annotation.PointerOverlay
import com.cgsapple.remotear.annotation.RenderedStroke
import com.cgsapple.remotear.ar.ARCoreManager
import com.cgsapple.remotear.data.livekit.CallConnectionStatus
import com.cgsapple.remotear.data.model.ModelItem
import com.cgsapple.remotear.data.realtime.ChatMessage
import com.cgsapple.remotear.data.realtime.SharedFileNotice
import com.cgsapple.remotear.ui.annotation.AnnotationOverlay
import com.cgsapple.remotear.ui.annotation.DrawingTouchLayer
import com.cgsapple.remotear.ui.annotation.PointerTouchLayer
import com.cgsapple.remotear.ui.call.AnnotationSidebar
import com.cgsapple.remotear.ui.call.ArFallbackBanner
import com.cgsapple.remotear.ui.call.ArScanRingOverlay
import com.cgsapple.remotear.ui.call.ArTrackingStateBar
import com.cgsapple.remotear.ui.call.AssistSessionTopBar
import com.cgsapple.remotear.ui.call.CallControlBottomSheet
import com.cgsapple.remotear.ui.call.ChatSheet
import com.cgsapple.remotear.ui.call.FileSharingSheet
import com.cgsapple.remotear.ui.call.LiveKitVideoView
import com.cgsapple.remotear.ui.call.ModelDetailSheet
import com.cgsapple.remotear.ui.call.ReconnectingBanner
import com.cgsapple.remotear.ui.call.RemoteNetworkUnstableBadge
import com.cgsapple.remotear.ui.call.SessionOptionsSheet
import com.cgsapple.remotear.ui.theme.Background
import com.cgsapple.remotear.ui.theme.OnSurfaceVariant
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.video.CameraCapturerUtils
import livekit.org.webrtc.CameraXHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerCallScreen(
    uiState: CallUiState,
    arCoreManager: ARCoreManager,
    annotationStrokes: List<RenderedStroke>,
    draftStroke: RenderedStroke?,
    pointerOverlay: PointerOverlay,
    activeTool: AnnotationTool,
    activeColor: String,
    chatMessages: List<ChatMessage>,
    sharedFiles: List<SharedFileNotice>,
    isPremium: Boolean = BuildConfig.IS_PREMIUM,
    onViewSizeChanged: (Float, Float) -> Unit,
    onDraftChanged: (RenderedStroke?) -> Unit,
    onStrokeStreaming: (RenderedStroke) -> Unit,
    onStrokeCommitted: (RenderedStroke) -> Unit,
    onSidebarToolSelected: (SidebarTool) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onTogglePause: () -> Unit,
    onEndSession: () -> Unit,
    onOpenSessionMenu: () -> Unit,
    onDismissSessionPanel: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenFiles: () -> Unit,
    onChatInputChange: (String) -> Unit,
    onSendChat: () -> Unit,
    onPickFile: () -> Unit,
    onOpenSharedFile: (SharedFileNotice) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as ComponentActivity
    val snackbarHostState = remember { SnackbarHostState() }

    DisposableEffect(lifecycleOwner, activity) {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            arCoreManager.onResume()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> arCoreManager.onResume()
                Lifecycle.Event.ON_PAUSE -> arCoreManager.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            arCoreManager.onDestroy()
        }
    }

    DisposableEffect(lifecycleOwner, uiState.arFallbackActive) {
        if (uiState.arFallbackActive) {
            val cameraProvider = CameraXHelper.createCameraProvider(lifecycleOwner)
            if (cameraProvider.isSupported(context.applicationContext)) {
                CameraCapturerUtils.registerCameraProvider(cameraProvider)
            }
            onDispose { CameraCapturerUtils.unregisterCameraProvider(cameraProvider) }
        } else {
            onDispose { }
        }
    }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    val drawingEnabled = uiState.liveKitStarted &&
        uiState.permissionsGranted &&
        uiState.connectionStatus != CallConnectionStatus.RECONNECTING &&
        (uiState.trackingUiState == com.cgsapple.remotear.ar.ArTrackingUiState.STABLE ||
            uiState.trackingUiState == com.cgsapple.remotear.ar.ArTrackingUiState.SURFACE_FOUND ||
            uiState.arFallbackActive)

    val drawToolActive = uiState.sidebarTool.isDrawingTool()

    Box(modifier = modifier.fillMaxSize().background(Background)) {
        AndroidView(
            factory = { ctx ->
                arCoreManager.attach(activity)
                GLSurfaceView(ctx).also { arCoreManager.bindGlSurface(it) }
            },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    onViewSizeChanged(size.width.toFloat(), size.height.toFloat())
                },
        )

        AnnotationOverlay(
            strokes = annotationStrokes,
            draftStroke = draftStroke,
            pointerOverlay = pointerOverlay,
            modifier = Modifier.fillMaxSize(),
        )

        if (uiState.arFallbackActive) {
            Box(Modifier.fillMaxSize().background(Background.copy(alpha = 0.35f)))
        }

        ArScanRingOverlay(visible = uiState.arActive && !uiState.arFallbackActive && uiState.planeCount == 0)
        // Drawing gestures ABOVE AR surface but BELOW chrome - zIndex(9) previously ate mute/end/undo taps.
        if (drawToolActive) {
            DrawingTouchLayer(
                enabled = drawingEnabled,
                activeTool = activeTool,
                activeColor = activeColor,
                onViewSizeChanged = onViewSizeChanged,
                onDraftChanged = onDraftChanged,
                onStrokeStreaming = onStrokeStreaming,
                onStrokeCommitted = onStrokeCommitted,
                modifier = Modifier.fillMaxSize().zIndex(5f),
            )
        }

        AssistCallChrome(
            uiState = uiState,
            isTechnician = false,
            isPremium = isPremium,
            drawingEnabled = drawingEnabled,
            onSidebarToolSelected = onSidebarToolSelected,
            onToggleMute = onToggleMute,
            onToggleSpeaker = onToggleSpeaker,
            onTogglePause = onTogglePause,
            onEndSession = onEndSession,
            onOpenSessionMenu = onOpenSessionMenu,
            onToggleBottomSheet = { },
            onSearchQueryChange = { },
            onModelSelected = { },
            onDismissModelDetail = { },
            onPlaceModel = { },
        )

        SessionPanelOverlays(
            uiState = uiState,
            chatMessages = chatMessages,
            sharedFiles = sharedFiles,
            isPremium = isPremium,
            onDismiss = onDismissSessionPanel,
            onOpenChat = onOpenChat,
            onOpenFiles = onOpenFiles,
            onChatInputChange = onChatInputChange,
            onSendChat = onSendChat,
            onPickFile = onPickFile,
            onOpenSharedFile = onOpenSharedFile,
            onStartRecording = onStartRecording,
            onStopRecording = onStopRecording,
        )

        if (!uiState.liveKitStarted && uiState.permissionsGranted) {
            Box(
                Modifier.fillMaxSize().background(Background.copy(alpha = 0.4f)).zIndex(15f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier.size(40.dp), strokeWidth = 3.dp)
            }
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).zIndex(25f))

        uiState.errorMessage?.let { msg ->
            Text(
                text = msg,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp).zIndex(25f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TechnicianCallScreen(
    uiState: CallUiState,
    room: Room?,
    remoteVideoTrack: VideoTrack?,
    annotationStrokes: List<RenderedStroke>,
    draftStroke: RenderedStroke?,
    activeTool: AnnotationTool,
    activeColor: String,
    chatMessages: List<ChatMessage>,
    sharedFiles: List<SharedFileNotice>,
    isPremium: Boolean = BuildConfig.IS_PREMIUM,
    onViewSizeChanged: (Float, Float) -> Unit,
    onDraftChanged: (RenderedStroke?) -> Unit,
    onStrokeStreaming: (RenderedStroke) -> Unit,
    onStrokeCommitted: (RenderedStroke) -> Unit,
    onPointerEvent: (Float, Float, Boolean) -> Unit,
    onSidebarToolSelected: (SidebarTool) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onTogglePause: () -> Unit,
    onEndSession: () -> Unit,
    onOpenSessionMenu: () -> Unit,
    onDismissSessionPanel: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenFiles: () -> Unit,
    onChatInputChange: (String) -> Unit,
    onSendChat: () -> Unit,
    onPickFile: () -> Unit,
    onOpenSharedFile: (SharedFileNotice) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onToggleBottomSheet: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onModelSelected: (ModelItem) -> Unit,
    onDismissModelDetail: () -> Unit,
    onPlaceModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayCode = formatJoinCodeForDisplay(uiState.joinCode)
    val hasVideo = room != null && remoteVideoTrack != null
    val drawingEnabled = hasVideo && uiState.connectionStatus != CallConnectionStatus.RECONNECTING
    val drawToolActive = uiState.sidebarTool.isDrawingTool()
    val pointerActive = uiState.sidebarTool == SidebarTool.POINTER
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    Box(modifier = modifier.fillMaxSize().background(Background)) {
        if (hasVideo) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(HOST_VIDEO_ASPECT)
                        .onSizeChanged { size ->
                            onViewSizeChanged(size.width.toFloat(), size.height.toFloat())
                        },
                ) {
                    LiveKitVideoView(
                        room = room!!,
                        videoTrack = remoteVideoTrack,
                        paused = uiState.isVideoPaused,
                        modifier = Modifier.fillMaxSize().zIndex(0f),
                    )
                    AnnotationOverlay(
                        strokes = annotationStrokes,
                        draftStroke = draftStroke,
                        modifier = Modifier.fillMaxSize().zIndex(2f),
                        showDebugProbe = BuildConfig.ANNOTATION_DEBUG_OVERLAY,
                    )
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 3.dp)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Waiting for customer camera…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        AssistCallChrome(
            uiState = uiState,
            isTechnician = true,
            isPremium = isPremium,
            sessionLabel = "Session $displayCode",
            drawingEnabled = drawingEnabled,
            onSidebarToolSelected = onSidebarToolSelected,
            onToggleMute = onToggleMute,
            onToggleSpeaker = onToggleSpeaker,
            onTogglePause = onTogglePause,
            onEndSession = onEndSession,
            onOpenSessionMenu = onOpenSessionMenu,
            onToggleBottomSheet = onToggleBottomSheet,
            onSearchQueryChange = onSearchQueryChange,
            onModelSelected = onModelSelected,
            onDismissModelDetail = onDismissModelDetail,
            onPlaceModel = onPlaceModel,
        )

        SessionPanelOverlays(
            uiState = uiState,
            chatMessages = chatMessages,
            sharedFiles = sharedFiles,
            isPremium = isPremium,
            onDismiss = onDismissSessionPanel,
            onOpenChat = onOpenChat,
            onOpenFiles = onOpenFiles,
            onChatInputChange = onChatInputChange,
            onSendChat = onSendChat,
            onPickFile = onPickFile,
            onOpenSharedFile = onOpenSharedFile,
            onStartRecording = onStartRecording,
            onStopRecording = onStopRecording,
        )

        // Constrain draw/pointer hit-testing to the 9:16 video frame only (not full screen),
        // and keep zIndex below AssistCallChrome so mute/end/sidebar remain tappable.
        if (hasVideo) {
            Box(
                modifier = Modifier.fillMaxSize().zIndex(5f),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.fillMaxHeight().aspectRatio(HOST_VIDEO_ASPECT)) {
                    if (drawToolActive) {
                        DrawingTouchLayer(
                            enabled = drawingEnabled,
                            activeTool = activeTool,
                            activeColor = activeColor,
                            onViewSizeChanged = onViewSizeChanged,
                            onDraftChanged = onDraftChanged,
                            onStrokeStreaming = onStrokeStreaming,
                            onStrokeCommitted = onStrokeCommitted,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (pointerActive) {
                        PointerTouchLayer(
                            enabled = drawingEnabled,
                            onViewSizeChanged = onViewSizeChanged,
                            onPointerEvent = onPointerEvent,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).zIndex(25f))

        uiState.errorMessage?.let { msg ->
            Text(
                text = msg,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp).zIndex(25f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionPanelOverlays(
    uiState: CallUiState,
    chatMessages: List<ChatMessage>,
    sharedFiles: List<SharedFileNotice>,
    isPremium: Boolean = BuildConfig.IS_PREMIUM,
    onDismiss: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenFiles: () -> Unit,
    onChatInputChange: (String) -> Unit,
    onSendChat: () -> Unit,
    onPickFile: () -> Unit,
    onOpenSharedFile: (SharedFileNotice) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
) {
    if (!isPremium) return
    when (uiState.sessionPanel) {
        SessionPanel.MENU -> SessionOptionsSheet(
            isRecording = uiState.isRecording,
            recordingSeconds = uiState.recordingSeconds,
            onStartRecording = onStartRecording,
            onStopRecording = onStopRecording,
            onOpenChat = onOpenChat,
            onOpenFiles = onOpenFiles,
            onDismiss = onDismiss,
        )
        SessionPanel.CHAT -> ChatSheet(
            messages = chatMessages,
            input = uiState.chatInput,
            onInputChange = onChatInputChange,
            onSend = onSendChat,
            onDismiss = onDismiss,
        )
        SessionPanel.FILES -> FileSharingSheet(
            files = sharedFiles,
            isUploading = uiState.isUploadingFile,
            onPickFile = onPickFile,
            onOpenFile = onOpenSharedFile,
            onDismiss = onDismiss,
        )
        SessionPanel.NONE -> Unit
    }
}

@Composable
private fun AssistCallChrome(
    uiState: CallUiState,
    isTechnician: Boolean,
    isPremium: Boolean = BuildConfig.IS_PREMIUM,
    drawingEnabled: Boolean,
    onSidebarToolSelected: (SidebarTool) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onTogglePause: () -> Unit,
    onEndSession: () -> Unit,
    onOpenSessionMenu: () -> Unit,
    onToggleBottomSheet: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onModelSelected: (ModelItem) -> Unit,
    onDismissModelDetail: () -> Unit,
    onPlaceModel: () -> Unit,
    sessionLabel: String = "Session #${uiState.joinCode.takeLast(3)}",
) {
    Box(Modifier.fillMaxSize().zIndex(20f)) {
        Column(Modifier.fillMaxWidth()) {
            if (uiState.connectionStatus == CallConnectionStatus.RECONNECTING) ReconnectingBanner()
            if (uiState.arFallbackActive) ArFallbackBanner()
            if (uiState.remoteParticipantUnstable) {
                RemoteNetworkUnstableBadge(if (isTechnician) "Customer" else "Technician")
            }
            if (uiState.isRecording) {
                Text(
                    text = "● REC ${formatElapsed(uiState.recordingSeconds)}",
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(com.cgsapple.remotear.ui.theme.ErrorRed.copy(alpha = 0.85f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = com.cgsapple.remotear.ui.theme.OnBackground,
                )
            }
            AssistSessionTopBar(
                onSessionPillClick = { if (isPremium) onOpenSessionMenu() },
                onProfileClick = { if (isPremium) onOpenSessionMenu() },
            )
        }

        if (drawingEnabled || isTechnician) {
            AnnotationSidebar(
                activeTool = uiState.sidebarTool,
                drawingEnabled = drawingEnabled,
                onToolSelected = onSidebarToolSelected,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).zIndex(7f),
            )
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().zIndex(8f),
        ) {
            if (!isTechnician && uiState.trackingUiState != com.cgsapple.remotear.ar.ArTrackingUiState.STABLE) {
                ArTrackingStateBar(trackingState = uiState.trackingUiState)
            }
            if (uiState.selectedModel != null && isTechnician) {
                ModelDetailSheet(
                    model = uiState.selectedModel,
                    onBack = onDismissModelDetail,
                    onPlace = onPlaceModel,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            } else {
                CallControlBottomSheet(
                    isMuted = uiState.isMuted,
                    isEnding = uiState.isEnding,
                    isTechnician = isTechnician,
                    isPremium = isPremium,
                    expanded = uiState.bottomSheetExpanded,
                    modelsLoading = uiState.modelsLoading,
                    assetSearchQuery = uiState.assetSearchQuery,
                    models = uiState.models,
                    recentModelIds = uiState.recentModelIds,
                    speakerOn = uiState.isSpeakerOn,
                    paused = uiState.isVideoPaused,
                    onToggleExpanded = onToggleBottomSheet,
                    onToggleSpeaker = onToggleSpeaker,
                    onToggleMute = onToggleMute,
                    onTogglePause = onTogglePause,
                    onEndCall = onEndSession,
                    onSearchQueryChange = onSearchQueryChange,
                    onModelSelected = onModelSelected,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

private fun formatElapsed(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

@Composable
fun SessionEndedScreen(
    onBackToHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Session ended", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(
            "The session has been closed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        androidx.compose.material3.FilledTonalButton(
            onClick = onBackToHome,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Back to Home")
        }
    }
}
