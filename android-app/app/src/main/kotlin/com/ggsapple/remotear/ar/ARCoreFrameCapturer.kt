package com.ggsapple.remotear.ar

import android.content.Context
import android.media.Image
import android.util.Log
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoCapturer
import livekit.org.webrtc.VideoFrame
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
        if (!isCapturing.compareAndSet(false, true)) {
            Log.w(TAG, "startCapture ignored — capture already active")
            return
        }
        Log.i(TAG, "startCapture ${width}x${height}@${framerate}")
        capturerObserver?.onCapturerStarted(true)
    }

    override fun stopCapture() {
        if (!isCapturing.compareAndSet(true, false)) {
            return
        }
        Log.i(TAG, "stopCapture")
        capturerObserver?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) = Unit

    override fun dispose() {
        stopCapture()
        capturerObserver = null
    }

    override fun isScreencast(): Boolean = false

    fun isActive(): Boolean = isCapturing.get()

    fun updateRotation(rotationDegrees: Int) {
        this.rotationDegrees = rotationDegrees
    }

    /**
     * Push an already-oriented POV composite (rotation should be 0, matching iOS).
     */
    fun pushVideoFrame(frame: VideoFrame) {
        if (!isCapturing.get()) {
            return
        }
        if (!frameInFlight.compareAndSet(false, true)) {
            return
        }
        try {
            capturerObserver?.onFrameCaptured(frame)
        } finally {
            frameInFlight.set(false)
        }
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
    companion object {
        private const val TAG = "ARCoreFrameCapturer"
    }
}
