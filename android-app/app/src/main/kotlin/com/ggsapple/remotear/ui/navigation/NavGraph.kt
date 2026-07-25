package com.ggsapple.remotear.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ggsapple.remotear.ui.auth.AuthScreen
import com.ggsapple.remotear.ui.auth.AuthUiState
import com.ggsapple.remotear.ui.auth.AuthViewModel
import com.ggsapple.remotear.ui.auth.SplashScreen

@Composable
fun RemoteArNavGraph(
    modifier: Modifier = Modifier,
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val authState by authViewModel.uiState.collectAsStateWithLifecycle()

    when (val state = authState) {
        AuthUiState.Loading -> SplashScreen(modifier = modifier)

        is AuthUiState.SigningIn -> AuthScreen(
            modifier = modifier,
            isSigningIn = true,
            errorMessage = null,
            onSignInClick = authViewModel::signInWithGoogle,
            onDismissError = authViewModel::clearError,
        )

        AuthUiState.Unauthenticated -> AuthScreen(
            modifier = modifier,
            isSigningIn = false,
            errorMessage = null,
            onSignInClick = authViewModel::signInWithGoogle,
            onDismissError = authViewModel::clearError,
        )

        is AuthUiState.Error -> AuthScreen(
            modifier = modifier,
            isSigningIn = false,
            errorMessage = state.message,
            onSignInClick = {
                authViewModel.clearError()
                authViewModel.signInWithGoogle()
            },
            onDismissError = authViewModel::clearError,
        )

        is AuthUiState.Authenticated -> AuthenticatedNavGraph(
            profile = state.profile,
            onSignOut = authViewModel::signOut,
            modifier = modifier,
        )
    }
}
