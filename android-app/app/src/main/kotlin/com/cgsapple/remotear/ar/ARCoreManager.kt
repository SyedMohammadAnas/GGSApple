package com.cgsapple.remotear.ar

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.opengl.GLSurfaceView
import android.os.ConditionVariable
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.cgsapple.remotear.annotation.AnnotationController
import com.cgsapple.remotear.annotation.HOST_VIDEO_HEIGHT
import com.cgsapple.remotear.annotation.HOST_VIDEO_WIDTH
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.SharedCamera
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.EnumSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ARCoreManager @Inject constructor(
    private val annotationController: AnnotationController,
) {
    @Volatile
    private var tutorialFrameSink: ((android.media.Image, Int) -> Unit)? = null

    fun setTutorialFrameSink(sink: ((android.media.Image, Int) -> Unit)?) {
        tutorialFrameSink = sink
    }

    val frameCapturer = ARCoreFrameCapturer()

    private val arMode = AtomicBoolean(true)
    private val shouldUpdateSurfaceTexture = AtomicBoolean(false)
    private val arcoreActive = AtomicBoolean(false)
    private val arcoreResumeFailed = AtomicBoolean(false)
    private val safeToExitApp = ConditionVariable()

    @Volatile
    private var activity: ComponentActivity? = null

    @Volatile
    private var glSurfaceView: GLSurfaceView? = null

    @Volatile
    private var renderer: ArCameraRenderer? = null

    @Volatile
    private var displayRotationHelper: DisplayRotationHelper? = null

    @Volatile
    private var sharedSession: Session? = null

    @Volatile
    private var sharedCamera: SharedCamera? = null

    @Volatile
    private var captureSession: CameraCaptureSession? = null

    @Volatile
    private var cameraDevice: CameraDevice? = null

    @Volatile
    private var cameraId: String? = null

    @Volatile
    private var previewCaptureRequestBuilder: CaptureRequest.Builder? = null

    @Volatile
    private var cpuImageReader: ImageReader? = null

    @Volatile
    private var backgroundThread: HandlerThread? = null

    @Volatile
    private var backgroundHandler: Handler? = null

    @Volatile
    private var cameraTextureId = -1

    @Volatile
    private var rendererBound = false

    @Volatile
    private var isDestroyed = false

    @Volatile
    private var surfaceCreated = false

    @Volatile
    private var captureSessionChangesPossible = true

    @Volatile
    private var viewportWidth = 0

    @Volatile
    private var viewportHeight = 0

    @Volatile
    private var captureWidth = HOST_VIDEO_WIDTH

    @Volatile
    private var captureHeight = HOST_VIDEO_HEIGHT

    private var trackingPlaneCount = 0
    private var surfaceFoundShown = false

    private val _arcoreActive = MutableStateFlow(false)
    val arcoreActiveState: StateFlow<Boolean> = _arcoreActive.asStateFlow()

    private val _fallbackActive = MutableStateFlow(false)
    val fallbackActive: StateFlow<Boolean> = _fallbackActive.asStateFlow()

    private val _streamingReady = MutableStateFlow(false)
    val streamingReady: StateFlow<Boolean> = _streamingReady.asStateFlow()

    private val _trackingUiState = MutableStateFlow(ArTrackingUiState.SCANNING)
    val trackingUiState: StateFlow<ArTrackingUiState> = _trackingUiState.asStateFlow()

    private val _planeCount = MutableStateFlow(0)
    val planeCount: StateFlow<Int> = _planeCount.asStateFlow()

    fun attach(activity: ComponentActivity) {
        isDestroyed = false
        this.activity = activity
        // New call screen — allow a fresh AR attempt (singleton survives across sessions).
        arcoreResumeFailed.set(false)
        _fallbackActive.value = false
        releaseCaptureWaitLock()
        if (displayRotationHelper == null) {
            displayRotationHelper = DisplayRotationHelper(activity)
        }
        if (renderer == null) {
            renderer = ArCameraRenderer(this, displayRotationHelper!!)
        }
    }

    fun bindGlSurface(glSurfaceView: GLSurfaceView) {
        val glRenderer = renderer
        if (glRenderer == null) {
            Log.e(TAG, "bindGlSurface called before attach()")
            return
        }
        if (this.glSurfaceView === glSurfaceView && rendererBound) {
            return
        }

        this.glSurfaceView = glSurfaceView
        glSurfaceView.preserveEGLContextOnPause = true
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        if (!rendererBound) {
            glSurfaceView.setRenderer(glRenderer)
            rendererBound = true
        }
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    fun getRenderer(): ArCameraRenderer? = renderer

    fun getSession(): Session? = sharedSession

    fun isArcoreActive(): Boolean = arcoreActive.get()

    fun shouldUpdateSurfaceTexture(): Boolean = shouldUpdateSurfaceTexture.get()

    fun getCaptureSize(): Size = Size(captureWidth, captureHeight)

    fun onGlSurfaceCreated(textureId: Int) {
        cameraTextureId = textureId
        surfaceCreated = true
        val hostActivity = activity ?: return
        hostActivity.runOnUiThread {
            if (hasCameraPermission()) {
                openCamera()
            }
        }
    }

    fun onViewportChanged(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        if (width > 0 && height > 0) {
            annotationController.updateViewSize(width, height)
        }
        val session = sharedSession ?: return
        if (width > 0 && height > 0) {
            session.setDisplayGeometry(
                activity?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0,
                width,
                height,
            )
        }
    }

    fun onCameraPermissionGranted() {
        val hostActivity = activity ?: return
        hostActivity.runOnUiThread {
            startBackgroundThread()
            if (surfaceCreated) {
                openCamera()
            }
        }
    }

    fun onResume() {
        displayRotationHelper?.onResume()
        releaseCaptureWaitLock()
        startBackgroundThread()
        if (surfaceCreated && !_fallbackActive.value) {
            openCamera()
        }
    }

    fun onPause() {
        shouldUpdateSurfaceTexture.set(false)
        releaseCaptureWaitLock()
        if (arMode.get()) {
            pauseARCore()
        }
        closeCamera()
        stopBackgroundThread()
        _streamingReady.value = false
    }

    fun onDestroy() {
        if (isDestroyed) {
            return
        }
        isDestroyed = true
        pauseARCore()
        closeCamera()
        try {
            sharedSession?.close()
        } catch (error: Exception) {
            Log.w(TAG, "session.close failed", error)
        }
        sharedSession = null
        sharedCamera = null
        surfaceCreated = false
        cameraTextureId = -1
        rendererBound = false
        arcoreResumeFailed.set(false)
        surfaceFoundShown = false
        trackingPlaneCount = 0
        releaseCaptureWaitLock()
        _arcoreActive.value = false
        _fallbackActive.value = false
        _streamingReady.value = false
        _planeCount.value = 0
        _trackingUiState.value = ArTrackingUiState.SCANNING
        activity = null
        glSurfaceView = null
    }

    fun onFrameUpdated(frame: Frame) {
        val camera = frame.camera
        val planes =
            sharedSession
                ?.getAllTrackables(Plane::class.java)
                ?.count { plane ->
                    plane.trackingState == TrackingState.TRACKING && plane.subsumedBy == null
                }
                ?: 0

        if (planes != trackingPlaneCount) {
            trackingPlaneCount = planes
            _planeCount.value = planes
        }

        _trackingUiState.value =
            when {
                _fallbackActive.value -> ArTrackingUiState.STABLE
                planes > 0 && !surfaceFoundShown -> {
                    surfaceFoundShown = true
                    ArTrackingUiState.SURFACE_FOUND
                }
                planes > 0 -> ArTrackingUiState.STABLE
                camera.trackingState == TrackingState.PAUSED -> ArTrackingUiState.SCANNING
                camera.trackingState == TrackingState.STOPPED -> ArTrackingUiState.TRACKING_LOST
                else -> ArTrackingUiState.SCANNING
            }

        if (viewportWidth > 0 && viewportHeight > 0) {
            sharedSession?.let { session ->
                annotationController.processFrame(session, frame)
            }
        }
    }

    private fun openCamera() {
        val hostActivity = activity ?: return
        if (isDestroyed || _fallbackActive.value) {
            return
        }
        startBackgroundThread()
        if (cameraDevice != null) {
            return
        }
        if (!hasCameraPermission()) {
            return
        }
        when (checkArCoreAvailability(hostActivity)) {
            ArCoreAvailability.READY -> Unit
            ArCoreAvailability.CHECKING -> {
                hostActivity.window.decorView.postDelayed({ openCamera() }, AR_CHECK_RETRY_MS)
                return
            }
            ArCoreAvailability.UNAVAILABLE -> {
                activateFallback("ARCore not supported on this device")
                return
            }
        }

        if (sharedSession == null) {
            try {
                sharedSession = Session(hostActivity, EnumSet.of(Session.Feature.SHARED_CAMERA))
            } catch (error: Exception) {
                Log.e(TAG, "Failed to create ARCore SHARED_CAMERA session", error)
                activateFallback("ARCore session create failed")
                return
            }

            val config = sharedSession!!.config
            config.focusMode = Config.FocusMode.AUTO
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            sharedSession!!.configure(config)
        }

        sharedCamera = sharedSession!!.sharedCamera
        cameraId = sharedSession!!.cameraConfig.cameraId

        val desiredCpuImageSize = sharedSession!!.cameraConfig.imageSize
        captureWidth = desiredCpuImageSize.width
        captureHeight = desiredCpuImageSize.height

        cpuImageReader?.close()
        cpuImageReader =
            ImageReader.newInstance(
                desiredCpuImageSize.width,
                desiredCpuImageSize.height,
                ImageFormat.YUV_420_888,
                2,
            ).also { reader ->
                reader.setOnImageAvailableListener({ reader2 -> onImageAvailable(reader2) }, backgroundHandler)
            }

        sharedCamera!!.setAppSurfaces(cameraId!!, listOf(cpuImageReader!!.surface))

        try {
            val wrappedCallback =
                sharedCamera!!.createARDeviceStateCallback(cameraDeviceCallback, backgroundHandler)
            val cameraManager = hostActivity.getSystemService(CameraManager::class.java)
            captureSessionChangesPossible = false
            cameraManager.openCamera(cameraId!!, wrappedCallback, backgroundHandler)
        } catch (error: Exception) {
            Log.e(TAG, "Failed to open camera", error)
            releaseCaptureWaitLock()
            activateFallback("Open camera failed")
        }
    }

    private enum class ArCoreAvailability {
        READY,
        CHECKING,
        UNAVAILABLE,
    }

    private fun checkArCoreAvailability(hostActivity: ComponentActivity): ArCoreAvailability {
        return when (ArCoreApk.getInstance().checkAvailability(hostActivity)) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> ArCoreAvailability.READY
            ArCoreApk.Availability.UNKNOWN_CHECKING -> ArCoreAvailability.CHECKING
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
            -> {
                val installStatus = ArCoreApk.getInstance().requestInstall(hostActivity, true)
                if (installStatus == ArCoreApk.InstallStatus.INSTALLED) {
                    ArCoreAvailability.READY
                } else {
                    ArCoreAvailability.UNAVAILABLE
                }
            }
            else -> ArCoreAvailability.UNAVAILABLE
        }
    }

    private val cameraDeviceCallback =
        object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                createCameraPreviewSession()
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                cameraDevice = null
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                cameraDevice = null
                activateFallback("Camera device error: $error")
            }
        }

    private val cameraSessionStateCallback =
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                if (arMode.get()) {
                    setRepeatingCaptureRequest()
                }
            }

            override fun onActive(session: CameraCaptureSession) {
                if (arMode.get() && !arcoreActive.get() && !arcoreResumeFailed.get()) {
                    resumeARCore()
                }
                synchronized(this@ARCoreManager) {
                    captureSessionChangesPossible = true
                    (this@ARCoreManager as Object).notifyAll()
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Failed to configure camera capture session.")
                activateFallback("Capture session configuration failed")
            }
        }

    private val cameraCaptureCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                shouldUpdateSurfaceTexture.set(true)
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure,
            ) {
                Log.e(TAG, "onCaptureFailed: ${failure.frameNumber} ${failure.reason}")
            }
        }

    private fun createCameraPreviewSession() {
        val session = sharedSession ?: return
        val sc = sharedCamera ?: return
        val device = cameraDevice ?: return
        val hostActivity = activity ?: return
        val surfaceView = glSurfaceView ?: return
        if (cameraTextureId < 0) {
            return
        }

        surfaceView.queueEvent {
            try {
                if (viewportWidth > 0 && viewportHeight > 0) {
                    session.setDisplayGeometry(
                        hostActivity.windowManager.defaultDisplay.rotation,
                        viewportWidth,
                        viewportHeight,
                    )
                }
                session.setCameraTextureName(cameraTextureId)
            } catch (error: Exception) {
                Log.e(TAG, "Failed to set camera texture on GL thread", error)
                hostActivity.runOnUiThread {
                    activateFallback("Camera texture setup failed")
                }
                return@queueEvent
            }

            backgroundHandler?.post {
                createCameraPreviewSessionOnCameraThread(session, sc, device)
            }
        }
    }

    private fun createCameraPreviewSessionOnCameraThread(
        session: Session,
        sc: SharedCamera,
        device: CameraDevice,
    ) {
        try {
            sc.surfaceTexture.setOnFrameAvailableListener(
                { glSurfaceView?.requestRender() },
                backgroundHandler,
            )

            previewCaptureRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            val surfaceList = ArrayList<Surface>(sc.arCoreSurfaces)
            surfaceList.add(cpuImageReader!!.surface)
            surfaceList.forEach { previewCaptureRequestBuilder!!.addTarget(it) }

            val wrappedCallback =
                sc.createARSessionStateCallback(cameraSessionStateCallback, backgroundHandler)
            device.createCaptureSession(surfaceList, wrappedCallback, backgroundHandler)
        } catch (error: Exception) {
            Log.e(TAG, "Failed to create preview session", error)
            activity?.runOnUiThread {
                activateFallback("Preview session failed")
            }
        }
    }

    private fun setRepeatingCaptureRequest() {
        try {
            val builder = previewCaptureRequestBuilder ?: return
            captureSession?.setRepeatingRequest(builder.build(), cameraCaptureCallback, backgroundHandler)
        } catch (error: CameraAccessException) {
            Log.e(TAG, "Failed to set repeating request", error)
        }
    }

    private fun resumeARCore() {
        val session = sharedSession ?: return
        if (arcoreActive.get() || arcoreResumeFailed.get()) {
            return
        }

        runGlBeforeResume {
            val hostActivity = activity ?: return@runGlBeforeResume
            hostActivity.runOnUiThread {
                try {
                    if (viewportWidth > 0 && viewportHeight > 0) {
                        session.setDisplayGeometry(
                            hostActivity.windowManager.defaultDisplay.rotation,
                            viewportWidth,
                            viewportHeight,
                        )
                    }

                    session.resume()
                    arcoreActive.set(true)
                    arcoreResumeFailed.set(false)
                    sharedCamera?.setCaptureCallback(cameraCaptureCallback, backgroundHandler)
                    _arcoreActive.value = true
                    _fallbackActive.value = false
                    _streamingReady.value = true
                    glSurfaceView?.requestRender()
                    Log.i(TAG, "Shared camera active, ARCore resumed")
                } catch (error: Exception) {
                    handleResumeFailure(error)
                }
            }
        }
    }

    private fun handleResumeFailure(error: Exception) {
        Log.e(TAG, "Failed to resume ARCore session (${error.javaClass.simpleName})", error)
        arcoreResumeFailed.set(true)
        arcoreActive.set(false)
        _arcoreActive.value = false
        activateFallback("ARCore resume failed — video mode only")
    }

    private fun activateFallback(reason: String) {
        Log.w(TAG, reason)
        arcoreResumeFailed.set(true)
        arcoreActive.set(false)
        _arcoreActive.value = false
        pauseARCore()
        closeCamera()
        try {
            sharedSession?.close()
        } catch (error: Exception) {
            Log.w(TAG, "session.close during fallback failed", error)
        }
        sharedSession = null
        sharedCamera = null
        releaseCaptureWaitLock()
        _fallbackActive.value = true
        _streamingReady.value = true
        _trackingUiState.value = ArTrackingUiState.STABLE
    }

    private fun releaseCaptureWaitLock() {
        synchronized(this) {
            captureSessionChangesPossible = true
            (this as Object).notifyAll()
        }
    }

    private fun pauseARCore() {
        if (!arcoreActive.getAndSet(false)) {
            return
        }
        try {
            sharedSession?.pause()
        } catch (error: CameraNotAvailableException) {
            Log.w(TAG, "pauseARCore failed", error)
        }
        _arcoreActive.value = false
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            shouldUpdateSurfaceTexture.set(true)
            val rotation = cameraId?.let { displayRotationHelper?.getCameraSensorToDisplayRotation(it) } ?: 90
            frameCapturer.updateRotation(rotation)
            tutorialFrameSink?.invoke(image, rotation)
            frameCapturer.pushImage(image)
        } finally {
            image.close()
        }
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null

        cameraDevice?.let { device ->
            releaseCaptureWaitLock()
            safeToExitApp.close()
            device.close()
            safeToExitApp.block(3000)
            cameraDevice = null
        }

        cpuImageReader?.close()
        cpuImageReader = null
    }

    private fun startBackgroundThread() {
        if (backgroundThread != null) {
            return
        }
        backgroundThread = HandlerThread("sharedCameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (error: InterruptedException) {
            Log.e(TAG, "Interrupted stopping background thread", error)
        }
        backgroundThread = null
        backgroundHandler = null
    }

    private fun runGlBeforeResume(onGlReady: () -> Unit) {
        val surfaceView = glSurfaceView ?: run {
            onGlReady()
            return
        }
        val latch = CountDownLatch(1)
        surfaceView.queueEvent {
            renderer?.onBeforeResumeArcore()
            latch.countDown()
        }
        Thread {
            latch.await(3, TimeUnit.SECONDS)
            onGlReady()
        }.start()
    }

    private fun hasCameraPermission(): Boolean {
        val hostActivity = activity ?: return false
        return ContextCompat.checkSelfPermission(hostActivity, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "ARCoreManager"
        private const val AR_CHECK_RETRY_MS = 250L
    }
}
