package com.cgsapple.remotear.data.remote

import com.cgsapple.remotear.data.repository.RuntimeConfigRepository
import com.cgsapple.remotear.data.model.ModelsListResponse
import com.cgsapple.remotear.data.model.SessionApiException
import com.cgsapple.remotear.data.model.ApiErrorResponse
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelsApiService @Inject constructor(
    private val httpClient: HttpClient,
    private val supabase: SupabaseClient,
    private val runtimeConfig: RuntimeConfigRepository,
) {
    private val baseUrl: String
        get() = runtimeConfig.apiUrlBlocking()

    suspend fun fetchModels(): ModelsListResponse {
        val response = httpClient.get("$baseUrl/api/models") {
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
