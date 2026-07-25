package com.ggsapple.remotear.data.repository

import com.ggsapple.remotear.data.model.ActiveSession
import com.ggsapple.remotear.data.model.CreateSessionResponse
import com.ggsapple.remotear.data.model.JoinSessionResponse
import com.ggsapple.remotear.data.model.SessionApiException
import com.ggsapple.remotear.data.model.SessionParticipantRole
import com.ggsapple.remotear.data.model.SessionStatus
import com.ggsapple.remotear.data.model.SessionRow
import com.ggsapple.remotear.data.remote.SessionApiService
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepository @Inject constructor(
    private val sessionApiService: SessionApiService,
) {
    private var cachedActiveSession: ActiveSession? = null

    fun getCachedSession(sessionId: String): ActiveSession? =
        cachedActiveSession?.takeIf { it.sessionId == sessionId }

    fun clearCachedSession() {
        cachedActiveSession = null
    }

    suspend fun createSession(): Result<ActiveSession> =
        runCatching {
            val response = sessionApiService.createSession()
            toActiveSession(response, SessionParticipantRole.CUSTOMER)
        }.fold(
            onSuccess = { session ->
                cachedActiveSession = session
                Result.success(session)
            },
            onFailure = { Result.failure(mapError(it)) },
        )

    suspend fun joinSession(joinCode: String): Result<ActiveSession> =
        runCatching {
            val response = sessionApiService.joinSession(joinCode)
            toActiveSession(response, SessionParticipantRole.TECHNICIAN)
        }.fold(
            onSuccess = { session ->
                cachedActiveSession = session
                Result.success(session)
            },
            onFailure = { Result.failure(mapError(it)) },
        )

    suspend fun joinSessionByPublicId(publicId: String): Result<ActiveSession> =
        runCatching {
            val response = sessionApiService.joinSessionByPublicId(publicId)
            toActiveSession(response, SessionParticipantRole.TECHNICIAN)
        }.fold(
            onSuccess = { session ->
                cachedActiveSession = session
                Result.success(session)
            },
            onFailure = { Result.failure(mapError(it)) },
        )

    suspend fun fetchSession(sessionId: String): SessionRow? =
        runCatching {
            sessionApiService.getSession(sessionId)
        }.getOrNull()

    suspend fun endSession(sessionId: String): Result<Unit> =
        runCatching {
            sessionApiService.endSession(sessionId)
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.failure(mapError(it)) },
        )

    suspend fun fetchSessionStatus(sessionId: String): SessionStatus? =
        runCatching {
            sessionApiService.getSession(sessionId).status
        }.getOrNull()?.let(SessionStatus::fromRaw)

    /**
     * Polls the backend every second for session status changes.
     * Matches the proven v1 React Native `useSessionSync` approach.
     */
    fun observeSessionStatus(sessionId: String): Flow<SessionStatus> = flow {
        while (currentCoroutineContext().isActive) {
            fetchSessionStatus(sessionId)?.let { status ->
                emit(status)
            }
            delay(POLL_INTERVAL_MS)
        }
    }.distinctUntilChanged()

    private fun toActiveSession(
        response: CreateSessionResponse,
        role: SessionParticipantRole,
    ): ActiveSession =
        ActiveSession(
            sessionId = response.sessionId,
            joinCode = response.joinCode,
            roomName = response.roomName,
            livekitToken = response.token,
            role = role,
        )

    private fun toActiveSession(
        response: JoinSessionResponse,
        role: SessionParticipantRole,
    ): ActiveSession =
        ActiveSession(
            sessionId = response.sessionId,
            joinCode = response.joinCode,
            roomName = response.roomName,
            livekitToken = response.token,
            role = role,
        )

    private fun mapError(throwable: Throwable): Throwable {
        val message = throwable.message.orEmpty()
        if (
            message.contains("Failed to connect", ignoreCase = true) ||
            message.contains("Unable to resolve host", ignoreCase = true) ||
            message.contains("Connection refused", ignoreCase = true) ||
            message.contains("Network is unreachable", ignoreCase = true)
        ) {
            return SessionApiException(
                "Cannot reach the session server. Check that Docker is running and API_URL in local.properties matches your PC's Wi‑Fi IP.",
                0,
            )
        }
        return when (throwable) {
            is SessionApiException -> throwable
            else -> SessionApiException(throwable.message ?: "Session request failed")
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1_000L
    }
}
