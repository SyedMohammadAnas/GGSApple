package com.ggsapple.remotear.ar

import android.content.Context
import android.media.Image
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoCapturer
import java.util.concurrent.atomic.AtomicBoolean

class ARCoreFrameCapturer : VideoCapturer {
    private var capturerObserver: CapturerObserver? = null
    private val isCapturing = AtomicBoolean(false)
    private val frameInFlight = AtomicBoolean(false)

    @Volatile
    private var rotationDegrees = 0

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        applicationContext: Context?,
        capturerObserver: CapturerObserver?,
    ) {
        this.capturerObserver = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        isCapturing.set(true)
    }

    override fun stopCapture() {
        isCapturing.set(false)
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) = Unit

    override fun dispose() {
        isCapturing.set(false)
        capturerObserver = null
    }

    override fun isScreencast(): Boolean = false

    fun updateRotation(rotationDegrees: Int) {
        this.rotationDegrees = rotationDegrees
    }

    fun pushImage(image: Image) {
        if (!isCapturing.get()) {
            return
        }
        if (!frameInFlight.compareAndSet(false, true)) {
            return
        }

        try {
            val frame = YuvToI420Converter.imageToVideoFrame(image, rotationDegrees) ?: return
            capturerObserver?.onFrameCaptured(frame)
            frame.release()
        } finally {
            frameInFlight.set(false)
        }
    }
}
