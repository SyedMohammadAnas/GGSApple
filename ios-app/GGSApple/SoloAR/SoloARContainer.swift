import SwiftUI
import RealityKit
import ARKit
import UIKit

/// Full-screen offline Solo AR Assistant.
struct SoloARView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel = SoloARViewModel()

    var body: some View {
        ZStack {
            SoloARContainer(viewModel: viewModel)
                .ignoresSafeArea()

            VStack {
                topBar
                Spacer()
                bottomBar
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .preferredColorScheme(.dark)
    }

    private var topBar: some View {
        HStack {
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(10)
                    .background(.black.opacity(0.45))
                    .clipShape(Circle())
            }

            Spacer()

            Text("Solo AR · Offline")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(.black.opacity(0.45))
                .clipShape(Capsule())
        }
    }

    private var bottomBar: some View {
        VStack(spacing: 10) {
            Text(viewModel.statusBanner)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(viewModel.gateStatus == .passed ? .green : .white)
                .multilineTextAlignment(.center)

            HStack(spacing: 16) {
                metric("Planes", "\(viewModel.planeCount)")
                metric("Marks", "\(viewModel.annotationCount)")
                metric("Track", shortTrack)
            }
            .font(.caption.monospaced())
            .foregroundStyle(.white.opacity(0.8))

            Text("Tap anywhere on a detected plane to place a sticky cyan marker.")
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.55))
                .multilineTextAlignment(.center)

            if viewModel.gateStatus == .passed {
                Button {
                    dismiss()
                } label: {
                    Text("Continue")
                        .fontWeight(.semibold)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(Color.cyan)
                        .foregroundStyle(.black)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
            }
        }
        .padding(16)
        .background(.black.opacity(0.55))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var shortTrack: String {
        switch viewModel.gateStatus {
        case .passed: return "OK"
        case .tracking: return "OK"
        case .scanning: return "…"
        case .limited: return "LIM"
        case .failed: return "ERR"
        case .idle: return "—"
        }
    }

    private func metric(_ title: String, _ value: String) -> some View {
        VStack(spacing: 2) {
            Text(value).fontWeight(.bold)
            Text(title).foregroundStyle(.white.opacity(0.45))
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - UIKit bridge

struct SoloARContainer: UIViewRepresentable {
    @Bindable var viewModel: SoloARViewModel

    func makeUIView(context: Context) -> ARView {
        let arView = ARView(frame: .zero)
        arView.automaticallyConfigureSession = false
        arView.session.delegate = context.coordinator

        // World tracking + horizontal/vertical planes — no LiDAR required.
        let config = ARWorldTrackingConfiguration()
        config.planeDetection = [.horizontal, .vertical]
        config.environmentTexturing = .automatic
        if ARWorldTrackingConfiguration.supportsFrameSemantics(.personSegmentationWithDepth) {
            // Optional; fine to skip on devices without it.
        }

        arView.session.run(config, options: [.resetTracking, .removeExistingAnchors])
        print("[SoloAR] session run worldTracking planes=H+V")

        let tap = UITapGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleTap(_:))
        )
        arView.addGestureRecognizer(tap)
        context.coordinator.arView = arView
        return arView
    }

    func updateUIView(_ uiView: ARView, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(viewModel: viewModel)
    }

    /// Not @MainActor — holding ARFrames across MainActor hops stalls the camera.
    final class Coordinator: NSObject, ARSessionDelegate {
        let viewModel: SoloARViewModel
        weak var arView: ARView?
        private var knownPlaneIds = Set<UUID>()

        init(viewModel: SoloARViewModel) {
            self.viewModel = viewModel
        }

        func session(_ session: ARSession, cameraDidChangeTrackingState camera: ARCamera) {
            let state = camera.trackingState
            DispatchQueue.main.async { [weak self] in
                self?.viewModel.updateTracking(state)
            }
        }

        func session(_ session: ARSession, didAdd anchors: [ARAnchor]) {
            for anchor in anchors {
                if let plane = anchor as? ARPlaneAnchor {
                    knownPlaneIds.insert(plane.identifier)
                    print("[SoloAR] plane added id=\(plane.identifier) alignment=\(plane.alignment.rawValue)")
                }
            }
            let count = knownPlaneIds.count
            DispatchQueue.main.async { [weak self] in
                self?.viewModel.updatePlaneCount(count)
            }
        }

        func session(_ session: ARSession, didRemove anchors: [ARAnchor]) {
            for anchor in anchors {
                if let plane = anchor as? ARPlaneAnchor {
                    knownPlaneIds.remove(plane.identifier)
                }
            }
            let count = knownPlaneIds.count
            DispatchQueue.main.async { [weak self] in
                self?.viewModel.updatePlaneCount(count)
            }
        }

        func session(_ session: ARSession, didFailWithError error: Error) {
            let message = error.localizedDescription
            print("[SoloAR] session FAILED: \(message)")
            DispatchQueue.main.async { [weak self] in
                self?.viewModel.markFailed(message)
            }
        }

        @objc func handleTap(_ gesture: UITapGestureRecognizer) {
            guard let arView else { return }
            guard viewModel.canPlace else {
                print("[SoloAR] tap ignored — not ready")
                return
            }

            let location = gesture.location(in: arView)

            // Prefer plane raycast so markers stick in world space.
            if let result = arView.raycast(
                from: location,
                allowing: .existingPlaneGeometry,
                alignment: .any
            ).first {
                placeMarker(at: result.worldTransform, in: arView)
                return
            }

            // Fallback: estimated plane
            if let result = arView.raycast(
                from: location,
                allowing: .estimatedPlane,
                alignment: .any
            ).first {
                placeMarker(at: result.worldTransform, in: arView)
                return
            }

            print("[SoloAR] tap miss — no plane hit")
        }

        private func placeMarker(at transform: simd_float4x4, in arView: ARView) {
            let anchor = AnchorEntity(world: transform)

            // Cyan sphere = local sticky annotation (offline stand-in for stroke/pointer).
            let mesh = MeshResource.generateSphere(radius: 0.025)
            var material = SimpleMaterial()
            material.color = .init(tint: .cyan, texture: nil)
            let model = ModelEntity(mesh: mesh, materials: [material])
            // Slight lift so it sits visibly on the plane.
            model.position = SIMD3<Float>(0, 0.03, 0)

            anchor.addChild(model)
            arView.scene.addAnchor(anchor)
            viewModel.didAddAnnotation()
            print("[SoloAR] marker anchored world")
        }
    }
}
