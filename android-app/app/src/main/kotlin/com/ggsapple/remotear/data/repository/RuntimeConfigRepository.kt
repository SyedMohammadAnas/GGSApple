package com.ggsapple.remotear.data.repository

import com.ggsapple.remotear.BuildConfig
import com.ggsapple.remotear.data.local.RuntimeConfigStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RuntimeConfigRepository @Inject constructor(
    private val store: RuntimeConfigStore,
) {
    val apiUrl: Flow<String> = store.apiUrlOverride.map { override ->
        override?.trimEnd('/') ?: BuildConfig.API_URL.trimEnd('/')
    }

    val livekitUrl: Flow<String> = store.livekitUrlOverride.map { override ->
        override ?: BuildConfig.LIVEKIT_URL
    }

    fun apiUrlBlocking(): String = runBlocking {
        apiUrl.first()
    }

    fun livekitUrlBlocking(): String = runBlocking {
        livekitUrl.first()
    }

    suspend fun setApiUrlOverride(url: String?) = store.setApiUrlOverride(url)

    suspend fun setLivekitUrlOverride(url: String?) = store.setLivekitUrlOverride(url)

    val apiUrlOverride: Flow<String?> = store.apiUrlOverride
    val livekitUrlOverride: Flow<String?> = store.livekitUrlOverride
}
