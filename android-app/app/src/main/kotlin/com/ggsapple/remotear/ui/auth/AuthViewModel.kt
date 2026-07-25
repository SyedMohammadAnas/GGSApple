package com.ggsapple.remotear.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ggsapple.remotear.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.sessionStatus.collect { status ->
                when (status) {
                    SessionStatus.Initializing -> {
                        _uiState.update { current ->
                            if (current is AuthUiState.SigningIn) current else AuthUiState.Loading
                        }
                    }

                    is SessionStatus.NotAuthenticated -> {
                        _uiState.update { current ->
                            when {
                                status.isSignOut -> AuthUiState.Unauthenticated
                                current is AuthUiState.SigningIn -> AuthUiState.Error(
                                    "Sign-in was cancelled or failed.",
                                )
                                else -> AuthUiState.Unauthenticated
                            }
                        }
                    }

                    is SessionStatus.RefreshFailure -> {
                        // Session refresh failed; keep current UI until sign-out or retry.
                    }

                    is SessionStatus.Authenticated -> {
                        val userId = status.session.user?.id
                        if (userId == null) {
                            _uiState.value = AuthUiState.Error("Signed in but user id is missing.")
                            return@collect
                        }
                        loadProfile(userId)
                    }
                }
            }
        }
    }

    fun signInWithGoogle() {
        viewModelScope.launch {
            _uiState.value = AuthUiState.SigningIn()
            runCatching {
                authRepository.signInWithGoogle()
            }.onFailure { error ->
                _uiState.value = AuthUiState.Error(
                    error.message ?: "Google sign-in failed.",
                )
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            runCatching {
                authRepository.signOut()
            }.onFailure { error ->
                _uiState.value = AuthUiState.Error(
                    error.message ?: "Sign-out failed.",
                )
            }
        }
    }

    fun clearError() {
        if (_uiState.value is AuthUiState.Error) {
            _uiState.value = AuthUiState.Unauthenticated
        }
    }

    private suspend fun loadProfile(userId: String) {
        val profile = authRepository.fetchProfile(userId)
        if (profile == null) {
            _uiState.value = AuthUiState.Error(
                "Profile not found. Check Supabase profiles table for this user.",
            )
            return
        }
        _uiState.value = AuthUiState.Authenticated(profile)
    }
}
