---
name: Native Shared Camera Pipeline
overview: "COMPLETE (2026-06-20). Native shared camera shipped; Phase 4 annotations mostly done. See docs/summary.md for current status."
todos:
  - id: docs-architecture
    content: Update learnings, implementation-plan, summary; native-shared-camera-architecture.md
    status: completed
  - id: native-shared-camera
    content: SharedCameraController + ARCameraModule + ARCameraView (phone flavor)
    status: completed
  - id: livekit-bridge
    content: FramePushVideoCapturer + WebRTCModule.createStream + JS publishTrack
    status: completed
  - id: js-integration
    content: useSharedCameraSession, HostCameraSurface, sharedCameraMedia
    status: completed
  - id: spike-isolation
    content: native-spike proved resume failure is device-level (M14)
    status: completed
  - id: cpu-fallback
    content: CpuPreviewDisplay + FallbackPoseTracker + annotation projection
    status: completed
  - id: verify-gate
    content: E2E call + annotations on M14 with CPU fallback
    status: completed
  - id: second-device-ar
    content: Validate ARCore resume + true plane hit-test on second phone
    status: pending
isProject: false
---

# Native Shared Camera Pipeline — COMPLETE

This plan is **archived**. Current status lives in:

- [`docs/summary.md`](docs/summary.md) — phase progress
- [`docs/learnings.md`](docs/learnings.md) — debugging notes
- [`docs/native-shared-camera-architecture.md`](docs/native-shared-camera-architecture.md) — technical design
- [`docs/instructions.md`](docs/instructions.md) — operator test steps

**Next MVP work:** Phase 5 (3D models), then second-device ARCore validation.
