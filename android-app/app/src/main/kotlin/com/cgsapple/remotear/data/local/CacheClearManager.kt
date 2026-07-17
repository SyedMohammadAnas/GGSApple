package com.cgsapple.remotear.data.local

import android.content.Context
import coil.imageLoader
import coil.memory.MemoryCache
import com.cgsapple.remotear.data.repository.ModelRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Clears app caches while preserving the Supabase auth session and debug URL overrides.
 */
@Singleton
class CacheClearManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRepository: ModelRepository,
) {
    suspend fun clearAppCache(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            modelRepository.clearCache()
            context.imageLoader.memoryCache?.clear()
            context.imageLoader.diskCache?.clear()
            deleteDirContents(context.cacheDir)
            context.externalCacheDir?.let { deleteDirContents(it) }
            context.codeCacheDir?.let { deleteDirContents(it) }
            Unit
        }
    }

    private fun deleteDirContents(dir: java.io.File) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
        }
    }
}
