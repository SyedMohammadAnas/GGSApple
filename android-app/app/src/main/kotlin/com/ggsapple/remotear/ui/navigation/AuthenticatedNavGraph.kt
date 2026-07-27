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
import com.ggsapple.remotear.ui.tutorial.LocalTutorialScreen
import com.ggsapple.remotear.ui.tutorial.LocalTutorialViewModel
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

            AssistHomeScreen(
                profile = profile,
                uiState = uiState,
                onShareId = viewModel::shareId,
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
            route = Routes.CUSTOMER_CALL,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("joinCode") { type = NavType.StringType },
            ),
        ) {
            CustomerCallRoute(
                navController = navController,
                homeRoute = Routes.HOME,
                onReturnHome = {
                    // Resume customer-enter polling when popping back to home.
                },
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
private fun CustomerCallRoute(
    navController: androidx.navigation.NavHostController,
    homeRoute: String,
    onReturnHome: () -> Unit,
) {
    val viewModel: CallViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val annotationStrokes by viewModel.annotationStrokes.collectAsStateWithLifecycle()
    val draftStroke by viewModel.draftStroke.collectAsStateWithLifecycle()
    val pointerOverlay by viewModel.pointerOverlay.collectAsStateWithLifecycle()
    val activeTool by viewModel.activeTool.collectAsStateWithLifecycle()
    val activeColor by viewModel.activeColor.collectAsStateWithLifecycle()
    val chatMessages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val sharedFiles by viewModel.sharedFiles.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val requiredPermissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

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
        if (uri != null) {
            viewModel.shareFile(uri) { picked ->
                context.contentResolver.openInputStream(picked)
            }
        }
    }

    LaunchedEffect(uiState.pendingRecordingIntent) {
        if (uiState.pendingRecordingIntent) {
            recordingLauncher.launch(viewModel.createRecordingIntent())
        }
    }

    LaunchedEffect(Unit) {
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
            onReturnHome()
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

    CustomerCallScreen(
        uiState = uiState,
        arCoreManager = viewModel.arCoreManagerRef,
        annotationStrokes = annotationStrokes,
        draftStroke = draftStroke,
        pointerOverlay = pointerOverlay,
        activeTool = activeTool,
        activeColor = activeColor,
        chatMessages = chatMessages,
        sharedFiles = sharedFiles,
        onViewSizeChanged = viewModel::onAnnotationViewSize,
        onDraftChanged = viewModel::setDraftStroke,
        onStrokeStreaming = { },
        onStrokeCommitted = viewModel::commitAnnotationStroke,
        onSidebarToolSelected = viewModel::setSidebarTool,
        onToggleMute = viewModel::toggleMute,
        onToggleSpeaker = viewModel::toggleSpeaker,
        onTogglePause = viewModel::toggleVideoPaused,
        onEndSession = viewModel::endSession,
        onOpenSessionMenu = viewModel::openSessionMenu,
        onDismissSessionPanel = viewModel::dismissSessionPanel,
        onOpenChat = viewModel::openChat,
        onOpenFiles = viewModel::openFiles,
        onChatInputChange = viewModel::setChatInput,
        onSendChat = viewModel::sendChatMessage,
        onPickFile = { filePickerLauncher.launch("*/*") },
        onOpenSharedFile = openSharedFile,
        onStartRecording = viewModel::requestStartRecording,
        onStopRecording = viewModel::stopRecording,
    )
}
