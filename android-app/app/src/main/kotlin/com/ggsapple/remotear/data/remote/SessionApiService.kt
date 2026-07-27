package com.ggsapple.remotear.data.remote

import com.ggsapple.remotear.data.model.ApiErrorResponse
import com.ggsapple.remotear.data.model.EndSessionResponse
import com.ggsapple.remotear.data.model.JoinSessionResponse
import com.ggsapple.remotear.data.model.SessionDetailResponse
import com.ggsapple.remotear.data.model.SessionRow
import com.ggsapple.remotear.data.model.SessionApiException
import com.ggsapple.remotear.data.repository.RuntimeConfigRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Customer Instant session API.
 * Expert joins via expert-web; customer claims the room with [customerEnter].
 */
@Singleton
class SessionApiService @Inject constructor(
    private val httpClient: HttpClient,
    private val supabase: SupabaseClient,
    private val runtimeConfig: RuntimeConfigRepository,
) {
    private val baseUrl: String
        get() = runtimeConfig.apiUrlBlocking()

    /** Claims LiveKit credentials once an expert has activated a session for this user. */
    suspend fun customerEnter(): JoinSessionResponse =
        postJson("$baseUrl/api/sessions/customer-enter")

    suspend fun endSession(sessionId: String): EndSessionResponse =
        postJson("$baseUrl/api/sessions/$sessionId/end")

    suspend fun getSession(sessionId: String): SessionRow {
        val response = httpClient.get("$baseUrl/api/sessions/$sessionId") {
            header(HttpHeaders.Authorization, bearerToken())
        }
        return parseResponse<SessionDetailResponse>(response).session
    }

    private suspend inline fun <reified T> postJson(url: String): T {
        val response = httpClient.post(url) {
            header(HttpHeaders.Authorization, bearerToken())
        }
        return parseResponse(response)
    }

    private suspend fun bearerToken(): String {
        val token = supabase.auth.currentSessionOrNull()?.accessToken
        require(!token.isNullOrBlank()) { "Not authenticated" }
        return "Bearer $token"
    }

    private suspend inline fun <reified T> parseResponse(response: HttpResponse): T {
        if (!response.status.isSuccess()) {
            val message = runCatching {
                response.body<ApiErrorResponse>().error
            }.getOrNull() ?: "Request failed (${response.status.value})"
            throw SessionApiException(message, response.status.value)
        }
        return response.body()
    }
}
