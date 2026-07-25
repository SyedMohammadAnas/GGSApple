# Cursor Agent Prompt — Remote AR Assistant (Native Android Rewrite)

---

## Context

You are building a **native Android application** (Kotlin + Jetpack Compose) from scratch in the
`android-app/` folder at the root of this repository. The previous React Native / Expo project
(`remote-ar/`) is **fully retired and must not be touched**. Do not read from it, do not run it,
do not reference it as a model for new code.

All project documentation is in the repository root. Read ALL of the following documents before
writing any code:

- `prd.md` — Product Requirements; what the app must do; Demo Day checklist
- `trd.md` — Technical Requirements; exact stack, library versions, project structure
- `ux-design.md` — UI/UX design; colors, layout, animation; treat as law
- `app-flow.md` — Every screen transition, data flow, and user journey
- `backend-schema.md` — Supabase schema, API endpoints, LiveKit config, env vars
- `implementation-plan.md` — Phases; only work on the active phase
- `research.md` — How TeamViewer Assist AR and Vuforia Chalk work; the correct AR annotation
  architecture; lessons from the failed React Native attempt

**Read all 7 documents now before doing anything else.**

---

## What You Are Building

A two-role mobile app:

**Customer (field worker):** Starts a session. Their Android phone runs a full ARCore session via
`Session.Feature.SHARED_CAMERA`. The camera feed is streamed live to the technician via LiveKit
(using a custom `VideoCapturer` that reads from ARCore's `ImageReader` surface). They see
annotations drawn by the technician as world-anchored overlays that stick to physical surfaces.

**Technician (remote expert):** Joins the session using a 6-character code. Sees the customer's
live video feed. Draws annotations (freehand, circle, arrow) on the video. Those annotations are
sent via Supabase Realtime to the customer, converted to ARCore world anchors, and reflected back
as projected 2D coordinates.

The end result must match TeamViewer Assist AR's annotation behaviour: annotations stick to
physical surfaces and do not drift when the customer moves the phone.

---

## Critical Architecture — Read This First

**The one rule that must never be violated:**
> ARCore owns the camera. LiveKit reads frames from ARCore's ImageReader surface.
> They never compete for the camera.

Achieve this via `Session.Feature.SHARED_CAMERA`:
1. Create ARCore `Session` with `Session.Feature.SHARED_CAMERA`
2. Open Camera2 via ARCore's wrapped `CameraDevice.StateCallback`
3. Add an `ImageReader` (YUV_420_888, 1280×720) as an app surface to the shared session
4. Implement `ARCoreFrameCapturer` (implements LiveKit's `VideoCapturer` interface)
5. `ARCoreFrameCapturer` reads from the `ImageReader`, converts YUV → I420, calls
   `capturerObserver.onFrameCaptured()`
6. Pass this capturer to `room.localParticipant.createVideoTrack()`

**Session.resume() ordering is critical:**
- GLSurfaceView.onResume() must be called BEFORE session.resume()
- session.resume() must be called in Activity.onResume(), not onCreate()
- This ordering was the root cause of the FatalException in the old React Native version

**Annotation anchor pattern (the TeamViewer pattern):**
1. Technician draws → normalised 2D coordinates sent via Supabase Realtime
2. Customer receives → `frame.hitTest(normX * viewWidth, normY * viewHeight)` per point
3. Hit returns `HitResult` on detected plane → `hitResult.createAnchor()`
4. Every GL render frame: project each anchor's world-space pose to 2D screen coords
5. Draw annotation at projected 2D positions (Canvas overlay, not 3D geometry)
6. Broadcast projected 2D coords back to technician (they see their annotations on the video)

---

## What Exists (Do Not Rebuild)

The backend is working and must not be changed:

- **Supabase project:** `https://suuellchcoegerddqyjb.supabase.co`
  - Tables: `profiles`, `sessions`, `models` — schema in `backend-schema.md`
  - Auth: Google OAuth with Client IDs already configured
  - Deep link: `remotear://auth-callback`
  - Android package: `com.ggsapple.remotear`
  - Debug SHA-1: `5E:8F:16:06:2E:A3:CD:2C:4A:0D:54:78:76:BA:A6:F3:8C:AB:F6:25`

- **Backend API (Node.js + Docker):** Running at `http://localhost:3000` (or Cloudflare tunnel URL)
  - `POST /api/sessions` — creates session, returns LiveKit token for customer
  - `POST /api/sessions/join` — joins session, returns LiveKit token for technician
  - `PATCH /api/sessions/:id/end` — ends session
  - `GET /health`

- **LiveKit (Docker):** Running at `http://localhost:7880`; Cloudflare tunnel provides `wss://` URL

- **Supabase Realtime:** Use **one broadcast channel per session:**
  - `annotations:{sessionId}` — events: `annotation`, `annotation_sync`, `clear_annotations`
  - Technician publishes `annotation`; both roles subscribe; customer publishes `annotation_sync`; technician overlay driven by sync

---

## Hardware Setup

- Two Samsung phones connected via USB with USB debugging enabled
- Both on the same WiFi network as the development laptop
- Server laptop running Docker (backend + LiveKit) on the same WiFi
- Android SDK at `D:\AndroidStudio`
- JAVA_HOME: `C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot` (JDK 17, not 21 or 25)
- Project root: `d:\GitHub Projects\GGSApple`
- New Android app: `d:\GitHub Projects\GGSApple\android-app\`

To install on both phones:
```powershell
# In Android Studio: Run → Select deployment target → choose each phone
# Or via Gradle:
cd "d:\GitHub Projects\GGSApple\android-app"
./gradlew installDebug    # installs on the first connected device
# Set ANDROID_SERIAL env var to target a specific device from `adb devices`
```

---

## Phase-by-Phase Instructions

Work through `implementation-plan.md` one phase at a time. Do not start the next phase until the
human operator confirms the current phase's verification checklist passes.

**Current phase: Phase 6** (error handling, clear sync, demo polish).  
**Phases 0–5 verified on device 2026-06-29** — see `docs/NativeRework/progress.md` and `learnings.md`.

Key native-only gotchas (read before changing annotation/AR code):
- **`ktor-client-okhttp`** for Supabase Realtime — never `ktor-client-android`
- **9:16 portrait** video coords on technician (`VideoFrameCoords.kt`) — not RN's 16:9
- **`TextureViewRenderer`** for LiveKit — not SurfaceView under Compose touch
- **Technician overlay** follows customer `annotation_sync` for **all** stroke ids (including own)
- **`local.properties` LAN IP** must match dev laptop Wi‑Fi; rebuild after change
- **AR fallback** must release camera + reset singleton on each call attach

---

## Code Standards

### Kotlin
- Use Kotlin coroutines and `Flow` / `StateFlow` / `SharedFlow` for async operations
- Use `suspend` functions for all I/O (no callbacks unless a library forces it)
- Sealed classes for UI state and navigation events
- `Result<T>` for error-returning operations
- No `!!` non-null assertions; use `?.let {}` or `requireNotNull()` with message
- All ViewModel state in `StateFlow<ScreenState>` observed via `collectAsStateWithLifecycle()`

### Compose
- All UI in Compose — no XML layouts except for `AndroidView { GLSurfaceView }` in AR screens
- Use `MaterialTheme` with the dark color scheme from `ux-design.md`
- `derivedStateOf` for computed state derived from other state
- `LaunchedEffect` for one-time side effects (navigation, permissions)
- `DisposableEffect` for lifecycle-bound resources (ARCore session, LiveKit room)

### ARCore
- Always check `ArCoreApk.checkAvailability()` before creating a session
- Use `Session.Feature.SHARED_CAMERA` — never create a plain `Session` on the customer device
- Wrap `session.resume()` in try-catch for `CameraNotAvailableException`, `FatalException`,
  `UnavailableArcoreNotInstalledException`, `UnavailableDeviceNotCompatibleException`
- On any resume exception: activate `ARFallbackManager`, do not crash
- Detach all anchors in `onDestroy` — ARCore holds significant native heap; leaking anchors
  will crash the app over time
- Never call `session.resume()` before the GLSurfaceView's renderer `onSurfaceCreated` has fired

### LiveKit
- Use `io.livekit:livekit-android:2.x` (native Kotlin SDK, not RN bridge)
- Collect `Room.events` as a `Flow` in a coroutine scope tied to the ViewModel lifecycle
- Disconnect the room in `onCleared()` of the ViewModel (or in a `DisposableEffect`)
- Custom `VideoCapturer` (ARCoreFrameCapturer): drop stale frames, never queue more than 1 frame,
  convert on the camera thread (not the GL thread)

### Supabase
- Use `supabase-kt` (`io.github.jan-tennert.supabase`)
- Store Supabase client as a singleton in a Hilt module
- Auth tokens are handled by the library; never store them manually
- Realtime channels: always unsubscribe in `onCleared()` or `DisposableEffect`

---

## Specific Implementation Guidance

### `ARCoreFrameCapturer.kt`
This is the single most important file in the project. Get it right before anything else.

```kotlin
class ARCoreFrameCapturer(
    private val imageReader: ImageReader,
) : VideoCapturer {
    private var capturerObserver: CapturerObserver? = null
    private val converter = YuvToI420Converter()
    @Volatile private var latestImage: Image? = null

    override fun initialize(helper: SurfaceTextureHelper?, context: Context?, observer: CapturerObserver?) {
        capturerObserver = observer
    }

    fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        // Drop any previously held image
        latestImage?.close()
        latestImage = image
        val i420Buffer = converter.convert(image)
        // rotation: match device display rotation
        val videoFrame = VideoFrame(i420Buffer, displayRotation, image.timestamp)
        capturerObserver?.onFrameCaptured(videoFrame)
        image.close()
        latestImage = null
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) { /* no-op, ARCore controls rate */ }
    override fun stopCapture() { latestImage?.close(); latestImage = null }
    override fun dispose() { stopCapture() }
    override fun isScreencast() = false
}
```

### ARCore Session Lifecycle in Compose
Use `DisposableEffect` to manage the ARCore session alongside the composable:

```kotlin
DisposableEffect(Unit) {
    arcoreManager.createSession()
    glSurfaceView.onResume()           // GL context first
    arcoreManager.resumeSession()      // ARCore second
    onDispose {
        arcoreManager.pauseSession()
        glSurfaceView.onPause()
        arcoreManager.closeSession()
    }
}
```

### Annotation Hit Test on Customer Device
```kotlin
// In ARCoreManager or AnchorManager:
fun hitTestAndAnchor(normX: Float, normY: Float, frame: Frame, viewWidth: Int, viewHeight: Int): Anchor? {
    val screenX = normX * viewWidth
    val screenY = normY * viewHeight
    val hits = frame.hitTest(screenX, screenY)
    // Prefer plane hits over feature points
    val hit = hits.firstOrNull { it.trackable is Plane && (it.trackable as Plane).trackingState == TrackingState.TRACKING }
        ?: hits.firstOrNull { it.trackable is Point }
    return hit?.createAnchor()
}
```

### Project World Anchor to Screen 2D
```kotlin
// In AnnotationProjector:
fun projectAnchorToScreen(
    anchor: Anchor,
    viewMatrix: FloatArray,
    projMatrix: FloatArray,
    viewWidth: Int,
    viewHeight: Int
): Offset? {
    if (anchor.trackingState != TrackingState.TRACKING) return null
    val worldPos = anchor.pose.translation   // [x, y, z]
    val vec4 = floatArrayOf(worldPos[0], worldPos[1], worldPos[2], 1f)
    // Multiply by view then projection matrix
    val clipSpace = FloatArray(4)
    Matrix.multiplyMV(clipSpace, 0, projMatrix, 0, FloatArray(4).also {
        Matrix.multiplyMV(it, 0, viewMatrix, 0, vec4, 0)
    }, 0)
    if (clipSpace[3] <= 0) return null   // Behind camera
    val ndcX = clipSpace[0] / clipSpace[3]
    val ndcY = clipSpace[1] / clipSpace[3]
    val screenX = (ndcX + 1f) / 2f * viewWidth
    val screenY = (1f - ndcY) / 2f * viewHeight
    return Offset(screenX, screenY)
}
```

---

## Supabase MCP Tool Usage

You have access to a Supabase MCP tool to inspect the database. Use it to:
- Verify the existing schema matches `backend-schema.md`
- Check that RLS policies are correct
- Verify the auth trigger (`on_auth_user_created`) is present
- Query session rows during testing to confirm state transitions

Do NOT use the Supabase MCP tool to make schema changes unless explicitly instructed. If The schema
is correct; do not alter it.

---

## Cloudflare Tunnels

If tunnel URLs need refreshing during development:
```powershell
# In separate terminals on the server laptop:
cloudflared tunnel --url http://localhost:3000   # API
cloudflared tunnel --url http://localhost:7880   # LiveKit
```

Copy the new `https://` URLs into `local.properties`:
```
LIVEKIT_URL=wss://<new-tunnel>.trycloudflare.com
API_URL=https://<new-tunnel>.trycloudflare.com
```

For LAN-only development (both phones on same WiFi as laptop):
```
LIVEKIT_URL=ws://192.168.x.x:7880     # replace with laptop's ipconfig LAN IP
API_URL=http://192.168.x.x:3000
```

---

## Never Do These Things

- Do NOT open the `remote-ar/` folder or modify any file in it
- Do NOT use React Native, Expo, ViroReact, or any JavaScript-based AR library
- Do NOT try to share the camera between ARCore and LiveKit using two separate Camera2 sessions
  (this was the fatal flaw of the old approach — use SHARED_CAMERA as described above)
- Do NOT call `session.resume()` before the GL surface is ready
- Do NOT use `FallbackPoseTracker` or IMU-based pseudo-AR as the primary tracking method
  (the FallbackPoseTracker approach was explicitly rejected because it produces screen-fixed, not
  world-relative, annotations — it is only acceptable as a last-resort fallback when ARCore
  `session.resume()` throws an exception)
- Do NOT implement the 3D GLB model system yet — that is Phase 8+ (post-MVP)
- Do NOT change the backend, the Supabase schema, or the LiveKit configuration
- Do NOT generate code for a phase you have not been asked to implement yet
- Do NOT proceed to the next phase without explicit human verification of the current phase

---

## When You Are Stuck

If `session.resume()` throws `FatalException`:
- Check that GLSurfaceView has been resumed before calling `session.resume()`
- Check that no other Camera2 session is open at the time
- Check `adb logcat -s ARCore` for the native cause
- Activate `ARFallbackManager` and continue — do not block the demo on ARCore hardware issues

If LiveKit video is not reaching the technician:
- Check `adb logcat -s LiveKit` for connection errors
- Confirm `LIVEKIT_URL` in `local.properties` points to the current tunnel URL or LAN IP
- Confirm `ARCoreFrameCapturer.onFrameCaptured()` is actually being called (add a log)
- Confirm the room token has `canPublish: true`

If annotations are screen-fixed instead of world-relative:
- Check that `frame.hitTest()` is returning `Plane` hits (log the hit results)
- Check that the tracking state is `TRACKING` (not `PAUSED`) before accepting hit tests
- Check that anchor poses are being read inside the `onDrawFrame` callback, not cached outside it

---

## Starting Command

**Phases 0–5 are complete** (verified 2026-06-29). Continue with **Phase 6** from
`implementation-plan.md` (clear sync, polish, demo dry run). Read `progress.md` and
`learnings.md` before changing annotation, LiveKit, or ARCore code.
