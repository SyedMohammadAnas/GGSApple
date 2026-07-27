package com.ggsapple.remotear.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ggsapple.remotear.data.local.CacheClearManager
import com.ggsapple.remotear.data.model.ActiveSession
import com.ggsapple.remotear.data.model.Profile
import com.ggsapple.remotear.data.model.SessionApiException
import com.ggsapple.remotear.data.repository.RuntimeConfigRepository
import com.ggsapple.remotear.data.repository.SessionRepository
import com.ggsapple.remotear.util.PublicIdFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val profile: Profile? = null,
    val formattedPublicId: String = "—",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val navigateToCustomerCall: ActiveSession? = null,
    val incomingSession: Boolean = false,
    val showDebugSheet: Boolean = false,
    val debugApiUrl: String = "",
    val debugLivekitUrl: String = "",
    val connectionReady: Boolean = false,
    val cacheClearMessage: String? = null,
)

/**
 * Customer-only Instant home.
 * Polls POST /api/sessions/customer-enter until expert-web activates a session.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val runtimeConfigRepository: RuntimeConfigRepository,
    private val cacheClearManager: CacheClearManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var customerWatcherJob: Job? = null

    fun bindProfile(profile: Profile) {
        _uiState.update {
            it.copy(
                profile = profile,
                formattedPublicId = PublicIdFormatter.formatDisplay(profile.publicId),
            )
        }
        startCustomerWatcher()
    }

    init {
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

    fun shareId() {
        // Share intent is fired from the UI; nothing to prepare server-side.
    }

    fun clearNavigation() {
        _uiState.update {
            it.copy(
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
        // Prefill with effective URLs so a stale iMac bake-in is visible immediately.
        _uiState.update {
            it.copy(
                showDebugSheet = true,
                debugApiUrl = it.debugApiUrl.ifBlank { runtimeConfigRepository.apiUrlBlocking() },
                debugLivekitUrl = it.debugLivekitUrl.ifBlank { runtimeConfigRepository.livekitUrlBlocking() },
            )
        }
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
            _uiState.update {
                it.copy(
                    // After clearing overrides, show the baked Homelab/Vercel defaults.
                    debugApiUrl = runtimeConfigRepository.apiUrlBlocking(),
                    debugLivekitUrl = runtimeConfigRepository.livekitUrlBlocking(),
                    showDebugSheet = false,
                )
            }
        }
    }

    /** Resume watcher after returning from a call or tutorial. */
    fun resumeCustomerWatcher() {
        if (_uiState.value.navigateToCustomerCall == null) {
            startCustomerWatcher()
        }
    }

    private fun startCustomerWatcher() {
        customerWatcherJob?.cancel()
        customerWatcherJob = viewModelScope.launch {
            while (isActive) {
                // Skip while already navigating into a call.
                if (_uiState.value.navigateToCustomerCall == null && !_uiState.value.incomingSession) {
                    pollCustomerEnter()
                }
                delay(CUSTOMER_ENTER_POLL_MS)
            }
        }
    }

    private suspend fun pollCustomerEnter() {
        sessionRepository.customerEnter()
            .onSuccess { session ->
                android.util.Log.i(TAG, "incoming active session ${session.sessionId}")
                _uiState.update { it.copy(incomingSession = true, errorMessage = null) }
                // Brief "incoming" cue, then enter the call (matches iOS prompt timing).
                delay(1_200)
                _uiState.update { it.copy(navigateToCustomerCall = session) }
                customerWatcherJob?.cancel()
            }
            .onFailure { error ->
                // 404 = no active session yet — expected while waiting for expert-web.
                val code = (error as? SessionApiException)?.statusCode ?: 0
                if (code != 404 && code != 0) {
                    android.util.Log.w(TAG, "customer-enter failed: ${error.message}")
                }
                // Network errors (code 0): surface once, keep polling.
                if (code == 0 && error is SessionApiException) {
                    _uiState.update { it.copy(errorMessage = error.errorMessage) }
                }
            }
    }

    private fun stopCustomerWatcher() {
        customerWatcherJob?.cancel()
        customerWatcherJob = null
    }

    override fun onCleared() {
        stopCustomerWatcher()
        super.onCleared()
    }

    companion object {
        private const val TAG = "HomeViewModel"
        private const val CUSTOMER_ENTER_POLL_MS = 2_500L
    }
}
