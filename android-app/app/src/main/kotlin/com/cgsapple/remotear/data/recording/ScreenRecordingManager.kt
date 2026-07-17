package com.cgsapple.remotear.data.recording

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenRecordingManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val projectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var recordingStartMs: Long = 0L

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingSeconds = MutableStateFlow(0)
    val recordingSeconds: StateFlow<Int> = _recordingSeconds.asStateFlow()

    fun createCaptureIntent(): Intent = projectionManager.createScreenCaptureIntent()

    fun startRecording(resultCode: Int, data: Intent): Result<String> {
        if (_isRecording.value) {
            return Result.failure(IllegalStateException("Already recording"))
        }
        return runCatching {
            val startIntent = Intent(context, ScreenCaptureForegroundService::class.java).apply {
                action = ScreenCaptureForegroundService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }

            val projection = projectionManager.getMediaProjection(resultCode, data)
                ?: error("MediaProjection unavailable")
            mediaProjection = projection
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                projection.registerCallback(object : MediaProjection.Callback() {}, null)
            }

            val moviesDir = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(moviesDir, "recordings").apply { mkdirs() }
            val file = File(dir, "session_${System.currentTimeMillis()}.mp4")
            outputFile = file

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(720, 1280)
                setVideoFrameRate(24)
                setVideoEncodingBitRate(4_000_000)
                setOutputFile(file.absolutePath)
                prepare()
            }

            val surface = recorder.surface
            virtualDisplay = projection.createVirtualDisplay(
                "RemoteArRecording",
                720,
                1280,
                context.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null,
            )

            recorder.start()
            mediaRecorder = recorder
            recordingStartMs = System.currentTimeMillis()
            _isRecording.value = true
            _recordingSeconds.value = 0
            file.absolutePath
        }.onFailure { e ->
            Log.e(TAG, "startRecording failed", e)
            stopRecordingInternal(discard = true)
        }
    }

    fun stopRecording(): Result<String> {
        if (!_isRecording.value) {
            return Result.failure(IllegalStateException("Not recording"))
        }
        return runCatching {
            val path = outputFile?.absolutePath ?: error("No output file")
            stopRecordingInternal(discard = false)
            path
        }
    }

    fun tickRecordingTimer() {
        if (_isRecording.value) {
            val elapsed = ((System.currentTimeMillis() - recordingStartMs) / 1000).toInt()
            _recordingSeconds.value = elapsed
        }
    }

    fun reset() {
        stopRecordingInternal(discard = true)
    }

    private fun stopRecordingInternal(discard: Boolean) {
        runCatching { mediaRecorder?.stop() }
        runCatching { mediaRecorder?.reset() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
        runCatching {
            val stopIntent = Intent(context, ScreenCaptureForegroundService::class.java).apply {
                action = ScreenCaptureForegroundService.ACTION_STOP
            }
            context.startService(stopIntent)
        }
        _isRecording.value = false
        if (discard) {
            outputFile?.delete()
        }
        outputFile = null
    }

    companion object {
        private const val TAG = "ScreenRecordingManager"
        const val REQUEST_MEDIA_PROJECTION = 9001
    }
}
