package com.ggsapple.remotear.data.repository

import com.ggsapple.remotear.data.model.ModelItem
import com.ggsapple.remotear.data.remote.ModelsApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelRepository @Inject constructor(
    private val modelsApiService: ModelsApiService,
) {
    private var cachedModels: List<ModelItem>? = null

    suspend fun getModels(forceRefresh: Boolean = false): Result<List<ModelItem>> {
        if (!forceRefresh) {
            cachedModels?.let { return Result.success(it) }
        }
        return runCatching {
            modelsApiService.fetchModels().models.also { cachedModels = it }
        }
    }

    fun clearCache() {
        cachedModels = null
    }
}
