package com.cgsapple.remotear.data.repository

import android.content.Intent
import com.cgsapple.remotear.data.model.Profile
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val supabase: SupabaseClient,
) {
    val sessionStatus: Flow<SessionStatus> = supabase.auth.sessionStatus

    fun handleAuthDeeplink(intent: Intent?) {
        intent ?: return
        supabase.handleDeeplinks(intent)
    }

    suspend fun signInWithGoogle() {
        supabase.auth.signInWith(Google)
    }

    suspend fun signOut() {
        supabase.auth.signOut()
    }

    suspend fun fetchProfile(userId: String): Profile? {
        repeat(PROFILE_FETCH_ATTEMPTS) { attempt ->
            val profile = runCatching {
                supabase.from("profiles")
                    .select(Columns.ALL) {
                        filter {
                            eq("id", userId)
                        }
                    }
                    .decodeSingleOrNull<Profile>()
            }.getOrNull()

            if (profile != null) {
                return profile
            }

            if (attempt < PROFILE_FETCH_ATTEMPTS - 1) {
                delay(PROFILE_FETCH_DELAY_MS)
            }
        }
        return null
    }

    companion object {
        private const val PROFILE_FETCH_ATTEMPTS = 5
        private const val PROFILE_FETCH_DELAY_MS = 400L
    }
}
