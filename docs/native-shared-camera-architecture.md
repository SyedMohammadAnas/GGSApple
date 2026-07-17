# Native Shared Camera Architecture

> Android-only · Phone flavor · Customer call path

## Problem

ARCore and WebRTC (LiveKit default camera) cannot share the rear camera on Android. JS handoff (`useARCameraHandoff`) caused black screens and frozen technician video — **retired**.

## Solution

Phone-only native module owns ARCore `Session` with `Session.Feature.SHARED_CAMERA`, opens Camera2, and fans frames to:

1. **Customer preview** — `ARCameraView` (GLES when ARCore resumes; **ImageView CPU** when resume fails)
2. **LiveKit stream** — `ImageReader` → `YuvToI420Converter` → `FramePushVideoCapturer` → JS `publishTrack`

LiveKit room connect stays in JavaScript ([`livekitService.ts`](../remote-ar/src/services/livekitService.ts)).

## Data flow

```
ARCallScreen (JS)
  ├─ useSharedCameraSession / sharedCameraMedia.ts
  │    ├─ releaseHostCamera → armSharedCameraStart()
  │    ├─ ARCameraModule.startSharedCameraSession()
  │    │    ├─ SharedCameraController → ARCore + Camera2 + ImageReader
  │    │    ├─ YuvToI420 → FramePushVideoCapturer → WebRTC VideoTrack
  │    │    └─ (fallback) CpuPreviewDisplay → ImageView
  │    └─ room.localParticipant.publishTrack(nativeVideoTrack)
  ├─ <HostCameraSurface /> → ARCameraView
  ├─ useAnnotationSync + useAnnotationProjection
  └─ AnnotationOverlay (customer)
```

## Fallback path (Samsung M14 and similar)

When `session.resume()` throws `FatalException`:

| Component | Behavior |
|-----------|----------|
| `arcoreResumeFailed` | Skips resume retries; starts `FallbackPoseTracker` |
| `CpuPreviewDisplay` | NV21 → JPEG bitmap with **inverted** rotation vs WebRTC metadata |
| `FallbackPoseTracker` | Rotation-vector sensor; raycast/project for annotations |
| GLES `ARCameraView` | Hidden; ImageView preview on top |

Reproduced in [`native-spike/`](../native-spike/) — not React Native–specific.

## Native module API

| Method | Description |
|--------|-------------|
| `armSharedCameraStart()` / `disarmSharedCameraStart()` | Gate before GL + camera open |
| `startSharedCameraSession()` | WebRTC track + shared camera pipeline |
| `stopSharedCameraSession()` / `releaseSharedCameraTrack()` | Teardown levels |
| `raycast(normX, normY)` | World point (plane hit, ARCore unproject, or fallback sensor) |
| `projectPoint(x, y, z)` | Normalized display coords for annotation sync |
| `isArcoreFallbackActive()` | CPU preview + pseudo-AR mode |

### Events

| Event | Payload |
|-------|---------|
| `onTrackingStateChanged` | `{ state }` |
| `onPlaneDetected` | `{ count }` |
| `onArcoreFallbackChanged` | `{ active: boolean }` |
| `onARCameraError` | `{ code, message }` |

## File map

| Path | Role |
|------|------|
| `ARCameraModule.kt` | RN bridge |
| `ARCameraView.kt` | FrameLayout: GLSurfaceView + ImageView preview |
| `SharedCameraController.kt` | Session, Camera2, stream + preview orchestration |
| `FramePushVideoCapturer.kt` | Latest-frame-only external capturer |
| `YuvToI420Converter.kt` | ImageReader → WebRTC I420 |
| `CpuPreviewDisplay.kt` / `FrameBitmapDecoder.kt` | CPU preview |
| `StreamOrientationHelper.kt` | WebRTC CCW ↔ bitmap rotation; display/buffer coords |
| `FallbackPoseTracker.kt` | Pseudo-AR when resume fails |
| `ARCameraRenderer.kt` | GLES path when ARCore active |
| `src/native/ARCameraModule.ts` | Typed JS wrapper |
| `src/services/sharedCameraMedia.ts` | LiveKit publish |
| `src/hooks/useAnnotationSync.ts` | Annotation data channel |
| `src/hooks/useAnnotationProjection.ts` | World → screen reprojection loop |

## Latency design

- Stream encoded **first** on camera thread (20 fps cap)
- Preview decoded on **separate** low-priority thread (~12 fps), only in fallback
- Capturer **drops** superseded frames (never queues stale I420)

## References

| Resource | URL |
|----------|-----|
| ARCore SharedCamera | https://developers.google.com/ar/reference/java/com/google/ar/core/SharedCamera |
| Camera sharing guide | https://developers.google.com/ar/develop/java/camera-sharing |
| Spike app | [`native-spike/README.md`](../native-spike/README.md) |

## Out of scope / retired

- Viro on call path
- `useARCameraHandoff`, `ar_mode` video pause
- iOS ARKit shared camera
- Native `Room.connect` (JS owns signaling)
