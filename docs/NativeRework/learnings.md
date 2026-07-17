# Native Android Rewrite — Learnings

> Engineering notes from building and debugging the Kotlin app in `android-app/`.  
> RN-era learnings (LiveKit tunnels, emulator flavors, etc.) stay in [`../learnings.md`](../learnings.md).  
> This file is **native rewrite only** — add entries as we discover and fix issues.

**Last updated:** 2026-07-08 — Phase 7 ID join + home revamp ✅

---

## Phase 7 — ID join, safe areas, debug URLs

### `profiles.public_id`
- 11 numeric digits, unique, auto-assigned via DB trigger on insert.
- Display with dashes (`X-XXX-XXX-XXX`) only in UI — API stores plain digits.

### Debug backend URL
- `RuntimeConfigStore` (DataStore) overrides `API_URL` and `LIVEKIT_URL` at runtime.
- Avoids rebuild when Cloudflare quick tunnel URLs rotate.

### Safe areas with edge-to-edge
- `enableEdgeToEdge()` + `Modifier.windowInsetsPadding(WindowInsets.safeDrawing)` on `NavHost`.
- Prevents home/call chrome from drawing under status bar and nav buttons.

### Product flavors vs git branches
- `premium` / `free` Gradle flavors with `BuildConfig.IS_PREMIUM` — both APKs from one tree.
- Free strips collaboration UI (chat, files, recording, speaker, pause) via `IS_PREMIUM` gates.
- Shared core in both: home, ID join, tutorial, models, annotations.

### Model placement v1
- `place_model` broadcast on annotation channel; `PlaceModelPayload` with model URL + normalized position.
- Full ARCore GLB rendering deferred — state sync + toast confirmation in v1.

---

## Architecture choices (keep)

### Match the proven RN wire format
- **One Realtime channel per session:** `annotations:{sessionId}` (not separate channels per event type).
- **Events:** `annotation`, `annotation_sync`, `clear_annotations` — names must match RN exactly.
- **Transport:** Supabase Realtime **broadcast** only — not LiveKit data channels. RN confirmed faster and survives ICE restarts.

### Customer owns AR anchoring
- Both parties may draw in the native app, but **only the customer device** runs ARCore hit-test → anchors → per-frame projection.
- Technician overlay is **2D Canvas on video** at normalized coords (TeamViewer / Chalk pattern).

### Single ARCore camera pipeline (Phase 4)
- Shared camera → custom LiveKit capturer avoids the RN “two owners of rear camera” failure mode.
- Do not reintroduce a second camera path for annotations.

### Native intentionally diverges from RN on video aspect
- RN reference uses **16:9** (`remote-ar/src/utils/videoFrameCoords.ts`).
- Native app uses **9:16 mobile portrait** (`HOST_VIDEO_ASPECT = 9f/16f`, 720×1280 defaults) because both test phones are portrait and the technician view is a tall phone panel — not a landscape monitor.

---

## Phase 5 — what “working” looks like (user-verified 2026-06-29)

Bidirectional annotation sync on SM-F936B (customer) + SM-N980F (technician):

| Flow | Expected behavior |
|------|-------------------|
| Customer draws on AR plane | Strokes stay world-anchored while phone moves |
| Customer → technician | Technician sees strokes on LiveKit overlay via `annotation_sync` |
| Technician draws | Customer receives `annotation`, hit-tests, anchors on plane |
| Technician → technician overlay | **Own strokes track the scene** via customer round-trip `annotation_sync` (not pinned to original screen draw position) |
| Technician live drag | Customer sees partial stroke **before finger lifts** (~40 ms stream interval) |
| Technician viewport | **9:16 portrait stack** — touch, video, and overlay share one coordinate space |

**The pipeline that must log on both devices:**
```
[WS] subscribed channelId=annotations:{sessionId} status=SUBSCRIBED realtime=CONNECTED
[SUBSCRIBE] connected sessionId=...
[TX/RX] annotation / annotation_sync
[OVERLAY] technician activeStrokes=N
```

---

## Annotation sync — RN vs native

### How RN routes events (`useAnnotationSync.ts`)
- **`annotation` event:** handled on **customer only** — incoming technician strokes → raycast → anchor → then send `annotation_sync`.
- **`annotation_sync` event:** handled on **technician only** — updates overlay with projected/normalized screen points.
- RN technician **does not filter out own stroke ids** on sync — native now matches this.

### Native extensions beyond RN
- **Customer can also draw** — native sends `annotation_sync` from customer GL projection loop.
- **Live streaming** — technician sends partial `annotation` events during drag (TeamViewer-style).
- **Technician own strokes on technician screen** — optimistic local display until customer `annotation_sync` arrives; then authoritative projected positions (world-relative on both sides).

### Technician render path (native)
- **`annotation_sync` is authoritative** for overlay positions on the technician screen (customer-origin **and** technician-origin after round-trip).
- **`annotation` on technician** stores metadata only (tool/color).
- **`technicianLocalStrokes`** — optimistic until `syncStrokes` has the same id from customer projection.

---

## Coordinate spaces (critical)

### Two spaces — do not mix on customer AR
| Space | Used for |
|-------|----------|
| **Container-normalized** (0–1 over full GL/view) | Customer hit-test, local overlay, `AnchorManager`, `AnnotationProjector` fallback |
| **Video-normalized** (0–1 inside **9:16 portrait** capture rect) | Wire format between devices; customer→technician `annotation_sync` |

### Native portrait constants (`VideoFrameCoords.kt`)
```kotlin
const val HOST_VIDEO_ASPECT = 9f / 16f   // width/height
const val HOST_VIDEO_WIDTH = 720
const val HOST_VIDEO_HEIGHT = 1280
```

### Mistake we made
- Converting customer strokes to **video-normalized** before storing/anchoring caused hit-tests at wrong pixel locations → strokes **warped and expanded** as the camera moved.
- Locking technician UI to **16:9 full-width** on a portrait phone created an **invisible drawable band** — strokes worked inside the band but overlay/touch diverged outside it. **Fix:** 9:16 `fillMaxHeight()` + `aspectRatio(9/16)` centered stack.
- Pinning **technician own strokes** to local container coords ignored customer projection sync → strokes looked **screen-fixed on tech phone** while correct in AR on customer. **Fix:** apply `annotation_sync` for all stroke ids.

### Correct split
- **Customer local:** always container-normalized until send.
- **Network:** convert container → video normalized in `VideoFrameCoords.kt` only when calling `AnnotationChannel.send*`.
- **Technician incoming:** store video-normalized; render with `inVideoSpace = true` → `toVideoMappedOffsets()`.
- **Technician → customer wire:** container → clamped video-normalized (`containerNormToVideoNormClamped`).

### LiveKit renderer
- **`LiveKitVideoView` uses `TextureViewRenderer`** + `SCALE_ASPECT_FIT` — letterbox matches coord math.
- Do **not** use `SurfaceViewRenderer` under Compose draw/touch layers.

### SurfaceView blocks Compose touch (critical on technician)
- **`SurfaceViewRenderer`** punches a separate compositor layer — Compose overlay may render but **not receive touches** in letterbox regions.
- **Fix:** `TextureViewRenderer`; drawing `pointerInput` on parent Box at highest zIndex.

---

## Supabase Realtime (native / supabase-kt)

### ⚠️ THE blocker: `ktor-client-android` has no WebSocket support

**Symptom:** REST broadcast returns **202** on send; receiver never gets `[RX]`.

**Fix:** `ktor-client-okhttp` + `ktor-client-websockets` in `AppModule.kt`.

**Lesson:** Never pair **supabase-kt Realtime** with **ktor-client-android**.

### WS + REST double-send caused technician jitter
- REST **only when WS broadcast fails**
- Dedupe sync by `SYNC_POINT_EPSILON` (0.003)
- Single in-flight sync job; debounce technician overlay ~15 fps

### Send strategy (current native)
- WS `broadcast()` when subscribed; REST path fallback **only on WS failure**.
- `realtime.setAuth(jwt)` before subscribe.
- `AnnotationController` / `AnnotationChannel` are **`@Singleton`** — `disconnect()` on session end.

---

## ARCore fallback lifecycle (2026-06-29)

### Symptom
Customer sees **“AR not available — video mode only”** on every session join, even after force-stop — AR had worked previously.

### Root causes
1. **`activateFallback()` did not release the camera** — LiveKit CameraX fallback could not acquire the camera; AR retries failed in a broken state.
2. **Singleton `ARCoreManager` retained fallback flags** across sessions if cleanup was incomplete.
3. **`captureSessionChangesPossible` wait lock** could deadlock `onPause` / `closeCamera` after a failed camera open.
4. **`UNKNOWN_CHECKING`** from `ArCoreApk.checkAvailability()` was treated as unsupported — fallback triggered too early.

### Fixes (`ARCoreManager.kt`)
- On fallback: `pauseARCore()` → `closeCamera()` → close `sharedSession` → **then** set `streamingReady` / `fallbackActive`.
- On **`attach()`** (each new call screen): reset `arcoreResumeFailed`, `fallbackActive`, release wait lock.
- Retry `openCamera()` after 250 ms when ARCore is still checking availability.
- Removed blocking `waitUntilCameraCaptureSessionIsActive()` from `onResume` / `closeCamera`.

### Lesson
Fallback is not just a UI flag — it must **fully release camera hardware** before LiveKit video-only mode starts.

---

## Dev environment — LAN IP baked into APK

### Symptom
“Failed to connect” on customer phone at session create or LiveKit join.

### Cause
`API_URL` and `LIVEKIT_URL` in `android-app/local.properties` are compiled into `BuildConfig` at build time. When the dev laptop DHCP address changes (e.g. `192.168.0.102` → `192.168.0.104`), phones still target the old IP.

### Fix
1. Run `ipconfig` → use **Wi‑Fi IPv4**
2. Update `local.properties`:
   ```properties
   API_URL=http://<PC_WIFI_IP>:3000
   LIVEKIT_URL=ws://<PC_WIFI_IP>:7880
   ```
3. Rebuild and reinstall: `.\gradlew installDebug`

Docker must be running (`docker ps` shows `cgsapple-api-1` and `cgsapple-livekit-1`).

---

## Rendering (technician)

### Z-order (current)
- Video: zIndex 0 (`TextureViewRenderer`)
- Overlay canvas: zIndex 2
- Touch layer: zIndex 4

### Do not rebuild overlay on every network sync
- Throttle/debounce overlay updates; ignore sync below epsilon delta.

---

## Customer overlay bugs (fixed)

### Strokes disappearing after finger lift
- Persist locally before network; ignore invalid sync payloads (`pts < 2`).

### Duplicate / flickering layers
- One layer per stroke id (projected > cached > catalog).

---

## What not to do (native)

| Anti-pattern | Why |
|--------------|-----|
| LiveKit data channel for annotations | ICE restarts break sync |
| Video-normalized coords in `AnchorManager` | Hit-test pixels wrong → shape drift |
| `ktor-client-android` with supabase-kt Realtime | No WebSocket |
| WS + REST on every send when WS succeeds | Duplicate RX → overlay jitter |
| **16:9 technician stack on portrait phone** | Invisible drawable band; coord mismatch |
| **Ignore `annotation_sync` for technician own stroke ids** | Screen-fixed strokes on tech phone |
| **`SurfaceViewRenderer` under Compose draw layer** | Touch blocked; use TextureViewRenderer |
| Fallback without releasing camera | Stuck “AR not available”; LiveKit camera fight |
| Stale `local.properties` IP without rebuild | “Failed to connect” |
| Assume 202 REST means peer received | Need active WS subscriber |

---

## References in repo

| Path | Contents |
|------|----------|
| `remote-ar/src/services/annotationRealtime.ts` | Canonical Realtime client (retired app — read only) |
| `remote-ar/src/hooks/useAnnotationSync.ts` | Role-based event routing |
| `remote-ar/src/utils/videoFrameCoords.ts` | RN 16:9 coord math (native uses 9:16) |
| `android-app/.../annotation/` | Native implementation |
| `docs/NativeRework/progress.md` | What’s built and verified |
