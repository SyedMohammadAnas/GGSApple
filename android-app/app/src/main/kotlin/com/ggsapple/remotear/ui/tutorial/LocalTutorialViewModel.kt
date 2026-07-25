package com.ggsapple.remotear.ui.tutorial

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ggsapple.remotear.ar.ARCoreManager
import com.ggsapple.remotear.data.recording.TutorialVideoRecorder
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

data class LocalTutorialUiState(
    val permissionsGranted: Boolean = false,
    val isRecording: Boolean = false,
    val recordingSeconds: Int = 0,
    val savedPath: String? = null,
    val toastMessage: String? = null,
)

@HiltViewModel
class LocalTutorialViewModel @Inject constructor(
    val arCoreManager: ARCoreManager,
    private val tutorialVideoRecorder: TutorialVideoRecorder,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocalTutorialUiState())
    val uiState: StateFlow<LocalTutorialUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null

    init {
        viewModelScope.launch {
            tutorialVideoRecorder.isRecording.collect { recording ->
                _uiState.update { it.copy(isRecording = recording) }
            }
        }
        viewModelScope.launch {
            tutorialVideoRecorder.recordingSeconds.collect { seconds ->
                _uiState.update { it.copy(recordingSeconds = seconds) }
            }
        }
    }

    fun onPermissionsResult(granted: Boolean) {
        _uiState.update { it.copy(permissionsGranted = granted) }
        if (granted) {
            arCoreManager.onCameraPermissionGranted()
        }
    }

    fun requestStartRecording() {
        tutorialVideoRecorder.start()
            .onSuccess {
                arCoreManager.setTutorialFrameSink { image, rotation ->
                    tutorialVideoRecorder.offerFrame(image, rotation)
                }
                startTimer()
                showToast("Recording AR camera feed")
            }
            .onFailure { showToast(it.message ?: "Could not start recording") }
    }

    fun endTutorial() {
        arCoreManager.setTutorialFrameSink(null)
        timerJob?.cancel()
        tutorialVideoRecorder.stop()
            .onSuccess { path ->
                _uiState.update { it.copy(savedPath = path) }
                showToast("Tutorial saved: $path")
            }
            .onFailure { showToast(it.message ?: "Could not save recording") }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
            }
        }
    }

    fun showToast(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    override fun onCleared() {
        arCoreManager.setTutorialFrameSink(null)
        arCoreManager.onDestroy()
        super.onCleared()
    }
}
