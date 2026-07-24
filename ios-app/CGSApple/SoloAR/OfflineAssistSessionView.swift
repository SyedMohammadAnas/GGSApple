import SwiftUI
import RealityKit
import ARKit
import UIKit

/// Offline “Create video tutorial” — TeamViewer Assist AR–style markers + Liquid Glass chrome.
struct OfflineAssistSessionView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel = OfflineAssistViewModel()
    @State private var showAssets = false

    var body: some View {
        ZStack {
            OfflineARContainer(viewModel: viewModel)
                .ignoresSafeArea()

            // Pointer uses SwiftUI drag in the same coordinate space as the glowing dot
            // (UIKit ARView coords were drifting vs the overlay).
            if viewModel.selectedTool == .pointer {
                Color.clear
                    .contentShape(Rectangle())
                    .ignoresSafeArea()
                    .gesture(
                        DragGesture(minimumDistance: 0)
                            .onChanged { value in
                                viewModel.screenPointer = value.location
                            }
                    )

                if let point = viewModel.screenPointer {
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
        }
        .ignoresSafeArea()
        .preferredColorScheme(.dark)
        .sheet(isPresented: $showAssets) {
            AssetsPlaceholderSheet()
                .presentationDetents([.medium, .large])
        }
        .onAppear {
            print("[OfflineAssist] opened — surface-normal arrows + per-hit freehand ink")
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

            Text(viewModel.trackingLabel)
                .font(.caption2.weight(.semibold))
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .assistGlassCapsule(interactive: false)

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
                Button {
                    viewModel.selectTool(tool)
                } label: {
                    Image(systemName: tool.systemImage)
                        .font(.body.weight(.semibold))
                        .frame(width: 40, height: 40)
                        .foregroundStyle(viewModel.selectedTool == tool ? AppTheme.orange : .primary)
                }
                .assistGlassRounded(12, tint: viewModel.selectedTool == tool ? AppTheme.orange.opacity(0.35) : nil)
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

            Text(viewModel.hintText)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            HStack(spacing: 18) {
                glassCallButton(
                    title: "speaker",
                    system: viewModel.isSpeakerOn ? "speaker.wave.2.fill" : "speaker.slash.fill"
                ) { viewModel.toggleSpeaker() }

                glassCallButton(
                    title: "mute",
                    system: viewModel.isMuted ? "mic.slash.fill" : "mic.fill"
                ) { viewModel.toggleMute() }

                glassCallButton(
                    title: viewModel.isVideoPaused ? "resume" : "pause",
                    system: viewModel.isVideoPaused ? "play.fill" : "pause.fill"
                ) { viewModel.togglePause() }

                VStack(spacing: 4) {
                    Button {
                        print("[OfflineAssist] end")
                        dismiss()
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
}

// MARK: - View model

@MainActor
@Observable
final class OfflineAssistViewModel {
    /// Default matches TeamViewer: place surface arrows first.
    var selectedTool: AnnotationTool = .arrow
    /// Drawing tool to restore after undo / delete (do not jump back to arrow).
    private var lastContentTool: AnnotationTool = .arrow
    var isMuted = false
    var isSpeakerOn = true
    var isVideoPaused = false
    var planeCount = 0
    var trackingNormal = false
    var annotationCount = 0
    /// Screen-space pointer (UI overlay) — not world-anchored.
    var screenPointer: CGPoint?

    weak var worldBridge: OfflineWorldBridge?

    var trackingLabel: String {
        if isVideoPaused { return "Paused" }
        if trackingNormal && planeCount > 0 { return "Surfaces \(planeCount)" }
        if trackingNormal { return "Scan all surfaces" }
        return "Starting…"
    }

    var hintText: String {
        switch selectedTool {
        case .arrow: return "Tap a surface — arrow sticks perpendicular, pointing at it"
        case .freehand: return "Draw light ink that sticks on surfaces in AR"
        case .pointer: return "Drag — screen pointer (tiny red glow dot)"
        case .undo: return "Undo last marker"
        case .delete: return "Clear all markers"
        }
    }

    func selectTool(_ tool: AnnotationTool) {
        print("[OfflineAssist] tool=\(tool.rawValue)")
        if tool == .undo {
            worldBridge?.undo()
            annotationCount = worldBridge?.annotationCount ?? 0
            // Stay on highlighter / pointer / arrow — whatever was active.
            selectedTool = lastContentTool
            return
        }
        if tool == .delete {
            worldBridge?.clearAll()
            annotationCount = 0
            selectedTool = lastContentTool
            return
        }
        selectedTool = tool
        lastContentTool = tool
        if tool != .pointer {
            screenPointer = nil
        }
    }

    func toggleMute() {
        isMuted.toggle()
        print("[OfflineAssist] mute=\(isMuted) (offline stub)")
    }

    func toggleSpeaker() {
        isSpeakerOn.toggle()
        print("[OfflineAssist] speaker=\(isSpeakerOn) (offline stub)")
    }

    func togglePause() {
        isVideoPaused.toggle()
        print("[OfflineAssist] pause=\(isVideoPaused)")
    }
}

// MARK: - World bridge

/// Freehand = light unlit ink segments stuck on planes (not fancy lit 3D).
/// Arrow = flat unlit mark perpendicular to surface.
/// Pointer = screen-space UI only.
@MainActor
final class OfflineWorldBridge: NSObject {
    weak var arView: ARView?

    /// One undo/clear unit — an arrow is a single anchor; a stroke is many anchors.
    private struct MarkerGroup {
        var anchors: [AnchorEntity]

        @MainActor
        func removeFromParent() {
            anchors.forEach { $0.removeFromParent() }
        }
    }

    private var markers: [MarkerGroup] = []
    /// In-progress freehand pieces (each placed like an arrow: AnchorEntity(world: hit)).
    private var draftAnchors: [AnchorEntity] = []
    private var draftPlaneID: UUID?
    private var draftPlaneTransform: simd_float4x4?
    private var draftWorldPoints: [SIMD3<Float>] = []
    private var nextArrowNumber = 0

    private let markerColor = UIColor(red: 1.0, green: 0.48, blue: 0.0, alpha: 1.0)
    private let highlighterColor = UIColor(red: 1.0, green: 0.75, blue: 0.1, alpha: 0.95)

    /// Thin ink; keep entity count low to avoid jetsam during draw.
    private let highlighterRadius: Float = 0.0018
    private let highlighterSampleSpacing: Float = 0.01
    private let maxStrokePoints = 48
    /// Sit just above the plane along its normal (matches arrow tip offset).
    private let surfaceLift: Float = 0.002
    /// Reject samples that leave the locked plane (meters).
    private let planeStickTolerance: Float = 0.03

    var annotationCount: Int { markers.count }

    func undo() {
        guard let last = markers.popLast() else { return }
        last.removeFromParent()
        print("[OfflineAssist] undo count=\(markers.count)")
    }

    func clearAll() {
        markers.forEach { $0.removeFromParent() }
        markers.removeAll()
        discardDraft()
        nextArrowNumber = 0
        print("[OfflineAssist] cleared all markers")
    }

    // MARK: Arrow — few unlit entities, surface-normal

    func placeArrow(at screen: CGPoint) {
        guard let arView, let hit = hitResult(screen) else {
            print("[OfflineAssist] arrow miss — keep scanning surfaces")
            return
        }
        nextArrowNumber += 1
        let number = nextArrowNumber

        let anchor = AnchorEntity(world: hit.worldTransform)
        let content = makeSurfaceNormalArrow(number: number, color: markerColor)
        content.position = SIMD3<Float>(0, surfaceLift, 0)
        anchor.addChild(content)
        arView.scene.addAnchor(anchor)
        markers.append(MarkerGroup(anchors: [anchor]))
        print("[OfflineAssist] placed surface-normal arrow #\(number)")
    }

    // MARK: Highlighter — arrow-identical placement (per-hit AnchorEntity)

    /// Different approach from plane-local / ray∩plane: every sample is placed the
    /// same way as an arrow tip — `AnchorEntity(world: hit.worldTransform)` + local lift.
    /// Segments are also their own world anchors at the midpoint. No coordinate conversion.
    func beginHighlighter(at screen: CGPoint) {
        discardDraft()
        guard let hit = inkHit(screen, locking: true), let arView else {
            print("[OfflineAssist] highlighter begin miss")
            return
        }

        draftPlaneID = (hit.anchor as? ARPlaneAnchor)?.identifier
        draftPlaneTransform = hit.worldTransform
        let start = worldPosition(hit)
        draftWorldPoints = [start]

        // Bead at start — identical transform path as arrows.
        let bead = makeInkBeadAnchor(hit: hit)
        arView.scene.addAnchor(bead)
        draftAnchors.append(bead)
        print("[OfflineAssist] highlighter locked planeID=\(draftPlaneID?.uuidString.prefix(8) ?? "est")")
    }

    func moveHighlighter(to screen: CGPoint) {
        guard draftWorldPoints.count < maxStrokePoints,
              let last = draftWorldPoints.last,
              let arView,
              let hit = inkHit(screen, locking: false)
        else { return }

        let point = worldPosition(hit)
        guard length(point - last) >= highlighterSampleSpacing else { return }

        draftWorldPoints.append(point)

        // Bead on this hit (same as arrow tip placement).
        let bead = makeInkBeadAnchor(hit: hit)
        arView.scene.addAnchor(bead)
        draftAnchors.append(bead)

        // Connector between last and this world point.
        if let segment = makeInkSegmentAnchor(from: last, to: point) {
            arView.scene.addAnchor(segment)
            draftAnchors.append(segment)
        }
    }

    func endHighlighter() {
        if draftWorldPoints.count < 2 || draftAnchors.isEmpty {
            discardDraft()
            return
        }
        markers.append(MarkerGroup(anchors: draftAnchors))
        print("[OfflineAssist] ink stroke points=\(draftWorldPoints.count) anchors=\(draftAnchors.count) (per-hit world)")
        draftAnchors = []
        draftPlaneID = nil
        draftPlaneTransform = nil
        draftWorldPoints = []
    }

    private func discardDraft() {
        draftAnchors.forEach { $0.removeFromParent() }
        draftAnchors = []
        draftPlaneID = nil
        draftPlaneTransform = nil
        draftWorldPoints = []
    }

    /// Tiny sphere parented exactly like an arrow tip.
    private func makeInkBeadAnchor(hit: ARRaycastResult) -> AnchorEntity {
        let anchor = AnchorEntity(world: hit.worldTransform)
        let bead = ModelEntity(
            mesh: .generateSphere(radius: highlighterRadius * 1.4),
            materials: [UnlitMaterial(color: highlighterColor)]
        )
        bead.position = SIMD3<Float>(0, surfaceLift, 0)
        anchor.addChild(bead)
        return anchor
    }

    /// Segment between two world points — own `AnchorEntity` at midpoint (arrow-style).
    private func makeInkSegmentAnchor(from: SIMD3<Float>, to: SIMD3<Float>) -> AnchorEntity? {
        let delta = to - from
        let dist = length(delta)
        guard dist > 0.0005 else { return nil }

        let mid = (from + to) / 2
        // Lift midpoint along locked plane normal so the stroke sits above the surface.
        let liftedMid = mid + lockedPlaneNormal() * surfaceLift
        var transform = matrix_identity_float4x4
        transform.columns.3 = SIMD4(liftedMid.x, liftedMid.y, liftedMid.z, 1)

        let anchor = AnchorEntity(world: transform)
        let mesh = MeshResource.generateBox(
            width: highlighterRadius * 2,
            height: highlighterRadius * 2,
            depth: dist
        )
        let entity = ModelEntity(mesh: mesh, materials: [UnlitMaterial(color: highlighterColor)])
        // Aim box depth (-Z) along the stroke in the anchor’s local/world-aligned space.
        let dir = normalize(delta)
        entity.look(at: dir, from: .zero, relativeTo: anchor)
        anchor.addChild(entity)
        return anchor
    }

    private func lockedPlaneNormal() -> SIMD3<Float> {
        guard let plane = draftPlaneTransform else {
            return SIMD3<Float>(0, 1, 0)
        }
        return normalize(SIMD3<Float>(
            plane.columns.1.x,
            plane.columns.1.y,
            plane.columns.1.z
        ))
    }

    // MARK: Hits

    private func hitResult(_ screen: CGPoint) -> ARRaycastResult? {
        guard let arView else { return nil }
        if let hit = arView.raycast(from: screen, allowing: .existingPlaneGeometry, alignment: .any).first {
            return hit
        }
        if let hit = arView.raycast(from: screen, allowing: .existingPlaneInfinite, alignment: .any).first {
            return hit
        }
        return arView.raycast(from: screen, allowing: .estimatedPlane, alignment: .any).first
    }

    /// Same raycast stack as arrows; after begin, stay on the locked plane only.
    private func inkHit(_ screen: CGPoint, locking: Bool) -> ARRaycastResult? {
        guard let arView else { return nil }

        let geo = arView.raycast(from: screen, allowing: .existingPlaneGeometry, alignment: .any)
        let inf = arView.raycast(from: screen, allowing: .existingPlaneInfinite, alignment: .any)
        let est = arView.raycast(from: screen, allowing: .estimatedPlane, alignment: .any)
        let ordered = geo + inf + est

        if locking {
            return ordered.first
        }

        if let planeID = draftPlaneID {
            if let match = ordered.first(where: { ($0.anchor as? ARPlaneAnchor)?.identifier == planeID }) {
                return match
            }
            return nil
        }

        guard let hit = ordered.first, isNearLockedPlane(hit) else { return nil }
        return hit
    }

    private func isNearLockedPlane(_ hit: ARRaycastResult) -> Bool {
        guard let plane = draftPlaneTransform else { return true }
        let world = worldPosition(hit)
        let origin = SIMD3<Float>(plane.columns.3.x, plane.columns.3.y, plane.columns.3.z)
        let normal = normalize(SIMD3<Float>(
            plane.columns.1.x,
            plane.columns.1.y,
            plane.columns.1.z
        ))
        return abs(dot(world - origin, normal)) <= planeStickTolerance
    }

    private func worldPosition(_ hit: ARRaycastResult) -> SIMD3<Float> {
        let t = hit.worldTransform
        return SIMD3(t.columns.3.x, t.columns.3.y, t.columns.3.z)
    }

    // MARK: Geometry (unlit only — stick in AR, no fancy shading)

    private func makeSurfaceNormalArrow(number: Int, color: UIColor) -> Entity {
        let root = Entity()
        let mat = UnlitMaterial(color: color)
        let lineRadius: Float = 0.003

        let tip = SIMD3<Float>(0, 0.004, 0)
        let base = SIMD3<Float>(0, 0.08, 0)
        addFlatSegment(from: base, to: tip, radius: lineRadius, material: mat, parent: root)
        addFlatSegment(from: tip, to: SIMD3(-0.024, 0.028, 0), radius: lineRadius, material: mat, parent: root)
        addFlatSegment(from: tip, to: SIMD3(0.024, 0.028, 0), radius: lineRadius, material: mat, parent: root)

        let badge = ModelEntity(
            mesh: .generatePlane(width: 0.028, depth: 0.028),
            materials: [UnlitMaterial(color: color)]
        )
        badge.orientation = simd_quatf(angle: -.pi / 2, axis: SIMD3(1, 0, 0))
        badge.position = SIMD3(0.04, 0.06, 0)
        root.addChild(badge)

        let textMesh = MeshResource.generateText(
            "\(number)",
            extrusionDepth: 0.0005,
            font: .boldSystemFont(ofSize: 0.032),
            containerFrame: .zero,
            alignment: .center,
            lineBreakMode: .byTruncatingTail
        )
        let glyph = ModelEntity(mesh: textMesh, materials: [UnlitMaterial(color: .white)])
        glyph.position = SIMD3(0.028, 0.048, 0.002)
        root.addChild(glyph)

        return root
    }

    private func addFlatSegment(
        from a: SIMD3<Float>,
        to b: SIMD3<Float>,
        radius: Float,
        material: UnlitMaterial,
        parent: Entity
    ) {
        let segment = b - a
        let dist = length(segment)
        guard dist > 0.0005 else { return }
        let mesh = MeshResource.generateBox(width: radius * 2, height: radius * 2, depth: dist)
        let entity = ModelEntity(mesh: mesh, materials: [material])
        entity.position = (a + b) / 2
        let direction = normalize(segment)
        entity.look(at: entity.position + direction, from: entity.position, relativeTo: parent)
        parent.addChild(entity)
    }
}

// MARK: - AR configuration

enum OfflineARConfig {
    /// Lean tracking — planes only. No env maps / mesh recon (those blow memory).
    static func make() -> ARWorldTrackingConfiguration {
        let config = ARWorldTrackingConfiguration()
        config.planeDetection = [.horizontal, .vertical]
        config.environmentTexturing = .none
        return config
    }
}

// MARK: - AR container

struct OfflineARContainer: UIViewRepresentable {
    @Bindable var viewModel: OfflineAssistViewModel

    func makeUIView(context: Context) -> ARView {
        let arView = ARView(frame: .zero)
        arView.automaticallyConfigureSession = false
        arView.session.delegate = context.coordinator
        arView.debugOptions = []
        // Cheaper RealityKit path — annotations only need to stick, not look photographic.
        arView.renderOptions = [
            .disableMotionBlur,
            .disableDepthOfField,
            .disableGroundingShadows,
            .disablePersonOcclusion
        ]

        let config = OfflineARConfig.make()
        arView.session.run(config, options: [.resetTracking, .removeExistingAnchors])
        print("[OfflineAssist] AR session run — lean planes + unlit sticky annotations")

        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleTap(_:)))
        arView.addGestureRecognizer(tap)

        let pan = UIPanGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handlePan(_:)))
        pan.maximumNumberOfTouches = 1
        arView.addGestureRecognizer(pan)

        let bridge = OfflineWorldBridge()
        bridge.arView = arView
        context.coordinator.bridge = bridge
        context.coordinator.arView = arView
        viewModel.worldBridge = bridge

        return arView
    }

    func updateUIView(_ uiView: ARView, context: Context) {
        context.coordinator.applyPause(viewModel.isVideoPaused, on: uiView)
        viewModel.worldBridge = context.coordinator.bridge
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(viewModel: viewModel)
    }

    /// Not @MainActor — ARSessionDelegate must return quickly without retaining ARFrames.
    final class Coordinator: NSObject, ARSessionDelegate {
        let viewModel: OfflineAssistViewModel
        weak var arView: ARView?
        var bridge: OfflineWorldBridge?
        private var planeIds = Set<UUID>()
        private var wasPaused = false
        private var frameTick = 0
        private var lastReportedPlaneCount = -1

        init(viewModel: OfflineAssistViewModel) {
            self.viewModel = viewModel
        }

        func applyPause(_ paused: Bool, on arView: ARView) {
            if paused && !wasPaused {
                arView.session.pause()
                wasPaused = true
            } else if !paused && wasPaused {
                arView.session.run(OfflineARConfig.make())
                wasPaused = false
            }
        }

        @objc func handleTap(_ gesture: UITapGestureRecognizer) {
            // UIKit gestures arrive on the main thread — avoid Task queues that retain work.
            guard Thread.isMainThread, let arView else { return }
            let point = gesture.location(in: arView)
            MainActor.assumeIsolated {
                guard !viewModel.isVideoPaused else { return }
                if viewModel.selectedTool == .arrow {
                    bridge?.placeArrow(at: point)
                    viewModel.annotationCount = bridge?.annotationCount ?? 0
                }
            }
        }

        @objc func handlePan(_ gesture: UIPanGestureRecognizer) {
            guard Thread.isMainThread, let arView else { return }
            let point = gesture.location(in: arView)
            let state = gesture.state
            MainActor.assumeIsolated {
                guard !viewModel.isVideoPaused else { return }
                guard viewModel.selectedTool == .freehand else { return }
                switch state {
                case .began:
                    bridge?.beginHighlighter(at: point)
                case .changed:
                    bridge?.moveHighlighter(to: point)
                case .ended, .cancelled:
                    bridge?.endHighlighter()
                    viewModel.annotationCount = bridge?.annotationCount ?? 0
                default:
                    break
                }
            }
        }

        func session(_ session: ARSession, didUpdate frame: ARFrame) {
            // Copy ONLY scalars — never hop to MainActor while still holding `frame`.
            let isNormal: Bool
            if case .normal = frame.camera.trackingState {
                isNormal = true
            } else {
                isNormal = false
            }
            frameTick += 1
            // Throttle UI updates so MainActor isn't flooded (was retaining ARFrames).
            guard frameTick % 20 == 0 else { return }
            let normal = isNormal
            DispatchQueue.main.async { [weak self] in
                self?.viewModel.trackingNormal = normal
            }
        }

        func session(_ session: ARSession, didAdd anchors: [ARAnchor]) {
            for anchor in anchors {
                if let plane = anchor as? ARPlaneAnchor {
                    planeIds.insert(plane.identifier)
                }
            }
            publishPlaneCountIfNeeded()
        }

        func session(_ session: ARSession, didRemove anchors: [ARAnchor]) {
            for anchor in anchors {
                if let plane = anchor as? ARPlaneAnchor {
                    planeIds.remove(plane.identifier)
                }
            }
            publishPlaneCountIfNeeded()
        }

        private func publishPlaneCountIfNeeded() {
            let count = planeIds.count
            guard count != lastReportedPlaneCount else { return }
            lastReportedPlaneCount = count
            DispatchQueue.main.async { [weak self] in
                self?.viewModel.planeCount = count
            }
        }
    }
}
