package com.cgsapple.remotear.annotation

import android.util.Log
import com.cgsapple.remotear.BuildConfig
import com.cgsapple.remotear.di.ApplicationScope
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnnotationChannel @Inject constructor(
    private val supabaseClient: SupabaseClient,
    private val httpClient: HttpClient,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private var channel: RealtimeChannel? = null
    private var listenJob: Job? = null
    private var activeSessionId: String? = null
    @Volatile
    private var wsReady = false

    private val subscribeMutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    val isSubscribed: Boolean
        get() = wsReady && channel?.status?.value == RealtimeChannel.Status.SUBSCRIBED

    suspend fun subscribe(
        sessionId: String,
        onAnnotation: (AnnotationPayload) -> Unit,
        onAnnotationSync: (AnnotationSyncPayload) -> Unit,
        onClear: () -> Unit,
        onPointer: (PointerPayload) -> Unit = {},
        onClearSingle: (ClearSinglePayload) -> Unit = {},
        onPlaceModel: (PlaceModelPayload) -> Unit = {},
    ) {
        subscribeMutex.withLock {
            disconnectInternal()
            activeSessionId = sessionId
            wsReady = false

            val accessToken = waitForAuthToken()
                ?: throw IllegalStateException("Missing Supabase auth session for annotations")

            AnnotationPipelineLog.stage("SUBSCRIBE", "sessionId=$sessionId")

            var lastError: Exception? = null
            repeat(SUBSCRIBE_ATTEMPTS) { attempt ->
                try {
                    connectChannel(
                        sessionId = sessionId,
                        accessToken = accessToken,
                        onAnnotation = onAnnotation,
                        onAnnotationSync = onAnnotationSync,
                        onClear = onClear,
                        onPointer = onPointer,
                        onClearSingle = onClearSingle,
                        onPlaceModel = onPlaceModel,
                    )
                    AnnotationPipelineLog.stage(
                        "SUBSCRIBE",
                        "ready sessionId=$sessionId topic=${channelName(sessionId)} wsReady=$wsReady",
                    )
                    return
                } catch (error: Exception) {
                    lastError = error
                    Log.w(TAG, "Subscribe attempt ${attempt + 1}/$SUBSCRIBE_ATTEMPTS failed", error)
                    disconnectInternal()
                    activeSessionId = sessionId
                    delay(SUBSCRIBE_RETRY_MS * (attempt + 1))
                }
            }

            throw lastError ?: IllegalStateException("Failed to subscribe to ${channelName(sessionId)}")
        }
    }

    fun subscribeWithRetry(
        sessionId: String,
        onAnnotation: (AnnotationPayload) -> Unit,
        onAnnotationSync: (AnnotationSyncPayload) -> Unit,
        onClear: () -> Unit,
        onPointer: (PointerPayload) -> Unit = {},
        onClearSingle: (ClearSinglePayload) -> Unit = {},
        onPlaceModel: (PlaceModelPayload) -> Unit = {},
    ): Job =
        applicationScope.launch {
            var delayMs = SUBSCRIBE_RETRY_MS
            while (isActive) {
                if (activeSessionId != null && activeSessionId != sessionId) {
                    return@launch
                }
                val result = runCatching {
                    subscribe(
                        sessionId, onAnnotation, onAnnotationSync, onClear,
                        onPointer, onClearSingle, onPlaceModel,
                    )
                }
                if (result.isSuccess) {
                    AnnotationPipelineLog.stage("SUBSCRIBE", "connected sessionId=$sessionId")
                    return@launch
                }
                Log.w(TAG, "Background subscribe retry sessionId=$sessionId", result.exceptionOrNull())
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(BACKGROUND_RETRY_MAX_MS)
            }
        }

    private suspend fun connectChannel(
        sessionId: String,
        accessToken: String,
        onAnnotation: (AnnotationPayload) -> Unit,
        onAnnotationSync: (AnnotationSyncPayload) -> Unit,
        onClear: () -> Unit,
        onPointer: (PointerPayload) -> Unit,
        onClearSingle: (ClearSinglePayload) -> Unit,
        onPlaceModel: (PlaceModelPayload) -> Unit,
    ) {
        supabaseClient.realtime.setAuth(accessToken)
        AnnotationPipelineLog.stage("WS", "setAuth ok, subscribing channel")

        val channelId = channelName(sessionId)
        val realtimeChannel = supabaseClient.channel(channelId) {
            isPrivate = false
            broadcast {
                acknowledgeBroadcasts = false
                receiveOwnBroadcasts = false
            }
        }

        // Subscribe synchronously — channel.subscribe() opens the Realtime WebSocket if needed.
        realtimeChannel.subscribe(blockUntilSubscribed = true)
        channel = realtimeChannel
        wsReady = true
        AnnotationPipelineLog.stage(
            "WS",
            "subscribed channelId=$channelId status=${realtimeChannel.status.value} " +
                "realtime=${supabaseClient.realtime.status.value}",
        )

        listenJob = applicationScope.launch {
            launch {
                realtimeChannel.broadcastFlow<JsonObject>(EVENT_ANNOTATION)
                    .catch { error -> Log.e(TAG, "annotation flow error", error) }
                    .collect { raw ->
                        parseAnnotation(raw)?.let { payload ->
                            AnnotationPipelineLog.stage(
                                "RX",
                                "annotation id=${payload.id} pts=${payload.points.size}",
                            )
                            AnnotationPipelineLog.payload("RX", "annotation", payload)
                            onAnnotation(payload)
                        }
                    }
            }
            launch {
                realtimeChannel.broadcastFlow<JsonObject>(EVENT_ANNOTATION_SYNC)
                    .catch { error -> Log.e(TAG, "annotation_sync flow error", error) }
                    .collect { raw ->
                        parseAnnotationSync(raw)?.let { payload ->
                            AnnotationPipelineLog.stage(
                                "RX",
                                "annotation_sync id=${payload.id} pts=${payload.points.size}",
                            )
                            AnnotationPipelineLog.payload("RX", "annotation_sync", payload)
                            onAnnotationSync(payload)
                        }
                    }
            }
            launch {
                realtimeChannel.broadcastFlow<JsonObject>(EVENT_CLEAR)
                    .catch { error -> Log.e(TAG, "clear flow error", error) }
                    .collect {
                        AnnotationPipelineLog.stage("RX", "clear_annotations")
                        onClear()
                    }
            }
            launch {
                realtimeChannel.broadcastFlow<JsonObject>(EVENT_POINTER)
                    .catch { error -> Log.e(TAG, "pointer flow error", error) }
                    .collect { raw ->
                        parsePointer(raw)?.let { payload ->
                            onPointer(payload)
                        }
                    }
            }
            launch {
                realtimeChannel.broadcastFlow<JsonObject>(EVENT_CLEAR_SINGLE)
                    .catch { error -> Log.e(TAG, "clear_single flow error", error) }
                    .collect { raw ->
                        parseClearSingle(raw)?.let { payload ->
                            onClearSingle(payload)
                        }
                    }
            }
            launch {
                realtimeChannel.broadcastFlow<JsonObject>(EVENT_PLACE_MODEL)
                    .catch { error -> Log.e(TAG, "place_model flow error", error) }
                    .collect { raw ->
                        parsePlaceModel(raw)?.let { payload ->
                            onPlaceModel(payload)
                        }
                    }
            }
        }
    }

    suspend fun sendPlaceModel(sessionId: String, payload: PlaceModelPayload) {
        sendPayload(sessionId, EVENT_PLACE_MODEL, payload)
    }

    suspend fun sendPointer(sessionId: String, payload: PointerPayload) {
        sendPayload(sessionId, EVENT_POINTER, payload)
    }

    suspend fun sendClearSingle(sessionId: String, payload: ClearSinglePayload) {
        sendPayload(sessionId, EVENT_CLEAR_SINGLE, payload)
    }

    suspend fun sendAnnotation(sessionId: String, payload: AnnotationPayload) {
        AnnotationPipelineLog.stage("TX", "annotation id=${payload.id} pts=${payload.points.size}")
        AnnotationPipelineLog.payload("TX", "annotation", payload)
        sendPayload(sessionId, EVENT_ANNOTATION, payload)
    }

    suspend fun sendAnnotationSync(sessionId: String, payload: AnnotationSyncPayload) {
        if (payload.points.size < 2) {
            return
        }
        AnnotationPipelineLog.stage("TX", "annotation_sync id=${payload.id} pts=${payload.points.size}")
        AnnotationPipelineLog.payload("TX", "annotation_sync", payload)
        sendPayload(sessionId, EVENT_ANNOTATION_SYNC, payload)
    }

    suspend fun sendClear(sessionId: String) {
        AnnotationPipelineLog.stage("TX", "clear_annotations")
        sendPayload(sessionId, EVENT_CLEAR, JsonObject(emptyMap()))
    }

    suspend fun disconnect() {
        subscribeMutex.withLock {
            disconnectInternal()
        }
    }

    private suspend fun disconnectInternal() {
        wsReady = false
        listenJob?.cancel()
        listenJob = null
        channel?.let { ch ->
            runCatching { ch.unsubscribe() }
            runCatching { supabaseClient.realtime.removeChannel(ch) }
        }
        channel = null
        activeSessionId = null
    }

    private fun parseClearSingle(raw: JsonObject): ClearSinglePayload? =
        runCatching { json.decodeFromJsonElement<ClearSinglePayload>(raw) }
            .getOrElse { error ->
                Log.e(TAG, "Failed to parse clear_single raw=$raw", error)
                null
            }

    private fun parsePointer(raw: JsonObject): PointerPayload? =
        runCatching { json.decodeFromJsonElement<PointerPayload>(raw) }
            .getOrElse { error ->
                Log.e(TAG, "Failed to parse pointer raw=$raw", error)
                null
            }

    private fun parsePlaceModel(raw: JsonObject): PlaceModelPayload? =
        runCatching { json.decodeFromJsonElement<PlaceModelPayload>(raw) }
            .getOrElse { error ->
                Log.e(TAG, "Failed to parse place_model raw=$raw", error)
                null
            }

    private fun parseAnnotation(raw: JsonObject): AnnotationPayload? =
        runCatching { json.decodeFromJsonElement<AnnotationPayload>(raw) }
            .getOrElse { error ->
                Log.e(TAG, "Failed to parse annotation raw=$raw", error)
                null
            }

    private fun parseAnnotationSync(raw: JsonObject): AnnotationSyncPayload? =
        runCatching { json.decodeFromJsonElement<AnnotationSyncPayload>(raw) }
            .getOrElse { error ->
                Log.e(TAG, "Failed to parse annotation_sync raw=$raw", error)
                null
            }

    private suspend fun waitForAuthToken(): String? {
        repeat(AUTH_WAIT_ATTEMPTS) {
            supabaseClient.auth.currentSessionOrNull()?.accessToken?.let { return it }
            delay(AUTH_WAIT_MS)
        }
        return supabaseClient.auth.currentSessionOrNull()?.accessToken
    }

    private suspend fun sendPayload(sessionId: String, event: String, payload: Any) {
        val ch = channel
        var wsSucceeded = false
        if (wsReady && ch != null && ch.status.value == RealtimeChannel.Status.SUBSCRIBED) {
            wsSucceeded = runCatching {
                when (payload) {
                    is AnnotationPayload -> ch.broadcast(event, payload)
                    is AnnotationSyncPayload -> ch.broadcast(event, payload)
                    is PointerPayload -> ch.broadcast(event, payload)
                    is ClearSinglePayload -> ch.broadcast(event, payload)
                    is PlaceModelPayload -> ch.broadcast(event, payload)
                    is JsonObject -> ch.broadcast(event, payload)
                    else -> error("Unsupported payload")
                }
                AnnotationPipelineLog.stage("TX", "WS broadcast ok event=$event")
            }.onFailure { wsError ->
                Log.w(TAG, "WS broadcast failed event=$event", wsError)
            }.isSuccess
        }

        if (!wsSucceeded) {
            runCatching {
                restBroadcastPath(sessionId, event, payload)
            }.onFailure { error ->
                Log.w(TAG, "REST broadcast failed event=$event", error)
            }
        }
    }

    private suspend fun restBroadcastPath(sessionId: String, event: String, payload: Any) {
        val token = supabaseClient.auth.currentSessionOrNull()?.accessToken ?: return
        val topic = channelName(sessionId)
        val encodedTopic = URLEncoder.encode(topic, Charsets.UTF_8.name())
        val bodyString = when (payload) {
            is AnnotationPayload -> json.encodeToString(payload)
            is AnnotationSyncPayload -> json.encodeToString(payload)
            is PointerPayload -> json.encodeToString(payload)
            is ClearSinglePayload -> json.encodeToString(payload)
            is JsonObject -> json.encodeToString(payload)
            else -> return
        }

        val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/realtime/v1/api/broadcast/$encodedTopic/events/$event"
        val response = httpClient.post(url) {
            header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(bodyString)
        }

        if (!response.status.isSuccess()) {
            Log.w(TAG, "REST path broadcast failed status=${response.status} topic=$topic event=$event")
            restBroadcastBatch(sessionId, event, payload)
        } else {
            AnnotationPipelineLog.stage("TX", "REST ok status=${response.status.value} event=$event")
        }
    }

    private suspend fun restBroadcastBatch(sessionId: String, event: String, payload: Any) {
        val token = supabaseClient.auth.currentSessionOrNull()?.accessToken ?: return
        val topic = channelName(sessionId)
        val payloadJson = when (payload) {
            is AnnotationPayload -> json.encodeToJsonElement(payload)
            is AnnotationSyncPayload -> json.encodeToJsonElement(payload)
            is PointerPayload -> json.encodeToJsonElement(payload)
            is ClearSinglePayload -> json.encodeToJsonElement(payload)
            is JsonObject -> payload
            else -> return
        }

        val body = buildJsonObject {
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("topic", topic)
                            put("event", event)
                            put("payload", payloadJson)
                        },
                    )
                },
            )
        }

        val response = httpClient.post("${BuildConfig.SUPABASE_URL.trimEnd('/')}/realtime/v1/api/broadcast") {
            header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(body))
        }

        if (response.status.isSuccess()) {
            AnnotationPipelineLog.stage("TX", "REST batch ok status=${response.status.value} event=$event")
        } else {
            Log.w(TAG, "REST batch broadcast failed event=$event status=${response.status}")
        }
    }

    companion object {
        private const val TAG = "AnnotationChannel"
        private const val EVENT_ANNOTATION = "annotation"
        private const val EVENT_ANNOTATION_SYNC = "annotation_sync"
        private const val EVENT_CLEAR = "clear_annotations"
        private const val EVENT_POINTER = "pointer"
        private const val EVENT_CLEAR_SINGLE = "clear_single"
        private const val EVENT_PLACE_MODEL = "place_model"
        private const val AUTH_WAIT_ATTEMPTS = 40
        private const val AUTH_WAIT_MS = 250L
        private const val SUBSCRIBE_ATTEMPTS = 3
        private const val SUBSCRIBE_RETRY_MS = 1_000L
        private const val BACKGROUND_RETRY_MAX_MS = 8_000L

        fun channelName(sessionId: String): String = "annotations:$sessionId"
    }
}
