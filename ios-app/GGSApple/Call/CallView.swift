import SwiftUI
import LiveKit
import UIKit
import ARKit
import RealityKit

/// Live call — Instant is **customer-only**; Assist AR web is the expert.
struct CallView: View {
    let credentials: SessionCredentials
    var onEnd: () -> Void

    @StateObject private var liveKit = LiveKitManager()
    /// Customer keeps Offline-style AR for local annotations while streaming frames to LiveKit.
    @State private var arViewModel = OfflineAssistViewModel()
    @State private var drawerSnap: CallDrawerSnap = .collapsed
    @State private var connectError: String?
    @State private var flashScreenshot = false
    @State private var sessionStartedAt = Date()
    @State private var annotationCollapsed = false
    @State private var showSurfaceCoach = false
    @State private var didStartConnect = false
    /// Avoid double leave when LiveKit signal + status poll both fire.
    @State private var didLeaveCall = false
    @State private var statusPollTask: Task<Void, Never>?
    
    // Model loading feedback for expert actions
    @State private var expertLoadingModel: String?

    private var chromeVisible: Bool { !showSurfaceCoach }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            // True customer POV for the expert: ARView composites (camera + world annotations).
            OfflineARContainer(
                viewModel: arViewModel,
                // POV encode starts after the surface coach so scanning stays light.
                streamMode: showSurfaceCoach ? .off : .customerPOV,
                onCameraFrame: { buffer, rotationDegrees in
                    liveKit.captureARFrame(buffer, rotationDegrees: rotationDegrees)
                },
                // Customer local marks → web expert wire; POV already bakes them into video.
                onAnnotationEvent: { payload in
                    forwardCustomerAnnotation(payload)
                }
            )
            .ignoresSafeArea()

            // Pointer uses SwiftUI drag in the same coordinate space as the glowing dot.
            if chromeVisible, arViewModel.selectedTool == .pointer {
                Color.clear
                    .contentShape(Rectangle())
                    .ignoresSafeArea()
                    .gesture(
                        DragGesture(minimumDistance: 0)
                            .onChanged { value in
                                arViewModel.screenPointer = value.location
                            }
                    )

                if let point = arViewModel.screenPointer {
                    ZStack {
                        Circle()
                            .fill(Color.red.opacity(0.4))
                            .frame(width: 18, height: 18)
                            .blur(radius: 4)
                        Circle()
                            .fill(Color.red)
                            .frame(width: 6, height: 6)
                            .shadow(color: .red.opacity(0.85), radius: 3, x: 0, y: 0)
                    }
                    .position(point)
                    .allowsHitTesting(false)
                }
            }

            if chromeVisible {
                VStack {
                    topBar
                    if let modelName = expertLoadingModel {
                        expertLoadingBadge(modelName)
                            .padding(.top, 8)
                    }
                    Spacer()
                }
                .padding(.horizontal, 12)
                .padding(.top, 8)
                .zIndex(2)

                // Behind the drawer so peek / expanded cover the rail.
                HStack {
                    Spacer()
                    AnnotationToolRail(
                        selectedTool: Binding(
                            get: { arViewModel.selectedTool },
                            set: { arViewModel.selectTool($0) }
                        ),
                        isCollapsed: $annotationCollapsed,
                        dimmed: drawerSnap == .expanded
                    )
                    .padding(.trailing, annotationCollapsed ? 0 : 8)
                }
                .zIndex(3)

                CallBottomDrawer(
                    snap: $drawerSnap,
                    controls: liveCallControls,
                    recentAssetItems: arViewModel.recentAssetItems,
                    catalogAssetItems: arViewModel.catalogItems,
                    selectedModelId: arViewModel.selectedModelId,
                    onSelectAsset: { item in
                        arViewModel.selectCatalogModel(item)
                    }
                )
                .zIndex(4)
            }

            SurfaceCoachOverlay(isPresented: $showSurfaceCoach)
                .zIndex(8)

            if let connectError {
                Text(connectError)
                    .font(.footnote)
                    .foregroundStyle(.orange)
                    .padding()
                    .assistGlassRounded(12)
                    .zIndex(10)
            }

            if flashScreenshot {
                Color.white
                    .ignoresSafeArea()
                    .opacity(0.35)
                    .allowsHitTesting(false)
                    .zIndex(11)
                    .transition(.opacity)
            }
        }
        .preferredColorScheme(.dark)
        .onAppear {
            sessionStartedAt = Date()
            showSurfaceCoach = true
            arViewModel.worldBridge?.localRole = .customer
            // Expert model loading feedback
            arViewModel.worldBridge?.onExpertLoadingModel = { modelName in
                expertLoadingModel = modelName
            }
            // Web expert draws → raycast into this customer's AR space.
            let vm = arViewModel
            liveKit.onAnnotationReceived = { msg in
                vm.worldBridge?.applyRemoteWireEvent(msg)
                vm.annotationCount = vm.worldBridge?.annotationCount ?? 0
            }
            // Expert End Call (or expert left room) → leave Instant + reset home.
            liveKit.onRemoteSessionEnded = {
                Task { @MainActor in
                    await leaveCall(reason: "remote_livekit")
                }
            }
            startSessionStatusPoll()
            arViewModel.syncModelWireToPeer = true
            Task { await arViewModel.fetchModelCatalog() }
            print("[Call] customer-only — AR + surface coach; remote web annotations enabled")
        }
        .onChange(of: arViewModel.planeCount) { _, count in
            dismissSurfaceCoachIfNeeded(planeCount: count)
        }
        .onChange(of: liveKit.isVideoPaused) { _, paused in
            arViewModel.isVideoPaused = paused
        }
        .onDisappear {
            statusPollTask?.cancel()
            statusPollTask = nil
            liveKit.onAnnotationReceived = nil
            liveKit.onRemoteSessionEnded = nil
            arViewModel.worldBridge?.teardownARSession()
        }
    }

    private var topBar: some View {
        HStack {
            ARSessionTimerCapsule(startedAt: sessionStartedAt)

            Spacer()

            CallScreenshotButton {
                captureCleanScreenshot()
            }
        }
    }
    
    private func expertLoadingBadge(_ modelName: String) -> some View {
        HStack(spacing: 8) {
            ProgressView()
                .tint(AppTheme.orange)
                .scaleEffect(0.8)
            
            Text("Expert loading \(modelName)")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.white)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background {
            Capsule()
                .fill(.ultraThinMaterial.opacity(0.8))
                .background(Capsule().fill(Color.black.opacity(0.3)))
        }
        .overlay {
            Capsule()
                .strokeBorder(AppTheme.orange.opacity(0.3), lineWidth: 1)
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
                id: "pause",
                title: liveKit.isVideoPaused ? "resume" : "pause",
                systemImage: liveKit.isVideoPaused ? "play.fill" : "pause.fill"
            ) { liveKit.toggleVideoPaused() },
            CallControlAction(
                id: "end",
                title: "end",
                systemImage: "xmark",
                isDestructive: true
            ) {
                Task {
                    // Notify Assist AR before tear-down so the expert leaves too.
                    await liveKit.publishSessionEnd(reason: "customer_ended")
                    await leaveCall(reason: "customer_end_button")
                }
            }
        ]
    }

    private func dismissSurfaceCoachIfNeeded(planeCount: Int) {
        guard showSurfaceCoach, planeCount > 0 else { return }
        withAnimation(.easeOut(duration: 0.35)) {
            showSurfaceCoach = false
        }
        print("[Call] surface coach dismissed — planes=\(planeCount); starting LiveKit (AR buffers)")
        Task { await connectOnce() }
    }

    /// Customer: ARView snapshot only (no SwiftUI chrome).
    private func captureCleanScreenshot() {
        print("[Call] screenshot requested (clean ARView)")
        arViewModel.worldBridge?.captureCleanScreenshot { image in
            guard let image else { return }
            CallScreenshotCapture.saveToPhotos(image)
            withAnimation(.easeOut(duration: 0.12)) { flashScreenshot = true }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                withAnimation(.easeOut(duration: 0.2)) { flashScreenshot = false }
            }
        }
    }

    private func connectOnce() async {
        guard !didStartConnect else { return }
        didStartConnect = true
        do {
            try await liveKit.connect(
                url: credentials.livekitUrl,
                token: credentials.token,
                publishMode: .arBuffers
            )
        } catch {
            let detail = error.localizedDescription
            connectError = "LiveKit failed (\(credentials.livekitUrl)): \(detail)"
            print("[Call] connect FAILED url=\(credentials.livekitUrl) err=\(detail)")
        }
    }

    /// Single-fire leave used by End button, remote signal, and status poll.
    @MainActor
    private func leaveCall(reason: String) async {
        guard !didLeaveCall else { return }
        didLeaveCall = true
        statusPollTask?.cancel()
        statusPollTask = nil
        print("[Call] leaving reason=\(reason)")
        await liveKit.disconnect()
        onEnd()
    }

    /// Backup when expert ends before LiveKit is up (surface coach) or data packet is missed.
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
                        print("[Call] session status=\(status) — ending locally")
                        await leaveCall(reason: "session_status_\(status)")
                        break
                    }
                } catch {
                    print("[Call] status poll skipped: \(error.localizedDescription)")
                }
            }
        }
    }

    /// Forward customer-origin wire events (blue marks) to the web expert over LiveKit data.
    private func forwardCustomerAnnotation(_ payload: [String: Any]) {
        var msg = payload
        if msg["role"] == nil {
            msg["role"] = AnnotationRole.customer.rawValue
        }
        let type = msg["type"] as? String ?? ""
        // Stream freehand points unreliably for lower latency; keep place/undo reliable.
        let reliable = type != "stroke_point" && type != "pointer"
        liveKit.publishAnnotation(msg, reliable: reliable)
    }
}

/// TeamViewer Assist AR–style tool set (shared offline + live chrome).
enum AnnotationTool: String, CaseIterable, Identifiable {
    case arrow, freehand, pointer, model, undo, delete

    var id: String { rawValue }

    var systemImage: String {
        switch self {
        case .arrow: return "arrow.down.to.line"
        case .freehand: return "highlighter"
        case .pointer: return "hand.point.up.left.fill"
        case .model: return "cube.fill"
        case .undo: return "arrow.uturn.backward"
        case .delete: return "trash"
        }
    }

    /// Drawing / placement tools (not undo/delete).
    var isContentTool: Bool {
        switch self {
        case .undo, .delete: return false
        default: return true
        }
    }
}
