package com.cgsapple.remotear.ui.auth

import com.cgsapple.remotear.data.model.Profile

sealed interface AuthUiState {
    data object Loading : AuthUiState

    data object Unauthenticated : AuthUiState

    data class SigningIn(
        val message: String = "Opening Google sign-in…",
    ) : AuthUiState

    data class Authenticated(
        val profile: Profile,
    ) : AuthUiState

    data class Error(
        val message: String,
    ) : AuthUiState
}
