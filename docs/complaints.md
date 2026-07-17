# Remote AR — Status Report

> **Last updated:** 2026-06-24 (end of debugging sprint)  
> **Related:** [`summary.md`](summary.md), [`learnings.md`](learnings.md)

---

## Executive summary (2026-06-24)

**The core product goal is still not met on real hardware:**

- Technician often does **not** get customer video quickly (especially in `shared-ar` mode).
- Annotations are **not** reliably **world-relative / AR-anchored** to detected surfaces.

A lot of engineering work landed (annotation transport, video modes, handoff optimizations, coordinate mapping, session teardown). **None of it produced a user-confirmed pass** for “fast technician video + true ARCore plane-anchored annotations” together.

**What did get proven:**

| Claim | Status |
|-------|--------|
| LiveKit video works on LAN (`livekit` mode, correct IP, phone flavor) | ✅ User confirmed |
| Annotation sync is fast via Supabase Realtime (not LiveKit data channels) | ✅ User confirmed |
| True ARCore world annotations + reliable shared-ar video | ❌ **Still failing** |

---

## Executive summary (2026-06-20 — historical)

**The MVP call path is working** for development demos: customer starts a session, technician joins, live video streams both ways, customer sees a camera preview (CPU fallback on Samsung M14), and technician can draw annotations that appear on the customer overlay with sync back.

**Remaining gap for “true AR”:** Samsung SM-M146B cannot complete ARCore `session.resume()` (`FatalException` at `Session.nativeResume`). Isolated in `native-spike/` — not React Native–specific. Until a second ARCore-capable device is tested, annotations use **sensor-based pseudo-3D** (`FallbackPoseTracker`), not detected planes.

> **Note:** The 2026-06-20 summary was optimistic. The 2026-06-24 sprint showed that even with shared camera shipped, **world-relative annotations and shared-ar video timing remain broken** from the user's perspective.

---

## What works

| Area | Notes |
|------|-------|
| Phase 2 LiveKit (phone host + emulator technician) | Verified with dual Metro, flavor URLs, TURN |
| Native shared camera stream to technician | I420 `FramePushVideoCapturer` → LiveKit |
| Customer preview (fallback path) | `ImageView` + `CpuPreviewDisplay` when resume fails |
| Preview orientation matches technician stream | WebRTC CCW vs Matrix CW fix (`StreamOrientationHelper`) |
| Stream latency | Decoupled preview from stream; frame dropping; 20 fps cap |
| Technician director UI + drawing tools | Freehand, circle, arrow, colors, clear |
| Annotation overlay + sync (transport) | Supabase Realtime broadcast — **fast sync confirmed** |
| **`livekit` mode video to technician** | LAN `ws://192.168.0.102:7880` — **confirmed in sync** |
| Session teardown ordering | `callSessionTeardown.ts` — fewer end-session races |
| Pseudo-3D annotation anchoring (fallback only) | `FallbackPoseTracker` + `useAnnotationProjection` — **not true plane AR** |

---

## Still broken / unproven (2026-06-24)

| Issue | User impact | What we tried | Result |
|-------|-------------|---------------|--------|
| **Technician video slow or missing** | “Waiting for customer video” 10–15s+ | `livekit` mode; shared-ar handoff tuning; `CustomerSharedArLayer` early warmup; skip camera-release delay; remove 300ms publish delay; force video subscribe in `livekitService` | `livekit` ✅ video; `shared-ar` ❌ still bad |
| **Annotations not world-relative** | Strokes glued to screen when customer moves phone | `shared-ar` + raycast → `worldPoints` → `useAnnotationProjection`; `videoFrameCoords`; wait for ARCore tracking; IMU hybrid in `livekit` mode | ❌ Still screen-fixed per user |
| **True ARCore plane anchoring** | Product requirement | `shared-ar` mode, plane `hitTest` in native `SharedCameraController` | ❌ Not demonstrated E2E |
| **IMU “world” mode in `livekit`** | Looked like a shortcut | `startImuPoseTracking` without camera handoff | ❌ **Not true AR** — abandoned |

---

## What we built this sprint (nothing removed — all still in repo)

### 1. Annotation transport off LiveKit

- **Files:** `annotationRealtime.ts`, `annotationChannel.ts`, `useAnnotationSync.ts`
- **Why:** ICE restarts dropped data-channel messages; decouple AR from WebRTC.
- **Proved:** Sync is fast.
- **Did not prove:** World anchoring works.

### 2. Host video modes (`hostVideoMode.ts`)

| Mode | Camera owner | Video speed | True AR |
|------|--------------|-------------|---------|
| `livekit` | LiveKit WebRTC | Fast ✅ | No ❌ |
| `shared-ar` | ARCore shared → LiveKit | Slow / flaky ❌ | Possible in theory ⚠️ |

- **Env:** `EXPO_PUBLIC_HOST_VIDEO_MODE`, `EXPO_PUBLIC_AR_WORLD_ANNOTATIONS`

### 3. IMU pseudo-AR (livekit + world annotations)

- **Files:** `useImuWorldTracking.ts`, native `startImuPoseTracking` / `stopImuPoseTracking`
- **Why:** Avoid shared-camera handoff while faking world coords.
- **Verdict:** **Not true AR.** User correctly rejected this direction.

### 4. shared-ar speed / lifecycle optimizations

- **`CustomerSharedArLayer`** — arm ARCore on Waiting screen; keep one `ARCameraView` across navigation
- **`HostCameraSurface`** — no second AR view on ARCall; transparent screen background
- **`hostMedia.ts`** — skip 1s delay if LiveKit never had camera; 400ms delay if it did
- **`useSharedCameraSession`** — publish on `onSharedCameraRunningChanged`; no extra 300ms sleep
- **Verdict:** User reports **still not working** — no proof of improvement.

### 5. Coordinate mapping

- **`videoFrameCoords.ts`** — map touches/overlays through 16:9 video cover rect
- **`DrawingCanvas`** — technician draws in video-frame norm space
- **`AnnotationOverlay`** — `mapVideoCoords` for technician + customer
- **Verdict:** Theoretically fixes aspect mismatch; **not verified on device**.

### 6. Infrastructure / stability fixes

- Stale LiveKit URL (`192.168.0.111` → `192.168.0.102`); gradle `BuildConfig.LIVEKIT_WS_URL`
- `sessionLifecycle.ts` deleted → `sessionEnding.ts` + `callSessionTeardown.ts` (require cycles)
- LiveKit reconnect: preserve `RoomContext` on `SignalReconnecting`, republish camera, re-sync annotations
- Technician video: `RemoteHostVideo`, subscribe on `TrackPublished`

---

## Known limitations

| Issue | Impact | Next step |
|-------|--------|-----------|
| ARCore `session.resume()` fails on M14 | No GLES AR preview, no plane hit-test | Test on second phone |
| Pseudo-3D vs real planes | Annotations drift vs true world lock | Automatic upgrade when resume works |
| **shared-ar handoff latency** | Technician waits for video | May be inherent; or bug in publish path |
| **World points may be empty** | Annotations fall back to screen coords | Add HUD: `world=Y/N`, log raycast failures |
| Cloudflare tunnel URLs ephemeral | Breaks after tunnel restart | Update `.env` or use named tunnel |
| Annotation sync latency | ~100–200 ms roundtrip | Acceptable when sync works |
| **Single test device (M14)** | Can't separate device vs code bugs | Second ARCore-capable phone required |

---

## Resolved (was blocking)

| Issue | Resolution |
|-------|------------|
| Black customer screen / frozen technician video | Native shared camera + handoff ordering |
| SIGSEGV on session create | ARCore 1.51 Maven dep; armed start gate |
| Wrong APK on phone (`10.0.2.2`) | Flavor install scripts + wrong-build banner |
| Preview 90° rotated / stretched | Invert rotation for bitmap preview vs WebRTC metadata |
| Screen-only annotations (transport) | Supabase Realtime + world point pipeline **wired** — **anchoring unproven** |
| Technician video lag (`livekit` mode) | Stream-first pipeline; LAN IP; force subscribe |
| LiveKit annotation sync during ICE restart | Moved to Supabase Realtime |
| End-session `NegotiationError` / zombie reconnect | `beginSessionEnd()` + ordered `clearCallSession()` |
| Metro require cycles | Split session lifecycle modules |

---

## Why this sprint didn't close the loop

1. **Conflicting modes** — fixes for video (`livekit`) and fixes for true AR (`shared-ar`) fight each other on one rear camera.
2. **No E2E proof artifact** — no recorded pass with checklist (video <3s, `worldPoints` populated, surface stick on move).
3. **Hardware uncertainty** — M14 may never do plane AR; failures may be device-specific but we kept testing on it.
4. **Silent failures** — empty raycast → screen-fixed annotations with no user-visible error.
5. **Rebuild / env traps** — wrong IP, stale bundle, skipped native rebuild → wasted test cycles.

---

## Honest current config

```env
EXPO_PUBLIC_HOST_VIDEO_MODE=shared-ar
EXPO_PUBLIC_AR_WORLD_ANNOTATIONS=true
EXPO_PUBLIC_LIVEKIT_URL=ws://192.168.0.102:7880
```

**Expected if working:** ARCore on customer from Waiting screen → technician sees video soon after join → draw locks to planes.

**Actual per user:** Still not working.

---

## Not started (post–Phase 4)

- Phase 5: 3D GLB models (load, place, transform sync)
- Phase 6: Full error matrix polish
- Phase 7: Demo dry runs on real hardware
- iOS / ARKit
- **Debug HUD:** tracking state, `worldPoints` count, shared camera running, publish state
- **Second ARCore device** test matrix

---

## Changelog

| Date | Update |
|------|--------|
| 2026-06-20 | Initial status: MVP call path working; M14 fallback; pseudo-3D |
| 2026-06-24 | Major sprint doc: video mode split, Supabase annotations, shared-ar optimizations, IMU rejected, **user confirms still broken** |
