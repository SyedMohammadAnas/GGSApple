package com.ggsapple.remotear.ar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.ggsapple.remotear.annotation.AnnotationController
import com.ggsapple.remotear.annotation.HOST_VIDEO_HEIGHT
import com.ggsapple.remotear.annotation.HOST_VIDEO_WIDTH
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    /** True while we are PixelCopying the customer GL view into LiveKit (iOS customerPOV). */
    private val povStreaming = AtomicBoolean(false)
    private val povHandler = Handler(Looper.getMainLooper())
    private val povTick =
        object : Runnable {
            override fun run() {
                capturePovFrame()
                if (povStreaming.get()) {
                    povHandler.postDelayed(this, PovCompositeEncoder.FRAME_INTERVAL_MS)
                }
            }
        }

    private val arMode = AtomicBoolean(true)
    private val shouldUpdateSurfaceTexture = AtomicBoolean(false)
    private val arcoreActive = AtomicBoolean(false)
    private val arcoreResumeFailed = AtomicBoolean(false)
    private val lifecycleResumed = AtomicBoolean(false)
    private val lifecycleGeneration = AtomicInteger(0)

    /**
     * Google ARCore install flow: first requestInstall(..., true) may return
     * INSTALL_REQUESTED. Subsequent resumes must pass false so we don't loop
     * the Play Store prompt forever (official ARCore sample pattern).
     */
    @Volatile
    private var userRequestedInstall = true

    /** True while Play Services for AR install/update is in flight — do not fall back to CameraX yet. */
    private val awaitingArInstall = AtomicBoolean(false)

    /**
     * Serializes Camera2 open + discards stale onOpened / preview-session work.
     * Concurrent openCamera() (onResume + GL surface + permission + AR check retries) was closing
     * the device mid-setup → "CameraDevice was already closed" → permanent CameraX fallback.
     */
    private val cameraOpenGeneration = AtomicInteger(0)
    private val cameraOpenInFlight = AtomicBoolean(false)
    private val previewCreateRetries = AtomicInteger(0)

    @Volatile
    private var openingGeneration = 0

    /**
     * Samsung One UI 8 / ARCore 1.54 workaround: keep uncalibrated IMU sensors streaming
     * before Session.create/resume so ARCore's EnableSensor does not hit a fatal queueBatch path.
     * See https://github.com/google-ar/arcore-android-sdk/issues/1762
     */
    @Volatile
    private var sensorManager: SensorManager? = null

    private val sensorKeepAliveActive = AtomicBoolean(false)

    private val arSensorKeepAliveListener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) = Unit

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

    private val arKeepAliveSensorTypes =
        intArrayOf(
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
        )

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
        lifecycleGeneration.incrementAndGet()
        // New call screen — allow a fresh AR attempt (singleton survives across sessions).
        arcoreResumeFailed.set(false)
        awaitingArInstall.set(false)
        userRequestedInstall = true
        _fallbackActive.value = false
        cameraOpenInFlight.set(false)
        previewCreateRetries.set(0)
        cameraOpenGeneration.incrementAndGet()
        if (displayRotationHelper == null) {
            displayRotationHelper = DisplayRotationHelper(activity)
        }
        if (renderer == null) {
            renderer = ArCameraRenderer(this, displayRotationHelper!!)
        }
        // Warm IMU keep-alive as early as possible on Samsung Android 16.
        startArSensorKeepAlive()
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

        val isNewSurface = this.glSurfaceView !== glSurfaceView
        this.glSurfaceView = glSurfaceView
        glSurfaceView.preserveEGLContextOnPause = true
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        if (isNewSurface || !rendererBound) {
            glSurfaceView.setRenderer(glRenderer)
            rendererBound = true
        }
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        if (lifecycleResumed.get()) {
            glSurfaceView.onResume()
        }
        if (arcoreActive.get() && !_fallbackActive.value) {
            startPovStreaming()
        }
    }

    fun getRenderer(): ArCameraRenderer? = renderer

    fun getSession(): Session? = sharedSession

    fun isArcoreActive(): Boolean = arcoreActive.get()

    fun shouldUpdateSurfaceTexture(): Boolean = shouldUpdateSurfaceTexture.get()

    fun getCaptureSize(): Size =
        if (_fallbackActive.value) {
            Size(HOST_VIDEO_WIDTH, HOST_VIDEO_HEIGHT)
        } else {
            Size(PovCompositeEncoder.TARGET_WIDTH, PovCompositeEncoder.TARGET_HEIGHT)
        }

    fun onGlSurfaceCreated(textureId: Int) {
        cameraTextureId = textureId
        surfaceCreated = true
        val hostActivity = activity ?: return
        hostActivity.runOnUiThread {
            if (lifecycleResumed.get() && hasCameraPermission()) {
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
    }

    fun onCameraPermissionGranted() {
        val hostActivity = activity ?: return
        hostActivity.runOnUiThread {
            if (!lifecycleResumed.get() || isDestroyed) {
                return@runOnUiThread
            }
            startBackgroundThread()
            if (surfaceCreated) {
                openCamera()
            }
        }
    }

    fun onResume() {
        if (isDestroyed) {
            return
        }
        lifecycleResumed.set(true)
        lifecycleGeneration.incrementAndGet()
        displayRotationHelper?.onResume()
        glSurfaceView?.onResume()
        startBackgroundThread()
        // Returning from Play Store after AR install: clear premature failure flags and retry.
        if (awaitingArInstall.get()) {
            Log.i(TAG, "onResume while awaiting AR install — retrying openCamera()")
            arcoreResumeFailed.set(false)
            _fallbackActive.value = false
        }
        startArSensorKeepAlive()
        if (surfaceCreated && hasCameraPermission() && !_fallbackActive.value) {
            openCamera()
        }
    }

    fun onPause() {
        lifecycleResumed.set(false)
        lifecycleGeneration.incrementAndGet()
        shouldUpdateSurfaceTexture.set(false)
        stopPovStreaming()
        glSurfaceView?.onPause()
        if (arMode.get()) {
            pauseARCore()
        }
        closeCamera()
        displayRotationHelper?.onPause()
        stopBackgroundThread()
        stopArSensorKeepAlive()
        _streamingReady.value = false
    }

    fun onDestroy() {
        if (isDestroyed) {
            return
        }
        isDestroyed = true
        lifecycleResumed.set(false)
        lifecycleGeneration.incrementAndGet()
        glSurfaceView?.onPause()
        displayRotationHelper?.onPause()
        stopPovStreaming()
        pauseARCore()
        closeCamera()
        stopBackgroundThread()
        stopArSensorKeepAlive()
        frameCapturer.stopCapture()
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
        awaitingArInstall.set(false)
        userRequestedInstall = true
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
        if (isDestroyed || !lifecycleResumed.get() || _fallbackActive.value) {
            Log.i(
                TAG,
                "openCamera skipped — destroyed=$isDestroyed " +
                    "resumed=${lifecycleResumed.get()} fallback=${_fallbackActive.value}",
            )
            return
        }
        val lifecycleToken = lifecycleGeneration.get()
        startBackgroundThread()
        if (cameraDevice != null) {
            Log.i(TAG, "openCamera skipped — cameraDevice already open")
            return
        }
        if (!cameraOpenInFlight.compareAndSet(false, true)) {
            Log.i(TAG, "openCamera skipped — open already in flight")
            return
        }
        if (!hasCameraPermission()) {
            cameraOpenInFlight.set(false)
            return
        }
        when (checkArCoreAvailability(hostActivity)) {
            ArCoreAvailability.READY -> Unit
            ArCoreAvailability.CHECKING -> {
                cameraOpenInFlight.set(false)
                _trackingUiState.value = ArTrackingUiState.SCANNING
                hostActivity.window.decorView.postDelayed(
                    {
                        if (isLifecycleCurrent(lifecycleToken)) {
                            openCamera()
                        }
                    },
                    AR_CHECK_RETRY_MS,
                )
                return
            }
            ArCoreAvailability.INSTALLING -> {
                // Play Store / AR services install in progress — wait for onResume, do NOT fall back.
                cameraOpenInFlight.set(false)
                awaitingArInstall.set(true)
                _trackingUiState.value = ArTrackingUiState.INSTALLING
                Log.i(TAG, "ARCore install requested — waiting for onResume before opening camera")
                return
            }
            ArCoreAvailability.UNAVAILABLE -> {
                cameraOpenInFlight.set(false)
                activateFallback("ARCore not supported on this device")
                return
            }
        }

        awaitingArInstall.set(false)

        // Must start before Session(SHARED_CAMERA) / resume on Samsung Android 16 + ARCore 1.54.
        startArSensorKeepAlive()

        if (sharedSession == null) {
            try {
                sharedSession = Session(hostActivity, EnumSet.of(Session.Feature.SHARED_CAMERA))
            } catch (error: Exception) {
                cameraOpenInFlight.set(false)
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
            openingGeneration = cameraOpenGeneration.incrementAndGet()
            Log.i(TAG, "openCamera() generation=$openingGeneration")
            val wrappedCallback =
                sharedCamera!!.createARDeviceStateCallback(cameraDeviceCallback, backgroundHandler)
            val cameraManager = hostActivity.getSystemService(CameraManager::class.java)
            if (ContextCompat.checkSelfPermission(
                    hostActivity,
                    Manifest.permission.CAMERA,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                cameraOpenInFlight.set(false)
                Log.w(TAG, "Camera permission was revoked before opening the camera")
                return
            }
            cameraManager.openCamera(cameraId!!, wrappedCallback, backgroundHandler)
        } catch (error: Exception) {
            cameraOpenInFlight.set(false)
            Log.e(TAG, "Failed to open camera", error)
            activateFallback("Open camera failed")
        }
    }

    private enum class ArCoreAvailability {
        READY,
        CHECKING,
        INSTALLING,
        UNAVAILABLE,
    }

    private fun checkArCoreAvailability(hostActivity: ComponentActivity): ArCoreAvailability {
        return when (val availability = ArCoreApk.getInstance().checkAvailability(hostActivity)) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> {
                awaitingArInstall.set(false)
                ArCoreAvailability.READY
            }
            ArCoreApk.Availability.UNKNOWN_CHECKING,
            ArCoreApk.Availability.UNKNOWN_TIMED_OUT,
            -> {
                // Do not treat transient checks as unsupported (known premature-fallback pitfall).
                Log.i(TAG, "ARCore availability still checking ($availability) — retry")
                ArCoreAvailability.CHECKING
            }
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
            -> {
                try {
                    val installStatus =
                        ArCoreApk.getInstance().requestInstall(hostActivity, userRequestedInstall)
                    when (installStatus) {
                        ArCoreApk.InstallStatus.INSTALLED -> {
                            awaitingArInstall.set(false)
                            userRequestedInstall = true
                            ArCoreAvailability.READY
                        }
                        ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                            // Official pattern: pause setup, set flag false, resume after Play Store.
                            userRequestedInstall = false
                            awaitingArInstall.set(true)
                            Log.i(TAG, "ARCore InstallStatus.INSTALL_REQUESTED")
                            ArCoreAvailability.INSTALLING
                        }
                        else -> {
                            Log.w(TAG, "Unexpected ARCore install status: $installStatus")
                            ArCoreAvailability.UNAVAILABLE
                        }
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "ARCore requestInstall failed", error)
                    ArCoreAvailability.UNAVAILABLE
                }
            }
            else -> {
                Log.w(TAG, "ARCore unavailable: $availability")
                ArCoreAvailability.UNAVAILABLE
            }
        }
    }

    private val cameraDeviceCallback =
        object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (isDestroyed ||
                    !lifecycleResumed.get() ||
                    _fallbackActive.value ||
                    openingGeneration != cameraOpenGeneration.get()
                ) {
                    Log.w(
                        TAG,
                        "onOpened ignored — stale/torn-down gen=$openingGeneration " +
                            "current=${cameraOpenGeneration.get()} destroyed=$isDestroyed",
                    )
                    if (openingGeneration == cameraOpenGeneration.get()) {
                        cameraOpenInFlight.set(false)
                    }
                    try {
                        device.close()
                    } catch (_: Exception) {
                    }
                    return
                }
                Log.i(TAG, "onOpened generation=$openingGeneration")
                cameraDevice = device
                cameraOpenInFlight.set(false)
                previewCreateRetries.set(0)
                createCameraPreviewSession()
            }

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "CameraDevice onDisconnected")
                cameraOpenInFlight.set(false)
                val wasCurrentDevice = cameraDevice === device
                if (wasCurrentDevice) {
                    cameraDevice = null
                }
                try {
                    device.close()
                } catch (_: Exception) {
                }
                if (wasCurrentDevice &&
                    lifecycleResumed.get() &&
                    !isDestroyed &&
                    !_fallbackActive.value
                ) {
                    activateFallback("Camera device disconnected")
                }
            }

            override fun onError(device: CameraDevice, error: Int) {
                Log.e(TAG, "CameraDevice onError code=$error")
                cameraOpenInFlight.set(false)
                if (cameraDevice === device) {
                    cameraDevice = null
                }
                try {
                    device.close()
                } catch (_: Exception) {
                }
                activateFallback("Camera device error: $error")
            }

            override fun onClosed(device: CameraDevice) {
                Log.i(TAG, "CameraDevice onClosed")
            }
        }

    private val cameraSessionStateCallback =
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                // Fallback/teardown may have already closed the device — never crash the BG thread.
                if (isDestroyed ||
                    !lifecycleResumed.get() ||
                    _fallbackActive.value ||
                    cameraDevice == null ||
                    openingGeneration != cameraOpenGeneration.get()
                ) {
                    Log.w(TAG, "onConfigured ignored — camera already torn down")
                    try {
                        session.close()
                    } catch (_: Exception) {
                    }
                    return
                }
                captureSession = session
                if (arMode.get()) {
                    setRepeatingCaptureRequest()
                }
            }

            override fun onActive(session: CameraCaptureSession) {
                if (isDestroyed || !lifecycleResumed.get() || _fallbackActive.value) {
                    return
                }
                if (arMode.get() && !arcoreActive.get() && !arcoreResumeFailed.get()) {
                    resumeARCore()
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Failed to configure camera capture session.")
                activity?.runOnUiThread {
                    activateFallback("Capture session configuration failed")
                }
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
        if (isDestroyed || !lifecycleResumed.get() || _fallbackActive.value) {
            Log.w(TAG, "createCameraPreviewSession skipped — torn down")
            return
        }
        // Stale open from a raced openCamera()/closeCamera() — do not fall back permanently.
        if (device !== cameraDevice || openingGeneration != cameraOpenGeneration.get()) {
            Log.w(
                TAG,
                "createCameraPreviewSession skipped — stale device " +
                    "(gen=$openingGeneration current=${cameraOpenGeneration.get()})",
            )
            try {
                device.close()
            } catch (_: Exception) {
            }
            return
        }
        try {
            sc.surfaceTexture.setOnFrameAvailableListener(
                { glSurfaceView?.requestRender() },
                backgroundHandler,
            )

            // Prefer TEMPLATE_PREVIEW on Samsung; only fall back to RECORD if PREVIEW is unsupported.
            previewCaptureRequestBuilder = createCaptureRequestBuilder(device)
            val surfaceList = ArrayList<Surface>(sc.arCoreSurfaces)
            surfaceList.add(cpuImageReader!!.surface)
            surfaceList.forEach { previewCaptureRequestBuilder!!.addTarget(it) }

            val wrappedCallback =
                sc.createARSessionStateCallback(cameraSessionStateCallback, backgroundHandler)
            device.createCaptureSession(surfaceList, wrappedCallback, backgroundHandler)
            Log.i(TAG, "createCaptureSession requested generation=$openingGeneration")
        } catch (error: IllegalStateException) {
            // Typical race: CameraDevice closed between onOpened and this BG work.
            Log.w(TAG, "Preview session race (camera closed)", error)
            scheduleOpenCameraRetry("Preview session race (camera closed)")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to create preview session", error)
            activity?.runOnUiThread {
                activateFallback("Preview session failed")
            }
        }
    }

    /**
     * Prefer TEMPLATE_PREVIEW on modern Samsung HALs — TEMPLATE_RECORD can succeed at
     * createCaptureRequest time but still leave the shared session in a state where
     * Session.resume() throws FatalException.
     *
     * If the device is already closed, rethrow — caller retries open instead of RECORD.
     */
    private fun createCaptureRequestBuilder(device: CameraDevice): CaptureRequest.Builder {
        return try {
            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).also {
                Log.i(TAG, "Using CameraDevice.TEMPLATE_PREVIEW")
            }
        } catch (closed: IllegalStateException) {
            throw closed
        } catch (error: Exception) {
            Log.w(TAG, "TEMPLATE_PREVIEW failed — trying TEMPLATE_RECORD", error)
            device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).also {
                Log.i(TAG, "Using CameraDevice.TEMPLATE_RECORD")
            }
        }
    }

    /**
     * Soft-retry camera open after a closed-device race. Avoids locking the call into CameraX
     * fallback when a concurrent onPause/open tore the device down mid-setup.
     */
    private fun scheduleOpenCameraRetry(reason: String) {
        val attempt = previewCreateRetries.incrementAndGet()
        Log.w(TAG, "$reason — scheduling openCamera retry $attempt/$MAX_PREVIEW_RETRIES")
        cameraOpenInFlight.set(false)
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null
        previewCaptureRequestBuilder = null
        val device = cameraDevice
        cameraDevice = null
        try {
            device?.close()
        } catch (_: Exception) {
        }

        if (attempt > MAX_PREVIEW_RETRIES) {
            activity?.runOnUiThread {
                activateFallback(reason)
            }
            return
        }

        val hostActivity = activity ?: return
        val lifecycleToken = lifecycleGeneration.get()
        hostActivity.runOnUiThread {
            if (isDestroyed || !lifecycleResumed.get() || _fallbackActive.value) {
                return@runOnUiThread
            }
            hostActivity.window.decorView.postDelayed(
                {
                    if (isLifecycleCurrent(lifecycleToken) &&
                        cameraDevice == null
                    ) {
                        openCamera()
                    }
                },
                PREVIEW_RETRY_MS,
            )
        }
    }

    /**
     * Holds uncalibrated IMU sensors in continuous mode so ARCore EnableSensor does not
     * fatal on Samsung One UI 8 / Play Services for AR 1.54 (issue #1762).
     */
    private fun startArSensorKeepAlive() {
        if (sensorKeepAliveActive.get()) {
            Log.i(TAG, "AR sensor keep-alive already active")
            return
        }
        val hostActivity = activity ?: return
        val manager =
            sensorManager
                ?: (hostActivity.getSystemService(Context.SENSOR_SERVICE) as SensorManager).also {
                    sensorManager = it
                }
        var registered = 0
        for (type in arKeepAliveSensorTypes) {
            val sensor = manager.getDefaultSensor(type) ?: continue
            val ok =
                manager.registerListener(
                    arSensorKeepAliveListener,
                    sensor,
                    SensorManager.SENSOR_DELAY_FASTEST,
                )
            if (ok) {
                registered += 1
            } else {
                Log.w(TAG, "Failed to register keep-alive sensor type=$type")
            }
        }
        if (registered > 0) {
            sensorKeepAliveActive.set(true)
        }
        Log.i(TAG, "AR sensor keep-alive registered=$registered/${arKeepAliveSensorTypes.size}")
    }

    private fun stopArSensorKeepAlive() {
        if (!sensorKeepAliveActive.getAndSet(false)) {
            return
        }
        try {
            sensorManager?.unregisterListener(arSensorKeepAliveListener)
            Log.i(TAG, "AR sensor keep-alive stopped")
        } catch (error: Exception) {
            Log.w(TAG, "unregister AR sensor keep-alive failed", error)
        }
    }

    private fun setRepeatingCaptureRequest() {
        try {
            if (isDestroyed ||
                !lifecycleResumed.get() ||
                _fallbackActive.value ||
                cameraDevice == null
            ) {
                return
            }
            val builder = previewCaptureRequestBuilder ?: return
            val session = captureSession ?: return
            session.setRepeatingRequest(builder.build(), cameraCaptureCallback, backgroundHandler)
        } catch (error: CameraAccessException) {
            Log.e(TAG, "Failed to set repeating request", error)
        } catch (error: IllegalStateException) {
            // Device already closed during fallback — do not crash sharedCameraBackground.
            Log.w(TAG, "setRepeatingRequest skipped — camera closed", error)
        }
    }

    private fun resumeARCore() {
        val session = sharedSession ?: return
        if (arcoreActive.get() || arcoreResumeFailed.get()) {
            return
        }

        val lifecycleToken = lifecycleGeneration.get()
        runGlBeforeResume {
            activity ?: return@runGlBeforeResume
            if (!isLifecycleCurrent(lifecycleToken) ||
                openingGeneration != cameraOpenGeneration.get() ||
                cameraDevice == null ||
                captureSession == null
            ) {
                Log.i(TAG, "resumeARCore ignored — lifecycle or camera setup is stale")
                return@runGlBeforeResume
            }

            try {
                session.resume()
                arcoreActive.set(true)
                arcoreResumeFailed.set(false)
                awaitingArInstall.set(false)
                userRequestedInstall = true
                sharedCamera?.setCaptureCallback(cameraCaptureCallback, backgroundHandler)
                _arcoreActive.value = true
                _fallbackActive.value = false
                _streamingReady.value = true
                glSurfaceView?.requestRender()
                startPovStreaming()
                Log.i(TAG, "Shared camera active, ARCore resumed — POV composite armed")
            } catch (error: Exception) {
                handleResumeFailure(error)
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
        val hostActivity = activity
        if (hostActivity != null && Looper.myLooper() != Looper.getMainLooper()) {
            hostActivity.runOnUiThread { activateFallback(reason) }
            return
        }
        if (isDestroyed || !lifecycleResumed.get()) {
            Log.i(TAG, "Fallback ignored outside resumed lifecycle: $reason")
            return
        }
        Log.w(TAG, reason)
        stopPovStreaming()
        pauseARCore()
        arcoreResumeFailed.set(true)
        arcoreActive.set(false)
        _arcoreActive.value = false
        closeCamera()
        try {
            sharedSession?.close()
        } catch (error: Exception) {
            Log.w(TAG, "session.close during fallback failed", error)
        }
        sharedSession = null
        sharedCamera = null
        _fallbackActive.value = true
        _streamingReady.value = true
        _trackingUiState.value = ArTrackingUiState.STABLE
    }

    private fun pauseARCore() {
        stopPovStreaming()
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

    private fun startPovStreaming() {
        if (isDestroyed || !lifecycleResumed.get() || _fallbackActive.value) {
            return
        }
        if (!arcoreActive.get()) {
            return
        }
        if (!povStreaming.compareAndSet(false, true)) {
            return
        }
        Log.i(TAG, "customer POV stream started (GL PixelCopy @15fps → 540x960)")
        povHandler.removeCallbacks(povTick)
        povHandler.post(povTick)
    }

    private fun stopPovStreaming() {
        if (!povStreaming.getAndSet(false)) {
            povHandler.removeCallbacks(povTick)
            return
        }
        povHandler.removeCallbacks(povTick)
        Log.i(TAG, "customer POV stream stopped")
    }

    private fun capturePovFrame() {
        if (!povStreaming.get() || !frameCapturer.isActive()) {
            return
        }
        val surfaceView = glSurfaceView ?: return
        val overlay = annotationController.overlayStrokes.value
        val draft = annotationController.draftStroke.value
        val pointer = annotationController.pointerOverlay.value
        PovCompositeEncoder.capture(
            surfaceView = surfaceView,
            strokes = overlay,
            draftStroke = draft,
            pointerOverlay = pointer,
        ) { frame ->
            frameCapturer.pushVideoFrame(frame)
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            shouldUpdateSurfaceTexture.set(true)
            val rotation = cameraId?.let { displayRotationHelper?.getCameraSensorToDisplayRotation(it) } ?: 90
            frameCapturer.updateRotation(rotation)
            tutorialFrameSink?.invoke(image, rotation)
            // LiveKit receives the GL POV composite, not this raw sensor image.
        } finally {
            image.close()
        }
    }

    private fun closeCamera() {
        // Invalidate any in-flight onOpened / preview-session work from a raced open.
        cameraOpenGeneration.incrementAndGet()
        cameraOpenInFlight.set(false)

        try {
            captureSession?.close()
        } catch (error: Exception) {
            Log.w(TAG, "camera capture session close failed", error)
        }
        captureSession = null
        previewCaptureRequestBuilder = null

        val device = cameraDevice
        cameraDevice = null
        device?.let {
            try {
                it.close()
            } catch (error: Exception) {
                Log.w(TAG, "cameraDevice.close failed", error)
            }
        }

        try {
            cpuImageReader?.close()
        } catch (error: Exception) {
            Log.w(TAG, "cpu ImageReader close failed", error)
        }
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
        val hostActivity = activity ?: return
        val surfaceView = glSurfaceView ?: run {
            hostActivity.runOnUiThread(onGlReady)
            return
        }
        surfaceView.queueEvent {
            renderer?.onBeforeResumeArcore()
            hostActivity.runOnUiThread(onGlReady)
        }
    }

    private fun isLifecycleCurrent(token: Int): Boolean =
        !isDestroyed &&
            lifecycleResumed.get() &&
            lifecycleGeneration.get() == token

    private fun hasCameraPermission(): Boolean {
        val hostActivity = activity ?: return false
        return ContextCompat.checkSelfPermission(hostActivity, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "ARCoreManager"
        private const val AR_CHECK_RETRY_MS = 250L
        private const val PREVIEW_RETRY_MS = 350L
        private const val MAX_PREVIEW_RETRIES = 4
    }
}
