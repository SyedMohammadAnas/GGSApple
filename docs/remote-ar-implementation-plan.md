# Remote AR Assistant — Implementation Plan

> Version 1.2 · MVP Scope · **Progress snapshot: 2026-06-20**

---

## Progress snapshot

| Phase | Status | Notes |
|-------|--------|-------|
| **0** Environment & build | ✅ Done | Docker, Supabase, Expo dev builds, models |
| **1** Auth & navigation | ✅ Done | Google OAuth, role flow, session create/join |
| **2** LiveKit video call | ✅ Done | Dual Metro, flavors, TURN, data channel |
| **3** Native shared camera | ✅ Done | CPU preview fallback on M14; spike isolated resume failure |
| **4** World-relative annotations | 🟡 Mostly done | Drawing + sync + pseudo-3D; true planes need second device |
| **5** 3D models | ⏳ Next | |
| **6** Error handling & polish | ⏳ Pending | |
| **7** Integration & demo | ⏳ Pending | |

Phases 0–2 checklists below are **historical** — all gate criteria met. Use unchecked items only in Phases 4–7.

---

## Phase 0 — Environment Setup and Build Pipeline

The most underestimated phase. Nothing in this project can be developed or tested without a working Expo Development Build on physical Android hardware. Resolve this first before writing any feature code.

### Backend Infrastructure

- [ ] Install Docker Desktop on server laptop
- [ ] Create `docker-compose.yml` with LiveKit server and Node.js API containers
- [ ] Generate LiveKit API key and secret from LiveKit Cloud dashboard (or configure self-hosted)
- [ ] Configure `livekit.yaml` with correct port bindings and API credentials
- [ ] Start LiveKit container and verify it is accessible on localhost:7880
- [ ] Install Cloudflared on server laptop
- [ ] Create Cloudflare Tunnel for Node.js API (port 3000) and LiveKit (ports 7880, 7881)
- [ ] Verify Cloudflare Tunnel URLs are reachable from a phone on mobile data
- [ ] Create `backend/` Node.js project with TypeScript, Express, `livekit-server-sdk`, and `@supabase/supabase-js`
- [ ] Implement `GET /health` — verify it returns 200 through Cloudflare Tunnel
- [ ] Set up `backend/assets/models/` and `backend/assets/thumbnails/` directories
- [ ] Serve static assets from `/assets/` route

### Supabase Setup

- [ ] Create Supabase project
- [ ] Enable Google OAuth provider in Supabase Auth dashboard (add Google Cloud OAuth credentials)
- [ ] Configure redirect URL for deep link: `remote-ar://auth-callback`
- [ ] Create `profiles` table with SQL from backend schema document
- [ ] Create `sessions` table with SQL from backend schema document
- [ ] Create `models` table with SQL from backend schema document
- [ ] Enable RLS on all three tables and add policies per schema document
- [ ] Create `on_auth_user_created` trigger and function
- [ ] Seed `models` table with 2–3 placeholder entries (URLs pointing to your tunnel)

### Expo Project Setup

- [ ] Run `npx create-expo-app remote-ar --template expo-template-blank-typescript`
- [ ] Install all dependencies: `@reactvision/react-viro`, `@livekit/react-native`, `@livekit/react-native-webrtc`, `livekit-client`, `zustand`, `@supabase/supabase-js`, `expo-file-system`, `@react-native-async-storage/async-storage`, `expo-linking`
- [ ] Configure `app.json` with ViroReact and LiveKit Expo plugins
- [ ] Add all required Android permissions to `app.json`
- [ ] Connect ARCore-capable Android phone via USB with USB Debugging enabled
- [ ] Run `npx expo run:android` — verify build succeeds and installs on device
- [ ] Confirm Fast Refresh works for JS changes without rebuild
- [ ] Create `.env` with all environment variables
- [ ] Create `src/` folder structure: `screens/`, `stores/`, `components/`, `services/`, `types/`, `utils/`, `constants/`

### Model Asset Preparation

- [ ] Download 2–3 free GLB models from Poly Pizza or Sketchfab
- [ ] Install `gltf-pipeline` globally: `npm install -g gltf-pipeline`
- [ ] Compress all models: `gltf-pipeline -i model.glb -o model_draco.glb -d`
- [ ] Verify compressed sizes are below 10 MB each
- [ ] Place compressed files in `backend/assets/models/`
- [ ] Create 128×128 PNG thumbnails for each model and place in `backend/assets/thumbnails/`
- [ ] Verify models are served correctly from the Node.js static file route via Cloudflare Tunnel

**Phase 0 Gate:** Expo Development Build runs on physical device. `GET /health` returns 200 through Cloudflare Tunnel. All three model files download successfully from Tunnel URL via curl on a phone.

---

## Phase 1 — Authentication and Navigation Foundation

### Zustand Store Initialisation

- [ ] Create `src/stores/sessionStore.ts` (user, role, auth state)
- [ ] Create `src/stores/callStore.ts` (room, status, session id)
- [ ] Create `src/stores/arStore.ts` (model, placement, rotation, scale, annotations, error)
- [ ] Export stores from `src/stores/index.ts`

### Supabase Auth Integration

- [ ] Create `src/services/supabaseClient.ts` — initialise Supabase client with env vars
- [ ] Implement `signInWithGoogle()` using `supabase.auth.signInWithOAuth`
- [ ] Configure Expo Linking to handle `remote-ar://auth-callback` deep link
- [ ] Implement `onAuthStateChange` listener to detect session and populate SessionStore
- [ ] Implement `signOut()` function
- [ ] Fetch user profile from `profiles` table after login and store role in SessionStore

### Screens

- [ ] Build `SplashScreen` — full-screen bg-primary with loading indicator; checks auth state on mount
- [ ] Build `AuthScreen` — single Google sign-in button, centered; branded with app name
- [ ] Build `RoleRouter` — invisible screen that reads SessionStore.role and navigates accordingly
- [ ] Build navigation container (React Navigation or Expo Router) with auth flow and role branching

### Customer Home

- [ ] Build `CustomerHomeScreen` — "Start Session" button; calls `POST /api/sessions` on tap
- [ ] Display loading state during API call
- [ ] On success: navigate to `WaitingScreen` with session data

### Technician Home

- [ ] Build `TechnicianHomeScreen` — join code input field (6-char, JetBrains Mono); "Join Session" button
- [ ] Validate code format client-side before submitting
- [ ] On submit: call `POST /api/sessions/:id/join` using entered code
- [ ] Handle 404 (session not found) with error toast

**Phase 1 Gate:** Both users can sign in with Google. Role routing works. Customer can create a session and see the join code. Technician can enter the code and navigate forward. No video or AR yet.

---

## Phase 2 — Video Calling with LiveKit

### LiveKit Integration

- [ ] Call `registerGlobals()` in `index.js`
- [ ] Create `src/services/livekitService.ts` — Room instantiation, connect, disconnect utilities
- [ ] Implement `connectToRoom(url, token)` that updates CallStore on success/failure
- [ ] Subscribe to Room events: `ParticipantConnected`, `ParticipantDisconnected`, `Reconnecting`, `Reconnected`, `Disconnected`

### Customer Video Publishing

- [ ] On `WaitingScreen`: connect to LiveKit room using token from Phase 1 API response
- [ ] Publish camera track: `room.localParticipant.setCameraEnabled(true)`
- [ ] Publish microphone: `room.localParticipant.setMicrophoneEnabled(true)`
- [ ] When `ParticipantConnected` fires (technician joins): navigate to `ARCallScreen`

### Technician Video Subscribing

- [ ] On `DirectorPanelScreen`: connect to LiveKit room using token from join response
- [ ] Subscribe to customer's camera track
- [ ] Render customer's camera track using `VideoView` from `@livekit/react-native`
- [ ] Publish microphone for two-way audio

### Session Ended Screen

- [ ] Build `SessionEndedScreen` — display duration; "Go Home" button; navigate back to respective Home screen

### Reconnection UI

- [ ] Implement reconnection banner (warning yellow, slides down from top) on `Reconnecting` event
- [ ] Hide banner on `Reconnected`
- [ ] Show `SessionEndedScreen` if reconnection fails after timeout

### Data Channel Setup

- [ ] Implement `sendReliable(message)` utility using `room.localParticipant.publishData({ reliable: true })`
- [ ] Implement `sendUnreliable(message)` utility using `room.localParticipant.publishData({ reliable: false })`
- [ ] Implement `dataChannelRouter(message)` — parses incoming messages and dispatches to correct handler
- [ ] Subscribe to `RoomEvent.DataReceived` and pipe through router

**Phase 2 Gate:** Customer and Technician can be on a live video call. Customer publishes camera; Technician sees it. Audio is bidirectional. Data channel is established and `sendReliable({ type: 'ping' })` is received on the other end.

---

## Phase 3 — AR Foundation (Native Shared Camera)

> **v1.2:** Viro on the call path is **superseded** by native `ARCameraModule`. Viro components remain for reference; Phase 4 hit-test will use native bridges. See [`native-shared-camera-architecture.md`](native-shared-camera-architecture.md).

### Native module (phone flavor)

- [x] `ARCameraModule.kt` — `startSharedCameraSession`, `stopSharedCameraSession`, pause/resume
- [x] `SharedCameraController.kt` — ARCore `SHARED_CAMERA` + Camera2 + `ImageReader`
- [x] `FramePushVideoCapturer` + `SharedCameraCaptureController` — WebRTC video track
- [x] `ARCameraView` + `ARCameraRenderer` — customer AR preview (planes + camera background)
- [x] Register `ARCameraPackage` via reflection in `MainApplication` (phone flavor only)

### JavaScript integration

- [x] `src/native/ARCameraModule.ts` + `src/services/sharedCameraMedia.ts`
- [x] Replace `useARCameraHandoff` with `useSharedCameraSession`
- [x] `HostCameraSurface` renders `ARCameraView` on phone; video-only fallback on emulator
- [x] Remove `ar_mode` technician video pause
- [x] Waiting screen keeps default LiveKit camera until AR call

### AR HUD (unchanged)

- [x] `ARHUDStrip`, `ARTrackingBanner`, tracking events from native module

### Deferred (was Viro)

- [ ] `CustomerARView` / `ViroARPlaneSelector` on call path — **not used**
- [ ] Phase 4: native `raycast(normX, normY)` bridge

**Phase 3 Gate:** ✅ Met (2026-06-20) — Customer sees live preview (GLES or CPU fallback). Technician sees continuous live video. HUD + data channel from Phase 2 unchanged.

**Device note:** Samsung M14 uses CPU fallback when `session.resume()` fails; stream and annotations still work.

---

## Phase 4 — World-Relative Annotations

Build the technician-draw → customer-raycast → render → project-back sync loop before adding GLB models.

### AR Hit Testing (Customer — native bridge)

- [x] `ARCameraModule.raycast(normX, normY)` + `projectPoint(worldX, worldY, worldZ)`
- [x] Plane hit priority when ARCore active; depth unproject fallback
- [x] `FallbackPoseTracker` when `session.resume()` fails (rotation vector + pinhole)
- [x] `StreamOrientationHelper` — display ↔ buffer coords for stream-normalized touches

### Annotation Drawing (Technician)

- [x] `DrawingCanvas` + `DrawingToolbar` on technician director view
- [x] Freehand, circle, arrow tools + color picker + clear
- [x] `annotation` data channel message on stroke complete

### Annotation Rendering (Customer)

- [x] `useAnnotationSync` — receive strokes, raycast to `worldPoints`
- [x] `AnnotationOverlay` — 2D segments from screen or projected points
- [x] `useAnnotationProjection` — reproject world points ~20 Hz as camera moves
- [ ] Native GLES `AnnotationRenderer` in `ARCameraRenderer` — **deferred** (JS overlay sufficient for MVP)

### Annotation Sync (Customer → Technician)

- [x] `annotation_sync` with projected screen coordinates
- [x] Technician overlay uses `syncedScreenPoints` (`preferSynced`)

### Clear Annotations

- [x] `clear_annotations` on both sides

**Phase 4 Gate:** 🟡 **Mostly met** — Technician draws; customer sees anchored overlay (pseudo-3D on M14); technician sees sync. **Full gate** (plane-locked AR) pending test on device where ARCore resume succeeds.

---

## Phase 5 — 3D Model in AR Scene

### Model Selector (Technician)

- [ ] Implement `GET /api/models` backend endpoint
- [ ] Build `ModelSelectorStrip` — horizontal `FlatList` of model cards
- [ ] Fetch models from API on Director Panel mount; populate from response
- [ ] Each card: thumbnail + name + selected state (cyan border)
- [ ] On card tap: `sendReliable({ type: 'load_model', payload: { modelId, url, name } })`

### Model Loading (Customer)

- [ ] Implement `getOrDownloadModel(url, modelId)` in `src/services/modelCache.ts` using `expo-file-system`
- [ ] Show loading overlay with progress ring when download is in progress
- [ ] On load success: set `arStore.modelUrl` and `arStore.modelId`
- [ ] On load failure: retry once; if retry fails, show error toast with retry button
- [ ] Handle `load_model` data channel message: trigger `getOrDownloadModel`

### 3D Model Rendering (Customer)

- [ ] Render 3D model via **native bridge** (Viro `Viro3DObject` not on call path)
- [ ] Apply `arStore.rotation` and `arStore.scale` as ViroReact props
- [ ] Set up `onLoadStart` and `onLoadEnd` on `Viro3DObject` to control loading state

### Tap-to-Place (Customer receives, Technician sends)

- [ ] Handle `place_model` data channel message on customer device: extract `normX`, `normY`; raycast via shared `arHitTest` utility
- [ ] Set `arStore.placement` to world position result
- [ ] On technician Director Panel: add tap handler on `VideoView` zone (coordinate with annotation drawing — separate mode or gesture disambiguation)
- [ ] On press: calculate `normX = x / videoLayoutWidth`, `normY = y / videoLayoutHeight`; call `sendReliable` with `place_model`
- [ ] Show crosshair pulse animation at tap location on technician screen

### Transform Sync (Technician sends, Customer receives)

- [ ] Build `RotationSlider` component (X and Y axes) on Director Panel
- [ ] Build `ScaleSlider` component on Director Panel
- [ ] On slider `onChange`: update local technician state + call `sendUnreliable` with `transform` message
- [ ] Throttle sends to max 60 fps using `requestAnimationFrame`
- [ ] Handle `transform` data channel message on customer: `arStore.setRotation` + `arStore.setScale`
- [ ] Verify model updates on customer screen within 100 ms of slider move

**Phase 5 Gate:** Technician can select a model, tap the video to place it, and see the model appear on the customer's AR screen. Rotating and scaling the model via sliders updates the customer's view in real time. The tap-to-place raycast hits a flat surface correctly in demo conditions.

---

## Phase 6 — Error Handling, Fallbacks, and Polish

### Error State Matrix Implementation

- [ ] Implement `AR_NOT_SUPPORTED` fallback: video-only mode with banner (already partially in Phase 3)
- [ ] Implement `AR_SESSION_FAILED`: 3-retry logic on `TRACKING_UNAVAILABLE`; after 3 retries, send error to technician and show guidance
- [ ] Implement `MODEL_LOAD_FAILED`: retry-once logic with user-facing retry button
- [ ] Implement `PERMISSION_DENIED_CAMERA`: detect before requesting, surface settings deep-link
- [ ] Implement `PERMISSION_DENIED_MICROPHONE`: audio-only notice
- [ ] Implement `PLACEMENT_NO_SURFACE`: fallback placement at 0.5 m with toast notification

### Loading and Progress UI

- [ ] Build `ModelLoadingOverlay` — semi-transparent background + cyan progress ring + percentage text + model name
- [ ] Wire up `arStore.loadProgress` from `expo-file-system` download progress callback

### Session End Handling

- [ ] Handle `session_end` data channel message on receiving device: disconnect from room, navigate to `SessionEndedScreen`
- [ ] Implement session duration tracking in `callStore`; display on `SessionEndedScreen`
- [ ] Implement "End Call" flow on Technician settings sheet

### UI Polish Pass

- [ ] Apply full color system from UX Design document to all screens
- [ ] Set Inter font for body text; JetBrains Mono for session code and numeric values
- [ ] Ensure all interactive elements meet 44×44 px touch target minimum
- [ ] Add toast notification utility for one-off status messages
- [ ] Verify all error messages follow UX copy guidelines (conversational, not technical)
- [ ] Test slider reach from bottom of screen in portrait orientation on both test phones

### ARCore Availability Guidance

- [ ] If ARCore is not installed on customer device: surface a prompt to install from Google Play Store using `IntentLauncher`
- [ ] Handle gracefully if the user dismisses the prompt

**Phase 6 Gate:** All items from the Error State Matrix are implemented and manually tested. Loading progress shows during model download. Session ends cleanly on both devices. UI matches the Design Philosophy document.

---

## Phase 7 — Integration Testing and Demo Preparation

### End-to-End Testing on Physical Devices

- [ ] Full E2E test: Customer creates session → Technician joins → Model loaded → Placed on engine bonnet → Rotated 90° → Scaled up → Circle annotation drawn → Cleared → Session ended cleanly
- [ ] Test with Customer phone on mobile data and Technician phone on WiFi (different networks)
- [ ] Test AR tracking on a real car engine surface (dark, complex geometry)
- [ ] Test AR tracking fallback: cover camera → confirm guidance banner → uncover → confirm recovery
- [ ] Test model load with phone in airplane mode for 2 s mid-download → confirm error + retry
- [ ] Test reconnection: toggle WiFi off mid-session for 5 s → confirm reconnecting banner → toggle back → confirm recovery
- [ ] Verify model caching: load a model once, kill and relaunch app, load same model → confirm instant load (no progress bar)
- [ ] Verify annotation sync: draw annotation on Technician → visible on Customer AR in < 500 ms
- [ ] Verify transform throttle: move slider continuously → no perceptible lag on customer screen

### Demo Script Preparation

- [ ] Pre-load models on Customer phone to ensure zero download time during demo
- [ ] Confirm both phones have USB Debugging disabled for demo (cleaner UI bar)
- [ ] Prepare a flat, reasonably textured surface for AR demo (printed sheet works well for plane detection)
- [ ] Prepare a 5-minute demo script: auth → session → place engine model → rotate to show underside → draw arrow → clear → end session
- [ ] Do at least 3 full dry runs of the demo before the presentation
- [ ] Identify and document known limitations to acknowledge proactively during demo

### Known Limitations to Acknowledge in Demo

| Limitation | Notes |
|---|---|
| Annotation 3D→2D projection may drift as camera moves | Acceptable for MVP; listed as Phase 2 improvement |
| AR plane detection on dark, textureless surfaces (e.g., black plastic engine cover) is unreliable | Guide customer to scan a visible edge or use a printed marker |
| Annotation sync has ~100–200 ms roundtrip latency due to raycast + project cycle | Imperceptible for static annotations |
| Model position cannot be moved after tap-to-place (scale and rotation only) | Listed as Phase 2 feature |

---

## Post-MVP Backlog (Phase 2 Reference)

The following items are explicitly deferred and should not be built until the MVP gates above are all met.

| Feature | Notes |
|---|---|
| iOS support | Requires Mac for EAS Build; ARKit integration via ViroReact |
| Customer-side drawing | Adds annotation message direction and UI |
| Model translation after placement | Add translation sliders alongside rotation/scale |
| Annotation persistence (session history) | Requires storage layer additions |
| Coordinator role and dashboard | Backend + new role in auth system |
| Full dynamic asset library (admin UI) | CRUD for models table + file upload endpoint |
| Undo/Redo for annotations | Stack-based annotation history in ARStore |
| Multiple simultaneous models | Multiple ViroNode instances + model management UI |
| Session recording / replay | Storage + video recording integration |
| Animated 3D models | ViroReact animation system |
| Cloud Anchors for persistent session state | Now using ReactVision Platform Cloud Anchors |
