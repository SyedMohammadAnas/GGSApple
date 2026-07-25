package com.ggsapple.remotear.data.recording

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records ARCore camera frames (YUV) to MP4 without MediaProjection.
 * Used for local video tutorials — captures the AR camera feed at ~15 fps.
 */
@Singleton
class TutorialVideoRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var frameWidth = 0
    private var frameHeight = 0
    private var outputFile: File? = null
    private var startTimeUs = 0L
    private var lastFrameUs = 0L
    private val frameIntervalUs = 1_000_000L / TARGET_FPS

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingSeconds = MutableStateFlow(0)
    val recordingSeconds: StateFlow<Int> = _recordingSeconds.asStateFlow()

    fun start(): Result<Unit> {
        if (_isRecording.value) {
            return Result.failure(IllegalStateException("Already recording"))
        }
        return runCatching {
            resetEncoder()
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "tutorials").apply { mkdirs() }
            outputFile = File(dir, "tutorial_${System.currentTimeMillis()}.mp4")
            _isRecording.value = true
            _recordingSeconds.value = 0
            startTimeUs = System.nanoTime() / 1_000
            lastFrameUs = startTimeUs
        }
    }

    fun offerFrame(image: Image, rotationDegrees: Int) {
        if (!_isRecording.value) return
        val nowUs = System.nanoTime() / 1_000
        if (nowUs - lastFrameUs < frameIntervalUs) return
        lastFrameUs = nowUs

        val width = image.width
        val height = image.height
        ensureEncoder(width, height)
        val nv21 = yuv420ToNv21(image) ?: return
        encodeNv21Frame(nv21, width, height, nowUs - startTimeUs)
        _recordingSeconds.value = ((nowUs - startTimeUs) / 1_000_000L).toInt()
    }

    fun stop(): Result<String> {
        if (!_isRecording.value) {
            return Result.failure(IllegalStateException("Not recording"))
        }
        return runCatching {
            drainEncoder(endOfStream = true)
            val path = outputFile?.absolutePath ?: error("No output file")
            resetEncoder()
            _isRecording.value = false
            path
        }.onFailure { error ->
            Log.e(TAG, "stop failed", error)
            resetEncoder()
            _isRecording.value = false
        }
    }

    private fun ensureEncoder(width: Int, height: Int) {
        if (encoder != null && frameWidth == width && frameHeight == height) return
        resetEncoder()
        frameWidth = width
        frameHeight = height
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        muxer = MediaMuxer(checkNotNull(outputFile).absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        trackIndex = -1
        muxerStarted = false
    }

    private fun encodeNv21Frame(nv21: ByteArray, width: Int, height: Int, presentationTimeUs: Long) {
        val codec = encoder ?: return
        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()
            inputBuffer.put(nv21)
            codec.queueInputBuffer(inputIndex, 0, nv21.size, presentationTimeUs, 0)
        }
        drainEncoder(endOfStream = false)
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val codec = encoder ?: return
        if (endOfStream) {
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(inputIndex, 0, 0, lastFrameUs - startTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val mux = muxer ?: return
                    if (!muxerStarted) {
                        trackIndex = mux.addTrack(codec.outputFormat)
                        mux.start()
                        muxerStarted = true
                    }
                }
                outputIndex >= 0 -> {
                    val mux = muxer
                    if (mux != null && muxerStarted) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            mux.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
            }
        }
    }

    private fun resetEncoder() {
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching {
            if (muxerStarted) muxer?.stop()
        }
        runCatching { muxer?.release() }
        muxer = null
        muxerStarted = false
        trackIndex = -1
        frameWidth = 0
        frameHeight = 0
    }

    private fun yuv420ToNv21(image: Image): ByteArray? {
        if (image.planes.size < 3) return null
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        var offset = 0
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(nv21, offset, width)
            offset += width
        }

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        var uvOffset = ySize
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val uIndex = row * uRowStride + col * uPixelStride
                val vIndex = row * vRowStride + col * vPixelStride
                nv21[uvOffset++] = vBuffer.get(vIndex)
                nv21[uvOffset++] = uBuffer.get(uIndex)
            }
        }
        return nv21
    }

    companion object {
        private const val TAG = "TutorialVideoRecorder"
        private const val TARGET_FPS = 15
        private const val TIMEOUT_US = 10_000L
    }
}
