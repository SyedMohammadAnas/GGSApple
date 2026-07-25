package com.ggsapple.remotear.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ggsapple.remotear.data.local.AppMode
import com.ggsapple.remotear.data.local.AppModeStore
import com.ggsapple.remotear.data.local.CacheClearManager
import com.ggsapple.remotear.data.model.ActiveSession
import com.ggsapple.remotear.data.model.Profile
import com.ggsapple.remotear.data.model.SessionApiException
import com.ggsapple.remotear.data.model.SessionStatus
import com.ggsapple.remotear.data.repository.RuntimeConfigRepository
import com.ggsapple.remotear.data.repository.SessionRepository
import com.ggsapple.remotear.util.PublicIdFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val profile: Profile? = null,
    val appMode: AppMode = AppMode.CUSTOMER,
    val formattedPublicId: String = "—",
    val expertIdInput: String = "",
    val isLoading: Boolean = false,
    val isEnsuringSession: Boolean = false,
    val errorMessage: String? = null,
    val joinedSession: ActiveSession? = null,
    val navigateToCustomerCall: ActiveSession? = null,
    val incomingSession: Boolean = false,
    val showDebugSheet: Boolean = false,
    val debugApiUrl: String = "",
    val debugLivekitUrl: String = "",
    val connectionReady: Boolean = false,
    val cacheClearMessage: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val appModeStore: AppModeStore,
    private val runtimeConfigRepository: RuntimeConfigRepository,
    private val cacheClearManager: CacheClearManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var customerSession: ActiveSession? = null
    private var sessionPollJob: Job? = null

    fun bindProfile(profile: Profile) {
        _uiState.update {
            it.copy(
                profile = profile,
                formattedPublicId = PublicIdFormatter.formatDisplay(profile.publicId),
            )
        }
        if (_uiState.value.appMode == AppMode.CUSTOMER) {
            ensureCustomerWaitingSession()
        }
    }

    init {
        viewModelScope.launch {
            appModeStore.mode.collect { mode ->
                _uiState.update { it.copy(appMode = mode, incomingSession = false) }
                if (mode == AppMode.CUSTOMER) {
                    ensureCustomerWaitingSession()
                } else {
                    stopSessionPolling()
                }
            }
        }
        viewModelScope.launch {
            runtimeConfigRepository.apiUrlOverride.collect { override ->
                _uiState.update { it.copy(debugApiUrl = override.orEmpty()) }
            }
        }
        viewModelScope.launch {
            runtimeConfigRepository.livekitUrlOverride.collect { override ->
                _uiState.update { it.copy(debugLivekitUrl = override.orEmpty()) }
            }
        }
    }

    fun setAppMode(mode: AppMode) {
        viewModelScope.launch {
            appModeStore.setMode(mode)
        }
    }

    fun onExpertIdChange(value: String) {
        val digits = value.filter { it.isDigit() }.take(11)
        _uiState.update { it.copy(expertIdInput = digits, errorMessage = null) }
    }

    fun pasteExpertId(text: String) {
        onExpertIdChange(PublicIdFormatter.normalize(text))
    }

    /** Ensures a waiting session exists so experts can join while the customer stays on home. */
    fun ensureCustomerWaitingSession() {
        if (_uiState.value.appMode != AppMode.CUSTOMER || customerSession != null || _uiState.value.isEnsuringSession) {
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isEnsuringSession = true, errorMessage = null) }
            sessionRepository.createSession()
                .onSuccess { session ->
                    customerSession = session
                    _uiState.update { it.copy(isEnsuringSession = false) }
                    startSessionPolling(session)
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isEnsuringSession = false,
                            errorMessage = error.message ?: "Failed to prepare session",
                        )
                    }
                }
        }
    }

    fun shareId() {
        if (_uiState.value.appMode != AppMode.CUSTOMER) return
        if (customerSession == null) {
            ensureCustomerWaitingSession()
        }
    }

    fun joinSessionById() {
        val id = _uiState.value.expertIdInput
        if (!PublicIdFormatter.isValid(id) || _uiState.value.isLoading) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            sessionRepository.joinSessionByPublicId(id)
                .onSuccess { session ->
                    _uiState.update { it.copy(isLoading = false, joinedSession = session) }
                }
                .onFailure { error ->
                    val message = when (error) {
                        is SessionApiException -> error.errorMessage
                        else -> error.message ?: "Failed to join session"
                    }
                    _uiState.update { it.copy(isLoading = false, errorMessage = message) }
                }
        }
    }

    fun clearNavigation() {
        _uiState.update {
            it.copy(
                joinedSession = null,
                navigateToCustomerCall = null,
                incomingSession = false,
            )
        }
    }

    fun onCustomerCallNavigated() {
        _uiState.update { it.copy(navigateToCustomerCall = null, incomingSession = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun setConnectionReady(ready: Boolean) {
        _uiState.update { it.copy(connectionReady = ready) }
    }

    fun clearCache() {
        viewModelScope.launch {
            cacheClearManager.clearAppCache()
                .onSuccess {
                    _uiState.update { it.copy(cacheClearMessage = "Cache cleared") }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(cacheClearMessage = error.message ?: "Failed to clear cache")
                    }
                }
        }
    }

    fun dismissCacheMessage() {
        _uiState.update { it.copy(cacheClearMessage = null) }
    }

    fun openDebugSheet() {
        _uiState.update { it.copy(showDebugSheet = true) }
    }

    fun dismissDebugSheet() {
        _uiState.update { it.copy(showDebugSheet = false) }
    }

    fun onDebugApiUrlChange(value: String) {
        _uiState.update { it.copy(debugApiUrl = value) }
    }

    fun onDebugLivekitUrlChange(value: String) {
        _uiState.update { it.copy(debugLivekitUrl = value) }
    }

    fun saveDebugUrls() {
        viewModelScope.launch {
            val state = _uiState.value
            runtimeConfigRepository.setApiUrlOverride(state.debugApiUrl.ifBlank { null })
            runtimeConfigRepository.setLivekitUrlOverride(state.debugLivekitUrl.ifBlank { null })
            _uiState.update { it.copy(showDebugSheet = false) }
        }
    }

    fun resetDebugUrls() {
        viewModelScope.launch {
            runtimeConfigRepository.setApiUrlOverride(null)
            runtimeConfigRepository.setLivekitUrlOverride(null)
            _uiState.update { it.copy(debugApiUrl = "", debugLivekitUrl = "", showDebugSheet = false) }
        }
    }

    private fun startSessionPolling(session: ActiveSession) {
        stopSessionPolling()
        sessionPollJob = viewModelScope.launch {
            sessionRepository.observeSessionStatus(session.sessionId).collect { status ->
                when (status) {
                    SessionStatus.ACTIVE -> {
                        _uiState.update { it.copy(incomingSession = true) }
                        _uiState.update {
                            it.copy(navigateToCustomerCall = session)
                        }
                    }
                    SessionStatus.ENDED -> {
                        customerSession = null
                        _uiState.update { it.copy(incomingSession = false) }
                        ensureCustomerWaitingSession()
                    }
                    SessionStatus.WAITING -> Unit
                }
            }
        }
    }

    private fun stopSessionPolling() {
        sessionPollJob?.cancel()
        sessionPollJob = null
    }

    override fun onCleared() {
        stopSessionPolling()
        super.onCleared()
    }
}
