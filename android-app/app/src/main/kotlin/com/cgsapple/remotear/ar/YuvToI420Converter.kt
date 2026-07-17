package com.cgsapple.remotear.ar

import android.graphics.ImageFormat
import android.media.Image
import livekit.org.webrtc.JavaI420Buffer
import livekit.org.webrtc.VideoFrame
import java.nio.ByteBuffer

object YuvToI420Converter {

    fun imageToVideoFrame(image: Image, rotationDegrees: Int): VideoFrame? {
        if (image.format != ImageFormat.YUV_420_888) {
            return null
        }

        val width = image.width
        val height = image.height
        val i420 = JavaI420Buffer.allocate(width, height)

        copyPlane(
            image.planes[0].buffer,
            image.planes[0].rowStride,
            image.planes[0].pixelStride,
            width,
            height,
            i420.dataY,
            i420.strideY,
        )
        copyPlane(
            image.planes[1].buffer,
            image.planes[1].rowStride,
            image.planes[1].pixelStride,
            width / 2,
            height / 2,
            i420.dataU,
            i420.strideU,
        )
        copyPlane(
            image.planes[2].buffer,
            image.planes[2].rowStride,
            image.planes[2].pixelStride,
            width / 2,
            height / 2,
            i420.dataV,
            i420.strideV,
        )

        val timestampNs = System.nanoTime()
        return VideoFrame(i420, rotationDegrees, timestampNs)
    }

    private fun copyPlane(
        inBuffer: ByteBuffer,
        inRowStride: Int,
        inPixelStride: Int,
        width: Int,
        height: Int,
        outBuffer: ByteBuffer,
        outRowStride: Int,
    ) {
        inBuffer.rewind()
        outBuffer.rewind()

        if (inPixelStride == 1 && inRowStride == width && outRowStride == width) {
            val slice = minOf(width * height, inBuffer.remaining(), outBuffer.remaining())
            val temp = ByteArray(slice)
            inBuffer.get(temp)
            outBuffer.put(temp)
            return
        }

        for (row in 0 until height) {
            var inputOffset = row * inRowStride
            var outputOffset = row * outRowStride
            for (col in 0 until width) {
                if (inputOffset >= inBuffer.limit() || outputOffset >= outBuffer.limit()) {
                    return
                }
                outBuffer.put(outputOffset, inBuffer.get(inputOffset))
                inputOffset += inPixelStride
                outputOffset += 1
            }
        }
    }
}
