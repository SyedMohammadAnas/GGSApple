package com.ggsapple.remotear.ui.navigation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ggsapple.remotear.data.model.Profile
import com.ggsapple.remotear.data.repository.RuntimeConfigRepository
import com.ggsapple.remotear.ui.home.AssistHomeScreen
import com.ggsapple.remotear.ui.home.DeviceStatusBar
import com.ggsapple.remotear.ui.home.HomeViewModel
import com.ggsapple.remotear.ui.home.rememberConnectionReady
import com.ggsapple.remotear.ui.session.CallViewModel
import com.ggsapple.remotear.ui.session.CustomerCallScreen
import com.ggsapple.remotear.ui.session.SessionEndedScreen
import com.ggsapple.remotear.ui.session.TechnicianCallScreen
import com.ggsapple.remotear.ui.session.WaitingScreen
import com.ggsapple.remotear.ui.session.WaitingViewModel
import com.ggsapple.remotear.ui.tutorial.LocalTutorialScreen
import com.ggsapple.remotear.ui.tutorial.LocalTutorialViewModel
import com.ggsapple.remotear.util.PublicIdFormatter
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RuntimeConfigEntryPoint {
    fun runtimeConfigRepository(): RuntimeConfigRepository
}

@Composable
fun AuthenticatedNavGraph(
    profile: Profile,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val runtimeConfig = EntryPointAccessors.fromApplication(
        context.applicationContext,
        RuntimeConfigEntryPoint::class.java,
    ).runtimeConfigRepository()
    val connectionReady = rememberConnectionReady(runtimeConfig)

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        composable(Routes.HOME) {
            val viewModel: HomeViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(profile) {
                viewModel.bindProfile(profile)
            }

            LaunchedEffect(connectionReady) {
                viewModel.setConnectionReady(connectionReady)
            }

            LaunchedEffect(uiState.navigateToCustomerCall) {
                val session = uiState.navigateToCustomerCall ?: return@LaunchedEffect
                navController.navigate(
                    Routes.customerCall(session.sessionId, session.joinCode),
                ) { launchSingleTop = true }
                viewModel.onCustomerCallNavigated()
            }

            LaunchedEffect(uiState.joinedSession) {
                val session = uiState.joinedSession ?: return@LaunchedEffect
                navController.navigate(
                    Routes.technicianCall(session.sessionId, session.joinCode),
                ) { launchSingleTop = true }
                viewModel.clearNavigation()
            }

            AssistHomeScreen(
                profile = profile,
                uiState = uiState,
                onAppModeChange = viewModel::setAppMode,
                onExpertIdChange = viewModel::onExpertIdChange,
                onPasteExpertId = viewModel::pasteExpertId,
                onShareId = viewModel::shareId,
                onJoinSession = viewModel::joinSessionById,
                onCreateTutorial = { navController.navigate(Routes.LOCAL_TUTORIAL) },
                onSignOut = onSignOut,
                onClearCache = viewModel::clearCache,
                onDismissCacheMessage = viewModel::dismissCacheMessage,
                onOpenDebug = viewModel::openDebugSheet,
                onDismissDebug = viewModel::dismissDebugSheet,
                onDebugApiUrlChange = viewModel::onDebugApiUrlChange,
                onDebugLivekitUrlChange = viewModel::onDebugLivekitUrlChange,
                onSaveDebugUrls = viewModel::saveDebugUrls,
                onResetDebugUrls = viewModel::resetDebugUrls,
                connectionStatusContent = {
                    DeviceStatusBar(
                        connectionReady = uiState.connectionReady,
                        incomingSession = uiState.incomingSession,
                    )
                },
            )
        }

        composable(
            route = Routes.WAITING,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("joinCode") { type = NavType.StringType },
                navArgument("publicId") { type = NavType.StringType; defaultValue = "" },
            ),
        ) {
            val viewModel: WaitingViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(uiState.navigateToCall) {
                if (uiState.navigateToCall) {
                    navController.navigate(
                        Routes.customerCall(
                            sessionId = viewModel.sessionIdPublic,
                            joinCode = uiState.joinCode,
                        ),
                    ) {
                        popUpTo(Routes.HOME) { inclusive = false }
                    }
                    viewModel.onNavigatedToCall()
                }
            }

            LaunchedEffect(uiState.navigateHome) {
                if (uiState.navigateHome) {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                    viewModel.onNavigatedHome()
                }
            }

            WaitingScreen(
                uiState = uiState,
                onCancel = viewModel::cancelSession,
            )
        }

        composable(
            route = Routes.CUSTOMER_CALL,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("joinCode") { type = NavType.StringType },
            ),
        ) {
            CallRoute(
                navController = navController,
                homeRoute = Routes.HOME,
                isCustomer = true,
            )
        }

        composable(
            route = Routes.TECHNICIAN_CALL,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("joinCode") { type = NavType.StringType },
            ),
        ) {
            CallRoute(
                navController = navController,
                homeRoute = Routes.HOME,
                isCustomer = false,
            )
        }

        composable(Routes.LOCAL_TUTORIAL) {
            val viewModel: LocalTutorialViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val context = LocalContext.current

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { results ->
                val granted = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                    .all { results[it] == true }
                viewModel.onPermissionsResult(granted)
            }

            LaunchedEffect(Unit) {
                val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                val allGranted = perms.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
                if (allGranted) viewModel.onPermissionsResult(true)
                else permissionLauncher.launch(perms)
            }

            LocalTutorialScreen(
                uiState = uiState,
                arCoreManager = viewModel.arCoreManager,
                onStartRecording = viewModel::requestStartRecording,
                onEndTutorial = viewModel::endTutorial,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SESSION_ENDED) {
            SessionEndedScreen(
                onBackToHome = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
            )
        }
    }
}

@Composable
private fun CallRoute(
    navController: androidx.navigation.NavHostController,
    homeRoute: String,
    isCustomer: Boolean,
) {
    val viewModel: CallViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val room by viewModel.roomState.collectAsStateWithLifecycle()
    val remoteVideoTrack by viewModel.remoteVideoTrack.collectAsStateWithLifecycle()
    val annotationStrokes by viewModel.annotationStrokes.collectAsStateWithLifecycle()
    val draftStroke by viewModel.draftStroke.collectAsStateWithLifecycle()
    val pointerOverlay by viewModel.pointerOverlay.collectAsStateWithLifecycle()
    val activeTool by viewModel.activeTool.collectAsStateWithLifecycle()
    val activeColor by viewModel.activeColor.collectAsStateWithLifecycle()
    val chatMessages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val sharedFiles by viewModel.sharedFiles.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isPremium = com.ggsapple.remotear.BuildConfig.IS_PREMIUM

    val requiredPermissions = if (isCustomer) {
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    } else {
        arrayOf(Manifest.permission.RECORD_AUDIO)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val granted = requiredPermissions.all { permission -> results[permission] == true }
        viewModel.onPermissionsResult(granted)
    }

    val recordingLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onRecordingPermissionResult(result.resultCode, result.data)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null && isPremium) {
            viewModel.shareFile(uri) { picked ->
                context.contentResolver.openInputStream(picked)
            }
        }
    }

    LaunchedEffect(uiState.pendingRecordingIntent) {
        if (uiState.pendingRecordingIntent && isPremium) {
            recordingLauncher.launch(viewModel.createRecordingIntent())
        }
    }

    LaunchedEffect(isCustomer) {
        if (!isCustomer) {
            viewModel.loadModels()
        }
    }

    LaunchedEffect(isCustomer) {
        val allGranted = requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            viewModel.onPermissionsResult(true)
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    LaunchedEffect(uiState.navigateToSessionEnded) {
        if (uiState.navigateToSessionEnded) {
            navController.navigate(Routes.SESSION_ENDED) {
                popUpTo(homeRoute) { inclusive = false }
            }
            viewModel.onNavigatedToSessionEnded()
        }
    }

    val openSharedFile: (com.ggsapple.remotear.data.realtime.SharedFileNotice) -> Unit = { file ->
        runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(file.fileUrl))
            context.startActivity(intent)
        }.onFailure {
            viewModel.showToast("Could not open file")
        }
    }

    if (isCustomer) {
        CustomerCallScreen(
            uiState = uiState,
            arCoreManager = viewModel.arCoreManagerRef,
            annotationStrokes = annotationStrokes,
            draftStroke = draftStroke,
            pointerOverlay = pointerOverlay,
            activeTool = activeTool,
            activeColor = activeColor,
            chatMessages = if (isPremium) chatMessages else emptyList(),
            sharedFiles = if (isPremium) sharedFiles else emptyList(),
            isPremium = isPremium,
            onViewSizeChanged = viewModel::onAnnotationViewSize,
            onDraftChanged = viewModel::setDraftStroke,
            onStrokeStreaming = { },
            onStrokeCommitted = viewModel::commitAnnotationStroke,
            onSidebarToolSelected = viewModel::setSidebarTool,
            onToggleMute = viewModel::toggleMute,
            onToggleSpeaker = viewModel::toggleSpeaker,
            onTogglePause = viewModel::toggleVideoPaused,
            onEndSession = viewModel::endSession,
            onOpenSessionMenu = { if (isPremium) viewModel.openSessionMenu() },
            onDismissSessionPanel = viewModel::dismissSessionPanel,
            onOpenChat = { if (isPremium) viewModel.openChat() },
            onOpenFiles = { if (isPremium) viewModel.openFiles() },
            onChatInputChange = viewModel::setChatInput,
            onSendChat = viewModel::sendChatMessage,
            onPickFile = { if (isPremium) filePickerLauncher.launch("*/*") },
            onOpenSharedFile = openSharedFile,
            onStartRecording = { if (isPremium) viewModel.requestStartRecording() },
            onStopRecording = viewModel::stopRecording,
        )
    } else {
        TechnicianCallScreen(
            uiState = uiState,
            room = room,
            remoteVideoTrack = remoteVideoTrack,
            annotationStrokes = annotationStrokes,
            draftStroke = draftStroke,
            activeTool = activeTool,
            activeColor = activeColor,
            chatMessages = if (isPremium) chatMessages else emptyList(),
            sharedFiles = if (isPremium) sharedFiles else emptyList(),
            isPremium = isPremium,
            onViewSizeChanged = viewModel::onAnnotationViewSize,
            onDraftChanged = viewModel::setDraftStroke,
            onStrokeStreaming = viewModel::streamAnnotationStroke,
            onStrokeCommitted = viewModel::commitAnnotationStroke,
            onPointerEvent = viewModel::streamPointer,
            onSidebarToolSelected = viewModel::setSidebarTool,
            onToggleMute = viewModel::toggleMute,
            onToggleSpeaker = viewModel::toggleSpeaker,
            onTogglePause = viewModel::toggleVideoPaused,
            onEndSession = viewModel::endSession,
            onOpenSessionMenu = { if (isPremium) viewModel.openSessionMenu() },
            onDismissSessionPanel = viewModel::dismissSessionPanel,
            onOpenChat = { if (isPremium) viewModel.openChat() },
            onOpenFiles = { if (isPremium) viewModel.openFiles() },
            onChatInputChange = viewModel::setChatInput,
            onSendChat = viewModel::sendChatMessage,
            onPickFile = { if (isPremium) filePickerLauncher.launch("*/*") },
            onOpenSharedFile = openSharedFile,
            onStartRecording = { if (isPremium) viewModel.requestStartRecording() },
            onStopRecording = viewModel::stopRecording,
            onToggleBottomSheet = viewModel::toggleBottomSheetExpanded,
            onSearchQueryChange = viewModel::setAssetSearchQuery,
            onModelSelected = viewModel::selectModel,
            onDismissModelDetail = viewModel::dismissModelDetail,
            onPlaceModel = viewModel::placeSelectedModel,
        )
    }
}
