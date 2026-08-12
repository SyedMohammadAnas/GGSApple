import SwiftUI
import LiveKit
import UIKit

/// Native Expert call — views customer POV video, annotates + places models (no AR / Simulator-safe).
struct ExpertCallView: View {
    let credentials: SessionCredentials
    var onEnd: () -> Void

    @StateObject private var liveKit = LiveKitManager()
    @State private var drawerSnap: CallDrawerSnap = .collapsed
    @State private var connectError: String?
    @State private var sessionStartedAt = Date()
    @State private var annotationCollapsed = false
    @State private var selectedTool: AnnotationTool = .freehand
    @State private var didStartConnect = false
    @State private var didLeaveCall = false
    @State private var statusPollTask: Task<Void, Never>?
    @State private var stageSize: CGSize = .zero

    // Freehand stroke streaming
    @State private var strokeId: String?
    @State private var isDrawing = false

    // Models
    @State private var catalogItems: [AssetPlaceholderItem] = []
    @State private var recentItems: [AssetPlaceholderItem] = []
    @State private var selectedModelId: String?
    @State private var selectedModelURL: String?
    @State private var modelLoaded = false
    @State private var modelScale: Double = 1.0
    @State private var modelRotationY: Double = 0
    
    // Screenshot capture
    @State private var screenshotImage: UIImage?
    
    // Model preview
    @State private var showModelPreview = false
    @State private var previewModel: AssetPlaceholderItem?

    private let expertRole = "expert"

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            // Customer POV video - full screen coverage
            Group {
                if let track = liveKit.remoteVideoTrack {
                    SwiftUIVideoView(track, layoutMode: .fill)
                        .clipped()
                        .background(
                            GeometryReader { geo in
                                Color.clear.preference(key: StageSizeKey.self, value: geo.size)
                            }
                        )
                } else {
                    VStack(spacing: 12) {
                        ProgressView().tint(AppTheme.orange)
                        Text(liveKit.isConnected ? "Waiting for customer POV…" : liveKit.statusText)
                            .font(.footnote)
                            .foregroundStyle(.white.opacity(0.7))
                    }
                }
            }
            .ignoresSafeArea(.all)
            .onPreferenceChange(StageSizeKey.self) { stageSize = $0 }

            // Hit layer for annotate / place / transform
            Color.clear
                .contentShape(Rectangle())
                .ignoresSafeArea()
                .gesture(expertGesture)
                .zIndex(1)

            VStack {
                topBar
                Spacer()
            }
            .padding(.horizontal, 12)
            .padding(.top, 8)
            .zIndex(2)

            HStack {
                Spacer()
                AnnotationToolRail(
                    selectedTool: $selectedTool,
                    isCollapsed: $annotationCollapsed,
                    dimmed: drawerSnap == .expanded
                )
                .padding(.trailing, annotationCollapsed ? 0 : 8)
            }
            .zIndex(3)

            CallBottomDrawer(
                snap: $drawerSnap,
                controls: liveCallControls,
                recentAssetItems: recentItems,
                catalogAssetItems: catalogItems,
                selectedModelId: selectedModelId,
                onSelectAsset: { item in
                    Task { await selectModel(item) }
                },
                onPreviewAsset: { item in
                    previewModel = item
                    showModelPreview = true
                }
            )
            .zIndex(4)

            if let connectError {
                Text(connectError)
                    .font(.footnote)
                    .foregroundStyle(.orange)
                    .padding()
                    .assistGlassRounded(12)
                    .zIndex(10)
            }
        }
        .sheet(isPresented: $showModelPreview) {
            if let model = previewModel {
                ModelPreviewSheet(model: model) {
                    Task { await selectModel(model) }
                    showModelPreview = false
                }
            }
        }
        .preferredColorScheme(.dark)
        .onAppear {
            sessionStartedAt = Date()
            liveKit.onRemoteSessionEnded = {
                Task { @MainActor in
                    await leaveCall(reason: "remote_livekit")
                }
            }
            // Ignore customer→expert annotation echoes for now (POV already shows marks).
            liveKit.onAnnotationReceived = { msg in
                let type = msg["type"] as? String ?? ""
                if type == "model_load_status" {
                    let ok = (msg["ok"] as? Bool) ?? false
                    modelLoaded = ok
                    print("[ExpertCall] model_load_status ok=\(ok)")
                }
            }
            startSessionStatusPoll()
            Task {
                await connectOnce()
                await fetchCatalog()
            }
            print("[ExpertCall] expert mode — POV viewer + annotations")
        }
        .onChange(of: selectedTool) { _, tool in
            if tool == .undo {
                publish(["type": "undo"], reliable: true)
                selectedTool = .freehand
            } else if tool == .delete {
                publish(["type": "clear"], reliable: true)
                selectedTool = .freehand
            }
        }
        .onDisappear {
            statusPollTask?.cancel()
            statusPollTask = nil
            liveKit.onAnnotationReceived = nil
            liveKit.onRemoteSessionEnded = nil
        }
    }

    private var topBar: some View {
        HStack {
            ARSessionTimerCapsule(startedAt: sessionStartedAt)
            Spacer()
            CallScreenshotButton {
                captureExpertScreenshot()
            }
        }
    }

    private var liveCallControls: [CallControlAction] {
        [
            CallControlAction(
                id: "speaker",
                title: "speaker",
                systemImage: liveKit.isSpeakerOn ? "speaker.wave.2.fill" : "speaker.slash.fill"
            ) { liveKit.toggleSpeaker() },
            CallControlAction(
                id: "mute",
                title: "mute",
                systemImage: liveKit.isMuted ? "mic.slash.fill" : "mic.fill"
            ) { liveKit.toggleMute() },
            CallControlAction(
                id: "end",
                title: "end",
                systemImage: "xmark",
                isDestructive: true
            ) {
                Task {
                    await liveKit.publishSessionEnd(reason: "expert_ended", role: expertRole)
                    await leaveCall(reason: "expert_end_button")
                }
            }
        ]
    }

    // MARK: - Gestures

    private var expertGesture: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                handleDragChanged(value)
            }
            .onEnded { value in
                handleDragEnded(value)
            }
            .simultaneously(with:
                MagnificationGesture()
                    .onChanged { mag in
                        guard selectedTool == .model, modelLoaded else { return }
                        let next = max(0.2, min(5.0, modelScale * Double(mag)))
                        modelScale = next
                        publishTransform()
                    }
            )
    }

    private func handleDragChanged(_ value: DragGesture.Value) {
        let norm = normalize(value.location)
        switch selectedTool {
        case .freehand:
            if strokeId == nil {
                let id = UUID().uuidString
                strokeId = id
                isDrawing = true
                publish(
                    [
                        "type": "stroke_begin",
                        "id": id,
                        "x": norm.x,
                        "y": norm.y,
                    ],
                    reliable: true
                )
            } else if let id = strokeId {
                publish(
                    [
                        "type": "stroke_point",
                        "id": id,
                        "x": norm.x,
                        "y": norm.y,
                    ],
                    reliable: false
                )
            }
        case .pointer:
            publish(["type": "pointer", "x": norm.x, "y": norm.y], reliable: false)
        case .model:
            // Drag rotates model around Y while loaded.
            if modelLoaded {
                let delta = Double(value.translation.width) * 0.4
                modelRotationY = delta
                publishTransform()
            }
        default:
            break
        }
    }

    private func handleDragEnded(_ value: DragGesture.Value) {
        let norm = normalize(value.location)
        switch selectedTool {
        case .arrow:
            publish(
                [
                    "type": "arrow",
                    "x": norm.x,
                    "y": norm.y,
                ],
                reliable: true
            )
        case .freehand:
            if let id = strokeId {
                publish(["type": "stroke_end", "id": id], reliable: true)
            }
            strokeId = nil
            isDrawing = false
        case .model:
            if modelLoaded {
                publish(
                    [
                        "type": "place_model",
                        "x": norm.x,
                        "y": norm.y,
                    ],
                    reliable: true
                )
            } else if selectedModelURL != nil {
                // Tap to place after load — still send place; customer may load async.
                publish(
                    [
                        "type": "place_model",
                        "x": norm.x,
                        "y": norm.y,
                    ],
                    reliable: true
                )
            }
        default:
            break
        }
    }

    private func normalize(_ point: CGPoint) -> (x: Double, y: Double) {
        let w = max(stageSize.width, 1)
        let h = max(stageSize.height, 1)
        // Prefer full screen if geometry not ready yet.
        let bounds = UIScreen.main.bounds
        let width = stageSize == .zero ? bounds.width : w
        let height = stageSize == .zero ? bounds.height : h
        return (
            x: min(1, max(0, Double(point.x / width))),
            y: min(1, max(0, Double(point.y / height)))
        )
    }

    // MARK: - Models

    private func fetchCatalog() async {
        do {
            let models = try await ModelCatalogService.fetchCatalog()
            catalogItems = models.compactMap { model in
                guard let url = model.assetURL else { return nil }
                return AssetPlaceholderItem(
                    id: model.id,
                    title: model.name,
                    systemImage: "cube.fill", // Fallback for items without thumbnails
                    modelURL: url,
                    thumbnailURL: model.thumbnailUrl
                )
            }
            print("[ExpertCall] catalog count=\(catalogItems.count)")
        } catch {
            print("[ExpertCall] catalog FAILED: \(error.localizedDescription)")
        }
    }

    private func selectModel(_ item: AssetPlaceholderItem) async {
        selectedModelId = item.id
        selectedModelURL = item.modelURL?.absoluteString
        modelLoaded = false
        modelScale = 1.0
        modelRotationY = 0
        recentItems.removeAll { $0.id == item.id }
        recentItems.insert(item, at: 0)
        if recentItems.count > 6 {
            recentItems = Array(recentItems.prefix(6))
        }
        guard let url = item.modelURL?.absoluteString else { return }
        publish(
            [
                "type": "load_model",
                "url": url,
                "modelId": item.id,
            ],
            reliable: true
        )
        selectedTool = .model
        print("[ExpertCall] load_model id=\(item.id)")
    }

    private func publishTransform() {
        publish(
            [
                "type": "transform",
                "rotationX": 0,
                "rotationY": modelRotationY,
                "scale": modelScale,
            ],
            reliable: false
        )
    }

    // MARK: - Screenshot
    
    /// Expert view screenshot — captures current POV video with annotations
    private func captureExpertScreenshot() {
        print("[ExpertCall] screenshot requested")
        
        // Get the main window's root view controller to capture the screen
        guard let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let window = windowScene.windows.first else {
            print("[ExpertCall] screenshot failed - no window")
            return
        }
        
        // Capture the current screen
        let renderer = UIGraphicsImageRenderer(bounds: window.bounds)
        let image = renderer.image { context in
            window.layer.render(in: context.cgContext)
        }
        
        // Save to photo library
        CallScreenshotCapture.saveToPhotos(image)
        screenshotImage = image
    }

    // MARK: - LiveKit

    private func publish(_ payload: [String: Any], reliable: Bool) {
        var msg = payload
        msg["role"] = expertRole
        let type = msg["type"] as? String ?? ""
        let useReliable = reliable && type != "stroke_point" && type != "pointer" && type != "transform"
        liveKit.publishAnnotation(msg, reliable: useReliable)
    }

    private func connectOnce() async {
        guard !didStartConnect else { return }
        didStartConnect = true
        do {
            try await liveKit.connect(
                url: credentials.livekitUrl,
                token: credentials.token,
                publishMode: .none
            )
            print("[ExpertCall] connected livekit=\(credentials.livekitUrl)")
        } catch {
            let detail = error.localizedDescription
            connectError = "LiveKit failed (\(credentials.livekitUrl)): \(detail)"
            print("[ExpertCall] connect FAILED url=\(credentials.livekitUrl) err=\(detail)")
        }
    }

    @MainActor
    private func leaveCall(reason: String) async {
        guard !didLeaveCall else { return }
        didLeaveCall = true
        statusPollTask?.cancel()
        statusPollTask = nil
        print("[ExpertCall] leaving reason=\(reason)")
        await liveKit.disconnect()
        onEnd()
    }

    private func startSessionStatusPoll() {
        statusPollTask?.cancel()
        let sessionId = credentials.sessionId
        statusPollTask = Task { @MainActor in
            while !Task.isCancelled, !didLeaveCall {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                if Task.isCancelled || didLeaveCall { break }
                do {
                    let session = try await AuthService.shared.client.auth.session
                    let status = try await SessionService.shared.fetchSessionStatus(
                        sessionId: sessionId,
                        accessToken: session.accessToken
                    )
                    if status != "active" {
                        print("[ExpertCall] session status=\(status) — ending locally")
                        await leaveCall(reason: "session_status_\(status)")
                        break
                    }
                } catch {
                    print("[ExpertCall] status poll skipped: \(error.localizedDescription)")
                }
            }
        }
    }
}

private struct StageSizeKey: PreferenceKey {
    static var defaultValue: CGSize = .zero
    static func reduce(value: inout CGSize, nextValue: () -> CGSize) {
        value = nextValue()
    }
}

/// Modal sheet for previewing and selecting 3D models
struct ModelPreviewSheet: View {
    let model: AssetPlaceholderItem
    let onSelect: () -> Void
    @Environment(\.dismiss) private var dismiss
    
    var body: some View {
        NavigationView {
            VStack(spacing: 20) {
                Spacer()
                
                // Large model preview
                Group {
                    if let thumbnailURL = model.thumbnailURL,
                       let url = URL(string: thumbnailURL) {
                        AsyncImage(url: url) { image in
                            image
                                .resizable()
                                .aspectRatio(contentMode: .fit)
                                .frame(maxWidth: 300, maxHeight: 300)
                                .clipShape(RoundedRectangle(cornerRadius: 16))
                                .shadow(radius: 8)
                        } placeholder: {
                            RoundedRectangle(cornerRadius: 16)
                                .fill(Color.gray.opacity(0.3))
                                .frame(width: 300, height: 300)
                                .overlay {
                                    ProgressView()
                                        .tint(.white)
                                }
                        }
                    } else {
                        RoundedRectangle(cornerRadius: 16)
                            .fill(Color.gray.opacity(0.3))
                            .frame(width: 300, height: 300)
                            .overlay {
                                Image(systemName: model.systemImage)
                                    .font(.system(size: 60))
                                    .foregroundStyle(.white.opacity(0.7))
                            }
                    }
                }
                
                VStack(spacing: 8) {
                    Text(model.title)
                        .font(.title2.weight(.semibold))
                        .foregroundStyle(.white)
                    
                    Text("3D Model")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.7))
                }
                
                Spacer()
                
                // Action buttons
                VStack(spacing: 12) {
                    Button("Place This Model") {
                        onSelect()
                    }
                    .font(.headline.weight(.semibold))
                    .foregroundStyle(.black)
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(AppTheme.orange)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    
                    Button("Cancel") {
                        dismiss()
                    }
                    .font(.headline)
                    .foregroundStyle(.white.opacity(0.7))
                }
                .padding(.horizontal)
            }
            .padding()
            .background(Color.black)
            .navigationTitle("Model Preview")
            .navigationBarTitleDisplayMode(.inline)
            .navigationBarHidden(true)
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }
}
