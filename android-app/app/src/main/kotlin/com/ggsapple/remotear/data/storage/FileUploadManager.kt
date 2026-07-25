package com.ggsapple.remotear.data.storage

import android.util.Log
import com.ggsapple.remotear.di.ApplicationScope
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileUploadManager @Inject constructor(
    private val supabase: SupabaseClient,
    @ApplicationScope private val scope: CoroutineScope,
) {
    suspend fun uploadSessionFile(
        sessionId: String,
        fileName: String,
        inputStream: InputStream,
        mimeType: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val userId = supabase.auth.currentUserOrNull()?.id
                ?: error("Not authenticated")
            val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val path = "$sessionId/$userId/${System.currentTimeMillis()}_$safeName"
            val bytes = inputStream.use { it.readBytes() }
            supabase.storage.from(BUCKET).upload(path, bytes) {
                upsert = false
                contentType = ContentType.parse(mimeType)
            }
            supabase.storage.from(BUCKET).publicUrl(path)
        }.onFailure { e ->
            Log.e(TAG, "upload failed sessionId=$sessionId file=$fileName", e)
        }
    }

    companion object {
        private const val TAG = "FileUploadManager"
        private const val BUCKET = "session-files"
    }
}
