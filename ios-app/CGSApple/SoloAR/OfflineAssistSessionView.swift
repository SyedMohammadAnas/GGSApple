import SwiftUI
import RealityKit
import ARKit
import UIKit
import Combine

/// Offline “Create video tutorial” — world-relative AR annotations + Liquid Glass chrome.
struct OfflineAssistSessionView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel = OfflineAssistViewModel()
    @State private var showAssets = false

    var body: some View {
        ZStack {
            OfflineARContainer(viewModel: viewModel)
                .ignoresSafeArea()

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
        .preferredColorScheme(.dark)
        .sheet(isPresented: $showAssets) {
            AssetsPlaceholderSheet()
                .presentationDetents([.medium, .large])
        }
        .onAppear {
            print("[OfflineAssist] opened — world-relative annotations + glass chrome")
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
    var selectedTool: AnnotationTool = .freehand
    var isMuted = false
    var isSpeakerOn = true
    var isVideoPaused = false
    var planeCount = 0
    var trackingNormal = false
    var annotationCount = 0

    /// Bridge to AR coordinator for world-space undo/clear.
    weak var worldBridge: OfflineWorldBridge?

    var trackingLabel: String {
        if isVideoPaused { return "Paused" }
        if trackingNormal && planeCount > 0 { return "Tracking" }
        if trackingNormal { return "Scan surfaces" }
        return "Starting…"
    }

    var hintText: String {
        switch selectedTool {
        case .pointer: return "Drag — pointer stays in world and faces you"
        case .arrow: return "Drag like on-screen — arrow anchors in world and billboards"
        case .freehand: return "Draw on a detected plane — strokes stick to the surface"
        case .circle: return "Drag like on-screen — circle anchors in world and billboards"
        case .undo: return "Undo last world annotation"
        case .delete: return "Clear all world annotations"
        }
    }

    func selectTool(_ tool: AnnotationTool) {
        selectedTool = tool
        print("[OfflineAssist] tool=\(tool.rawValue)")
        if tool == .undo {
            worldBridge?.undo()
            annotationCount = worldBridge?.annotationCount ?? 0
            selectedTool = .freehand
        } else if tool == .delete {
            worldBridge?.clearAll()
            annotationCount = 0
            selectedTool = .freehand
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

/// Owned by AR coordinator — places RealityKit entities on plane hits.
/// Freehand stays surface-stuck. Arrow / circle / pointer are screen-drawn
/// shapes locked to a world point and always billboard toward the camera.
@MainActor
final class OfflineWorldBridge: NSObject {
    weak var arView: ARView?
    private var strokeAnchors: [AnchorEntity] = []
    /// Content roots that must face the camera every frame.
    private var billboardRoots: [Entity] = []
    private var pointerAnchor: AnchorEntity?
    private var pointerBillboard: Entity?
    private var draftAnchor: AnchorEntity?
    private var draftBillboard: Entity?

    /// Surface freehand: world-space points on the plane.
    private var draftWorldPoints: [SIMD3<Float>] = []
    /// Billboard tools: screen points + world anchor from first hit.
    private var draftScreenPoints: [CGPoint] = []
    private var draftAnchorWorld: SIMD3<Float>?

    var annotationCount: Int { strokeAnchors.count }

    private let strokeColor = UIColor.systemRed
    private let pointerColor = UIColor.cyan

    func undo() {
        guard let last = strokeAnchors.popLast() else { return }
        // Drop matching billboard root if this stroke used one.
        if let content = last.children.first {
            billboardRoots.removeAll { $0 === content }
        }
        last.removeFromParent()
        print("[OfflineAssist] undo world count=\(strokeAnchors.count)")
    }

    func clearAll() {
        strokeAnchors.forEach { $0.removeFromParent() }
        strokeAnchors.removeAll()
        billboardRoots.removeAll()
        pointerAnchor?.removeFromParent()
        pointerAnchor = nil
        pointerBillboard = nil
        clearDraft()
        print("[OfflineAssist] cleared world annotations")
    }

    /// Keep billboard annotations facing the live camera (call from session updates).
    func updateBillboards(cameraTransform: simd_float4x4) {
        // Match camera rotation so local XY stays screen-aligned (always billboard).
        let camOrientation = simd_quatf(cameraTransform)
        for root in billboardRoots {
            root.setOrientation(camOrientation, relativeTo: nil)
        }
        draftBillboard?.setOrientation(camOrientation, relativeTo: nil)
        pointerBillboard?.setOrientation(camOrientation, relativeTo: nil)
    }

    func begin(at screen: CGPoint, tool: AnnotationTool) {
        clearDraft()
        guard let world = hitWorld(screen) else {
            print("[OfflineAssist] begin miss — no plane")
            return
        }

        if tool == .pointer {
            placePointer(at: world)
            return
        }

        if tool == .freehand {
            draftWorldPoints = [world]
            rebuildSurfaceDraft(tool: tool)
            return
        }

        // Arrow / circle: screen-feel stroke, world-anchored, always billboard.
        draftAnchorWorld = world
        draftScreenPoints = [screen]
        rebuildBillboardDraft(tool: tool)
    }

    func move(to screen: CGPoint, tool: AnnotationTool) {
        if tool == .pointer {
            guard let world = hitWorld(screen) else { return }
            placePointer(at: world)
            return
        }

        if tool == .freehand {
            guard let world = hitWorld(screen) else { return }
            if draftWorldPoints.isEmpty {
                draftWorldPoints = [world]
            } else if let last = draftWorldPoints.last, length(world - last) >= 0.008 {
                draftWorldPoints.append(world)
            } else {
                return
            }
            rebuildSurfaceDraft(tool: tool)
            return
        }

        // Billboard tools — keep anchoring to the first hit; shape follows screen drag.
        if draftAnchorWorld == nil {
            guard let world = hitWorld(screen) else { return }
            draftAnchorWorld = world
            draftScreenPoints = [screen]
        } else {
            if let last = draftScreenPoints.last {
                let dx = screen.x - last.x
                let dy = screen.y - last.y
                if (dx * dx + dy * dy) < 4 { return }
            }
            draftScreenPoints.append(screen)
        }
        rebuildBillboardDraft(tool: tool)
    }

    func end(tool: AnnotationTool) {
        defer { clearDraft() }
        if tool == .pointer { return }
        guard let arView else { return }

        if tool == .freehand {
            guard draftWorldPoints.count >= 2 else { return }
            let anchor = buildSurfaceStroke(points: draftWorldPoints, color: strokeColor)
            arView.scene.addAnchor(anchor)
            strokeAnchors.append(anchor)
            print("[OfflineAssist] surface freehand points=\(draftWorldPoints.count)")
            return
        }

        guard let origin = draftAnchorWorld, draftScreenPoints.count >= 2 else { return }
        let localPoints = screenPointsToLocalXY(draftScreenPoints, anchorWorld: origin)
        guard localPoints.count >= 2 else { return }
        let built = buildBillboardStroke(
            localPoints: localPoints,
            worldOrigin: origin,
            tool: tool,
            color: strokeColor
        )
        arView.scene.addAnchor(built.anchor)
        strokeAnchors.append(built.anchor)
        billboardRoots.append(built.content)
        // Snap orientation immediately.
        if let frame = arView.session.currentFrame {
            built.content.orientation = simd_quatf(frame.camera.transform)
        }
        print("[OfflineAssist] billboard \(tool.rawValue) points=\(localPoints.count)")
    }

    private func hitWorld(_ screen: CGPoint) -> SIMD3<Float>? {
        guard let arView else { return nil }
        let results =
            arView.raycast(from: screen, allowing: .existingPlaneGeometry, alignment: .any)
            + arView.raycast(from: screen, allowing: .estimatedPlane, alignment: .any)
        guard let t = results.first?.worldTransform else { return nil }
        return SIMD3<Float>(t.columns.3.x, t.columns.3.y, t.columns.3.z)
    }

    private func placePointer(at world: SIMD3<Float>) {
        guard let arView else { return }
        if pointerAnchor == nil {
            let anchor = AnchorEntity(world: world)
            let content = Entity()
            content.name = "pointerBillboard"
            // Screen-like ring (XY), not a surface disc.
            let ring = makeCirclePolyline(radius: 0.025, lineRadius: 0.003, material: SimpleMaterial(color: pointerColor, isMetallic: false))
            content.addChild(ring)
            let dot = ModelEntity(
                mesh: .generateSphere(radius: 0.008),
                materials: [SimpleMaterial(color: pointerColor, isMetallic: false)]
            )
            content.addChild(dot)
            anchor.addChild(content)
            arView.scene.addAnchor(anchor)
            pointerAnchor = anchor
            pointerBillboard = content
            if let frame = arView.session.currentFrame {
                content.orientation = simd_quatf(frame.camera.transform)
            }
        } else {
            pointerAnchor?.position = world
        }
    }

    private func rebuildSurfaceDraft(tool: AnnotationTool) {
        guard let arView, tool == .freehand else { return }
        draftAnchor?.removeFromParent()
        guard draftWorldPoints.count >= 1 else { return }
        let anchor = buildSurfaceStroke(
            points: draftWorldPoints,
            color: strokeColor.withAlphaComponent(0.7)
        )
        arView.scene.addAnchor(anchor)
        draftAnchor = anchor
        draftBillboard = nil
    }

    private func rebuildBillboardDraft(tool: AnnotationTool) {
        guard let arView, let origin = draftAnchorWorld else { return }
        draftAnchor?.removeFromParent()
        let localPoints = screenPointsToLocalXY(draftScreenPoints, anchorWorld: origin)
        guard !localPoints.isEmpty else { return }
        let built = buildBillboardStroke(
            localPoints: localPoints,
            worldOrigin: origin,
            tool: tool,
            color: strokeColor.withAlphaComponent(0.7)
        )
        arView.scene.addAnchor(built.anchor)
        draftAnchor = built.anchor
        draftBillboard = built.content
        if let frame = arView.session.currentFrame {
            built.content.orientation = simd_quatf(frame.camera.transform)
        }
    }

    private func clearDraft() {
        draftAnchor?.removeFromParent()
        draftAnchor = nil
        draftBillboard = nil
        draftWorldPoints = []
        draftScreenPoints = []
        draftAnchorWorld = nil
    }

    // MARK: - Screen → local XY (camera-facing plane at world anchor)

    private func screenPointsToLocalXY(_ screens: [CGPoint], anchorWorld: SIMD3<Float>) -> [SIMD3<Float>] {
        guard let arView, let frame = arView.session.currentFrame, let first = screens.first else { return [] }
        let cam = frame.camera.transform
        let right = SIMD3<Float>(cam.columns.0.x, cam.columns.0.y, cam.columns.0.z)
        let up = SIMD3<Float>(cam.columns.1.x, cam.columns.1.y, cam.columns.1.z)
        let forward = -SIMD3<Float>(cam.columns.2.x, cam.columns.2.y, cam.columns.2.z)

        guard let originWorld = intersectBillboardPlane(
            screen: first,
            planePoint: anchorWorld,
            planeNormal: forward,
            arView: arView
        ) else { return [] }

        var locals: [SIMD3<Float>] = [.zero]
        for screen in screens.dropFirst() {
            guard let world = intersectBillboardPlane(
                screen: screen,
                planePoint: anchorWorld,
                planeNormal: forward,
                arView: arView
            ) else { continue }
            let delta = world - originWorld
            locals.append(SIMD3<Float>(dot(delta, right), dot(delta, up), 0))
        }
        return locals
    }

    private func intersectBillboardPlane(
        screen: CGPoint,
        planePoint: SIMD3<Float>,
        planeNormal: SIMD3<Float>,
        arView: ARView
    ) -> SIMD3<Float>? {
        guard let ray = arView.ray(through: screen) else { return nil }
        let normal = normalize(planeNormal)
        let denom = dot(ray.direction, normal)
        guard abs(denom) > 1e-5 else { return nil }
        let t = dot(planePoint - ray.origin, normal) / denom
        guard t > 0 else { return nil }
        return ray.origin + ray.direction * t
    }

    // MARK: - Builders

    private func buildSurfaceStroke(points: [SIMD3<Float>], color: UIColor) -> AnchorEntity {
        let origin = points[0]
        let anchor = AnchorEntity(world: origin)
        let material = SimpleMaterial(color: color, isMetallic: false)
        addWorldPolyline(points, to: anchor, material: material, radius: 0.004)
        return anchor
    }

    private func buildBillboardStroke(
        localPoints: [SIMD3<Float>],
        worldOrigin: SIMD3<Float>,
        tool: AnnotationTool,
        color: UIColor
    ) -> (anchor: AnchorEntity, content: Entity) {
        let anchor = AnchorEntity(world: worldOrigin)
        let content = Entity()
        content.name = "billboardContent"
        let material = SimpleMaterial(color: color, isMetallic: false)

        switch tool {
        case .arrow:
            let start = localPoints[0]
            let end = localPoints.last ?? start
            addLocalPolyline([start, end], to: content, material: material, radius: 0.005)
            addLocalArrowHead(from: start, to: end, parent: content, material: material)
        case .circle:
            let start = localPoints[0]
            let end = localPoints.last ?? start
            let radius = max(length(end - start), 0.02)
            content.addChild(makeCirclePolyline(radius: radius, lineRadius: 0.004, material: material))
        default:
            addLocalPolyline(localPoints, to: content, material: material, radius: 0.004)
        }

        anchor.addChild(content)
        return (anchor, content)
    }

    private func addWorldPolyline(
        _ points: [SIMD3<Float>],
        to parent: Entity,
        material: SimpleMaterial,
        radius: Float
    ) {
        let origin = points[0]
        for i in 0..<(points.count - 1) {
            let a = points[i] - origin
            let b = points[i + 1] - origin
            addSegment(from: a, to: b, parent: parent, material: material, radius: radius)
        }
    }

    private func addLocalPolyline(
        _ points: [SIMD3<Float>],
        to parent: Entity,
        material: SimpleMaterial,
        radius: Float
    ) {
        for i in 0..<(points.count - 1) {
            addSegment(from: points[i], to: points[i + 1], parent: parent, material: material, radius: radius)
        }
    }

    private func addSegment(
        from a: SIMD3<Float>,
        to b: SIMD3<Float>,
        parent: Entity,
        material: SimpleMaterial,
        radius: Float
    ) {
        let segment = b - a
        let dist = length(segment)
        guard dist > 0.001 else { return }
        let mesh = MeshResource.generateBox(width: radius * 2, height: radius * 2, depth: dist)
        let entity = ModelEntity(mesh: mesh, materials: [material])
        entity.position = (a + b) / 2
        let direction = normalize(segment)
        entity.look(at: entity.position + direction, from: entity.position, relativeTo: parent)
        parent.addChild(entity)
    }

    private func addLocalArrowHead(
        from start: SIMD3<Float>,
        to end: SIMD3<Float>,
        parent: Entity,
        material: SimpleMaterial
    ) {
        let dir = normalize(end - start)
        for i in 0..<4 {
            let t = Float(i) / 3.0
            let r = 0.012 * (1.0 - t * 0.85)
            let tip = ModelEntity(
                mesh: .generateSphere(radius: r),
                materials: [material]
            )
            tip.position = end - dir * (0.008 * Float(i))
            parent.addChild(tip)
        }
    }

    private func makeCirclePolyline(radius: Float, lineRadius: Float, material: SimpleMaterial) -> Entity {
        let root = Entity()
        let segments = 48
        var ring: [SIMD3<Float>] = []
        for i in 0...segments {
            let t = Float(i) / Float(segments) * 2 * .pi
            // Local XY — faces camera when parent is billboarded.
            ring.append(SIMD3<Float>(cos(t) * radius, sin(t) * radius, 0))
        }
        for i in 0..<(ring.count - 1) {
            addSegment(from: ring[i], to: ring[i + 1], parent: root, material: material, radius: lineRadius)
        }
        return root
    }
}

// MARK: - AR container

struct OfflineARContainer: UIViewRepresentable {
    @Bindable var viewModel: OfflineAssistViewModel

    func makeUIView(context: Context) -> ARView {
        let arView = ARView(frame: .zero)
        arView.automaticallyConfigureSession = false
        arView.session.delegate = context.coordinator

        let config = ARWorldTrackingConfiguration()
        config.planeDetection = [.horizontal, .vertical]
        config.environmentTexturing = .automatic
        arView.session.run(config, options: [.resetTracking, .removeExistingAnchors])
        print("[OfflineAssist] AR session run")

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

    @MainActor
    final class Coordinator: NSObject, ARSessionDelegate {
        let viewModel: OfflineAssistViewModel
        weak var arView: ARView?
        var bridge: OfflineWorldBridge?
        private var planeIds = Set<UUID>()
        private var wasPaused = false

        init(viewModel: OfflineAssistViewModel) {
            self.viewModel = viewModel
        }

        func applyPause(_ paused: Bool, on arView: ARView) {
            if paused && !wasPaused {
                arView.session.pause()
                wasPaused = true
            } else if !paused && wasPaused {
                let config = ARWorldTrackingConfiguration()
                config.planeDetection = [.horizontal, .vertical]
                config.environmentTexturing = .automatic
                arView.session.run(config)
                wasPaused = false
            }
        }

        @objc func handlePan(_ gesture: UIPanGestureRecognizer) {
            guard let arView, !viewModel.isVideoPaused else { return }
            let point = gesture.location(in: arView)
            let tool = viewModel.selectedTool
            guard tool != .undo, tool != .delete else { return }

            switch gesture.state {
            case .began:
                bridge?.begin(at: point, tool: tool)
            case .changed:
                bridge?.move(to: point, tool: tool)
            case .ended, .cancelled:
                bridge?.end(tool: tool)
                viewModel.annotationCount = bridge?.annotationCount ?? 0
            default:
                break
            }
        }

        func session(_ session: ARSession, didUpdate frame: ARFrame) {
            // Copy values only — do not retain ARFrame (avoids camera stall warning).
            let isNormal: Bool
            if case .normal = frame.camera.trackingState {
                isNormal = true
            } else {
                isNormal = false
            }
            let cameraTransform = frame.camera.transform
            Task { @MainActor [weak self] in
                self?.viewModel.trackingNormal = isNormal
                self?.bridge?.updateBillboards(cameraTransform: cameraTransform)
            }
        }

        func session(_ session: ARSession, didAdd anchors: [ARAnchor]) {
            for anchor in anchors {
                if let plane = anchor as? ARPlaneAnchor {
                    planeIds.insert(plane.identifier)
                }
            }
            let count = planeIds.count
            Task { @MainActor [weak self] in
                self?.viewModel.planeCount = count
            }
        }

        func session(_ session: ARSession, didRemove anchors: [ARAnchor]) {
            for anchor in anchors {
                if let plane = anchor as? ARPlaneAnchor {
                    planeIds.remove(plane.identifier)
                }
            }
            let count = planeIds.count
            Task { @MainActor [weak self] in
                self?.viewModel.planeCount = count
            }
        }
    }
}
