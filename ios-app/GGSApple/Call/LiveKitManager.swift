import Foundation
import LiveKit
import AVFoundation

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

    let room = Room()

    override init() {
        super.init()
        room.add(delegate: self)
    }

    func connect(url: String, token: String, publishCamera: Bool) async throws {
        statusText = "Connecting to LiveKit…"
        print("[LiveKit] connect \(url) publishCamera=\(publishCamera)")

        try await room.connect(url: url, token: token)
        isConnected = true
        statusText = "Connected"

        try await room.localParticipant.setMicrophone(enabled: !isMuted)

        if publishCamera {
            try await room.localParticipant.setCamera(enabled: true)
            refreshLocalVideo()
            print("[LiveKit] camera published")
        }

        applySpeaker()
        print("[LiveKit] connected")
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
        Task {
            do {
                try await room.localParticipant.setCamera(enabled: !isVideoPaused)
                if !isVideoPaused {
                    refreshLocalVideo()
                }
                print("[LiveKit] videoPaused=\(isVideoPaused)")
            } catch {
                print("[LiveKit] video toggle error: \(error.localizedDescription)")
            }
        }
    }

    func disconnect() async {
        await room.disconnect()
        remoteVideoTrack = nil
        localVideoTrack = nil
        isConnected = false
        print("[LiveKit] disconnected")
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
        }
    }
}
