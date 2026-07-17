package com.cgsapple.remotear.annotation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

enum class AnnotationTool {
    FREEHAND,
    CIRCLE,
    ARROW,
    ;

    companion object {
        fun fromRaw(value: String?): AnnotationTool =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: FREEHAND
    }
}

@Serializable
data class NormalizedPoint(
    val x: Float,
    val y: Float,
)

@Serializable
data class AnnotationPayload(
    val id: String,
    val tool: String = "freehand",
    val color: String = DEFAULT_COLOR,
    val points: List<NormalizedPoint>,
)

@Serializable
data class AnnotationSyncPayload(
    val id: String,
    val points: List<NormalizedPoint>,
)

@Serializable
data class PointerPayload(
    val x: Float,
    val y: Float,
    val active: Boolean,
)

@Serializable
data class ClearSinglePayload(
    val id: String,
)

@Serializable
data class PlaceModelPayload(
    val modelId: String,
    val modelName: String,
    val modelUrl: String,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
)

data class PlacedModelOverlay(
    val modelId: String,
    val modelName: String,
    val modelUrl: String,
    val x: Float,
    val y: Float,
)

data class PointerOverlay(
    val position: Offset?,
    val active: Boolean,
)

data class RenderedStroke(
    val id: String,
    val tool: AnnotationTool,
    val color: Color,
    val points: List<Offset>,
    val thickness: Float = DEFAULT_THICKNESS,
)

fun NormalizedPoint.toOffset(viewWidth: Float, viewHeight: Float): Offset =
    Offset(x * viewWidth, y * viewHeight)

fun Offset.toNormalized(viewWidth: Float, viewHeight: Float): NormalizedPoint =
    NormalizedPoint(
        x = (x / viewWidth).coerceIn(0f, 1f),
        y = (y / viewHeight).coerceIn(0f, 1f),
    )

fun List<NormalizedPoint>.toOffsets(viewWidth: Float, viewHeight: Float): List<Offset> =
    map { it.toOffset(viewWidth, viewHeight) }

fun List<Offset>.toNormalizedPoints(viewWidth: Float, viewHeight: Float): List<NormalizedPoint> =
    map { it.toNormalized(viewWidth, viewHeight) }

fun parseComposeColor(hex: String): Color {
    val cleaned = hex.removePrefix("#")
    return when (cleaned.length) {
        6 -> Color(0xFF000000 or cleaned.toLong(16))
        8 -> Color(cleaned.toLong(16))
        else -> Color(DEFAULT_COLOR_HEX)
    }
}

val ANNOTATION_COLORS = listOf(
    "#00E5FF",
    "#FF5252",
    "#FFEB3B",
    "#69F0AE",
    "#E040FB",
    "#FFFFFF",
)

const val DEFAULT_COLOR = "#00E5FF"
const val DEFAULT_COLOR_HEX = 0xFF00E5FF
const val DEFAULT_THICKNESS = 4f

fun composeColorToHex(color: Color): String {
    val r = (color.red * 255f).roundToInt().coerceIn(0, 255)
    val g = (color.green * 255f).roundToInt().coerceIn(0, 255)
    val b = (color.blue * 255f).roundToInt().coerceIn(0, 255)
    return String.format("#%02X%02X%02X", r, g, b)
}
