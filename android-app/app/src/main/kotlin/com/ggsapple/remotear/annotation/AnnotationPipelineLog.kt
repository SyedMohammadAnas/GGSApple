package com.ggsapple.remotear.annotation

import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Unified logging for end-to-end annotation pipeline debugging. */
object AnnotationPipelineLog {
    private const val TAG = "AnnotationPipeline"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun stage(stage: String, message: String) {
        Log.i(TAG, "[$stage] $message")
    }

    fun payload(stage: String, label: String, value: Any) {
        val encoded = runCatching {
            when (value) {
                is AnnotationPayload -> json.encodeToString(value)
                is AnnotationSyncPayload -> json.encodeToString(value)
                else -> value.toString()
            }
        }.getOrElse { value.toString() }
        Log.i(TAG, "[$stage] $label=$encoded")
    }

    fun coords(stage: String, label: String, points: List<NormalizedPoint>) {
        if (points.isEmpty()) {
            Log.w(TAG, "[$stage] $label empty")
            return
        }
        val sample = points.take(3).joinToString { formatPoint(it) }
        val outOfRange = points.count { it.x !in 0f..1f || it.y !in 0f..1f }
        Log.i(
            TAG,
            "[$stage] $label count=${points.size} sample=[$sample] outOfRange=$outOfRange",
        )
        if (outOfRange > 0) {
            points.filter { it.x !in 0f..1f || it.y !in 0f..1f }.take(3).forEach { p ->
                Log.w(TAG, "[$stage] OUT OF RANGE ${formatPoint(p)}")
            }
        }
    }

    fun conversion(
        stage: String,
        from: String,
        to: String,
        input: List<NormalizedPoint>,
        output: List<NormalizedPoint>,
    ) {
        Log.i(TAG, "[$stage] convert $from→$to in=${input.size} out=${output.size}")
        coords(stage, "input", input)
        coords(stage, "output", output)
    }

    private fun formatPoint(p: NormalizedPoint): String =
        "(${p.x.format3()},${p.y.format3()})"

    private fun Float.format3(): String = "%.3f".format(this)
}
