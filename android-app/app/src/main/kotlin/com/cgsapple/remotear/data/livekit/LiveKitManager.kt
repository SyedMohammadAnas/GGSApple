package com.cgsapple.remotear.data.livekit

import android.content.Context
import android.util.Log
import com.cgsapple.remotear.annotation.HOST_VIDEO_HEIGHT
import com.cgsapple.remotear.annotation.HOST_VIDEO_WIDTH
import dagger.hilt.android.qualifiers.ApplicationContext
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import com.cgsapple.remotear.ar.ARCoreFrameCapturer
import io.livekit.android.room.Room
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveKitManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var room: Room? = null
    private var eventsJob: Job? = null
    private var customerVideoTrack: LocalVideoTrack? = null
    private var isCustomerRole = false
    private var customerArCapturer: ARCoreFrameCapturer? = null
    private var customerUsesArCapturer = false
    private var customerCaptureWidth = HOST_VIDEO_WIDTH
    private var customerCaptureHeight = HOST_VIDEO_HEIGHT

    private val _room = MutableStateFlow<Room?>(null)
    val roomState: StateFlow<Room?> = _room.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private val _connectionStatus = MutableStateFlow(CallConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<CallConnectionStatus> = _connectionStatus.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _remoteParticipantUnstable = MutableStateFlow(false)
    val remoteParticipantUnstable: StateFlow<Boolean> = _remoteParticipantUnstable.asStateFlow()

    private val _isVideoPaused = MutableStateFlow(false)
    val isVideoPaused: StateFlow<Boolean> = _isVideoPaused.asStateFlow()

    fun connect(
        url: String,
        token: String,
        isCustomer: Boolean,
        arCapturer: ARCoreFrameCapturer? = null,
        captureWidth: Int = HOST_VIDEO_WIDTH,
        captureHeight: Int = HOST_VIDEO_HEIGHT,
        useCameraFallback: Boolean = false,
    ) {
        if (url.isBlank() || token.isBlank()) {
            _connectionStatus.value = CallConnectionStatus.ERROR
            _connectionError.value = "LiveKit URL or token missing."
            return
        }

        scope.launch {
            try {
                disconnectInternal()

                _connectionStatus.value = CallConnectionStatus.CONNECTING
                _connectionError.value = null
                isCustomerRole = isCustomer

                val liveKitRoom = LiveKit.create(context)
                room = liveKitRoom
                _room.value = liveKitRoom

                eventsJob = launch {
                    liveKitRoom.events.collect { event ->
                        handleEvent(event)
                    }
                }

                withContext(Dispatchers.IO) {
                    liveKitRoom.connect(url, token)
                }

                if (isCustomer) {
                    publishCustomerVideo(
                        liveKitRoom = liveKitRoom,
                        arCapturer = arCapturer,
                        captureWidth = captureWidth,
                        captureHeight = captureHeight,
                        useCameraFallback = useCameraFallback,
                    )
                }

                liveKitRoom.localParticipant.setMicrophoneEnabled(true)
                scanExistingRemoteVideo(liveKitRoom)
                scanRemoteConnectionQuality(liveKitRoom)
                _connectionStatus.value = CallConnectionStatus.CONNECTED
            } catch (error: Exception) {
                Log.e(TAG, "LiveKit connect failed", error)
                _connectionStatus.value = CallConnectionStatus.ERROR
                _connectionError.value = error.message ?: "LiveKit connection failed"
            }
        }
    }

    fun toggleMute() {
        val liveKitRoom = room
        if (liveKitRoom == null) {
            Log.w(TAG, "toggleMute() skipped — room is null")
            return
        }
        scope.launch {
            val muted = !_isMuted.value
            Log.i(TAG, "toggleMute() applying muted=$muted (micEnabled=${!muted})")
            liveKitRoom.localParticipant.setMicrophoneEnabled(!muted)
            _isMuted.value = muted
            Log.i(TAG, "toggleMute() done isMuted=${_isMuted.value}")
        }
    }

    fun toggleVideoPaused() {
        _isVideoPaused.value = !_isVideoPaused.value
    }

    fun setVideoPaused(paused: Boolean) {
        _isVideoPaused.value = paused
    }

    fun disconnect() {
        scope.launch {
            disconnectInternal()
        }
    }

    private suspend fun disconnectInternal() {
        eventsJob?.cancel()
        eventsJob = null

        try {
            customerVideoTrack?.stopCapture()
        } catch (_: Exception) {
            // Ignore stale capture stop.
        }
        customerVideoTrack = null
        customerArCapturer = null
        customerUsesArCapturer = false

        withContext(Dispatchers.IO) {
            try {
                room?.disconnect()
            } catch (_: Exception) {
                // Ignore stale disconnect.
            }
            try {
                room?.release()
            } catch (_: Exception) {
                // Ignore stale release.
            }
        }

        room = null
        _room.value = null
        _localVideoTrack.value = null
        _remoteVideoTrack.value = null
        _connectionStatus.value = CallConnectionStatus.DISCONNECTED
        _connectionError.value = null
        _isMuted.value = false
        _remoteParticipantUnstable.value = false
        _isVideoPaused.value = false
        isCustomerRole = false
    }

    private suspend fun publishCustomerVideo(
        liveKitRoom: Room,
        arCapturer: ARCoreFrameCapturer?,
        captureWidth: Int,
        captureHeight: Int,
        useCameraFallback: Boolean,
    ) {
        try {
            customerVideoTrack?.stopCapture()
        } catch (_: Exception) {
            // Ignore stale capture stop.
        }

        customerCaptureWidth = captureWidth
        customerCaptureHeight = captureHeight

        val track =
            if (!useCameraFallback && arCapturer != null) {
                customerUsesArCapturer = true
                customerArCapturer = arCapturer
                liveKitRoom.localParticipant.createVideoTrack(
                    name = CUSTOMER_VIDEO_TRACK,
                    capturer = arCapturer,
                )
            } else {
                customerUsesArCapturer = false
                customerArCapturer = null
                liveKitRoom.localParticipant.createVideoTrack(
                    options = LocalVideoTrackOptions(position = CameraPosition.BACK),
                )
            }

        withContext(Dispatchers.IO) {
            track.startCapture()
            if (customerUsesArCapturer) {
                arCapturer?.startCapture(captureWidth, captureHeight, CAPTURE_FPS)
            }
        }
        liveKitRoom.localParticipant.publishVideoTrack(track)
        customerVideoTrack = track
    }

    private suspend fun republishCustomerVideo(liveKitRoom: Room) {
        publishCustomerVideo(
            liveKitRoom = liveKitRoom,
            arCapturer = customerArCapturer,
            captureWidth = customerCaptureWidth,
            captureHeight = customerCaptureHeight,
            useCameraFallback = !customerUsesArCapturer,
        )
    }

    private fun scanExistingRemoteVideo(liveKitRoom: Room) {
        liveKitRoom.remoteParticipants.values.forEach { participant ->
            participant.trackPublications.values.forEach { publication ->
                val track = publication.track
                if (track is VideoTrack) {
                    _remoteVideoTrack.value = track
                }
            }
        }
    }

    private fun scanRemoteConnectionQuality(liveKitRoom: Room) {
        _remoteParticipantUnstable.value = liveKitRoom.remoteParticipants.values.any { participant ->
            isUnstableQuality(participant.connectionQuality)
        }
    }

    private fun isUnstableQuality(quality: ConnectionQuality): Boolean =
        quality == ConnectionQuality.POOR || quality == ConnectionQuality.LOST

    private fun handleEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.ConnectionQualityChanged -> {
                if (event.participant is RemoteParticipant) {
                    _remoteParticipantUnstable.value = isUnstableQuality(event.quality)
                }
            }

            is RoomEvent.ParticipantDisconnected -> {
                _remoteParticipantUnstable.value = true
            }

            is RoomEvent.ParticipantConnected -> {
                _remoteParticipantUnstable.value =
                    isUnstableQuality(event.participant.connectionQuality)
            }

            is RoomEvent.TrackSubscribed -> {
                val track = event.track
                if (track is VideoTrack) {
                    _remoteVideoTrack.value = track
                }
            }

            is RoomEvent.TrackUnsubscribed -> {
                if (event.track == _remoteVideoTrack.value) {
                    _remoteVideoTrack.value = null
                }
            }

            is RoomEvent.Reconnecting -> {
                _connectionStatus.value = CallConnectionStatus.RECONNECTING
            }

            is RoomEvent.Reconnected -> {
                _connectionStatus.value = CallConnectionStatus.CONNECTED
                room?.let { liveKitRoom ->
                    if (isCustomerRole) {
                        scope.launch {
                            republishCustomerVideo(liveKitRoom)
                        }
                    }
                }
            }

            is RoomEvent.Disconnected -> {
                _connectionStatus.value = CallConnectionStatus.DISCONNECTED
            }

            else -> Unit
        }
    }

    companion object {
        private const val TAG = "LiveKitManager"
        private const val CUSTOMER_VIDEO_TRACK = "customer-camera"
        private const val CAPTURE_FPS = 15
    }
}
