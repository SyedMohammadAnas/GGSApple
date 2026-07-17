package com.cgsapple.remotear.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cgsapple.remotear.data.model.ActiveSession
import com.cgsapple.remotear.data.model.SessionApiException
import com.cgsapple.remotear.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class JoinSessionUiState(
    val joinCodeInput: String = "",
    val isJoining: Boolean = false,
    val errorMessage: String? = null,
    val joinedSession: ActiveSession? = null,
)

@HiltViewModel
class JoinSessionViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(JoinSessionUiState())
    val uiState: StateFlow<JoinSessionUiState> = _uiState.asStateFlow()

    fun onJoinCodeChange(value: String) {
        val filtered = value.uppercase().filter { it.isLetterOrDigit() }.take(6)
        _uiState.value = _uiState.value.copy(
            joinCodeInput = filtered,
            errorMessage = null,
        )
    }

    fun joinSession() {
        val code = _uiState.value.joinCodeInput
        if (code.length < 6 || _uiState.value.isJoining) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isJoining = true, errorMessage = null)
            sessionRepository.joinSession(code)
                .onSuccess { session ->
                    _uiState.value = _uiState.value.copy(
                        isJoining = false,
                        joinedSession = session,
                    )
                }
                .onFailure { error ->
                    val message = when (error) {
                        is SessionApiException -> error.errorMessage
                        else -> error.message ?: "Failed to join session"
                    }
                    _uiState.value = _uiState.value.copy(
                        isJoining = false,
                        errorMessage = message,
                    )
                }
        }
    }

    fun clearNavigation() {
        _uiState.value = _uiState.value.copy(joinedSession = null)
    }
}
