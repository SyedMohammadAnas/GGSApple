# Native Android Rewrite — Progress Log

> Living document for the Kotlin + Compose rewrite in `android-app/`.  
> Specs and phase checklists remain in `implementation-plan.md`, `trd.md`, and `cursor-prompt.md`.  
> This file tracks **what we actually built and verified on device**.

**Last updated:** 2026-07-25 — **Android Instant customer-only (expert = web)**

---

## Android Instant — customer-only + expert-web defaults (2026-07-25) ✅ code

| Item | Status |
|------|--------|
| Remove Instant/Master flavors → one `com.ggsapple.remotear` Instant | Done |
| Strip native expert toggle / join / technician call | Done |
| `POST /api/sessions/customer-enter` poll (no phone create-session) | Done |
| Always-on speaker / mute / pause / chat / files / recording | Done |
| Default API `http://100.83.95.8:3002` + LiveKit `:7880` | Done |
| Device lab vs expert-web | Blocked — `expert-web/` not on GitHub yet |

Vault: `Progress/GGSApple-Android-Instant-Customer-Only-2026-07-25`.

---

## New phones — ARCore + call chrome (2026-07-21 verified, 2026-07-22 committed) ✅

| Item | Status |
|------|--------|
| ARCore install wait (`INSTALLING`) + resume retry | Done |
| Samsung Android 16 IMU keep-alive (#1762) + `HIGH_SAMPLING_RATE_SENSORS` | Done |
| Camera2 open race (generation / inFlight / onClosed / soft retry) | Done |
| Prefer `TEMPLATE_PREVIEW`; ARCore 1.48.0; legacy JNI packaging | Done |
| Draw layer under chrome (zIndex 5 / 20 / 25); UNDO/DELETE always on | Done |
| Control-path instrumentation (AssistCallUi / CallViewModel / LiveKit) | Done |
| Backend Dockerfile: `mkdir -p public` | Done |
| Device verify A35 customer + S24 FE expert | Done |

Vault: SergantSwaggBase `Progress/GGSApple-New-Phones-AR-Fixes-2026-07-22`.

---

## Phase 9 — Annotation, UI polish, Vercel dashboard (2026-07-08) ✅

| Item | Status |
|------|--------|
| Asset dashboard moved to `asset-dashboard/` + Vercel deploy | Done |
| Expert annotation touch-layer fix | Done |
| Undo on customer + expert | Done |
| Black status/nav bars app-wide | Done |
| Tutorial recording via ARCore frames (no MediaProjection) | Done |
| In-call recording FGS for MediaProjection | Done |

See `phase-9-fixes.md` and `asset-dashboard.md`.

---

## Phase 8 — Home polish + naming + asset dashboard (2026-07-08) ✅

| Item | Status |
|------|--------|
| Pure black home background, larger preview | Done |
| Controls anchored above status bar | Done |
| Wi-Fi icon in status bar | Done |
| Clear cache (preserves auth session) | Done |
| Customer stays on home; share = share intent only | Done |
| Incoming session orange dot + message | Done |
| Expert bottom sheet starts collapsed | Done |
| `GET /api/models` + web dashboard `/dashboard/` | Done |
| Flavors renamed `master` / `instant` (Master / Instant labels) | Done |
| Installed Master + Instant on SM-F936B + SM-N980F | Done |
| Removed retired `native-spike/` and `remote-ar/` folders | Done |

### Build commands

```powershell
cd android-app
.\gradlew installMasterDebug    # "Master" — com.ggsapple.remotear
.\gradlew installInstantDebug    # "Instant" — com.ggsapple.remotear.instant
```

### Docs added/updated
- `asset-dashboard.md`, `feature-matrix.md`, `branching-strategy.md`, `app-flow-v3.md`

---

## Phase 7 — Home revamp + ID join + free tier (2026-07-08) ✅

| Feature | Status |
|---------|--------|
| Glass call chrome (sidebar, bottom sheet, session pill) | Done |
| Annotation tools: pointer, arrow, draw, circle, undo, delete | Done |
| In-session chat (Supabase Realtime) | Done |
| File sharing (Supabase Storage `session-files`) | Done |
| Session recording (MediaProjection) | Done |
| Speaker / mute / pause / end controls | Done |
| Asset library UI (search, recents, model detail) | Done |
| Model placement | Stub → full impl in Phase 7 |
| Device install SM-N980F | Done |

See `premium-phase6-summary.md` for full file list.

---

## Phase 7 — Home revamp + ID join + free tier (2026-07-08) ✅

| Item | Status |
|------|--------|
| Unified AssistHomeScreen + customer/expert toggle | Done |
| 11-digit `public_id` + `POST /api/sessions/join-by-id` | Done |
| Safe area insets (`WindowInsets.safeDrawing`) | Done |
| Debug backend URL override (DataStore) | Done |
| Local video tutorial screen | Done |
| 3D model placement (`place_model` event) | Done |
| Gradle flavors `master` / `instant` (`IS_PREMIUM`) | Done |
| Installed on SM-F936B + SM-N980F (both flavors) | Done |

### Build commands

```powershell
cd android-app
.\gradlew installPremiumDebug   # com.ggsapple.remotear
.\gradlew installFreeDebug      # com.ggsapple.remotear.free
```

### Docs added
- `feature-matrix.md`, `branching-strategy.md`, `premium-phase6-summary.md`
- `app-flow-v3.md`, `debug-backend-url.md`

---

**Last updated:** 2026-07-08 — **Phase 7 complete** ✅

---

## Phase summary

| Phase | Scope | Status |
|-------|--------|--------|
| **0** | Project bootstrap, Hilt, Compose, infra | Done |
| **1** | Google auth, role-based home | Done |
| **2** | Session create / join / end, polling | Done |
| **3** | LiveKit video + bidirectional audio | Done |
| **4** | ARCore shared camera, scan ring, tracking HUD, stable calls | Done |
| **5** | World-anchored annotations + cross-device sync | Verified 2026-06-29 |
| **6** | Premium call UI, chat, files, recording | Done 2026-07-08 |
| **7** | Home/ID UX, tutorial, models, Instant tier | Done 2026-07-08 |
| **8** | Home polish, Master/Instant naming, asset dashboard | Done 2026-07-08 |

**User-verified on two physical phones (SM-F936B customer + SM-N980F technician):**

| Flow | Status |
|------|--------|
| Customer draws → world-anchored on AR surface | ✅ |
| Customer strokes appear on technician LiveKit video overlay | ✅ |
| Technician draws → customer anchors on AR plane (world-relative) | ✅ |
| **Technician sees own strokes track the scene** (via customer `annotation_sync`, not screen-fixed) | ✅ |
| **Live streaming** — customer sees technician stroke **while finger is still down** | ✅ |
| **9:16 portrait** technician video stack — touch/overlay/video share one coord space | ✅ |
| Bidirectional sync stable; no invisible drawing rectangle on technician | ✅ |
| AR scan ring + plane tracking after lifecycle fixes | ✅ |
| App stable during multi-stroke session | ✅ |

### Commits (android-app repo → `origin/master`)

| Commit | Summary |
|--------|---------|
| `5b5b4ab` | Full-screen technician drawing + live streaming |
| `dbd1861` | Map technician overlay to video coords |
| `a79980d` | Lock technician UI to aspect stack (later switched to 9:16) |
| `bfa1368` | **9:16 mobile portrait** wire coords + technician viewport |
| `5d8808d` | Connection error messages + example IP refresh |
| `035dce3` | Technician own strokes follow customer projection sync |
| `644cdd4` | AR fallback lifecycle — release camera, reset singleton per call |

---

## Phase summary

| Phase | Scope | Status |
|-------|--------|--------|
| **0** | Project bootstrap, Hilt, Compose, infra | ✅ Done |
| **1** | Google auth, role-based home | ✅ Done |
| **2** | Session create / join / end, polling | ✅ Done |
| **3** | LiveKit video + bidirectional audio | ✅ Done |
| **4** | ARCore shared camera, scan ring, tracking HUD, stable calls | ✅ Done |
| **5** | World-anchored annotations + cross-device sync | ✅ **Verified 2026-06-29** |
| **6–7** | Error polish, demo dry run | ⏳ Next |

## Test environment

| Item | Value |
|------|--------|
| Customer phone | SM-F936B (`R3CT80DRKFP`) |
| Technician phone | SM-N980F (`RZ8R704R4AV`) |
| Backend API | `http://<PC_WIFI_IP>:3000` (Docker — **update `local.properties` when DHCP changes**) |
| LiveKit | `ws://<PC_WIFI_IP>:7880` |
| Supabase | `https://suuellchcoegerddqyjb.supabase.co` |
| Git (android-app) | `https://github.com/SyedMohammadAnas/remote-ar.git` |
| Build / install | `cd android-app && .\gradlew installDebug` |

> **LAN IP note:** Phones bake `API_URL` / `LIVEKIT_URL` from `android-app/local.properties` at build time. If the dev laptop gets a new Wi‑Fi address (e.g. `.102` → `.104`), update `local.properties` and rebuild — otherwise the app shows “Failed to connect”.

---

## Phase 5 — Architecture (verified working)

- **Customer:** ARCore GL loop → hit-test → anchors → per-frame projection → sends `annotation` + `annotation_sync`.
- **Technician:** LiveKit video + 2D canvas overlay in a **9:16 portrait** stack; draws in video-normalized space; overlay driven by incoming `annotation_sync` (including **own strokes** after customer projects them).
- **Single channel:** `annotations:{sessionId}` — events `annotation`, `annotation_sync`, `clear_annotations`.
- **Two coordinate spaces:** container-normalized (customer AR) vs **video-normalized 9:16 portrait** (network + technician overlay). See `VideoFrameCoords.kt` and `learnings.md`.

### Key files (`android-app/.../`)

| Path | Role |
|------|------|
| `annotation/AnnotationController.kt` | Orchestration, streaming, overlay state, role logic |
| `annotation/AnnotationChannel.kt` | Supabase Realtime (OkHttp WebSocket) |
| `annotation/VideoFrameCoords.kt` | `HOST_VIDEO_ASPECT = 9/16`, 720×1280 defaults |
| `annotation/AnchorManager.kt` | Hit-test → anchors |
| `annotation/AnnotationProjector.kt` | World → screen projection |
| `ar/ARCoreManager.kt` | Shared camera → LiveKit capturer; fallback lifecycle |
| `ui/call/LiveKitVideoView.kt` | **TextureViewRenderer** (not SurfaceView) |
| `ui/session/CallScreens.kt` | Customer AR + technician 9:16 video stack |
| `ui/annotation/TechnicianDrawingLayer.kt` | Live stream on drag (~40 ms) |
| `data/livekit/LiveKitManager.kt` | Connect, publish, fallback CameraX path |

### Wire format (matches RN `annotationRealtime.ts`)
- **Single channel:** `annotations:{sessionId}`
- **Events:** `annotation`, `annotation_sync`, `clear_annotations`
- **Payload:** `{ id, tool, color, points: [{x,y}, ...] }` (normalized 0–1 in **video space**)

---

## Phase 5 — Fixes applied (chronological)

1. **Initial pipeline** — models, channel, anchor manager, projector, controller, drawing UI on both screens.
2. **Strokes vanishing on finger lift** — synchronous local persist before network; ignore bad `annotation_sync` payloads.
3. **Blue flicker + ghost strokes** — single layer per stroke on customer; fixed `composeColorToHex()`; throttled overlay ~30 fps.
4. **Cross-device sync attempts** — REST broadcast, RLS on `realtime.messages`, subscribe ordering, application-scoped subscribe.
5. **Customer shape deterioration** — container-normalized local AR; video-normalized **only for network**.
6. **Realtime never connected (THE blocker)** — **`ktor-client-okhttp`** + WebSockets (never `ktor-client-android`).
7. **Technician overlay jitter** — REST only when WS fails; dedupe sync; debounce overlay ~15 fps.
8. **SurfaceView touch hole** — **`TextureViewRenderer`**; drawing touch layer at highest zIndex.
9. **Live streaming** — `streamTechnicianStroke()` sends partial `annotation` every ~40 ms during drag; customer 2D preview + debounced AR anchor (~120 ms).
10. **16:9 → 9:16 portrait** — technician viewport `fillMaxHeight()` + `aspectRatio(9/16)`; wire coords match mobile capture orientation.
11. **Technician own strokes screen-fixed** — apply customer `annotation_sync` for technician-originated ids (TeamViewer round-trip).
12. **Stale LAN IP** — rebuild after updating `local.properties`; clearer network error messages.
13. **AR fallback stuck** — release camera/session on fallback; reset singleton state on each `attach()`; fix capture wait-lock deadlock.

---

## Phase 5 — Debug instrumentation (still active in debug builds)

- Unified tag **`AnnotationPipeline`** — SUBSCRIBE, TX, RX, TECH_COMMIT, ANCHOR, OVERLAY, coord conversions
- **`BuildConfig.ANNOTATION_DEBUG_OVERLAY`** — red probe circle on technician overlay (debug only)

### Logcat (both devices during test)
```powershell
adb -s R3CT80DRKFP logcat -s AnnotationPipeline AnnotationChannel AnnotationController ARCoreManager LiveKitManager
adb -s RZ8R704R4AV logcat -s AnnotationPipeline AnnotationChannel AnnotationController LiveKitManager
```

### Expected log sequence (technician draws)
1. **Tech:** `[TECH_COMMIT]` or live `[TX] annotation` during drag
2. **Customer:** `[RX] annotation` → `[ANCHOR] hitTest PLANE` → `[ANCHOR] created`
3. **Customer:** `[TX] annotation_sync` every ~50 ms (projected world positions)
4. **Tech:** `[RX] annotation_sync` → overlay updates; stroke **moves with video** when customer pans

---

## Verified on device ✅

- Customer: scan ring → surface found → stable tracking → world-anchored strokes while moving phone.
- Customer draws → technician sees matching overlay on LiveKit video (via `annotation_sync`).
- Technician draws → customer receives, anchors on plane; **technician overlay tracks scene** via round-trip sync.
- **Live stroke streaming** during technician drag — customer sees ink before finger lifts.
- **9:16 portrait** technician panel — full-height drawing, no invisible 16:9 band mismatch.
- WebSocket subscribe succeeds on both devices (~700 ms after join).

### Phase 5 exit checklist — remaining (Phase 6)
- [ ] Clear button syncs both devices under load
- [ ] Long session stability (30+ min, many strokes)
- [ ] Circle / arrow tools end-to-end QA
- [ ] Disable debug overlay probe for release builds

---

## Phase 0–4 — Completed work (summary)

### Project & auth
- Android project under `android-app/` — Compose, Hilt, Supabase Auth (Google + `remotear://auth-callback`).
- Customer / technician home screens; session API via Docker backend.

### LiveKit (Phase 3)
- `LiveKitManager`: connect, publish/subscribe A/V, mute, reconnect banner, CameraX fallback when AR unavailable.

### ARCore (Phase 4)
- `ARCoreManager`: shared camera → custom LiveKit capturer; GL frame loop.
- Scan ring, tracking state bar, plane count HUD; camera permission gating; video-only fallback with banner.

---

## Supabase changes (annotation transport)

Applied on project `suuellchcoegerddqyjb`:
- RLS policies on `realtime.messages` for authenticated **broadcast** SELECT/INSERT.

---

## Next steps (Phase 6)

1. Clear button sync under load — both devices wipe together.
2. Reconnect / Realtime resubscribe after network blip.
3. Circle and arrow tools end-to-end QA.
4. Long-session stability pass (30+ min).
5. Gate debug overlay for demo/release builds.
6. Demo dry run per `implementation-plan.md` Phase 6–7.

---

## Related docs

| Doc | Use for |
|-----|---------|
| `implementation-plan.md` | Phase checklists |
| `cursor-prompt.md` | Agent context + anchor pattern |
| `trd.md` | File layout + technical spec |
| `learnings.md` (this folder) | Debugging lessons from native work |
| `../learnings.md` | RN-era infra (LiveKit, tunnels, emulator) |
