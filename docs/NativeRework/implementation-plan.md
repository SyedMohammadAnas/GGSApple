# Remote AR Assistant — Implementation Plan (Native Android)

> Phases only. Each phase ends with a verifiable pass/fail checklist.
> Do NOT proceed to the next phase until the current phase is verified by the human operator.

---

## Phase 0 — Project Bootstrap & Infrastructure Verification

### Goal
Create the native Kotlin Android project and verify all existing infrastructure (Supabase, LiveKit,
backend API, Docker, tunnels) is working before writing any app code.

### Tasks
- Create new Android project in `android-app/` at the repo root using Android Studio:
  - Package: `com.cgsapple.remotear`
  - Min SDK: 26
  - Language: Kotlin
  - UI: Jetpack Compose
- Configure `build.gradle.kts`:
  - Add all dependencies from TRD Section 10
  - Set `buildConfigField` for `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `LIVEKIT_URL`, `API_URL`
  - Add `local.properties` reading
- Configure `AndroidManifest.xml`:
  - Add all permissions from TRD Section 8
  - Add `<meta-data android:name="com.google.ar.core" android:value="required" />`
  - Add deep link intent filter for `remotear://auth-callback`
- Set up Hilt in `RemoteArApplication.kt`
- Set up Jetpack Navigation Compose with all route definitions
- Apply dark theme from UX Design color system
- Start Docker backend + two Cloudflare tunnels + verify health endpoint returns `{ "status": "ok" }`
- Connect both Samsung phones via USB; verify `adb devices` shows both

### Verification Checklist
- [ ] `adb devices` shows two connected phones
- [ ] `http://localhost:3000/health` returns `{ "status": "ok" }`
- [ ] LiveKit Docker container is running on port 7880
- [ ] App compiles and installs on both phones without error
- [ ] App launches and shows a placeholder screen on both phones
- [ ] Both phones are on the same WiFi as the server laptop

---

## Phase 1 — Authentication

### Goal
Google Sign-In works on both phones. User is routed to the correct home screen based on role.

### Tasks
- Implement `AuthScreen` with Google Sign-In button (Compose)
- Implement Supabase auth flow:
  - `supabase-kt` auth library
  - `signInWith(Google)` → Chrome Custom Tab
  - Handle `remotear://auth-callback` deep link in `MainActivity`
  - Exchange code for session token
- Implement `AuthViewModel`:
  - Observe auth state
  - On sign-in success: fetch profile from `profiles` table
  - If no profile: Supabase trigger creates it automatically
  - Route to `CustomerHomeScreen` or `TechnicianHomeScreen` based on `profile.role`
- Implement `SplashScreen` that checks for existing auth session on cold start
- Implement sign-out from a profile menu

### Verification Checklist
- [ ] Phone 1: sign in with Google, land on CustomerHomeScreen (role = customer)
- [ ] Phone 2: sign in with Google, land on TechnicianHomeScreen (role = technician)
- [ ] Force-kill app and reopen: still signed in (session persisted)
- [ ] Sign out: returns to AuthScreen
- [ ] Profile row visible in Supabase Dashboard → Authentication → Users

---

## Phase 2 — Session Management (No Video Yet)

### Goal
Customer creates a session, technician joins it. Both see each other's screens update. Session can
be ended from either side.

### Tasks
- Implement `CustomerHomeScreen` with "Start Session" button
- Implement `POST /api/sessions` call from customer
- Implement `WaitingScreen`:
  - Display join code in large monospace text
  - Copy and Share buttons
  - Pulsing cyan waiting animation
  - Supabase Realtime subscription on `sessions` row → listen for status change to "active"
  - Navigate to `CustomerCallScreen` placeholder when active
  - Cancel button → PATCH status to "ended"
- Implement `TechnicianHomeScreen` with "Join Session" button
- Implement `JoinSessionScreen` with code input
- Implement `POST /api/sessions/join` call from technician
- Navigate technician to `TechnicianCallScreen` placeholder
- Implement session end from either side:
  - PATCH `/api/sessions/:id/end`
  - Both sides listen on Supabase Realtime `sessions` channel for status = "ended"
  - Both navigate to `SessionEndedScreen`
- Implement `SessionEndedScreen` with "Back to Home" button

### Verification Checklist
- [ ] Customer taps "Start Session"; WaitingScreen shows a join code
- [ ] Technician enters the code and taps "Join"; both phones navigate to their call placeholders
- [ ] Customer taps "Cancel" on WaitingScreen; session status becomes "ended" in Supabase
- [ ] Either side taps "End Session"; both phones return to their home screens within 3 seconds
- [ ] Session row in Supabase shows `status = "ended"` and `ended_at` populated

---

## Phase 3 — LiveKit Video Call (No AR Yet)

### Goal
Customer publishes their rear camera video via LiveKit. Technician sees the live video feed.
Audio is bidirectional. This phase uses LiveKit's built-in camera (NOT the ARCore shared camera
yet — that comes in Phase 4).

### Tasks
- Implement `LiveKitManager`:
  - Connect to LiveKit room using token from Phase 2
  - Publish local video (built-in CameraX capturer) + audio on customer device
  - Subscribe to remote video + audio on technician device
- Implement `TechnicianCallScreen`:
  - `SurfaceView` or `TextureView` for remote video rendering
  - LiveKit `VideoRenderer` attached to the subscribed `RemoteVideoTrack`
  - "Waiting for customer camera..." spinner until first frame
  - Session header with customer name + timer
  - Settings sheet with End Call + Mute
- Implement `CustomerCallScreen` placeholder (just shows camera preview, no AR yet):
  - Local camera preview via CameraX
  - HUD strip with session timer and connection status
  - Settings sheet with End Call
- Implement LiveKit reconnect handling:
  - Show "Reconnecting..." banner on `RoomEvent.Reconnecting`
  - Hide banner on `RoomEvent.Reconnected`
  - Republish video track after reconnect

### Verification Checklist
- [ ] Customer starts session; technician joins; technician sees customer's live rear camera within 3 seconds
- [ ] Both users can hear each other (hold both phones near each other and confirm no echo / feedback loop)
- [ ] If WiFi is temporarily disrupted (toggle WiFi off/on on one phone), "Reconnecting..." banner appears and video resumes
- [ ] Ending session from either side: both phones return to home cleanly
- [ ] No video freeze or black screen during a 2-minute call

---

## Phase 4 — ARCore Integration (Customer Device)

### Goal
The customer's video (now coming from ARCore Shared Camera) still reaches the technician perfectly.
The customer sees the AR camera feed through ARCore's GL renderer. Plane detection is running.
Tracking state HUD is visible.

### Tasks
- Implement `ARCoreManager`:
  - `Session(context, SHARED_CAMERA)` creation
  - `GLSurfaceView` + `Renderer` setup
  - Correct lifecycle: GL resume → session.resume() ordering (see TRD Section 5)
  - `session.resume()` failure handling → `ARFallbackManager`
  - TrackingState emitted as `StateFlow`
- Implement `ARCoreFrameCapturer`:
  - `ImageReader` (YUV_420_888, 1280×720) added as app surface to ARCore Shared Camera
  - `OnImageAvailableListener` on camera thread
  - YUV → I420 conversion (`YuvToI420Converter`)
  - `capturerObserver.onFrameCaptured()` with correct rotation metadata
  - Frame dropping: only latest frame ever queued
- Replace CameraX capturer in `LiveKitManager` with `ARCoreFrameCapturer`
- Implement `CustomerCallScreen` ARCore view:
  - `GLSurfaceView` full-screen, edge-to-edge
  - HUD strip composable overlay (timer, connection status)
  - Tracking state bar composable overlay (bottom)
  - AR scan ring animation (Compose Canvas) while no planes detected
- Implement `ARFallbackManager`:
  - On ARCore failure: release shared camera + session, then LiveKit CameraX fallback
  - Show "AR not available — video mode only" banner
  - Reset singleton AR state on each new call (`attach()`) so fallback does not stick across sessions
  - Fallback annotations will be screen-fixed (no hit test)

### Verification Checklist
- [x] Customer opens call screen; ARCore starts; camera feed visible full-screen
- [x] Technician still receives customer's video within 3 seconds (frame pipeline is now ARCore → ImageReader → I420 → LiveKit)
- [x] Tracking state HUD shows "Scanning..." initially, then "Surface found" after pointing at a flat surface
- [x] When ARCore is active, customer's camera feed is smooth (no stutters, correct orientation, no stretching)
- [x] On ARCore failure: video-only fallback activates; technician still sees video; fallback banner visible on customer screen (fallback must release camera — see `learnings.md` 2026-06-29 fix)
- [x] Plane scan ring animation is visible and pulsing when in "Scanning" state

---

## Phase 5 — World-Anchored Annotations (The Core Feature)

### Goal
Technician draws on the video feed. Customer's ARCore converts the touch to an anchor on a
detected plane. The annotation sticks to the physical surface. Customer moves phone — annotation
stays on the surface. Technician sees the annotation reflected back on the video feed.

This phase is the core product feature and must be verified with both phones physically.

### Tasks
- Implement `AnnotationChannel` (Supabase Realtime):
  - **Single channel** `annotations:{sessionId}` with broadcast events `annotation`, `annotation_sync`, `clear_annotations` (matches RN — not separate channel names per event)
  - Technician: publish `annotation` on draw; subscribe to `annotation_sync` for overlay (including own strokes after customer round-trip)
  - Customer: subscribe to `annotation`; anchor + project; publish `annotation_sync` at ~20 fps
- Implement drawing layer on technician's call screen:
  - Transparent Compose overlay on **9:16 portrait** video stack (`fillMaxHeight` + `aspectRatio(9/16)`)
  - `TextureViewRenderer` for LiveKit video (not SurfaceView — touch must work in letterbox)
  - Normalise points to **video-normalized** coords (0–1 in 9:16 rect)
  - Live stream partial strokes during drag (~40 ms) + commit on finger-up
- Implement `AnchorManager` on customer's call screen:
  - On "stroke" received: `frame.hitTest()` for each point → `createAnchor()`
  - Store anchors keyed by `strokeId` + `pointIndex`
  - On "clear" received: `anchor.detach()` for all anchors; clear list
  - Fallback: if no plane hit, use feature point hit or 0.5 m fixed depth
- Implement `AnnotationProjector` on customer's call screen:
  - Every GL frame: for each active anchor, project world-space pose to screen 2D
  - Throttle broadcast of projected coords to 20 fps
  - Update `AnnotationOverlayState` (SnapshotStateList) from render loop via `Handler(main)`
- Implement `AnnotationOverlay` Compose Canvas on customer's call screen:
  - Draw lines, circles, arrows at projected 2D screen positions
  - Colors match original stroke colors
- Implement annotation overlay on technician's call screen:
  - Receive projected-back normalised coords from Supabase Realtime
  - Draw on video feed Canvas at those positions

### Verification Checklist (THE CRITICAL TEST)
- [x] Technician draws a freehand stroke on the video feed
- [x] Customer's phone shows the annotation within 1 second
- [x] Customer moves the phone left/right/up/down — annotation stays locked to the physical surface (does NOT drift with screen movement)
- [x] Technician sees own annotations **track the scene** on the video overlay (via customer `annotation_sync`, not screen-fixed)
- [x] Live streaming — customer sees technician stroke while finger is still down
- [x] Multiple strokes are all independently world-anchored simultaneously
- [ ] Technician draws a circle annotation — same result (tool wired; formal QA pending)
- [ ] Technician draws an arrow annotation — same result (tool wired; formal QA pending)
- [ ] Technician taps Clear — all annotations disappear on both phones
- [ ] After Clear, new annotations can be drawn and anchored again

**Verified 2026-06-29 on SM-F936B + SM-N980F.** See `progress.md` and `learnings.md` for commit history and coord-space notes.

---

## Phase 6 — Error Handling & Polish

### Goal
All error states are handled gracefully. The app is stable for a demo.

### Tasks
- Camera permission denied: rationale dialog + "Open Settings" button
- ARCore unavailable / FatalException: video-only fallback + clear banner (**release camera on fallback** — done 2026-06-29)
- Tracking lost: "Move camera slowly" banner on customer screen
- LiveKit "no video": "Waiting for customer camera..." spinner with timeout message
- Session not found on join: error message "Invalid session code"
- Network offline at join: retry prompt (**LAN IP in `local.properties`** — rebuild when DHCP changes; clearer error messages — done 2026-06-29)
- Session already ended: show message, navigate to home
- Reconnect banner polish (auto-dismiss when reconnected)
- Annotation canvas: prevent drawing while not connected
- Full screen immersive mode on call screens (hide nav bar + status bar)
- Clear button sync under load; circle/arrow formal QA; long-session stability; disable debug overlay for demo builds

### Verification Checklist
- [ ] Deny camera permission → rationale shown → "Open Settings" navigates to Android settings
- [ ] Enter invalid join code → "Invalid session code" error shown
- [ ] Fast-move phone to cause tracking loss → "Move camera slowly" banner appears → resume scanning → banner hides
- [ ] Disconnect WiFi on one phone → "Reconnecting..." shown → reconnect WiFi → video resumes
- [ ] End session from customer while technician has annotations → both phones return to home cleanly

---

## Phase 7 — Demo Dry Run & Hardware Verification

### Goal
A complete end-to-end demo run with both physical Samsung phones and the live backend.
The demo must match the Demo Day Acceptance Checklist in the PRD.

### Tasks
- Run the full PRD Demo Day Acceptance Checklist (Section 8 of PRD) end-to-end
- Test with different surfaces: engine bonnet, floor, table, white wall
- Verify annotation stability on textured vs textureless surfaces
- Test with tunnel URLs (not just LAN IP)
- Confirm both phones show no crash logs during a 5-minute call
- Document any known limitations for demo day context

### Verification Checklist
- [ ] Full PRD Demo Day Acceptance Checklist passes 100%
- [ ] Annotations stay on surface for at least 30 seconds while customer moves phone freely
- [ ] No ANR or crash during a 5-minute continuous session
- [ ] Tunnel URLs work (not just LAN IP)
- [ ] Demo can be reproduced 3 times in a row without failure

---

## Post-MVP Phases (Not in Scope Now)

| Phase | Feature |
|-------|---------|
| 8 | 3D GLB model placement (tap-to-place via ARCore hit test, rotate/scale via sliders) |
| 9 | Model library UI + Draco-compressed GLB download from backend |
| 10 | iOS ARKit implementation |
| 11 | Session recording |
| 12 | Cloud Anchors (persistent annotations across sessions) |
