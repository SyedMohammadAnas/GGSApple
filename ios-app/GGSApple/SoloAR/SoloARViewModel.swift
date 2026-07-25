import Foundation
import Observation
import ARKit
import RealityKit
import UIKit

/// Offline Solo AR gate — no LiveKit / no Supabase Realtime.
/// Pass criteria: tracking normal + ≥1 plane + place a local annotation that sticks while moving.
@MainActor
@Observable
final class SoloARViewModel {
    enum GateStatus: Equatable {
        case idle
        case scanning
        case tracking
        case limited(String)
        case failed(String)
        case passed
    }

    private(set) var gateStatus: GateStatus = .idle
    private(set) var planeCount = 0
    private(set) var annotationCount = 0
    private(set) var trackingDescription = "Starting…"
    private(set) var didPlaceStickyAnnotation = false
    /// Last ARKit camera tracking state (used for gate evaluation).
    private var lastTrackingNormal = false

    /// Call when ARSession reports state.
    func updateTracking(_ state: ARCamera.TrackingState) {
        switch state {
        case .normal:
            lastTrackingNormal = true
            if gateStatus != .passed {
                gateStatus = planeCount > 0 ? .tracking : .scanning
            }
            trackingDescription = planeCount > 0 ? "Tracking · planes found" : "Tracking · look for surfaces"
        case .notAvailable:
            lastTrackingNormal = false
            gateStatus = .failed("AR tracking not available on this device")
            trackingDescription = "Not available"
        case .limited(let reason):
            lastTrackingNormal = false
            let reasonText: String
            switch reason {
            case .initializing: reasonText = "Initializing"
            case .excessiveMotion: reasonText = "Move slower"
            case .insufficientFeatures: reasonText = "Point at more detail"
            case .relocalizing: reasonText = "Relocalizing"
            @unknown default: reasonText = "Limited"
            }
            if gateStatus != .passed {
                gateStatus = .limited(reasonText)
            }
            trackingDescription = reasonText
        @unknown default:
            trackingDescription = "Unknown"
        }
        evaluatePass()
    }

    func updatePlaneCount(_ count: Int) {
        planeCount = count
        if case .failed = gateStatus { return }
        if gateStatus != .passed, lastTrackingNormal {
            gateStatus = count > 0 ? .tracking : .scanning
        }
        evaluatePass()
    }

    func markFailed(_ message: String) {
        gateStatus = .failed(message)
        trackingDescription = message
    }

    func didAddAnnotation() {
        annotationCount += 1
        didPlaceStickyAnnotation = true
        print("[SoloAR] local annotation placed count=\(annotationCount)")
        evaluatePass()
    }

    private func evaluatePass() {
        // Pass: normal tracking + plane + at least one local sticky annotation.
        guard lastTrackingNormal, planeCount > 0, didPlaceStickyAnnotation else { return }
        if gateStatus != .passed {
            gateStatus = .passed
            print("[SoloAR] GATE PASSED")
        }
    }

    var statusBanner: String {
        switch gateStatus {
        case .idle: return "Starting AR…"
        case .scanning: return "Scan a flat surface (floor / table)"
        case .tracking: return "Tap to place a sticky marker"
        case .limited(let r): return r
        case .failed(let r): return r
        case .passed: return "Solo AR gate passed"
        }
    }

    var canPlace: Bool {
        switch gateStatus {
        case .tracking, .passed: return true
        default: return false
        }
    }
}
