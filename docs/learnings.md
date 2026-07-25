# Remote AR — Engineering Learnings

Notes from debugging sessions, fixes, and infrastructure setup. Add new entries as issues are discovered and resolved.

---

## Native Android — new Samsung phones (2026-07-21 / landed 2026-07-22)

Verified on Samsung A35 (SM-A356E) + S24 FE (SM-S721B), both Android 16. Vault: SergantSwaggBase `Progress/GGSApple-New-Phones-AR-Fixes-2026-07-22` + pitfalls under `Projects/GGSApple/`.

### ARCore `INSTALL_REQUESTED` must wait, not fall back

- **Symptom:** Play Store opens for Play Services for AR; after return, app stays in permanent CameraX / non-AR mode.
- **Cause:** Treating `InstallStatus.INSTALL_REQUESTED` like unsupported.
- **Fix:** Official sample pattern — `userRequestedInstall` flag, `awaitingArInstall`, UI `ArTrackingUiState.INSTALLING`, retry `openCamera()` on resume.

### Samsung Android 16 + ARCore 1.54 `Session.resume` FatalException

- **Upstream:** [arcore-android-sdk#1762](https://github.com/google-ar/arcore-android-sdk/issues/1762).
- **Fix:** Keep uncalibrated gyro + accel streaming at `SENSOR_DELAY_FASTEST` before create/resume; manifest `HIGH_SAMPLING_RATE_SENSORS`; prefer `TEMPLATE_PREVIEW`; ARCore `1.48.0+`; `jniLibs.useLegacyPackaging = true` for 16 KB page installs.

### Concurrent Camera2 open → permanent fallback

- **Symptom:** `CameraDevice was already closed` then CameraX fallback.
- **Cause:** Multiple `openCamera()` callers racing; `onClosed` missing so teardown `ConditionVariable` stalled 3s into the next open.
- **Fix:** Generation + in-flight guards, soft retry, implement `onClosed`.

### Call chrome taps stolen by draw layer

- **Symptom:** Mute / end / undo / clear dead while drawing still works.
- **Cause:** `DrawingTouchLayer` at `zIndex(9)` above chrome at `6`.
- **Fix:** Draw/pointer `zIndex(5)`, chrome `20`, snackbar/errors `25`; keep UNDO/DELETE always enabled.

### Backend Docker without `public/`

- **Fix:** `RUN mkdir -p public` instead of `COPY public` in `backend/Dockerfile`.

---

## LiveKit & WebRTC

### Session signaling vs media are separate paths

- **Signaling** (WebSocket to `/rtc/v1`) can succeed via Cloudflare tunnel while **media** (UDP/WebRTC ICE) still fails.
- UI can show "Session joined" (API session) while video shows "Connecting to video…" (LiveKit `RoomContext` missing or no remote tracks).

### `10.0.2.2` is emulator-only

- `10.0.2.2` is the Android emulator's alias for the host machine's loopback.
- A **physical phone** on Wi‑Fi (e.g. `192.168.0.100`) **cannot** reach `ws://10.0.2.2:7880`.
- **Symptom:** `websocket closed` with reason `failed to connect to /10.0.2.2 (port 7880) from /192.168.0.100`.
- **Fix:** Bind LiveKit URL to Android **product flavor** (`phone` vs `emulator`), not `Constants.isDevice` (unreliable in dev client builds).

| Build flavor | LiveKit signaling URL |
|--------------|----------------------|
| `phone` | `EXPO_PUBLIC_LIVEKIT_URL` (Cloudflare `wss://…`) |
| `emulator` | `ws://10.0.2.2:7880` (baked in `BuildConfig`) |

### ARCore and LiveKit cannot share the rear camera (Android)

> **Full write-up:** [Phase 3 roadblocks — AR + LiveKit camera](#phase-3-roadblocks--ar--livekit-camera)

- **Symptom:** Customer black screen after join; technician sees a few seconds of video then a frozen frame (or both sides black).
- **Cause:** `ViroARScene` (ARCore) and `@livekit/react-native-webrtc` both need exclusive access to the rear camera.
- **JS-only mitigations tried** (`useARCameraHandoff`, `ar_mode` placeholder): **not sufficient** as of 2026-06-20.
- **MVP paths:** native Shared Camera / custom `VideoCapturer`, product split (AR without live video), or defer AR.

### Emulator flavor on a physical phone (Phase 3)

- **Symptom:** Host shows "Video mode — AR not available", timer stuck at `00:00`, technician sees no video/audio.
- **Log:** `failed to connect to /10.0.2.2 (port 7880) from /192.168.x.x`.
- **Cause:** The **emulator** APK (`emulatorDebug`) was installed on the USB phone — `ENABLE_VIRO=false` and LiveKit URL `10.0.2.2`.
- **Common trigger:** Both phone and emulator connected; `npm run android:emulator` installed on the wrong device.
- **Fix:** Install phone flavor on the physical device only:
  ```powershell
  cd remote-ar\android
  $env:ANDROID_SERIAL="RZCX6253PKY"   # your phone serial from adb devices
  .\gradlew.bat app:installPhoneDebug -PreactNativeDevServerPort=8081
  ```
- **Prevention:** `android:emulator` now targets `emulator-5554` only. App shows a red **Wrong app build** banner when `appTarget=emulator` on a physical device.

### Public STUN IP breaks emulator ICE

- With `rtc.use_external_ip: true`, LiveKit advertises the public IP (e.g. `49.205.96.163`) from STUN.
- Emulator (`10.0.2.16`) could not complete ICE to that IP → `removing participant without connection` in Docker logs.
- **Fix:** `use_external_ip: false` and `node_ip: <host LAN IP>` (e.g. `192.168.0.102` from `ipconfig`).

### TURN helps emulator ↔ Docker LiveKit

- Enable embedded TURN in `livekit.yaml` and expose port `3478` in `docker-compose.yml`.
- Docker logs showed successful `udp relay` candidates for emulator after TURN was enabled.

### LiveKit config field names (v1.13)

- Use `rtc.node_ip`, **not** `nat_1to1_ips` (invalid in v1.13 — container fails to start).

### Host and technician must be in the same LiveKit room

- Technician showing "Waiting for customer to join…" with LiveKit connected means **no remote participant** — host failed to connect or joined a different room.
- Orphan sessions from repeated "Start Session" taps create new rooms; always use a single Start + single Join per test.

---

## React Native / Expo app

### Do not clear call state on Home focus

- `useFocusEffect` + `clearCall()` on Home caused races: session created → navigate to Waiting → state cleared → bounce to Home.
- Clear call state only on explicit end/cancel/sign-out.

### Atomic LiveKit enable in `setSession`

- Split `setSession` (livekitEnabled: false) + `enableLiveKit()` caused `LiveKitAppProvider` to drop `RoomContext` briefly.
- Enable LiveKit in the same `setSession` update.

### `connectLiveKitRoom` must not disconnect same-token connects

- Only disconnect when the **token changes**, not on every effect re-run.
- Use connect deduplication (`pendingConnect`) to avoid Strict Mode double-connect races.

### Native rebuild required after Gradle/Kotlin changes

- Flavor `BuildConfig` fields (`APP_TARGET`, `LIVEKIT_WS_URL`) require `npm run android:phone` / `android:emulator` rebuild, not just Metro reload.

### Dual Metro — only for phone + emulator (historical)

Running phone and **emulator** against the **same Metro port** bundled the wrong JS (different `BuildConfig` / LiveKit URL per flavor). That was the Phase 2 blocker.

| Device | Metro command | Port | Build |
|--------|---------------|------|-------|
| Phone (host) | `npm run start:phone` | **8081** | `npm run android:phone` |
| Emulator (technician) | `npm run start:emulator` | **8082** | `npm run android:emulator` |

**Two physical phones (current setup):** both use `phoneDebug` — **one Metro on 8081** is enough. Install with `npm run android:phone` and `npm run android:phone2` (both default to port 8081).

### Phase 2 verified (2026-06-19)

Successful session in Docker logs (`room 232403e6-…`):

- Host `312d5d70…` — `participant active`, `mediaTrack published` audio + **video** (1280×720 VP8)
- Technician `ec3ec5e2…` — `participant active`, audio published, subscribed to host video
- ICE via `192.168.0.102` LAN + TURN relay for emulator
- UI: technician sees live customer camera, **Data channel: connected**
- Session duration ~5 min, clean `CLIENT_REQUEST_LEAVE` on end

**Phase 3+ shipped 2026-06-20** — see [`summary.md`](summary.md).

---

## Phase 3 roadblocks — AR + LiveKit camera (resolved)

> **Status:** Native Shared Camera bridge is **shipped**. Viro handoff is **retired**. See [`native-shared-camera-architecture.md`](native-shared-camera-architecture.md).

Phone call path uses `ARCameraModule` + `SharedCameraController` + `ARCameraView`. When ARCore `session.resume()` fails (Samsung M14), app uses **CPU ImageView preview** + **sensor-based pseudo-AR** for annotations — still streams live video to technician.

### Build & environment (still apply)

| Issue | Fix |
|-------|-----|
| Wrong APK on phone | `$env:ANDROID_SERIAL="<phone>"`; `.\gradlew app:installPhoneDebug` |
| Shared Metro port (phone + emulator) | Phone **8081**, emulator **8082** — not needed for two phones |
| LiveKit tunnel down | Restart `cloudflared`; update `.env` |
| Phone log shows `10.0.2.2:7880` | Reinstall `phoneDebug` |

### Shared Camera crash on join (SIGSEGV) — fixed 2026-06-20

- **Cause:** ARCore SDK mismatch (Viro AAR vs Play Services 1.51) + camera race with LiveKit.
- **Fix:** Maven `com.google.ar:core:1.51.0`; two-phase handoff (`releaseHostCamera` → `armSharedCameraStart` → mount view → publish track).

### ARCore `session.resume()` FatalException (Samsung M14)

- Reproduces in **`native-spike/`** standalone app — **not RN-specific**.
- **Fallback:** `arcoreResumeFailed` → `CpuPreviewDisplay` (ImageView) + `FallbackPoseTracker` for raycast/project.
- **Do not** retry resume in a loop after failure.

### Preview rotation vs stream (fixed 2026-06-20)

- WebRTC `VideoFrame.rotation` is **counter-clockwise**; Android `Matrix.postRotate` is **clockwise**.
- Preview must use `StreamOrientationHelper.webrtcRotationToBitmapRotation()` or preview looks 90° off and stretched while technician stream is correct.
- `computeDisplayRotation()` should prefer **activity** `windowManager.defaultDisplay.rotation`, not only `GLSurfaceView.display`.

### Stream latency (fixed 2026-06-20)

- **Cause:** `onImageAvailable` did NV21 copy + I420 encode + preview JPEG on same thread every frame.
- **Fix:** Stream-first at 20 fps; preview only in fallback on low-priority thread (~12 fps); `FramePushVideoCapturer` drops superseded frames; ImageReader buffer = 2.

### Phase 4 annotations (2026-06-20)

- Technician draws in normalized video space → customer `raycast` → `worldPoints` → `useAnnotationProjection` reprojects as phone moves → `annotation_sync` to technician.
- With ARCore active: plane `hitTest` + ARCore `projectPoint`.
- With fallback: `FallbackPoseTracker` (rotation vector + pinhole unproject at ~1.2 m).

### Implementation state

| Area | State |
|------|--------|
| `ARCameraModule`, `SharedCameraController`, `ARCameraView` | Phone flavor ✅ |
| `sharedCameraMedia.ts`, `useSharedCameraSession` | ✅ |
| `useARCameraHandoff`, Viro on call path | **Removed / not mounted** |
| Phase 4 drawing + sync | ✅ (pseudo-3D on fallback) |
| Native GLES `AnnotationRenderer` | Deferred — JS overlay + reprojection for now |

### Do not assume

- ❌ Metro reload after Kotlin changes — **native rebuild required**
- ❌ Viro and native ARCore session together on call screen
- ❌ `bitmap.recycle()` after LiveKit `pushBitmap` — async thread still reads it

---

## Docker & backend

### Rebuild API image after backend changes

- `docker compose restart` does **not** pick up `backend/src` changes.
- Use `docker compose up -d --build` after editing session routes.

### Auto-end stale sessions on new create

- Backend ends prior `waiting`/`active` sessions for the same customer when `POST /api/sessions` runs — prevents orphan room/token confusion during dev retries.

### Cloudflare quick tunnels

- Tunnels are ephemeral; URLs change when `cloudflared` restarts.
- Update root `.env`, `remote-ar/.env`, and Supabase `models` if URLs change.
- LiveKit tunnel had `EOF` errors during Docker restarts; verify with `curl` after restart.

### LiveKit `node_ip` must match your LAN

- If Wi‑Fi IP changes, update `livekit.yaml` `rtc.node_ip` and `turn.domain`, then `docker compose up -d livekit`.

---

## LiveKit reconnection & state persistence

### ICE restart triggered by "peerconnection failed disconnected"

- **Symptom:** Customer phone "refreshes" mid-session, losing annotations and UI top bar (connected status, timer). Technician sees "waiting for customer video" despite audio still working.
- **Logs:** `WARN peerconnection failed disconnected`, followed by `INFO resuming RTC session` with `ReconnectReason: RR_PUBLISHER_FAILED`, then `short connection by client ice restart` (~40 seconds duration).
- **Cause:** Network instability or temporary ICE connectivity loss on Wi-Fi. Even with correct `node_ip` (`192.168.0.111`), peer connections can fail if packets are lost or Wi-Fi quality degrades.
- **LiveKit behavior:**
  - LiveKit client automatically attempts ICE restart to restore connectivity
  - During reconnection, the Room object's state may briefly become `disconnected` or `reconnecting`
  - Video track is unpublished and re-negotiated during ICE restart
  - Data channels (for annotations) are re-established, but listeners must survive the reconnection

### Best approach: React state + LiveKit reconnection lifecycle

**Root issue:** React components (`ARCallScreen`, UI state, etc.) rely on LiveKit `room` context. When the room reconnects:
1. The `room` object reference may change (triggering React re-renders)
2. UI components may remount, losing local React state (though Zustand persists)
3. Data channel event listeners (`RoomEvent.DataReceived`) must be properly re-attached

**Solution strategy (implemented 2026-06-24):**
1. **Zustand state survives:** Annotations in `useARStore` persist across re-renders ✅
2. **Reconnection banner:** Show during `Reconnecting` and `SignalReconnecting` ✅
3. **Keep `RoomContext` during `SignalReconnecting`:** `isLiveKitSessionActive()` in `LiveKitAppProvider` — was the main bug (context dropped → HUD/annotations lost)
4. **Re-publish shared camera on `Reconnected`:** `useSharedCameraSession` republishes if camera track missing
5. **Re-sync annotations on `Reconnected`:** Customer calls `resyncAnnotationsToRoom()` from `useAnnotationSync`
6. **HUD fallback without room:** `ARHUDStrip` / `TechnicianHUDStrip` show timer + "Reconnecting" when `livekitEnabled` but context briefly null
7. **Don't `resetScene()` on AR screen effect cleanup** — only on explicit session end (was clearing annotations on reconnect remount)

### Preventing unnecessary disconnects

- **Current fix:** `peerConnectionTimeout: 45_000` in `livekitService.ts` prevents indefinite hangs but doesn't prevent disconnections from network issues
- **Additional mitigations:**
  - Ensure `node_ip` in `livekit.yaml` matches the current host LAN IP (update after router changes or Wi-Fi reconnects)
  - Both phones must stay on the **same Wi-Fi network** as the server for LAN ICE
  - Avoid running other high-bandwidth tasks on the Wi-Fi during testing
  - For production: Use public TURN server (not LAN TURN) to handle clients on different networks

### Why video drops but audio continues

- **Audio track:** Lower bandwidth, more resilient to packet loss (uses RED redundancy)
- **Video track:** Higher bandwidth (640×480 VP8), more sensitive to network quality
- During ICE restart, video track is unpublished (as seen in logs: `unpublishing track TR_VCkkwTNpiV4Vtc`) and must be re-negotiated
- Audio may continue briefly on old connection while video waits for new ICE candidates

### Data channel reliability across reconnection

- LiveKit data channels use SCTP over DTLS (reliable ordered delivery)
- Channels are restored after ICE restart, but messages sent *during* the reconnection window may be lost
- **2026-06-24 — Annotations moved off LiveKit:** use **Supabase Realtime broadcast** on channel `annotations:{sessionId}` (`annotationRealtime.ts`). LiveKit is **video + audio only**. AR overlay sync survives WebRTC reconnects; technician drawing no longer depends on data channels.

### Session teardown order (2026-06-24)

End session must follow this order to avoid `PC manager is closed` and zombie reconnect logs:

1. `beginSessionEnd()` — block new publishes/connects
2. `livekitEnabled: false` — stop provider reconnect attempts
3. `leaveAllAnnotationChannels()` — Supabase broadcast cleanup
4. `teardownSharedCamera()` (customer) — native camera + unpublish
5. `disconnectLiveKitRoom(true)` + `AudioSession.stopAudioSession()`
6. Reset call store

Use `clearCallSession()` from `callSessionTeardown.ts` (formerly `sessionLifecycle.ts`, deleted to break require cycles); always `await` before navigating home.

### AR annotation architecture (recommended)

| Layer | Transport | Responsibility |
|-------|-----------|----------------|
| **LiveKit** | WebRTC | Customer camera (shared native track), mic, technician receives video |
| **Supabase Realtime** | WebSocket broadcast | Technician draw → customer raycast → `annotation_sync` back to technician |
| **Zustand `arStore`** | In-memory | Local overlay state; survives brief UI remounts |
| **Native ARCore** | On-device | `raycast` / `projectPoint` for 3D-anchored overlays |

Flow: technician draws in normalized video space → Supabase `annotation` event → customer raycasts to world points → reprojects each frame → Supabase `annotation_sync` → technician overlay updates.

**Do not** use LiveKit `publishData` for annotations — it couples AR UX to ICE/signal stability.

### Error: "dropping candidate with ufrag mIiV because it doesn't match the current ufrags"

- **Symptom:** Appears in LiveKit Docker logs during ICE restart
- **Cause:** ICE credentials (`ufrag`, `pwd`) change when a new offer/answer is negotiated
- **Harmless:** This is expected behavior when old ICE candidates arrive after the new offer is set. LiveKit correctly discards them.
- **Not a bug:** Does not cause the disconnection (it's a side effect, not the cause)

## Debugging checklist

1. **Docker:** `docker compose ps` + `docker compose logs --tail 50 livekit`
2. **API:** `curl http://127.0.0.1:3000/health`
3. **Tunnel:** `curl https://<api-tunnel>/health`
4. **Phone Metro:** signaling should use `wss://…trycloudflare.com`
5. **Emulator Metro:** signaling should use `ws://10.0.2.2:7880`
6. **LiveKit logs:** look for `participant active` (both users) and `mediaTrack published` (camera on host)
7. **One Start, one Join** per test; force-quit apps between failed attempts
8. **Reconnection debugging:**
   - Check LiveKit logs for `resuming RTC session` and `ReconnectReason`
   - Verify `ice reconnected or switched pair` appears after a few seconds
   - Ensure `node_ip` in `livekit.yaml` matches `ipconfig` LAN IP
   - Both phones should show "Reconnecting..." banner briefly, then return to connected state

---

## Changelog

| Date | Learning |
|------|----------|
| 2026-06-19 | Initial doc: ICE/emulator, flavor-based LiveKit URL, session race fixes, Docker/LiveKit config |
| 2026-06-19 | **Phase 2 verified:** separate Metro ports (8081 phone / 8082 emulator); never share one port |
| 2026-06-19 | Flavor `BuildConfig` for LiveKit URL beats `Constants.isDevice`; native rebuild after Gradle changes |
| 2026-06-20 | **Native Shared Camera:** ARCameraModule replaces Viro handoff; see `docs/native-shared-camera-architecture.md` |
| 2026-06-20 | **Shared Camera crash fix:** ARCore 1.51 Maven dep, camera handoff ordering, armed start gate |
| 2026-06-20 | **native-spike isolation test:** `session.resume()` FatalException on Samsung SM-M146B reproduces outside RN — not Viro-specific |
| 2026-06-20 | **Video-only MVP path:** ImageReader → I420/LiveKit stream + ImageView CPU preview when ARCore resume fails |
| 2026-06-20 | **LiveKit BitmapFrameCapturer:** never `bitmap.recycle()` after `pushBitmap` — async VideoCaptureThr still reads it |
| 2026-06-24 | **Two-phone setup:** single Metro on 8081; `android:phone2` defaults to same port |
| 2026-06-24 | **LiveKit ICE:** `node_ip` / TURN domain must match current LAN IP (`ipconfig`); stale IP caused negotiation timeouts |
| 2026-06-20 | **Latency:** stream-first pipeline, frame drop in capturer, decoupled CPU preview |
| 2026-06-20 | **Phase 4 pseudo-3D:** `FallbackPoseTracker`, `useAnnotationProjection`, raycast/project wired end-to-end |
| 2026-06-20 | **Session verified:** E2E call + annotations on M14 with CPU fallback; true plane AR pending second device |
| 2026-06-24 | **LiveKit reconnection lifecycle:** ICE restart after ~40s causes 'peerconnection failed' ? video unpublish ? customer UI 'refresh'. Annotations persist in Zustand; data channel listeners re-attach via useEffect. Best approach: treat Reconnecting state as transient (show banner, don't unmount), ensure node_ip current, both phones on same Wi-Fi. Video drops but audio continues due to different resilience levels. |
| 2026-06-24 | **Annotation transport:** Supabase Realtime broadcast (`annotations:{sessionId}`) replaces LiveKit data channels — sync survives ICE restarts; user confirmed faster than data channel path. |
| 2026-06-24 | **Host video modes:** `EXPO_PUBLIC_HOST_VIDEO_MODE` = `livekit` (fast WebRTC camera) vs `shared-ar` (ARCore shared camera → LiveKit). World annotations require `shared-ar` + `EXPO_PUBLIC_AR_WORLD_ANNOTATIONS=true`. Android cannot run both default LiveKit rear camera and full ARCore session without handoff. |
| 2026-06-24 | **IMU pseudo-AR attempt (livekit mode):** `startImuPoseTracking` + `FallbackPoseTracker` without camera handoff — **not true AR**; gravity-aligned fixed-depth plane only. Reverted product direction to `shared-ar` after user feedback. |
| 2026-06-24 | **shared-ar speed optimizations (unverified):** `CustomerSharedArLayer` arms ARCore on Waiting screen; single persistent `ARCameraView`; skip 1s camera-release delay when LiveKit never published camera; removed 300ms publish delay; `videoFrameCoords.ts` for 16:9 cover-crop mapping. **User report: still not working** — no successful E2E proof of fast video + plane-anchored annotations. |
| 2026-06-24 | **Stale LAN IP:** `.env` had `192.168.0.111` while PC was `192.168.0.102` — phones cached wrong LiveKit URL from Metro bundle + gradle `BuildConfig.LIVEKIT_WS_URL`. Fix: correct `.env`, restart LiveKit Docker, rebuild APKs. |
| 2026-06-24 | **Require-cycle cleanup:** deleted `sessionLifecycle.ts`; split into `sessionEnding.ts` (zero imports) + `callSessionTeardown.ts` + `callStore` bridge. |
| 2026-06-24 | **Technician video subscribe path:** `livekitService` force-subscribe on connect/`TrackPublished`; `CallVideoPanel` + `RemoteHostVideo` + debug overlay; `dynacast: false`. Worked in `livekit` mode on LAN; **not proven** in `shared-ar` mode. |

---

## 2026-06-24 debugging marathon — video + world annotations (unverified)

> **Outcome:** Multiple fixes shipped; **user still reports failure** — technician video delayed/missing and annotations not world-relative. Nothing in this session produced a confirmed pass on **true ARCore plane anchoring + reliable technician video** together.

### Problems reported (recurring)

| Symptom | When |
|---------|------|
| Technician “waiting for customer video” / no video for 10–15s+ | `shared-ar` mode, after handoff |
| Annotations screen-fixed (glued to display, not surfaces) | Both modes at various points |
| Session end errors (`NegotiationError`, ping timeout, zombie reconnect) | End Session / teardown races |
| Metro require-cycle warnings | `sessionLifecycle.ts` circular imports |

### Architecture work (kept in codebase)

| Change | Why | Proved? |
|--------|-----|---------|
| **Supabase Realtime** for annotations (`annotationRealtime.ts`, `annotationChannel.ts`) | Decouple AR sync from WebRTC ICE; faster than LiveKit data channels | ✅ Sync speed confirmed by user |
| **`hostVideoMode.ts`** — `livekit` vs `shared-ar` | Single rear camera on Android — pick fast video OR ARCore shared camera | ⚠️ Mode trade-off understood; neither mode fully validated for both goals |
| **`livekit` + IMU** (`startImuPoseTracking`, `useImuWorldTracking`) | Attempt “fast video + world annotations” without handoff | ❌ **Not true AR** — pseudo-3D only; abandoned for product direction |
| **`shared-ar` optimizations** (`CustomerSharedArLayer`, early arm on Waiting, persistent GL view, faster handoff) | Reduce cold-start when technician joins | ❌ User: still slow / no video / annotations not AR |
| **`videoFrameCoords.ts`** | Technician draws on letterboxed video panel; customer raycasts on full-screen AR — normalize to 16:9 video frame | ❓ Theoretically correct; not verified on device |
| **`worldAnchor.ts`** waits for `isPoseTrackingReady` + tracking `normal`/`limited` | Don't raycast before ARCore/IMU ready | ❓ Not verified — may still return empty `worldPoints` silently |
| **Session teardown** (`sessionEnding.ts`, `callSessionTeardown.ts`) | Ordered teardown; break require cycles | ✅ Reduced end-session noise in dev |
| **LiveKit URL in `BuildConfig`** (`livekitUrl.ts`, `build.gradle`) | `EXPO_PUBLIC_*` stale in cached bundle on phone | ✅ Fixed LAN connect when IP correct + rebuild |
| **`livekit` mode on LAN** (`ws://192.168.0.102:7880`) | Fast technician video | ✅ User confirmed video in sync |
| **`shared-ar` mode** | True ARCore plane hit-test + reprojection | ❌ User: video regressed + annotations still not world-relative |

### Fundamental constraint (unchanged)

```
Android rear camera (one hardware stream)
    ├── livekit mode: WebRTC owns camera → fast video, NO ARCore session → no plane AR
    └── shared-ar mode: ARCore Shared Camera → LiveKit publish → plane AR possible, ~handoff cost
```

**There is no magic third path** on a single phone without either:

- accepting shared-camera handoff latency, or
- accepting pseudo-AR (IMU / `FallbackPoseTracker`), or
- splitting product (AR without live video).

### Why fixes “didn’t prove anything”

1. **Success criteria shifted mid-debug** — video worked in `livekit`; world AR needed `shared-ar`; optimizing one broke the other.
2. **No single E2E checklist pass recorded** — fixes were incremental; user kept reporting the same two failures.
3. **Samsung M14 ARCore resume** — device may never reach `trackingState: normal` with plane hit-test; fallback path is pseudo-3D even in `shared-ar`.
4. **Coordinate space** — technician video panel vs customer full-screen AR may still mismatch despite `videoFrameCoords` (rotation, WebRTC frame rotation, ARCore texture dims not wired to JS).
5. **Env / rebuild sensitivity** — wrong LAN IP, stale Metro bundle, missing native rebuild after Kotlin changes → false negatives during testing.
6. **Persistent AR layer** (`CustomerSharedArLayer`) — AR view behind navigator; transparent `ARCallScreen` required; may have z-order / lifecycle bugs not validated.

### Current `.env` intent (as of last edit)

```env
EXPO_PUBLIC_HOST_VIDEO_MODE=shared-ar
EXPO_PUBLIC_AR_WORLD_ANNOTATIONS=true
EXPO_PUBLIC_LIVEKIT_URL=ws://192.168.0.102:7880
```

### What would actually prove true AR

1. Second phone known-good for ARCore `session.resume()` (not M14-only).
2. Logs or HUD showing `worldPoints.length > 0` after technician draw.
3. Customer moves phone — annotation stays on physical surface (not screen).
4. Technician sees video within ~3s of session `active` with shared-ar warmup on Waiting screen.

### Key files (this sprint)

| Path | Role |
|------|------|
| `remote-ar/src/config/hostVideoMode.ts` | `livekit` / `shared-ar`, `usesARWorldAnnotations()` |
| `remote-ar/src/components/ar/CustomerSharedArLayer.tsx` | Early ARCore arm + persistent `ARCameraView` |
| `remote-ar/src/hooks/useSharedCameraSession.ts` | Handoff + publish shared camera to LiveKit |
| `remote-ar/src/hooks/useAnnotationSync.ts` | Technician → customer → `annotation_sync` |
| `remote-ar/src/hooks/useAnnotationProjection.ts` | Customer reproject world → screen |
| `remote-ar/src/utils/worldAnchor.ts` | Raycast stroke → `worldPoints` |
| `remote-ar/src/utils/videoFrameCoords.ts` | 16:9 cover-crop normalized coords |
| `remote-ar/src/services/annotationRealtime.ts` | Supabase broadcast transport |
| `remote-ar/src/services/callSessionTeardown.ts` | Ordered session cleanup |

