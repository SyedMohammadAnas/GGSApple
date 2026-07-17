package com.cgsapple.remotear.annotation

import androidx.compose.ui.geometry.Offset

/** Mobile portrait host video (720×1280 capture). Aspect = width/height = 9:16. */
const val HOST_VIDEO_ASPECT = 9f / 16f

const val HOST_VIDEO_WIDTH = 720
const val HOST_VIDEO_HEIGHT = 1280

data class ContentRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

fun coverContentRect(
    containerWidth: Float,
    containerHeight: Float,
    contentAspect: Float = HOST_VIDEO_ASPECT,
): ContentRect {
    if (containerWidth <= 0f || containerHeight <= 0f) {
        return ContentRect(0f, 0f, 1f, 1f)
    }
    val containerAspect = containerWidth / containerHeight
    return if (containerAspect > contentAspect) {
        val height = containerHeight
        val width = height * contentAspect
        ContentRect(
            x = (containerWidth - width) / 2f,
            y = 0f,
            width = width,
            height = height,
        )
    } else {
        val width = containerWidth
        val height = width / contentAspect
        ContentRect(
            x = 0f,
            y = (containerHeight - height) / 2f,
            width = width,
            height = height,
        )
    }
}

/** Map touch inside container to normalized video-frame coordinates (0–1). */
fun touchToVideoNorm(
    x: Float,
    y: Float,
    containerWidth: Float,
    containerHeight: Float,
    contentAspect: Float = HOST_VIDEO_ASPECT,
): NormalizedPoint? {
    val rect = coverContentRect(containerWidth, containerHeight, contentAspect)
    if (rect.width <= 0f || rect.height <= 0f) {
        return null
    }
    val videoX = (x - rect.x) / rect.width
    val videoY = (y - rect.y) / rect.height
    if (videoX < 0f || videoX > 1f || videoY < 0f || videoY > 1f) {
        return null
    }
    return NormalizedPoint(videoX, videoY)
}

/** Map normalized video-frame coordinates to normalized container coordinates. */
fun videoNormToContainerNorm(
    point: NormalizedPoint,
    containerWidth: Float,
    containerHeight: Float,
    contentAspect: Float = HOST_VIDEO_ASPECT,
): NormalizedPoint {
    val rect = coverContentRect(containerWidth, containerHeight, contentAspect)
    return NormalizedPoint(
        x = (rect.x + point.x * rect.width) / containerWidth.coerceAtLeast(1f),
        y = (rect.y + point.y * rect.height) / containerHeight.coerceAtLeast(1f),
    )
}

fun List<Offset>.toVideoNormalizedPoints(
    containerWidth: Float,
    containerHeight: Float,
): List<NormalizedPoint> =
    mapNotNull { offset ->
        touchToVideoNorm(offset.x, offset.y, containerWidth, containerHeight)
    }

fun List<NormalizedPoint>.toVideoMappedOffsets(
    containerWidth: Float,
    containerHeight: Float,
): List<Offset> =
    map { point ->
        val mapped = videoNormToContainerNorm(point, containerWidth, containerHeight)
        mapped.toOffset(containerWidth, containerHeight)
    }

/** Map container-normalized coords to video-normalized, clamping to the visible video rect. */
fun containerNormToVideoNormClamped(
    point: NormalizedPoint,
    containerWidth: Float,
    containerHeight: Float,
    contentAspect: Float = HOST_VIDEO_ASPECT,
): NormalizedPoint {
    val rect = coverContentRect(containerWidth, containerHeight, contentAspect)
    val px = point.x * containerWidth
    val py = point.y * containerHeight
    val clampedX = px.coerceIn(rect.x, rect.x + rect.width)
    val clampedY = py.coerceIn(rect.y, rect.y + rect.height)
    return NormalizedPoint(
        x = (clampedX - rect.x) / rect.width.coerceAtLeast(1f),
        y = (clampedY - rect.y) / rect.height.coerceAtLeast(1f),
    )
}

fun List<NormalizedPoint>.toVideoNormalizedClampedFromContainer(
    containerWidth: Float,
    containerHeight: Float,
): List<NormalizedPoint> =
    map { containerNormToVideoNormClamped(it, containerWidth, containerHeight) }

/** Convert container-normalized coords to video-normalized (for network sync to technician). */
fun containerNormToVideoNorm(
    point: NormalizedPoint,
    containerWidth: Float,
    containerHeight: Float,
): NormalizedPoint {
    val offset = point.toOffset(containerWidth, containerHeight)
    return touchToVideoNorm(offset.x, offset.y, containerWidth, containerHeight) ?: point
}

fun List<NormalizedPoint>.toVideoNormalizedFromContainer(
    containerWidth: Float,
    containerHeight: Float,
): List<NormalizedPoint> =
    map { containerNormToVideoNorm(it, containerWidth, containerHeight) }

fun List<NormalizedPoint>.toContainerNormalizedFromVideo(
    containerWidth: Float,
    containerHeight: Float,
): List<NormalizedPoint> =
    map { videoNormToContainerNorm(it, containerWidth, containerHeight) }

/** Map full-screen touch pixels to video-overlay pixels (TeamViewer technician display). */
fun List<Offset>.toVideoOverlayOffsets(
    containerWidth: Float,
    containerHeight: Float,
): List<Offset> {
    if (size < 2) {
        return emptyList()
    }
    return map { offset ->
        val containerNorm = offset.toNormalized(containerWidth, containerHeight)
        val videoNorm = containerNormToVideoNormClamped(containerNorm, containerWidth, containerHeight)
        videoNormToContainerNorm(videoNorm, containerWidth, containerHeight)
            .toOffset(containerWidth, containerHeight)
    }
}
