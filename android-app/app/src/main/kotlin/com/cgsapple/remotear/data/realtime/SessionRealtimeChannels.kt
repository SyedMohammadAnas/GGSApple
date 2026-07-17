package com.cgsapple.remotear.data.realtime

import android.util.Log
import com.cgsapple.remotear.di.ApplicationScope
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatChannel @Inject constructor(
    private val supabaseClient: SupabaseClient,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private var channel: RealtimeChannel? = null
    private var listenJob: Job? = null
    private var activeSessionId: String? = null
    private var localUserId: String? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val subscribeMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val isSubscribed: Boolean
        get() = channel?.status?.value == RealtimeChannel.Status.SUBSCRIBED

    fun subscribeWithRetry(sessionId: String, userId: String): Job {
        localUserId = userId
        return applicationScope.launch {
            var delayMs = RETRY_MS
            while (isActive && activeSessionId == sessionId) {
                val result = runCatching { subscribe(sessionId) }
                if (result.isSuccess) return@launch
                Log.w(TAG, "Chat subscribe retry", result.exceptionOrNull())
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_MS)
            }
        }
    }

    suspend fun subscribe(sessionId: String) {
        subscribeMutex.withLock {
            disconnectInternal()
            activeSessionId = sessionId
            val token = supabaseClient.auth.currentSessionOrNull()?.accessToken
                ?: throw IllegalStateException("Missing auth for chat")
            supabaseClient.realtime.setAuth(token)

            val ch = supabaseClient.channel(channelName(sessionId)) {
                isPrivate = false
                broadcast { acknowledgeBroadcasts = false; receiveOwnBroadcasts = false }
            }
            ch.subscribe(blockUntilSubscribed = true)
            channel = ch

            listenJob = applicationScope.launch {
                ch.broadcastFlow<JsonObject>(EVENT_CHAT)
                    .catch { e -> Log.e(TAG, "chat flow error", e) }
                    .collect { raw ->
                        parseMessage(raw)?.let { payload ->
                            appendMessage(payload, isLocal = payload.senderId == localUserId)
                        }
                    }
            }
        }
    }

    suspend fun sendMessage(text: String, senderId: String, senderName: String) {
        val sessionId = activeSessionId ?: return
        val payload = ChatMessagePayload(
            senderId = senderId,
            senderName = senderName,
            text = text.trim(),
            timestamp = System.currentTimeMillis(),
        )
        if (payload.text.isBlank()) return

        appendMessage(payload, isLocal = true)
        val ch = channel ?: return
        runCatching { ch.broadcast(EVENT_CHAT, payload) }
            .onFailure { e -> Log.e(TAG, "send chat failed", e) }
    }

    suspend fun disconnect() {
        subscribeMutex.withLock { disconnectInternal() }
    }

    private fun disconnectInternal() {
        listenJob?.cancel()
        listenJob = null
        val ch = channel
        channel = null
        activeSessionId = null
        _messages.value = emptyList()
        if (ch != null) {
            applicationScope.launch {
                runCatching { ch.unsubscribe() }
                runCatching { supabaseClient.realtime.removeChannel(ch) }
            }
        }
    }

    private fun appendMessage(payload: ChatMessagePayload, isLocal: Boolean) {
        val msg = ChatMessage(
            senderId = payload.senderId,
            senderName = payload.senderName,
            text = payload.text,
            timestamp = payload.timestamp,
            isLocal = isLocal,
        )
        _messages.value = (_messages.value + msg).sortedBy { it.timestamp }
    }

    private fun parseMessage(raw: JsonObject): ChatMessagePayload? =
        runCatching { json.decodeFromJsonElement<ChatMessagePayload>(raw) }
            .getOrElse { null }

    companion object {
        private const val TAG = "ChatChannel"
        private const val EVENT_CHAT = "chat_message"
        private const val RETRY_MS = 1_000L
        private const val MAX_RETRY_MS = 8_000L
        fun channelName(sessionId: String) = "chat:$sessionId"
    }
}

@Singleton
class FileShareChannel @Inject constructor(
    private val supabaseClient: SupabaseClient,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private var channel: RealtimeChannel? = null
    private var listenJob: Job? = null
    private var activeSessionId: String? = null
    private var localUserId: String? = null

    private val _sharedFiles = MutableStateFlow<List<SharedFileNotice>>(emptyList())
    val sharedFiles: StateFlow<List<SharedFileNotice>> = _sharedFiles.asStateFlow()

    private val subscribeMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun subscribeWithRetry(sessionId: String, userId: String): Job {
        localUserId = userId
        return applicationScope.launch {
            var delayMs = RETRY_MS
            while (isActive && activeSessionId == sessionId) {
                val result = runCatching { subscribe(sessionId) }
                if (result.isSuccess) return@launch
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_MS)
            }
        }
    }

    suspend fun subscribe(sessionId: String) {
        subscribeMutex.withLock {
            disconnectInternal()
            activeSessionId = sessionId
            val token = supabaseClient.auth.currentSessionOrNull()?.accessToken
                ?: throw IllegalStateException("Missing auth for file share")
            supabaseClient.realtime.setAuth(token)

            val ch = supabaseClient.channel(channelName(sessionId)) {
                isPrivate = false
                broadcast { acknowledgeBroadcasts = false; receiveOwnBroadcasts = false }
            }
            ch.subscribe(blockUntilSubscribed = true)
            channel = ch

            listenJob = applicationScope.launch {
                ch.broadcastFlow<JsonObject>(EVENT_FILE)
                    .catch { e -> Log.e(TAG, "file flow error", e) }
                    .collect { raw ->
                        parseFile(raw)?.let { payload ->
                            appendFile(payload, isLocal = payload.senderId == localUserId)
                        }
                    }
            }
        }
    }

    suspend fun broadcastFile(payload: FileSharePayload) {
        val sessionId = activeSessionId ?: return
        appendFile(payload, isLocal = true)
        val ch = channel ?: return
        runCatching { ch.broadcast(EVENT_FILE, payload) }
            .onFailure { e -> Log.e(TAG, "broadcast file failed", e) }
    }

    suspend fun disconnect() {
        subscribeMutex.withLock { disconnectInternal() }
    }

    private fun disconnectInternal() {
        listenJob?.cancel()
        listenJob = null
        val ch = channel
        channel = null
        activeSessionId = null
        _sharedFiles.value = emptyList()
        if (ch != null) {
            applicationScope.launch {
                runCatching { ch.unsubscribe() }
                runCatching { supabaseClient.realtime.removeChannel(ch) }
            }
        }
    }

    private fun appendFile(payload: FileSharePayload, isLocal: Boolean) {
        val notice = SharedFileNotice(
            senderId = payload.senderId,
            senderName = payload.senderName,
            fileUrl = payload.fileUrl,
            fileName = payload.fileName,
            fileSizeBytes = payload.fileSizeBytes,
            timestamp = payload.timestamp,
            isLocal = isLocal,
        )
        _sharedFiles.value = (_sharedFiles.value + notice).sortedBy { it.timestamp }
    }

    private fun parseFile(raw: JsonObject): FileSharePayload? =
        runCatching { json.decodeFromJsonElement<FileSharePayload>(raw) }
            .getOrElse { null }

    companion object {
        private const val TAG = "FileShareChannel"
        private const val EVENT_FILE = "file_share"
        private const val RETRY_MS = 1_000L
        private const val MAX_RETRY_MS = 8_000L
        fun channelName(sessionId: String) = "file:$sessionId"
    }
}
