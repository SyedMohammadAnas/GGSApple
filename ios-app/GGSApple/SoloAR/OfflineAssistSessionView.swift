import SwiftUI
import RealityKit
import ARKit
import UIKit
import Combine

/// Offline “Create video tutorial” — TeamViewer Assist AR–style markers + Liquid Glass chrome.
struct OfflineAssistSessionView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel = OfflineAssistViewModel()
    @State private var drawerSnap: CallDrawerSnap = .collapsed
    @State private var flashScreenshot = false
    @State private var sessionStartedAt = Date()
    @State private var annotationCollapsed = false
    @State private var showSurfaceCoach = true

    /// Chrome stays hidden until the first surface is found.
    private var chromeVisible: Bool { !showSurfaceCoach }

    var body: some View {
        ZStack {
            OfflineARContainer(viewModel: viewModel)
                .ignoresSafeArea()

            // Pointer uses SwiftUI drag in the same coordinate space as the glowing dot
            // (UIKit ARView coords were drifting vs the overlay).
            if chromeVisible, viewModel.selectedTool == .pointer {
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

            if chromeVisible {
                VStack {
                    topBar
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
                            get: { viewModel.selectedTool },
                            set: { viewModel.selectTool($0) }
                        ),
                        isCollapsed: $annotationCollapsed,
                        dimmed: drawerSnap == .expanded
                    )
                    .padding(.trailing, annotationCollapsed ? 0 : 8)
                }
                .zIndex(3)

                CallBottomDrawer(
                    snap: $drawerSnap,
                    controls: offlineCallControls,
                    recentAssetItems: viewModel.recentAssetItems,
                    catalogAssetItems: viewModel.catalogItems,
                    selectedModelId: viewModel.selectedModelId,
                    onSelectAsset: { item in
                        viewModel.selectCatalogModel(item)
                    }
                )
                .zIndex(4)
            }

            SurfaceCoachOverlay(isPresented: $showSurfaceCoach)
                .zIndex(8)

            if flashScreenshot {
                Color.white
                    .ignoresSafeArea()
                    .opacity(0.35)
                    .allowsHitTesting(false)
                    .zIndex(9)
                    .transition(.opacity)
            }
        }
        .preferredColorScheme(.dark)
        .onAppear {
            sessionStartedAt = Date()
            showSurfaceCoach = true
            viewModel.syncModelWireToPeer = false
            Task { await viewModel.fetchModelCatalog() }
            print("[OfflineAssist] opened — surface coach + per-hit freehand ink")
        }
        .onChange(of: viewModel.planeCount) { _, count in
            dismissSurfaceCoachIfNeeded(planeCount: count)
        }
        .onDisappear {
            viewModel.worldBridge?.teardownARSession()
            print("[OfflineAssist] dismissed — AR session paused")
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

    private var offlineCallControls: [CallControlAction] {
        [
            CallControlAction(
                id: "speaker",
                title: "speaker",
                systemImage: viewModel.isSpeakerOn ? "speaker.wave.2.fill" : "speaker.slash.fill"
            ) { viewModel.toggleSpeaker() },
            CallControlAction(
                id: "mute",
                title: "mute",
                systemImage: viewModel.isMuted ? "mic.slash.fill" : "mic.fill"
            ) { viewModel.toggleMute() },
            CallControlAction(
                id: "pause",
                title: viewModel.isVideoPaused ? "resume" : "pause",
                systemImage: viewModel.isVideoPaused ? "play.fill" : "pause.fill"
            ) { viewModel.togglePause() },
            CallControlAction(
                id: "end",
                title: "end",
                systemImage: "xmark",
                isDestructive: true
            ) {
                print("[OfflineAssist] end")
                dismiss()
            }
        ]
    }

    private func dismissSurfaceCoachIfNeeded(planeCount: Int) {
        guard showSurfaceCoach, planeCount > 0 else { return }
        withAnimation(.easeOut(duration: 0.35)) {
            showSurfaceCoach = false
        }
        print("[OfflineAssist] surface coach dismissed — planes=\(planeCount)")
    }

    /// Capture ARView only — no SwiftUI chrome / toolbar / drawer.
    private func captureCleanScreenshot() {
        print("[OfflineAssist] screenshot requested (clean ARView)")
        viewModel.worldBridge?.captureCleanScreenshot { image in
            guard let image else { return }
            CallScreenshotCapture.saveToPhotos(image)
            withAnimation(.easeOut(duration: 0.12)) { flashScreenshot = true }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                withAnimation(.easeOut(duration: 0.2)) { flashScreenshot = false }
            }
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
    /// Expert-selected models shown in the bottom assets drawer (peek + expanded).
    var recentAssetItems: [AssetPlaceholderItem] = []
    /// Full catalog from `/api/models` — customer can pick and place locally.
    var catalogItems: [AssetPlaceholderItem] = []
    var selectedModelId: String?
    /// When true (live call), customer model actions sync to the web expert.
    var syncModelWireToPeer = false
    var catalogLoadError: String?

    /// Called when a remote model finishes loading on this device.
    func registerModelLoaded(id: String, name: String) {
        let url = catalogItems.first(where: { $0.id == id })?.modelURL
        let item = AssetPlaceholderItem(
            id: id,
            title: name,
            systemImage: "cube.fill",
            modelURL: url,
            thumbnailURL: nil
        )
        recentAssetItems.removeAll { $0.id == id }
        recentAssetItems.insert(item, at: 0)
        if recentAssetItems.count > 6 {
            recentAssetItems = Array(recentAssetItems.prefix(6))
        }
        selectedModelId = id
        print("[OfflineAssist] recent assets updated id=\(id) count=\(recentAssetItems.count)")
    }

    /// Pull USDZ catalog from Assist AR API (same list the expert sidebar uses).
    func fetchModelCatalog() async {
        do {
            let models = try await ModelCatalogService.fetchCatalog()
            catalogItems = models.compactMap { model in
                guard let url = model.assetURL else { return nil }
                return AssetPlaceholderItem(
                    id: model.id,
                    title: model.name,
                    systemImage: "cube.fill",
                    modelURL: url,
                    thumbnailURL: model.thumbnailUrl
                )
            }
            catalogLoadError = nil
            print("[OfflineAssist] catalog loaded count=\(catalogItems.count)")
        } catch {
            catalogLoadError = error.localizedDescription
            print("[OfflineAssist] catalog FAILED: \(error.localizedDescription)")
        }
    }

    /// Customer picks a model from the assets drawer — load + switch to Model tool.
    func selectCatalogModel(_ item: AssetPlaceholderItem) {
        guard let url = item.modelURL else {
            print("[OfflineAssist] catalog item missing url id=\(item.id)")
            return
        }
        selectedModelId = item.id
        selectedTool = .model
        lastContentTool = .model
        worldBridge?.requestLoadModel(
            modelId: item.id,
            url: url,
            name: item.title,
            emitWire: syncModelWireToPeer
        )
        print("[OfflineAssist] customer selected model id=\(item.id)")
    }

    func selectTool(_ tool: AnnotationTool) {
        print("[OfflineAssist] tool=\(tool.rawValue)")
        if tool == .undo {
            worldBridge?.undo()
            annotationCount = worldBridge?.annotationCount ?? 0
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

// MARK: - Role colors (TeamViewer-style)

/// Expert orange / customer blue — shared by offline + live + web expert wire.
enum AnnotationRole: String {
    case customer
    case expert

    /// Solid mark color (arrows / badges).
    var markerUIColor: UIColor {
        switch self {
        case .expert:
            return UIColor(red: 1.0, green: 0.48, blue: 0.0, alpha: 1.0)
        case .customer:
            return UIColor(red: 0.15, green: 0.55, blue: 0.95, alpha: 1.0)
        }
    }

    /// Freehand ribbon color.
    var inkUIColor: UIColor {
        switch self {
        case .expert:
            return UIColor(red: 1.0, green: 0.72, blue: 0.12, alpha: 0.95)
        case .customer:
            return UIColor(red: 0.25, green: 0.65, blue: 1.0, alpha: 0.95)
        }
    }

    static func parse(_ raw: String?) -> AnnotationRole? {
        guard let raw else { return nil }
        return AnnotationRole(rawValue: raw)
    }
}

// MARK: - World bridge

/// Freehand = per-hit world anchors (same path as arrows) — locked 2026-07-24.
/// Arrow = flat unlit mark perpendicular to surface.
/// Pointer = screen-space UI only.
@MainActor
final class OfflineWorldBridge: NSObject {
    weak var arView: ARView?
    /// Live-call wire: normalized screen events for the web expert overlay.
    var onWireEvent: (([String: Any]) -> Void)?
    /// Who owns local gestures on this device (customer phone = `.customer`).
    var localRole: AnnotationRole = .customer

    /// One undo/clear unit — an arrow is a single anchor; a stroke is many anchors.
    private struct MarkerGroup {
        var anchors: [AnchorEntity]

        @MainActor
        func removeFromParent() {
            anchors.forEach { $0.removeFromParent() }
        }
    }

    /// In-progress freehand (local customer vs remote expert must not share draft state).
    private final class StrokeDraft {
        var anchors: [AnchorEntity] = []
        var planeID: UUID?
        var planeTransform: simd_float4x4?
        var worldPoints: [SIMD3<Float>] = []
        var strokeId: String?
        var role: AnnotationRole = .customer

        func discard() {
            anchors.forEach { $0.removeFromParent() }
            anchors = []
            planeID = nil
            planeTransform = nil
            worldPoints = []
            strokeId = nil
        }
    }

    private var markers: [MarkerGroup] = []
    private let localDraft = StrokeDraft()
    private let remoteDraft = StrokeDraft()
    private var nextArrowNumber = 0

    // MARK: 3D model (expert → customer)

    /// Cached USDZ template after download + RealityKit load.
    private var modelTemplate: Entity?
    private var loadedModelId: String?
    private var loadedModelName: String?
    private var modelLoadTask: Task<Void, Never>?
    /// Avoid cancelling an in-flight load when expert sends duplicate load_model.
    private var loadingModelId: String?
    /// UI refresh when expert sends load_model / place_model.
    var onModelCatalogChanged: ((String, String) -> Void)?
    /// World anchor for the single active placed model.
    private var modelAnchor: AnchorEntity?
    private var modelRotationX: Float = 0
    private var modelRotationY: Float = 0
    private var modelScale: Float = 0.12
    /// Tap-to-place can beat async GLB load — hold until template is ready.
    private var pendingPlaceScreenPoint: CGPoint?
    /// Default AR scale — normalized models are ~1 m; shrink for tabletop use.
    private let defaultModelScale: Float = 0.15

    /// Slightly flat ribbon ink — wider than tall, still easy to see (no visible joint beads).
    private let highlighterWidth: Float = 0.0038
    private let highlighterHeight: Float = 0.0018
    private let highlighterSampleSpacing: Float = 0.01
    private let maxStrokePoints = 48
    /// Slight segment overlap so joints look continuous without bead spheres.
    private let segmentOverlap: Float = 1.12
    /// Sit just above the plane along its normal (matches arrow tip offset).
    private let surfaceLift: Float = 0.002
    /// Reject samples that leave the locked plane (meters) when plane ID is missing.
    private let planeStickTolerance: Float = 0.03

    var annotationCount: Int { markers.count }

    /// Pause AR + clear delegate so frames and FigCapture are released on leave.
    func teardownARSession() {
        guard let arView else { return }
        arView.session.pause()
        arView.session.delegate = nil
        print("[OfflineAssist] teardown — session paused, delegate cleared")
    }

    /// RealityKit snapshot of the AR view only (no SwiftUI overlays).
    func captureCleanScreenshot(completion: @escaping (UIImage?) -> Void) {
        guard let arView else {
            print("[OfflineAssist] screenshot miss — no ARView")
            completion(nil)
            return
        }
        arView.snapshot(saveToHDR: false) { image in
            DispatchQueue.main.async {
                if image != nil {
                    print("[OfflineAssist] ARView snapshot ready")
                } else {
                    print("[OfflineAssist] ARView snapshot failed")
                }
                completion(image)
            }
        }
    }

    func undo() {
        undo(emitWire: true)
    }

    func clearAll() {
        clearAll(emitWire: true)
    }

    // MARK: Remote wire (web expert → customer AR)

    /// Apply expert (or peer) normalized events into this customer's AR space.
    /// Does not re-broadcast — POV video already shows the result to the expert.
    func applyRemoteWireEvent(_ msg: [String: Any]) {
        let type = msg["type"] as? String ?? ""
        let role = AnnotationRole.parse(msg["role"] as? String) ?? .expert
        print("[OfflineAssist] remote wire type=\(type) role=\(role.rawValue)")

        switch type {
        case "arrow":
            guard let x = Self.jsonDouble(msg["x"]), let y = Self.jsonDouble(msg["y"]) else { return }
            placeArrow(at: screenPoint(nx: x, ny: y), role: role, emitWire: false)
        case "stroke_begin":
            guard let x = Self.jsonDouble(msg["x"]), let y = Self.jsonDouble(msg["y"]) else { return }
            let id = msg["id"] as? String
            beginHighlighter(
                at: screenPoint(nx: x, ny: y),
                role: role,
                strokeId: id,
                draft: remoteDraft,
                emitWire: false
            )
        case "stroke_point":
            guard let x = Self.jsonDouble(msg["x"]), let y = Self.jsonDouble(msg["y"]) else { return }
            moveHighlighter(
                to: screenPoint(nx: x, ny: y),
                draft: remoteDraft,
                emitWire: false
            )
        case "stroke_end":
            endHighlighter(draft: remoteDraft, emitWire: false)
        case "stroke_cancel":
            remoteDraft.discard()
        case "undo":
            undo(emitWire: false)
        case "clear":
            clearAll(emitWire: false)
        case "load_model":
            guard let modelId = msg["modelId"] as? String,
                  let urlString = msg["url"] as? String,
                  let url = URL(string: urlString)
            else { return }
            let name = msg["name"] as? String ?? modelId
            // Expert-originated loads should not re-broadcast to the wire.
            loadModel(modelId: modelId, url: url, name: name)
        case "place_model":
            guard let x = Self.jsonDouble(msg["x"]), let y = Self.jsonDouble(msg["y"]) else { return }
            enqueueOrPlaceModel(at: screenPoint(nx: x, ny: y))
        case "transform":
            let rotX = Float(Self.jsonDouble(msg["rotationX"]) ?? 0)
            let rotY = Float(Self.jsonDouble(msg["rotationY"]) ?? 0)
            let scale = Float(Self.jsonDouble(msg["scale"]) ?? Double(defaultModelScale))
            applyModelTransform(rotationX: rotX, rotationY: rotY, scale: scale)
        case "remove_model":
            removePlacedModel()
        default:
            print("[OfflineAssist] remote wire ignored type=\(type)")
        }
    }

    private static func jsonDouble(_ value: Any?) -> Double? {
        if let d = value as? Double { return d }
        if let n = value as? NSNumber { return n.doubleValue }
        if let i = value as? Int { return Double(i) }
        return nil
    }

    private func screenPoint(nx: Double, ny: Double) -> CGPoint {
        guard let arView else { return .zero }
        let w = max(arView.bounds.width, 1)
        let h = max(arView.bounds.height, 1)
        return CGPoint(
            x: CGFloat(min(max(nx, 0), 1)) * w,
            y: CGFloat(min(max(ny, 0), 1)) * h
        )
    }

    private func undo(emitWire: Bool) {
        guard let last = markers.popLast() else { return }
        last.removeFromParent()
        if emitWire {
            onWireEvent?([
                "type": "undo",
                "role": localRole.rawValue
            ])
        }
        print("[OfflineAssist] undo count=\(markers.count)")
    }

    private func clearAll(emitWire: Bool) {
        markers.forEach { $0.removeFromParent() }
        markers.removeAll()
        localDraft.discard()
        remoteDraft.discard()
        nextArrowNumber = 0
        if emitWire {
            onWireEvent?([
                "type": "clear",
                "role": localRole.rawValue
            ])
        }
        print("[OfflineAssist] cleared all markers")
    }

    // MARK: 3D model load / place / transform

    /// Customer or expert — optionally mirror load_model on the LiveKit wire.
    func requestLoadModel(modelId: String, url: URL, name: String, emitWire: Bool) {
        if emitWire {
            onWireEvent?([
                "type": "load_model",
                "modelId": modelId,
                "url": url.absoluteString,
                "name": name,
                "role": localRole.rawValue
            ])
        }
        loadModel(modelId: modelId, url: url, name: name)
    }

    /// Tap-to-place for customer Model tool — mirrors expert place_model wire when live.
    func requestPlaceModel(at screen: CGPoint, emitWire: Bool) {
        guard modelTemplate != nil else {
            print("[OfflineAssist] model place miss — select a model from assets first")
            return
        }
        if emitWire {
            let n = normalizedScreen(screen)
            onWireEvent?([
                "type": "place_model",
                "x": n.x,
                "y": n.y,
                "role": localRole.rawValue
            ])
            onWireEvent?([
                "type": "transform",
                "rotationX": Double(modelRotationX),
                "rotationY": Double(modelRotationY),
                "scale": Double(modelScale),
                "role": localRole.rawValue
            ])
        }
        placeModel(at: screen)
    }

    /// Download USDZ (if needed) and load into RealityKit on the main actor.
    private func loadModel(modelId: String, url: URL, name: String) {
        // Same model already loaded — skip redundant download/parse.
        if loadedModelId == modelId, modelTemplate != nil {
            print("[OfflineAssist] model already loaded id=\(modelId)")
            onModelCatalogChanged?(modelId, name)
            emitModelLoadStatus(modelId: modelId, name: name, ok: true)
            flushPendingPlaceIfNeeded()
            return
        }

        // Expert UI can fire load_model twice — do not cancel the first parse.
        if loadingModelId == modelId {
            print("[OfflineAssist] model load already in progress id=\(modelId)")
            return
        }

        modelLoadTask?.cancel()
        loadingModelId = modelId
        modelLoadTask = Task { @MainActor in
            defer { loadingModelId = nil }
            do {
                let localURL = try await Self.cachedModelURL(modelId: modelId, remoteURL: url)
                let entity = try await Self.loadEntity(from: localURL)
                guard !Task.isCancelled else { return }
                let normalized = Self.normalizeModelForAR(entity)
                modelTemplate = normalized
                loadedModelId = modelId
                loadedModelName = name
                modelScale = defaultModelScale
                modelRotationX = 0
                modelRotationY = 0
                let bounds = normalized.visualBounds(relativeTo: nil)
                print(
                    "[OfflineAssist] model loaded id=\(modelId) name=\(name) "
                        + "extents=\(bounds.extents)"
                )
                onModelCatalogChanged?(modelId, name)
                emitModelLoadStatus(modelId: modelId, name: name, ok: true)
                flushPendingPlaceIfNeeded()
            } catch is CancellationError {
                print("[OfflineAssist] model load cancelled id=\(modelId)")
            } catch {
                print("[OfflineAssist] model load FAILED id=\(modelId) error=\(error.localizedDescription)")
                let cacheExt = url.pathExtension.isEmpty ? "usdz" : url.pathExtension.lowercased()
                let cacheDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                    .appendingPathComponent("models", isDirectory: true)
                let cacheURL = cacheDir.appendingPathComponent("\(modelId).\(cacheExt)")
                Self.invalidateCachedModel(at: cacheURL, modelId: modelId)
                modelTemplate = nil
                loadedModelId = nil
                loadedModelName = nil
                pendingPlaceScreenPoint = nil
                emitModelLoadStatus(
                    modelId: modelId,
                    name: name,
                    ok: false,
                    error: error.localizedDescription
                )
            }
        }
    }

    /// Tell the web expert whether RealityKit accepted the model file.
    private func emitModelLoadStatus(modelId: String, name: String, ok: Bool, error: String? = nil) {
        var payload: [String: Any] = [
            "type": "model_load_status",
            "modelId": modelId,
            "name": name,
            "ok": ok,
            "role": localRole.rawValue
        ]
        if let error {
            payload["error"] = error
        }
        onWireEvent?(payload)
    }

    /// If place arrived before load finished, run it now.
    private func flushPendingPlaceIfNeeded() {
        guard let pending = pendingPlaceScreenPoint else { return }
        pendingPlaceScreenPoint = nil
        print("[OfflineAssist] applying queued place_model")
        placeModel(at: pending)
    }

    /// Queue placement until RealityKit template exists (load_model is async).
    private func enqueueOrPlaceModel(at screen: CGPoint) {
        guard modelTemplate != nil else {
            pendingPlaceScreenPoint = screen
            print("[OfflineAssist] place_model queued — template still loading")
            return
        }
        placeModel(at: screen)
    }

    /// Raycast tap → world anchor with cloned model template.
    private func placeModel(at screen: CGPoint) {
        guard let arView, let template = modelTemplate else {
            print("[OfflineAssist] place_model miss — no template loaded")
            return
        }
        guard let hit = hitResult(screen) else {
            print("[OfflineAssist] place_model miss — no surface")
            return
        }

        modelAnchor?.removeFromParent()
        modelAnchor = nil

        let anchor = AnchorEntity(world: hit.worldTransform)
        let instance = template.clone(recursive: true)
        instance.position = SIMD3<Float>(0, surfaceLift, 0)
        applyTransform(to: instance)
        anchor.addChild(instance)
        arView.scene.addAnchor(anchor)
        modelAnchor = anchor
        print("[OfflineAssist] model placed scale=\(modelScale)")
        if let loadedModelId, let loadedModelName {
            onWireEvent?([
                "type": "model_placed",
                "modelId": loadedModelId,
                "name": loadedModelName,
                "ok": true,
                "role": localRole.rawValue
            ])
        }
    }

    private func applyModelTransform(rotationX: Float, rotationY: Float, scale: Float) {
        modelRotationX = rotationX
        modelRotationY = rotationY
        modelScale = max(0.01, scale)
        guard let anchor = modelAnchor, let child = anchor.children.first else { return }
        applyTransform(to: child)
        print("[OfflineAssist] model transform rotX=\(rotationX) rotY=\(rotationY) scale=\(modelScale)")
    }

    private func applyTransform(to entity: Entity) {
        let rotX = simd_quatf(angle: modelRotationX * .pi / 180, axis: SIMD3<Float>(1, 0, 0))
        let rotY = simd_quatf(angle: modelRotationY * .pi / 180, axis: SIMD3<Float>(0, 1, 0))
        entity.orientation = rotY * rotX
        entity.scale = SIMD3(repeating: modelScale)
    }

    private func removePlacedModel() {
        modelAnchor?.removeFromParent()
        modelAnchor = nil
        print("[OfflineAssist] model removed")
    }

    /// Cache model under Documents/models/{id}.{ext} — RealityKit needs USDZ, not GLB.
    private static func cachedModelURL(modelId: String, remoteURL: URL) async throws -> URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("models", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        let ext = remoteURL.pathExtension.isEmpty ? "usdz" : remoteURL.pathExtension.lowercased()
        let local = dir.appendingPathComponent("\(modelId).\(ext)")

        // Drop stale GLB cache when manifest now points at USDZ.
        let staleGLB = dir.appendingPathComponent("\(modelId).glb")
        if ext != "glb", FileManager.default.fileExists(atPath: staleGLB.path) {
            try? FileManager.default.removeItem(at: staleGLB)
            print("[OfflineAssist] removed stale GLB cache id=\(modelId)")
        }

        if FileManager.default.fileExists(atPath: local.path) {
            if isValidModelFile(at: local) {
                print("[OfflineAssist] model cache hit id=\(modelId) ext=\(ext)")
                return local
            }
            invalidateCachedModel(at: local, modelId: modelId)
        }

        print("[OfflineAssist] model downloading id=\(modelId) url=\(remoteURL.absoluteString)")
        let (data, response) = try await URLSession.shared.data(from: remoteURL)
        if let http = response as? HTTPURLResponse, http.statusCode >= 400 {
            throw URLError(.badServerResponse)
        }
        guard isValidModelPayload(data, ext: ext) else {
            throw URLError(.cannotDecodeContentData)
        }
        try data.write(to: local, options: .atomic)
        print("[OfflineAssist] model cached id=\(modelId) bytes=\(data.count) ext=\(ext)")
        return local
    }

    /// RealityKit rejects bad/corrupt downloads — drop cache so the next load re-fetches.
    private static func invalidateCachedModel(at url: URL, modelId: String) {
        try? FileManager.default.removeItem(at: url)
        print("[OfflineAssist] invalidated model cache id=\(modelId)")
    }

    /// Quick magic-byte check before trusting a cached download.
    private static func isValidModelFile(at url: URL) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? handle.close() }
        guard let prefix = try? handle.read(upToCount: 4), prefix.count >= 4 else { return false }
        return isValidModelPayload(prefix, ext: url.pathExtension.lowercased())
    }

    private static func isValidModelPayload(_ data: Data, ext: String) -> Bool {
        guard data.count >= 4 else { return false }
        switch ext {
        case "usdz":
            // USDZ is a zip archive (PK..).
            return data.starts(with: [0x50, 0x4B])
        case "usdc", "usd":
            return data.starts(with: [0x50, 0x58, 0x52, 0x2D]) // PXR-
        case "reality":
            return true
        case "glb":
            // GLB is valid glTF but RealityKit cannot import it.
            return data.starts(with: [0x67, 0x6C, 0x54, 0x46])
        default:
            return false
        }
    }

    /// RealityKit async USDZ load (iOS 17 — loadAsync + continuation).
    private static func loadEntity(from url: URL) async throws -> Entity {
        // Prefer modern API when available (iOS 18+).
        if #available(iOS 18.0, *) {
            return try await Entity(contentsOf: url)
        }

        return try await withCheckedThrowingContinuation { continuation in
            var resumed = false
            var subscription: AnyCancellable?
            subscription = Entity.loadAsync(contentsOf: url).sink(
                receiveCompletion: { completion in
                    if case .failure(let error) = completion, !resumed {
                        resumed = true
                        continuation.resume(throwing: error)
                    }
                    subscription?.cancel()
                },
                receiveValue: { entity in
                    guard !resumed else { return }
                    resumed = true
                    continuation.resume(returning: entity)
                    subscription?.cancel()
                }
            )
        }
    }

    /// Fit arbitrary catalog models to tabletop AR — center XZ, rest on Y=0, ~1 m tall base.
    private static func normalizeModelForAR(_ entity: Entity) -> Entity {
        let bounds = entity.visualBounds(relativeTo: nil)
        let ext = bounds.extents
        let maxDim = max(ext.x, max(ext.y, ext.z))
        guard maxDim > 0.0001 else {
            print("[OfflineAssist] model normalize skipped — empty bounds")
            return entity
        }

        // Scale so the tallest axis is ~1 m before expert/user scale is applied.
        let fit = 1.0 / maxDim
        entity.scale *= SIMD3(repeating: fit)

        let fitted = entity.visualBounds(relativeTo: nil)
        let center = fitted.center
        entity.position = SIMD3(
            -center.x,
            -fitted.min.y,
            -center.z
        )

        print(
            "[OfflineAssist] model normalized rawMax=\(maxDim) fit=\(fit) "
                + "finalExtents=\(entity.visualBounds(relativeTo: nil).extents)"
        )
        return entity
    }

    // MARK: Arrow — few unlit entities, surface-normal

    func placeArrow(at screen: CGPoint) {
        placeArrow(at: screen, role: localRole, emitWire: true)
    }

    private func placeArrow(at screen: CGPoint, role: AnnotationRole, emitWire: Bool) {
        guard let arView, let hit = hitResult(screen) else {
            print("[OfflineAssist] arrow miss — keep scanning surfaces role=\(role.rawValue)")
            return
        }
        nextArrowNumber += 1
        let number = nextArrowNumber

        let anchor = AnchorEntity(world: hit.worldTransform)
        let content = makeSurfaceNormalArrow(number: number, color: role.markerUIColor)
        content.position = SIMD3<Float>(0, surfaceLift, 0)
        anchor.addChild(content)
        arView.scene.addAnchor(anchor)
        markers.append(MarkerGroup(anchors: [anchor]))

        if emitWire {
            let n = normalizedScreen(screen)
            onWireEvent?([
                "type": "arrow",
                "x": n.x,
                "y": n.y,
                "number": number,
                "role": role.rawValue
            ])
        }
        print("[OfflineAssist] placed surface-normal arrow #\(number) role=\(role.rawValue)")
    }

    // MARK: Highlighter — per-hit world (locked 2026-07-24)

    /// Samples still come from `ARView.raycast` (same as arrows). Visible ink is
    /// overlapping segment boxes only — joint beads stay hidden for a clean stroke.
    func beginHighlighter(at screen: CGPoint) {
        beginHighlighter(
            at: screen,
            role: localRole,
            strokeId: nil,
            draft: localDraft,
            emitWire: true
        )
    }

    func moveHighlighter(to screen: CGPoint) {
        moveHighlighter(to: screen, draft: localDraft, emitWire: true)
    }

    func endHighlighter() {
        endHighlighter(draft: localDraft, emitWire: true)
    }

    private func beginHighlighter(
        at screen: CGPoint,
        role: AnnotationRole,
        strokeId: String?,
        draft: StrokeDraft,
        emitWire: Bool
    ) {
        draft.discard()
        guard let hit = inkHit(screen, locking: true, draft: draft) else {
            print("[OfflineAssist] highlighter begin miss role=\(role.rawValue)")
            return
        }

        draft.role = role
        draft.planeID = (hit.anchor as? ARPlaneAnchor)?.identifier
        draft.planeTransform = hit.worldTransform
        draft.worldPoints = [worldPosition(hit)]
        draft.strokeId = strokeId ?? UUID().uuidString
        let n = normalizedScreen(screen)
        if emitWire, let id = draft.strokeId {
            onWireEvent?([
                "type": "stroke_begin",
                "id": id,
                "x": n.x,
                "y": n.y,
                "role": role.rawValue
            ])
        }
        print("[OfflineAssist] highlighter locked planeID=\(draft.planeID?.uuidString.prefix(8) ?? "est") role=\(role.rawValue)")
    }

    private func moveHighlighter(to screen: CGPoint, draft: StrokeDraft, emitWire: Bool) {
        guard draft.worldPoints.count < maxStrokePoints,
              let last = draft.worldPoints.last,
              let arView,
              let hit = inkHit(screen, locking: false, draft: draft)
        else { return }

        let point = worldPosition(hit)
        guard length(point - last) >= highlighterSampleSpacing else { return }

        draft.worldPoints.append(point)

        // Segment only — no joint bead spheres (keeps stroke looking clean).
        if let segment = makeInkSegmentAnchor(from: last, to: point, draft: draft) {
            arView.scene.addAnchor(segment)
            draft.anchors.append(segment)
        }

        let n = normalizedScreen(screen)
        if emitWire, let id = draft.strokeId {
            onWireEvent?([
                "type": "stroke_point",
                "id": id,
                "x": n.x,
                "y": n.y,
                "role": draft.role.rawValue
            ])
        }
    }

    private func endHighlighter(draft: StrokeDraft, emitWire: Bool) {
        if draft.worldPoints.count < 2 || draft.anchors.isEmpty {
            if emitWire, let id = draft.strokeId {
                onWireEvent?([
                    "type": "stroke_cancel",
                    "id": id,
                    "role": draft.role.rawValue
                ])
            }
            draft.discard()
            return
        }
        markers.append(MarkerGroup(anchors: draft.anchors))
        if emitWire, let id = draft.strokeId {
            onWireEvent?([
                "type": "stroke_end",
                "id": id,
                "role": draft.role.rawValue
            ])
        }
        print("[OfflineAssist] ink stroke points=\(draft.worldPoints.count) anchors=\(draft.anchors.count) role=\(draft.role.rawValue)")
        draft.anchors = []
        draft.planeID = nil
        draft.planeTransform = nil
        draft.worldPoints = []
        draft.strokeId = nil
    }

    /// Screen point normalized to ARView bounds (0…1) for the expert overlay.
    private func normalizedScreen(_ screen: CGPoint) -> (x: Double, y: Double) {
        guard let arView else { return (0, 0) }
        let w = max(arView.bounds.width, 1)
        let h = max(arView.bounds.height, 1)
        return (
            Double(min(max(screen.x / w, 0), 1)),
            Double(min(max(screen.y / h, 0), 1))
        )
    }

    private var lastPointerPublishTime: CFTimeInterval = 0

    func publishPointer(at screen: CGPoint) {
        let now = CACurrentMediaTime()
        guard now - lastPointerPublishTime >= (1.0 / 20.0) else { return }
        lastPointerPublishTime = now
        let n = normalizedScreen(screen)
        onWireEvent?([
            "type": "pointer",
            "x": n.x,
            "y": n.y,
            "role": localRole.rawValue
        ])
    }

    /// Segment between two world points — own AnchorEntity at lifted midpoint.
    private func makeInkSegmentAnchor(
        from: SIMD3<Float>,
        to: SIMD3<Float>,
        draft: StrokeDraft
    ) -> AnchorEntity? {
        let delta = to - from
        let dist = length(delta)
        guard dist > 0.0005 else { return nil }

        let mid = (from + to) / 2
        let liftedMid = mid + lockedPlaneNormal(draft) * surfaceLift
        var transform = matrix_identity_float4x4
        transform.columns.3 = SIMD4(liftedMid.x, liftedMid.y, liftedMid.z, 1)

        let anchor = AnchorEntity(world: transform)
        // Flat ribbon: wide × thin × length (height is off-plane thickness).
        let mesh = MeshResource.generateBox(
            width: highlighterWidth,
            height: highlighterHeight,
            depth: dist * segmentOverlap
        )
        let entity = ModelEntity(
            mesh: mesh,
            materials: [UnlitMaterial(color: draft.role.inkUIColor)]
        )
        // Align box -Z with stroke. Avoid look(at: direction) — that API expects a point.
        entity.orientation = orientationAligningNegativeZ(to: normalize(delta))
        anchor.addChild(entity)
        return anchor
    }

    private func lockedPlaneNormal(_ draft: StrokeDraft) -> SIMD3<Float> {
        guard let plane = draft.planeTransform else {
            return SIMD3<Float>(0, 1, 0)
        }
        return normalize(SIMD3<Float>(
            plane.columns.1.x,
            plane.columns.1.y,
            plane.columns.1.z
        ))
    }

    /// Quaternion that rotates the box’s default forward (-Z) onto `direction`.
    private func orientationAligningNegativeZ(to direction: SIMD3<Float>) -> simd_quatf {
        let forward = normalize(direction)
        let defaultForward = SIMD3<Float>(0, 0, -1)
        let d = simd_dot(defaultForward, forward)
        if d > 0.9999 {
            return simd_quatf(ix: 0, iy: 0, iz: 0, r: 1)
        }
        if d < -0.9999 {
            return simd_quatf(angle: .pi, axis: SIMD3<Float>(0, 1, 0))
        }
        return simd_quatf(from: defaultForward, to: forward)
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
    private func inkHit(_ screen: CGPoint, locking: Bool, draft: StrokeDraft) -> ARRaycastResult? {
        guard let arView else { return nil }

        let geo = arView.raycast(from: screen, allowing: .existingPlaneGeometry, alignment: .any)
        let inf = arView.raycast(from: screen, allowing: .existingPlaneInfinite, alignment: .any)
        let est = arView.raycast(from: screen, allowing: .estimatedPlane, alignment: .any)
        let ordered = geo + inf + est

        if locking {
            return ordered.first
        }

        if let planeID = draft.planeID {
            // Stay on the locked ARPlaneAnchor only (accepted freehand behavior).
            if let match = ordered.first(where: { ($0.anchor as? ARPlaneAnchor)?.identifier == planeID }) {
                return match
            }
            return nil
        }

        guard let hit = ordered.first, isNearLockedPlane(hit, draft: draft) else { return nil }
        return hit
    }

    private func isNearLockedPlane(_ hit: ARRaycastResult, draft: StrokeDraft) -> Bool {
        guard let plane = draft.planeTransform else { return true }
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

// MARK: - Customer POV composite → LiveKit

/// What to publish to the expert.
enum ARLiveStreamMode: Equatable {
    /// Offline / no stream.
    case off
    /// True customer POV: ARView snapshot (camera + RealityKit annotations), already upright.
    case customerPOV
}

/// Encodes an ARView snapshot into a LiveKit BGRA buffer sized for the published track.
enum ARCompositeFrameEncoder {
    /// Target matches `LiveKitManager` BufferCaptureOptions (portrait 540x960).
    static let targetWidth = 540
    static let targetHeight = 960

    static func pixelBuffer(from image: UIImage) -> CVPixelBuffer? {
        let srcSize = image.size
        guard srcSize.width > 1, srcSize.height > 1 else { return nil }

        let width = targetWidth
        let height = targetHeight
        let target = CGSize(width: width, height: height)

        // Aspect-fill into fixed canvas so LiveKit dimensions stay stable frame-to-frame.
        let fill = max(target.width / srcSize.width, target.height / srcSize.height)
        let drawW = srcSize.width * fill
        let drawH = srcSize.height * fill
        let drawRect = CGRect(
            x: (target.width - drawW) / 2,
            y: (target.height - drawH) / 2,
            width: drawW,
            height: drawH
        )

        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: target, format: format)
        let rendered = renderer.image { ctx in
            UIColor.black.setFill()
            ctx.fill(CGRect(origin: .zero, size: target))
            image.draw(in: drawRect)
        }
        guard let cgImage = rendered.cgImage else { return nil }

        var pixelBuffer: CVPixelBuffer?
        let attrs: [String: Any] = [
            kCVPixelBufferCGImageCompatibilityKey as String: true,
            kCVPixelBufferCGBitmapContextCompatibilityKey as String: true,
            kCVPixelBufferIOSurfacePropertiesKey as String: [:]
        ]
        let status = CVPixelBufferCreate(
            kCFAllocatorDefault,
            width,
            height,
            kCVPixelFormatType_32BGRA,
            attrs as CFDictionary,
            &pixelBuffer
        )
        guard status == kCVReturnSuccess, let pixelBuffer else { return nil }

        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, []) }

        guard let context = CGContext(
            data: CVPixelBufferGetBaseAddress(pixelBuffer),
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: CVPixelBufferGetBytesPerRow(pixelBuffer),
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
        ) else {
            return nil
        }

        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
        return pixelBuffer
    }
}

// MARK: - AR container

struct OfflineARContainer: UIViewRepresentable {
    @Bindable var viewModel: OfflineAssistViewModel
    /// Live call: publish true customer POV (ARView + annotations).
    var streamMode: ARLiveStreamMode = .off
    /// Optional LiveKit sink — pixel buffer + rotation degrees (0 for upright POV composites).
    var onCameraFrame: ((CVPixelBuffer, Int) -> Void)? = nil
    /// Optional annotation wire (unused for POV video; kept for future sync).
    var onAnnotationEvent: (([String: Any]) -> Void)? = nil

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
        // Phone-side gestures are always the customer role (blue).
        bridge.localRole = .customer
        bridge.onModelCatalogChanged = { [weak viewModel] id, name in
            viewModel?.registerModelLoaded(id: id, name: name)
        }
        context.coordinator.bridge = bridge
        context.coordinator.arView = arView
        context.coordinator.applyStreaming(mode: streamMode, sink: onCameraFrame)
        context.coordinator.applyAnnotationSink(onAnnotationEvent)
        viewModel.worldBridge = bridge

        return arView
    }

    func updateUIView(_ uiView: ARView, context: Context) {
        context.coordinator.applyPause(viewModel.isVideoPaused, on: uiView)
        context.coordinator.applyStreaming(mode: streamMode, sink: onCameraFrame)
        context.coordinator.applyAnnotationSink(onAnnotationEvent)
        viewModel.worldBridge = context.coordinator.bridge
    }

    static func dismantleUIView(_ uiView: ARView, coordinator: Coordinator) {
        uiView.session.pause()
        uiView.session.delegate = nil
        coordinator.applyStreaming(mode: .off, sink: nil)
        coordinator.applyAnnotationSink(nil)
        coordinator.bridge = nil
        coordinator.arView = nil
        print("[OfflineAssist] dismantleUIView — session paused")
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(viewModel: viewModel)
    }

    /// @MainActor so UIKit taps/pans call the bridge synchronously (no Task reorder on draw).
    /// ARSession callbacks stay `nonisolated` and only hop scalar state — never ARFrames.
    @MainActor
    final class Coordinator: NSObject, ARSessionDelegate {
        let viewModel: OfflineAssistViewModel
        weak var arView: ARView?
        var bridge: OfflineWorldBridge?
        private var streamMode: ARLiveStreamMode = .off
        private var frameSink: ((CVPixelBuffer, Int) -> Void)?
        private var planeIds = Set<UUID>()
        private var wasPaused = false
        private var lastReportedPlaneCount = -1
        private var lastTrackingNormal: Bool?
        private var povTimer: Timer?
        private var isSnapshotInFlight = false

        init(viewModel: OfflineAssistViewModel) {
            self.viewModel = viewModel
        }

        func applyStreaming(mode: ARLiveStreamMode, sink: ((CVPixelBuffer, Int) -> Void)?) {
            streamMode = mode
            frameSink = sink
            // Do not run the POV timer while paused.
            if mode == .customerPOV, sink != nil, !viewModel.isVideoPaused {
                startPOVStreaming()
            } else {
                stopPOVStreaming()
            }
        }

        func applyAnnotationSink(_ sink: (([String: Any]) -> Void)?) {
            bridge?.onWireEvent = sink
        }

        /// Customer POV: ARView snapshot @15fps → fixed 540x960 BGRA for LiveKit.
        private func startPOVStreaming() {
            guard povTimer == nil else { return }
            let timer = Timer(timeInterval: 1.0 / 15.0, repeats: true) { [weak self] _ in
                Task { @MainActor in
                    self?.pushPOVFrame()
                }
            }
            RunLoop.main.add(timer, forMode: .common)
            povTimer = timer
            print("[OfflineAssist] customer POV stream started (ARView snapshot @15fps → 540x960)")
        }

        private func stopPOVStreaming() {
            povTimer?.invalidate()
            povTimer = nil
            isSnapshotInFlight = false
        }

        private func pushPOVFrame() {
            guard streamMode == .customerPOV, let sink = frameSink, let arView else { return }
            guard !viewModel.isVideoPaused, !isSnapshotInFlight else { return }
            // Skip until the view has a real size (avoids tiny/empty first snapshots).
            guard arView.bounds.width > 64, arView.bounds.height > 64 else { return }

            isSnapshotInFlight = true
            arView.snapshot(saveToHDR: false) { [weak self] image in
                guard let image else {
                    Task { @MainActor in self?.isSnapshotInFlight = false }
                    return
                }
                // Encode off the main thread so AR/UI stay responsive.
                DispatchQueue.global(qos: .userInitiated).async {
                    let buffer = ARCompositeFrameEncoder.pixelBuffer(from: image)
                    Task { @MainActor in
                        guard let self else { return }
                        self.isSnapshotInFlight = false
                        // Rotation 0 — snapshot is already the upright phone POV.
                        if let buffer {
                            sink(buffer, 0)
                        } else {
                            print("[OfflineAssist] POV encode failed — dropping frame")
                        }
                    }
                }
            }
        }

        func applyPause(_ paused: Bool, on arView: ARView) {
            if paused && !wasPaused {
                arView.session.pause()
                wasPaused = true
                stopPOVStreaming()
            } else if !paused && wasPaused {
                arView.session.run(OfflineARConfig.make())
                wasPaused = false
                if streamMode == .customerPOV, frameSink != nil {
                    startPOVStreaming()
                }
            }
        }

        @objc func handleTap(_ gesture: UITapGestureRecognizer) {
            guard let arView, !viewModel.isVideoPaused else { return }
            let point = gesture.location(in: arView)
            switch viewModel.selectedTool {
            case .arrow:
                bridge?.placeArrow(at: point)
                viewModel.annotationCount = bridge?.annotationCount ?? 0
            case .model:
                bridge?.requestPlaceModel(at: point, emitWire: viewModel.syncModelWireToPeer)
            default:
                break
            }
        }

        @objc func handlePan(_ gesture: UIPanGestureRecognizer) {
            guard let arView, !viewModel.isVideoPaused else { return }
            guard viewModel.selectedTool == .freehand else { return }
            let point = gesture.location(in: arView)
            switch gesture.state {
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

        /// Tracking updates without receiving `ARFrame` — fixes "retaining N ARFrames".
        nonisolated func session(_ session: ARSession, cameraDidChangeTrackingState camera: ARCamera) {
            let isNormal: Bool
            if case .normal = camera.trackingState {
                isNormal = true
            } else {
                isNormal = false
            }
            Task { @MainActor [weak self] in
                guard let self, self.lastTrackingNormal != isNormal else { return }
                self.lastTrackingNormal = isNormal
                self.viewModel.trackingNormal = isNormal
            }
        }

        // No raw `didUpdate frame` publish — that path has no RealityKit marks.
        // Customer POV uses `pushPOVFrame` snapshots instead.

        nonisolated func session(_ session: ARSession, didAdd anchors: [ARAnchor]) {
            let added = anchors.compactMap { ($0 as? ARPlaneAnchor)?.identifier }
            Task { @MainActor [weak self] in
                guard let self else { return }
                for id in added { self.planeIds.insert(id) }
                self.publishPlaneCountIfNeeded()
            }
        }

        nonisolated func session(_ session: ARSession, didRemove anchors: [ARAnchor]) {
            let removed = anchors.compactMap { ($0 as? ARPlaneAnchor)?.identifier }
            Task { @MainActor [weak self] in
                guard let self else { return }
                for id in removed { self.planeIds.remove(id) }
                self.publishPlaneCountIfNeeded()
            }
        }

        private func publishPlaneCountIfNeeded() {
            let count = planeIds.count
            guard count != lastReportedPlaneCount else { return }
            lastReportedPlaneCount = count
            viewModel.planeCount = count
        }
    }
}
