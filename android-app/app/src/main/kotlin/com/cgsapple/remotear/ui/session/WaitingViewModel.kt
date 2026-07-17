package com.cgsapple.remotear.ui.session

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cgsapple.remotear.data.model.SessionStatus
import com.cgsapple.remotear.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WaitingUiState(
    val joinCode: String,
    val publicId: String = "",
    val expertJoining: Boolean = false,
    val isCancelling: Boolean = false,
    val errorMessage: String? = null,
    val navigateToCall: Boolean = false,
    val navigateHome: Boolean = false,
)

@HiltViewModel
class WaitingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"])
    val sessionIdPublic: String get() = sessionId
    private val joinCode: String = checkNotNull(savedStateHandle["joinCode"])
    private val publicId: String = savedStateHandle["publicId"] ?: ""

    private val _uiState = MutableStateFlow(
        WaitingUiState(joinCode = joinCode, publicId = publicId),
    )
    val uiState: StateFlow<WaitingUiState> = _uiState.asStateFlow()

    private var observeJob: Job? = null

    init {
        observeJob = viewModelScope.launch {
            while (true) {
                val session = sessionRepository.fetchSession(sessionId)
                if (session != null) {
                    val expertJoining = !session.technicianId.isNullOrBlank()
                    _uiState.value = _uiState.value.copy(expertJoining = expertJoining)
                    when (SessionStatus.fromRaw(session.status)) {
                        SessionStatus.ACTIVE -> {
                            _uiState.value = _uiState.value.copy(navigateToCall = true)
                            break
                        }
                        SessionStatus.ENDED -> {
                            _uiState.value = _uiState.value.copy(navigateHome = true)
                            break
                        }
                        SessionStatus.WAITING -> Unit
                        null -> Unit
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    fun cancelSession() {
        if (_uiState.value.isCancelling) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCancelling = true, errorMessage = null)
            sessionRepository.endSession(sessionId)
                .onSuccess {
                    sessionRepository.clearCachedSession()
                    _uiState.value = _uiState.value.copy(
                        isCancelling = false,
                        navigateHome = true,
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isCancelling = false,
                        errorMessage = error.message ?: "Failed to cancel session",
                    )
                }
        }
    }

    fun onNavigatedToCall() {
        _uiState.value = _uiState.value.copy(navigateToCall = false)
    }

    fun onNavigatedHome() {
        _uiState.value = _uiState.value.copy(navigateHome = false)
    }

    override fun onCleared() {
        observeJob?.cancel()
        super.onCleared()
    }
}
