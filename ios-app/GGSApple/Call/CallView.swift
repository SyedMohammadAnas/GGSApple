import SwiftUI
import LiveKit
import UIKit

struct CallView: View {
    let credentials: SessionCredentials
    var onEnd: () -> Void

    @StateObject private var liveKit = LiveKitManager()
    @State private var selectedTool: AnnotationTool = .freehand
    @State private var showAssets = false
    @State private var connectError: String?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            videoLayer

            VStack {
                topBar
                Spacer()
                bottomChrome
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)

            HStack {
                Spacer()
                annotationToolbar
                    .padding(.trailing, 8)
            }

            if let connectError {
                Text(connectError)
                    .font(.footnote)
                    .foregroundStyle(.orange)
                    .padding()
                    .assistGlassRounded(12)
            }
        }
        .preferredColorScheme(.dark)
        .task { await connect() }
        .sheet(isPresented: $showAssets) {
            AssetsPlaceholderSheet()
                .presentationDetents([.medium, .large])
        }
    }

    @ViewBuilder
    private var videoLayer: some View {
        if credentials.role == .expert {
            if let track = liveKit.remoteVideoTrack {
                LiveKitVideoView(track: track).ignoresSafeArea()
            } else {
                waitingVideo(status: liveKit.statusText)
            }
        } else if let track = liveKit.localVideoTrack {
            LiveKitVideoView(track: track).ignoresSafeArea()
        } else {
            waitingVideo(status: liveKit.statusText)
        }
    }

    private func waitingVideo(status: String) -> some View {
        Color.black.ignoresSafeArea()
            .overlay {
                VStack(spacing: 8) {
                    ProgressView().tint(.white)
                    Text(status)
                        .foregroundStyle(.white.opacity(0.7))
                        .font(.footnote)
                }
            }
    }

    private var topBar: some View {
        HStack {
            Button { showAssets = true } label: {
                HStack(spacing: 6) {
                    Text("Assist AR Session")
                        .font(.subheadline.weight(.semibold))
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.bold))
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
            }
            .assistGlassCapsule()

            Spacer()

            Button { showAssets = true } label: {
                Image(systemName: "circle.grid.2x2")
                    .font(.body.weight(.semibold))
                    .frame(width: 40, height: 40)
            }
            .assistGlassCircle()
        }
    }

    private var annotationToolbar: some View {
        VStack(spacing: 10) {
            ForEach(AnnotationTool.allCases) { tool in
                Button { selectedTool = tool } label: {
                    Image(systemName: tool.systemImage)
                        .font(.body.weight(.semibold))
                        .frame(width: 40, height: 40)
                        .foregroundStyle(selectedTool == tool ? AppTheme.orange : .primary)
                }
                .assistGlassRounded(12, tint: selectedTool == tool ? AppTheme.orange.opacity(0.35) : nil)
            }
        }
        .padding(8)
        .assistGlassRounded(18)
    }

    private var bottomChrome: some View {
        VStack(spacing: 10) {
            Capsule()
                .fill(Color.primary.opacity(0.25))
                .frame(width: 36, height: 4)

            HStack(spacing: 18) {
                glassCallButton(
                    title: "speaker",
                    system: liveKit.isSpeakerOn ? "speaker.wave.2.fill" : "speaker.slash.fill"
                ) { liveKit.toggleSpeaker() }

                glassCallButton(
                    title: "mute",
                    system: liveKit.isMuted ? "mic.slash.fill" : "mic.fill"
                ) { liveKit.toggleMute() }

                glassCallButton(
                    title: liveKit.isVideoPaused ? "resume" : "pause",
                    system: liveKit.isVideoPaused ? "play.fill" : "pause.fill"
                ) { liveKit.toggleVideoPaused() }

                VStack(spacing: 4) {
                    Button {
                        Task {
                            await liveKit.disconnect()
                            onEnd()
                        }
                    } label: {
                        Image(systemName: "xmark")
                            .font(.title3.weight(.bold))
                            .foregroundStyle(.white)
                            .frame(width: 56, height: 56)
                    }
                    .assistGlassCircle(tint: .red)

                    Text("end")
                        .font(.caption2)
                        .foregroundStyle(.white.opacity(0.75))
                }
            }
        }
        .padding(.top, 12)
        .padding(.bottom, 10)
        .padding(.horizontal, 16)
        .frame(maxWidth: .infinity)
        .assistGlassRounded(22)
    }

    private func glassCallButton(title: String, system: String, action: @escaping () -> Void) -> some View {
        VStack(spacing: 4) {
            Button(action: action) {
                Image(systemName: system)
                    .font(.title3)
                    .frame(width: 56, height: 56)
            }
            .assistGlassCircle()

            Text(title)
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.75))
        }
    }

    private func connect() async {
        do {
            try await liveKit.connect(
                url: RuntimeConfig.liveKitURL,
                token: credentials.token,
                publishCamera: credentials.role == .customer
            )
        } catch {
            connectError = error.localizedDescription
            print("[Call] connect FAILED: \(error.localizedDescription)")
        }
    }
}

struct LiveKitVideoView: UIViewRepresentable {
    let track: VideoTrack

    func makeUIView(context: Context) -> VideoView {
        let view = VideoView()
        view.track = track
        view.layoutMode = .fill
        return view
    }

    func updateUIView(_ uiView: VideoView, context: Context) {
        uiView.track = track
    }
}

/// TeamViewer Assist AR–style tool set (shared offline + live chrome).
enum AnnotationTool: String, CaseIterable, Identifiable {
    case arrow, freehand, pointer, undo, delete

    var id: String { rawValue }

    var systemImage: String {
        switch self {
        case .arrow: return "arrow.down.to.line"
        case .freehand: return "highlighter"
        case .pointer: return "hand.point.up.left.fill"
        case .undo: return "arrow.uturn.backward"
        case .delete: return "trash"
        }
    }
}

struct AssetsPlaceholderSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                TextField("Search through assets", text: .constant(""))
                    .padding(12)
                    .background(Color.white.opacity(0.08))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .disabled(true)

                Text("Asset library coming next")
                    .foregroundStyle(.white.opacity(0.55))

                LazyVGrid(
                    columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())],
                    spacing: 12
                ) {
                    ForEach(0..<6, id: \.self) { _ in
                        RoundedRectangle(cornerRadius: 12)
                            .fill(Color.white.opacity(0.08))
                            .frame(height: 88)
                            .overlay {
                                Image(systemName: "cube")
                                    .foregroundStyle(.white.opacity(0.35))
                            }
                    }
                }
                Spacer()
            }
            .padding()
            .background(Color.black.ignoresSafeArea())
            .navigationTitle("Assets")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
    }
}
