package com.ggsapple.remotear.ar

import android.opengl.GLSurfaceView
import android.util.Log
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArCameraRenderer(
    private val arCoreManager: ARCoreManager,
    private val displayRotationHelper: DisplayRotationHelper,
) : GLSurfaceView.Renderer {

    private val backgroundRenderer = BackgroundRenderer()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        backgroundRenderer.createOnGlThread()
        arCoreManager.onGlSurfaceCreated(backgroundRenderer.getTextureId())
        Log.i(TAG, "GL surface created")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        arCoreManager.onViewportChanged(width, height)
        displayRotationHelper.onSurfaceChanged(width, height)
        Log.i(TAG, "GL surface changed ${width}x$height")
    }

    override fun onDrawFrame(gl: GL10?) {
        android.opengl.GLES20.glClear(
            android.opengl.GLES20.GL_COLOR_BUFFER_BIT or android.opengl.GLES20.GL_DEPTH_BUFFER_BIT,
        )

        if (!arCoreManager.isArcoreActive()) {
            return
        }
        if (!arCoreManager.shouldUpdateSurfaceTexture()) {
            return
        }

        val session = arCoreManager.getSession() ?: return
        displayRotationHelper.updateSessionIfNeeded(session)

        try {
            val frame = session.update()
            arCoreManager.onFrameUpdated(frame)
            backgroundRenderer.draw(frame)

            val camera = frame.camera
            if (camera.trackingState == TrackingState.PAUSED) {
                return
            }
        } catch (error: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available during update", error)
        } catch (error: Throwable) {
            Log.e(TAG, "onDrawFrame failed", error)
        }
    }

    fun onBeforeResumeArcore() {
        backgroundRenderer.suppressTimestampZeroRendering(false)
    }

    companion object {
        private const val TAG = "ArCameraRenderer"
    }
}
