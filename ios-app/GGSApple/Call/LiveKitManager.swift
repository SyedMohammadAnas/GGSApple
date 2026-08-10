import Foundation
import LiveKit
import AVFoundation
import CoreVideo

/// How the local participant publishes video (if at all).
enum LiveKitPublishMode: Equatable {
    case none
    /// AVCapture rear camera (expert never uses this today).
    case rearCamera
    /// Push ARKit `capturedImage` buffers — ARView keeps the camera; annotations sync separately.
    case arBuffers
}

/// LiveKit room + media controls for Assist call.
@MainActor
final class LiveKitManager: NSObject, ObservableObject {
    @Published private(set) var isConnected = false
    @Published private(set) var isMuted = false
    @Published private(set) var isSpeakerOn = true
    @Published private(set) var isVideoPaused = false
    @Published private(set) var remoteVideoTrack: VideoTrack?
    @Published private(set) var localVideoTrack: VideoTrack?
    @Published var statusText = "Connecting…"

    /// Incoming annotation wire (topic `annotations`) — customer applies into AR.
    var onAnnotationReceived: (([String: Any]) -> Void)?

    /// Peer ended the call (session_end packet, remote left, or unexpected room drop).
    var onRemoteSessionEnded: (() -> Void)?

    /// True while we intentionally tear down (avoids treating our own disconnect as remote end).
    private var isEndingLocally = false
    /// Dedupes multiple remote-end signals into a single UI leave.
    private var didNotifyRemoteEnd = false

    /// Assist customers always stream the rear camera (AR / field view), never selfie.
    private static let rearCameraOptions = CameraCaptureOptions(position: .back)

    /// LiveKit data topic shared with the web expert page.
    static let annotationTopic = "annotations"

    /// Customer POV composites (ARView snapshot: camera + RealityKit marks), portrait 540x960.
    private static let arBufferOptions = BufferCaptureOptions(
        dimensions: Dimensions(width: 540, height: 960),
        fps: 15
    )

    let room = Room(
        delegate: nil,
        roomOptions: RoomOptions(defaultCameraCaptureOptions: rearCameraOptions)
    )

    private var publishMode: LiveKitPublishMode = .none
    private var arBufferTrack: LocalVideoTrack?
    private var arBufferCapturer: BufferCapturer?
    /// Nonisolated mirror so ARSession can push buffers without hopping (frame lifetime).
    nonisolated(unsafe) private var arBufferCapturerUnsafe: BufferCapturer?
    private var arTrackPublished = false
    private var arPublishTask: Task<Void, Never>?
    nonisolated(unsafe) private var isVideoPausedUnsafe = false
    /// Avoid scheduling a MainActor publish Task on every POV frame before the track is up.
    nonisolated(unsafe) private var arTrackPublishedUnsafe = false
    nonisolated(unsafe) private var arPublishScheduledUnsafe = false

    override init() {
        super.init()
        room.add(delegate: self)
    }

    func connect(url: String, token: String, publishMode: LiveKitPublishMode) async throws {
        self.publishMode = publishMode
        self.isEndingLocally = false
        self.didNotifyRemoteEnd = false
        self.arTrackPublished = false
        self.arTrackPublishedUnsafe = false
        self.arPublishScheduledUnsafe = false
        self.isVideoPaused = false
        self.isVideoPausedUnsafe = false
        statusText = "Connecting to LiveKit…"
        print("[LiveKit] connect \(url) publishMode=\(publishMode)")

        try await room.connect(url: url, token: token)
        isConnected = true
        statusText = "Connected"

        try await room.localParticipant.setMicrophone(enabled: !isMuted)

        switch publishMode {
        case .none:
            break
        case .rearCamera:
            try await room.localParticipant.setCamera(
                enabled: true,
                captureOptions: Self.rearCameraOptions
            )
            refreshLocalVideo()
            print("[LiveKit] rear camera published")
        case .arBuffers:
            // Track is created now; publish happens after the first AR frame (SDK requirement).
            let track = LocalVideoTrack.createBufferTrack(
                name: Track.cameraName,
                source: .camera,
                options: Self.arBufferOptions
            )
            arBufferTrack = track
            let capturer = track.capturer as? BufferCapturer
            arBufferCapturer = capturer
            arBufferCapturerUnsafe = capturer
            print("[LiveKit] AR buffer track ready — waiting for first frame before publish")
        }

        applySpeaker()
        print("[LiveKit] connected")
    }

    /// Called from AR POV encoder — capture synchronously; do not retain an ARFrame.
    /// `rotationDegrees` is the CW rotation WebRTC should apply for upright display (0/90/180/270).
    nonisolated func captureARFrame(_ pixelBuffer: CVPixelBuffer, rotationDegrees: Int) {
        guard !isVideoPausedUnsafe else { return }
        guard let capturer = arBufferCapturerUnsafe else { return }
        let rotation = VideoRotation(rawValue: rotationDegrees) ?? ._90
        capturer.capture(pixelBuffer, rotation: rotation)
        // Schedule publish at most once — do not spawn a Task per POV frame.
        guard !arTrackPublishedUnsafe, !arPublishScheduledUnsafe else { return }
        arPublishScheduledUnsafe = true
        Task { @MainActor in
            self.publishARBufferTrackIfNeeded()
        }
    }

    /// Send annotation events to the Assist AR web expert on topic `annotations`.
    func publishAnnotation(_ payload: [String: Any], reliable: Bool = true) {
        Task {
            await publishAnnotationAwaiting(payload, reliable: reliable)
        }
    }

    /// Awaitable publish — used for `session_end` so the peer receives it before we disconnect.
    @discardableResult
    func publishAnnotationAwaiting(_ payload: [String: Any], reliable: Bool = true) async -> Bool {
        guard isConnected else { return false }
        guard JSONSerialization.isValidJSONObject(payload),
              let data = try? JSONSerialization.data(withJSONObject: payload, options: [])
        else {
            print("[LiveKit] annotation payload invalid")
            return false
        }
        let type = payload["type"] as? String ?? "?"
        do {
            try await room.localParticipant.publish(
                data: data,
                options: DataPublishOptions(topic: Self.annotationTopic, reliable: reliable)
            )
            print("[LiveKit] annotation TX type=\(type) reliable=\(reliable)")
            return true
        } catch {
            print("[LiveKit] annotation publish FAILED: \(error.localizedDescription)")
            return false
        }
    }

    /// Tell the expert we are ending, then allow disconnect.
    func publishSessionEnd(reason: String = "customer_ended") async {
        _ = await publishAnnotationAwaiting(
            [
                "type": "session_end",
                "reason": reason,
                "role": "customer",
            ],
            reliable: true
        )
    }

    private func publishARBufferTrackIfNeeded() {
        guard !arTrackPublished, let track = arBufferTrack else { return }
        guard arPublishTask == nil else { return }
        arPublishTask = Task { @MainActor in
            defer { arPublishTask = nil }
            do {
                _ = try await room.localParticipant.publish(videoTrack: track)
                arTrackPublished = true
                arTrackPublishedUnsafe = true
                localVideoTrack = track
                print("[LiveKit] AR buffer track published (rotated camera + annotation data channel)")
            } catch {
                // Allow a later frame to retry scheduling publish.
                arPublishScheduledUnsafe = false
                print("[LiveKit] AR buffer publish FAILED: \(error.localizedDescription)")
            }
        }
    }

    private func refreshLocalVideo() {
        for publication in room.localParticipant.localVideoTracks {
            if let track = publication.track as? VideoTrack {
                localVideoTrack = track
                return
            }
        }
    }

    func toggleMute() {
        isMuted.toggle()
        Task {
            do {
                try await room.localParticipant.setMicrophone(enabled: !isMuted)
                print("[LiveKit] mute=\(isMuted)")
            } catch {
                print("[LiveKit] mute error: \(error.localizedDescription)")
            }
        }
    }

    func toggleSpeaker() {
        isSpeakerOn.toggle()
        applySpeaker()
        print("[LiveKit] speaker=\(isSpeakerOn)")
    }

    func toggleVideoPaused() {
        isVideoPaused.toggle()
        isVideoPausedUnsafe = isVideoPaused
        Task {
            do {
                switch publishMode {
                case .arBuffers:
                    // Current: mute → expert goes black. Freeze-last-frame attempt deferred —
                    // see vault Progress/GGSApple-iOS-Pause-Freeze-Deferred-2026-08-05.
                    if let track = arBufferTrack {
                        if isVideoPaused {
                            try await track.mute()
                        } else {
                            try await track.unmute()
                        }
                    }
                case .rearCamera:
                    try await room.localParticipant.setCamera(
                        enabled: !isVideoPaused,
                        captureOptions: Self.rearCameraOptions
                    )
                    if !isVideoPaused {
                        refreshLocalVideo()
                    }
                case .none:
                    break
                }
                print("[LiveKit] videoPaused=\(isVideoPaused)")
            } catch {
                print("[LiveKit] video toggle error: \(error.localizedDescription)")
            }
        }
    }

    func disconnect() async {
        // Mark local end first so RoomDelegate disconnect does not bounce as remote end.
        isEndingLocally = true
        arPublishTask?.cancel()
        arPublishTask = nil
        arBufferTrack = nil
        arBufferCapturer = nil
        arBufferCapturerUnsafe = nil
        arTrackPublished = false
        arTrackPublishedUnsafe = false
        arPublishScheduledUnsafe = false
        publishMode = .none
        isVideoPaused = false
        isVideoPausedUnsafe = false
        onAnnotationReceived = nil
        onRemoteSessionEnded = nil

        await room.disconnect()
        remoteVideoTrack = nil
        localVideoTrack = nil
        isConnected = false
        print("[LiveKit] disconnected")
    }

    /// Single-fire remote end for CallView to leave + reset home.
    private func notifyRemoteSessionEnded(reason: String) {
        guard !isEndingLocally, !didNotifyRemoteEnd else { return }
        didNotifyRemoteEnd = true
        print("[LiveKit] remote session ended reason=\(reason)")
        onRemoteSessionEnded?()
    }

    private func applySpeaker() {
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        do {
            // allowBluetooth is deprecated — use HFP (calls) + A2DP (media) explicitly.
            try session.setCategory(
                .playAndRecord,
                mode: .videoChat,
                options: [.defaultToSpeaker, .allowBluetoothHFP, .allowBluetoothA2DP]
            )
            try session.overrideOutputAudioPort(isSpeakerOn ? .speaker : .none)
            try session.setActive(true)
        } catch {
            print("[LiveKit] audio session error: \(error.localizedDescription)")
        }
        #endif
    }
}

extension LiveKitManager: RoomDelegate {
    nonisolated func room(
        _ room: Room,
        participant: RemoteParticipant,
        didSubscribeTrack publication: RemoteTrackPublication
    ) {
        Task { @MainActor in
            if let track = publication.track as? VideoTrack {
                self.remoteVideoTrack = track
                print("[LiveKit] remote video subscribed")
            }
        }
    }

    nonisolated func room(
        _ room: Room,
        participant: RemoteParticipant,
        didUnsubscribeTrack publication: RemoteTrackPublication
    ) {
        Task { @MainActor in
            if publication.track is VideoTrack {
                self.remoteVideoTrack = nil
                print("[LiveKit] remote video unsubscribed")
            }
        }
    }

    nonisolated func room(
        _ room: Room,
        participant: LocalParticipant,
        didPublishTrack publication: LocalTrackPublication
    ) {
        Task { @MainActor in
            if let track = publication.track as? VideoTrack {
                self.localVideoTrack = track
                print("[LiveKit] local track published")
            }
        }
    }

    nonisolated func room(_ room: Room, didDisconnectWithError error: LiveKitError?) {
        Task { @MainActor in
            self.isConnected = false
            self.statusText = "Disconnected"
            print("[LiveKit] disconnected event err=\(String(describing: error))")
            // Unexpected drop (expert killed room / network) — leave call UI.
            if !self.isEndingLocally {
                self.notifyRemoteSessionEnded(reason: "room_disconnected")
            }
        }
    }

    nonisolated func room(_ room: Room, participantDidDisconnect participant: RemoteParticipant) {
        Task { @MainActor in
            let remaining = room.remoteParticipants.count
            print(
                "[LiveKit] remote left identity=\(participant.identity?.stringValue ?? "?") remaining=\(remaining)"
            )
            // Assist is 1:1 — when the expert leaves, end the customer call.
            if remaining == 0 {
                self.notifyRemoteSessionEnded(reason: "expert_left_room")
            }
        }
    }

    /// Web expert → customer: place arrows / freehand into AR via normalized screen coords.
    nonisolated func room(
        _ room: Room,
        participant: RemoteParticipant?,
        didReceiveData data: Data,
        forTopic topic: String,
        encryptionType: EncryptionType
    ) {
        guard topic == LiveKitManager.annotationTopic else { return }
        guard
            let obj = try? JSONSerialization.jsonObject(with: data),
            let payload = obj as? [String: Any]
        else {
            print("[LiveKit] annotation RX invalid JSON bytes=\(data.count)")
            return
        }
        let type = payload["type"] as? String ?? "?"
        Task { @MainActor in
            print("[LiveKit] annotation RX type=\(type) from=\(participant?.identity?.stringValue ?? "?")")
            // Expert End Call → leave Instant without waiting for room teardown.
            if type == "session_end" {
                self.notifyRemoteSessionEnded(reason: "session_end_packet")
                return
            }
            self.onAnnotationReceived?(payload)
        }
    }
}
