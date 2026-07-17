package com.cgsapple.remotear.data.remote

import com.cgsapple.remotear.data.model.ApiErrorResponse
import com.cgsapple.remotear.data.model.CreateSessionResponse
import com.cgsapple.remotear.data.model.EndSessionResponse
import com.cgsapple.remotear.data.model.JoinSessionResponse
import com.cgsapple.remotear.data.model.JoinByPublicIdRequest
import com.cgsapple.remotear.data.model.SessionDetailResponse
import com.cgsapple.remotear.data.model.SessionRow
import com.cgsapple.remotear.data.model.SessionApiException
import com.cgsapple.remotear.data.repository.RuntimeConfigRepository
import com.cgsapple.remotear.util.PublicIdFormatter
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionApiService @Inject constructor(
    private val httpClient: HttpClient,
    private val supabase: SupabaseClient,
    private val runtimeConfig: RuntimeConfigRepository,
) {
    private val baseUrl: String
        get() = runtimeConfig.apiUrlBlocking()

    suspend fun createSession(): CreateSessionResponse =
        postJson("$baseUrl/api/sessions")

    suspend fun joinSession(joinCode: String): JoinSessionResponse {
        val normalized = normalizeJoinCode(joinCode)
        return postJson("$baseUrl/api/sessions/$normalized/join")
    }

    suspend fun joinSessionByPublicId(publicId: String): JoinSessionResponse {
        val normalized = PublicIdFormatter.normalize(publicId)
        val response = httpClient.post("$baseUrl/api/sessions/join-by-id") {
            header(HttpHeaders.Authorization, bearerToken())
            contentType(ContentType.Application.Json)
            setBody(JoinByPublicIdRequest(targetPublicId = normalized))
        }
        return parseResponse(response)
    }

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

    companion object {
        fun normalizeJoinCode(input: String): String {
            val cleaned = input.uppercase().filter { it.isLetterOrDigit() }
            if (cleaned.length != 6) {
                return input.trim().uppercase()
            }
            return "${cleaned.take(3)}-${cleaned.drop(3)}"
        }
    }
}
