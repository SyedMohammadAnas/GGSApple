import SwiftUI
import RealityKit
import ARKit
import UIKit

/// Offline “Create video tutorial” — full Assist call chrome + annotation tools, no LiveKit.
struct OfflineAssistSessionView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel = OfflineAssistViewModel()
    @State private var showAssets = false

    var body: some View {
        ZStack {
            OfflineARContainer(viewModel: viewModel)
                .ignoresSafeArea()

            // Screen-space annotation overlay (offline tool testing).
            AnnotationCanvas(viewModel: viewModel)
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
            print("[OfflineAssist] opened — full chrome + tools (no LiveKit)")
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
                .foregroundStyle(.white)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(.black.opacity(0.45))
                .clipShape(Capsule())
            }

            Spacer()

            Text(viewModel.trackingLabel)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.white.opacity(0.8))
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(.black.opacity(0.4))
                .clipShape(Capsule())

            Button { showAssets = true } label: {
                Image(systemName: "circle.grid.2x2")
                    .foregroundStyle(.black)
                    .padding(10)
                    .background(.white.opacity(0.85))
                    .clipShape(Circle())
            }
        }
    }

    private var annotationToolbar: some View {
        VStack(spacing: 14) {
            ForEach(AnnotationTool.allCases) { tool in
                Button {
                    viewModel.selectTool(tool)
                } label: {
                    Image(systemName: tool.systemImage)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.white)
                        .frame(width: 40, height: 40)
                        .background(viewModel.selectedTool == tool ? Color.white.opacity(0.28) : Color.clear)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
            }
        }
        .padding(8)
        .background(.black.opacity(0.45))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var bottomChrome: some View {
        VStack(spacing: 10) {
            Capsule()
                .fill(Color.white.opacity(0.25))
                .frame(width: 36, height: 4)

            Text(viewModel.hintText)
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.55))
                .multilineTextAlignment(.center)

            HStack(spacing: 22) {
                callButton(
                    title: "speaker",
                    system: viewModel.isSpeakerOn ? "speaker.wave.2.fill" : "speaker.slash.fill",
                    active: viewModel.isSpeakerOn
                ) { viewModel.toggleSpeaker() }

                callButton(
                    title: "mute",
                    system: viewModel.isMuted ? "mic.slash.fill" : "mic.fill",
                    active: !viewModel.isMuted
                ) { viewModel.toggleMute() }

                callButton(
                    title: viewModel.isVideoPaused ? "resume" : "pause",
                    system: viewModel.isVideoPaused ? "play.fill" : "pause.fill",
                    active: !viewModel.isVideoPaused
                ) { viewModel.togglePause() }

                Button {
                    print("[OfflineAssist] end")
                    dismiss()
                } label: {
                    VStack(spacing: 4) {
                        Image(systemName: "xmark")
                            .font(.title3.weight(.bold))
                            .foregroundStyle(.white)
                            .frame(width: 56, height: 56)
                            .background(Color.red)
                            .clipShape(Circle())
                        Text("end")
                            .font(.caption2)
                            .foregroundStyle(.white.opacity(0.7))
                    }
                }
            }
        }
        .padding(.top, 10)
        .padding(.bottom, 8)
        .frame(maxWidth: .infinity)
        .background(.black.opacity(0.55))
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private func callButton(title: String, system: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: system)
                    .font(.title3)
                    .foregroundStyle(.white)
                    .frame(width: 56, height: 56)
                    .background(Color.white.opacity(active ? 0.22 : 0.12))
                    .clipShape(Circle())
                Text(title)
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.7))
            }
        }
    }
}

// MARK: - View model

@MainActor
@Observable
final class OfflineAssistViewModel {
    var selectedTool: AnnotationTool = .freehand
    var strokes: [OfflineStroke] = []
    var pointer: CGPoint?
    var isMuted = false
    var isSpeakerOn = true
    var isVideoPaused = false
    var planeCount = 0
    var trackingNormal = false

    /// Live stroke while dragging.
    var draftPoints: [CGPoint] = []

    var trackingLabel: String {
        if isVideoPaused { return "Paused" }
        if trackingNormal && planeCount > 0 { return "Tracking" }
        if trackingNormal { return "Scan surfaces" }
        return "Starting…"
    }

    var hintText: String {
        switch selectedTool {
        case .pointer: return "Drag to move the pointer"
        case .arrow: return "Drag to draw an arrow"
        case .freehand: return "Draw freehand on the scene"
        case .circle: return "Drag to place a circle"
        case .undo: return "Tap undo to remove last stroke"
        case .delete: return "Tap delete to clear all"
        }
    }

    func selectTool(_ tool: AnnotationTool) {
        selectedTool = tool
        print("[OfflineAssist] tool=\(tool.rawValue)")
        if tool == .undo {
            undo()
            selectedTool = .freehand
        } else if tool == .delete {
            clearAll()
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

    func beginStroke(at point: CGPoint) {
        guard selectedTool != .undo, selectedTool != .delete else { return }
        if selectedTool == .pointer {
            pointer = point
            return
        }
        draftPoints = [point]
    }

    func moveStroke(to point: CGPoint) {
        if selectedTool == .pointer {
            pointer = point
            return
        }
        guard !draftPoints.isEmpty else { return }
        draftPoints.append(point)
    }

    func endStroke() {
        if selectedTool == .pointer {
            // Keep pointer visible at last point.
            return
        }
        guard draftPoints.count >= 2 else {
            draftPoints = []
            return
        }

        let stroke = OfflineStroke(
            id: UUID(),
            tool: selectedTool,
            color: Color.red,
            points: draftPoints
        )
        strokes.append(stroke)
        print("[OfflineAssist] stroke tool=\(selectedTool.rawValue) points=\(draftPoints.count)")
        draftPoints = []
    }

    func undo() {
        if !strokes.isEmpty {
            strokes.removeLast()
            print("[OfflineAssist] undo count=\(strokes.count)")
        }
    }

    func clearAll() {
        strokes.removeAll()
        pointer = nil
        draftPoints = []
        print("[OfflineAssist] cleared annotations")
    }
}

struct OfflineStroke: Identifiable {
    let id: UUID
    let tool: AnnotationTool
    let color: Color
    let points: [CGPoint]
}

// MARK: - Annotation canvas

struct AnnotationCanvas: View {
    @Bindable var viewModel: OfflineAssistViewModel

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Canvas { context, size in
                    for stroke in viewModel.strokes {
                        draw(stroke: stroke, in: &context, size: size)
                    }
                    if viewModel.draftPoints.count >= 2 {
                        let draft = OfflineStroke(
                            id: UUID(),
                            tool: viewModel.selectedTool,
                            color: .red,
                            points: viewModel.draftPoints
                        )
                        draw(stroke: draft, in: &context, size: size)
                    }
                    if let p = viewModel.pointer {
                        var circle = Path()
                        circle.addEllipse(in: CGRect(x: p.x - 10, y: p.y - 10, width: 20, height: 20))
                        context.stroke(circle, with: .color(.cyan), lineWidth: 3)
                    }
                }
                .allowsHitTesting(false)

                Color.clear
                    .contentShape(Rectangle())
                    .gesture(
                        DragGesture(minimumDistance: 0)
                            .onChanged { value in
                                if viewModel.draftPoints.isEmpty && viewModel.selectedTool != .pointer {
                                    viewModel.beginStroke(at: value.location)
                                } else {
                                    viewModel.moveStroke(to: value.location)
                                }
                            }
                            .onEnded { _ in
                                viewModel.endStroke()
                            }
                    )
            }
        }
    }

    private func draw(stroke: OfflineStroke, in context: inout GraphicsContext, size: CGSize) {
        guard let first = stroke.points.first else { return }
        switch stroke.tool {
        case .freehand, .pointer:
            var path = Path()
            path.move(to: first)
            for p in stroke.points.dropFirst() {
                path.addLine(to: p)
            }
            context.stroke(path, with: .color(stroke.color), style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
        case .arrow:
            guard let last = stroke.points.last else { return }
            var path = Path()
            path.move(to: first)
            path.addLine(to: last)
            context.stroke(path, with: .color(stroke.color), lineWidth: 3)
            // Arrow head
            let angle = atan2(last.y - first.y, last.x - first.x)
            let len: CGFloat = 16
            let a1 = CGPoint(x: last.x - len * cos(angle - .pi / 6), y: last.y - len * sin(angle - .pi / 6))
            let a2 = CGPoint(x: last.x - len * cos(angle + .pi / 6), y: last.y - len * sin(angle + .pi / 6))
            var head = Path()
            head.move(to: last)
            head.addLine(to: a1)
            head.move(to: last)
            head.addLine(to: a2)
            context.stroke(head, with: .color(stroke.color), lineWidth: 3)
        case .circle:
            guard let last = stroke.points.last else { return }
            let r = hypot(last.x - first.x, last.y - first.y)
            var circle = Path()
            circle.addEllipse(in: CGRect(x: first.x - r, y: first.y - r, width: r * 2, height: r * 2))
            context.stroke(circle, with: .color(stroke.color), lineWidth: 3)
        case .undo, .delete:
            break
        }
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
        context.coordinator.arView = arView
        return arView
    }

    func updateUIView(_ uiView: ARView, context: Context) {
        context.coordinator.applyPause(viewModel.isVideoPaused, on: uiView)
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(viewModel: viewModel)
    }

    @MainActor
    final class Coordinator: NSObject, ARSessionDelegate {
        let viewModel: OfflineAssistViewModel
        weak var arView: ARView?
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

        func session(_ session: ARSession, didUpdate frame: ARFrame) {
            if case .normal = frame.camera.trackingState {
                viewModel.trackingNormal = true
            } else {
                viewModel.trackingNormal = false
            }
        }

        func session(_ session: ARSession, didAdd anchors: [ARAnchor]) {
            for anchor in anchors {
                if let plane = anchor as? ARPlaneAnchor {
                    planeIds.insert(plane.identifier)
                }
            }
            viewModel.planeCount = planeIds.count
        }

        func session(_ session: ARSession, didRemove anchors: [ARAnchor]) {
            for anchor in anchors {
                if let plane = anchor as? ARPlaneAnchor {
                    planeIds.remove(plane.identifier)
                }
            }
            viewModel.planeCount = planeIds.count
        }
    }
}
