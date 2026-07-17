package com.cgsapple.remotear.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cgsapple.remotear.data.model.ActiveSession
import com.cgsapple.remotear.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface CustomerHomeUiState {
    data object Idle : CustomerHomeUiState
    data object Loading : CustomerHomeUiState
    data class Created(val session: ActiveSession) : CustomerHomeUiState
    data class Error(val message: String) : CustomerHomeUiState
}

@HiltViewModel
class CustomerHomeViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<CustomerHomeUiState>(CustomerHomeUiState.Idle)
    val uiState: StateFlow<CustomerHomeUiState> = _uiState.asStateFlow()

    fun startSession() {
        if (_uiState.value is CustomerHomeUiState.Loading) return

        viewModelScope.launch {
            _uiState.value = CustomerHomeUiState.Loading
            sessionRepository.createSession()
                .onSuccess { session ->
                    _uiState.value = CustomerHomeUiState.Created(session)
                }
                .onFailure { error ->
                    _uiState.value = CustomerHomeUiState.Error(
                        error.message ?: "Failed to create session",
                    )
                }
        }
    }

    fun clearNavigation() {
        if (_uiState.value is CustomerHomeUiState.Created) {
            _uiState.value = CustomerHomeUiState.Idle
        }
    }

    fun clearError() {
        if (_uiState.value is CustomerHomeUiState.Error) {
            _uiState.value = CustomerHomeUiState.Idle
        }
    }
}
