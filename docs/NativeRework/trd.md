# Remote AR Assistant — Technical Requirements Document (Native Android)

> Version 2.0 · Kotlin + Jetpack Compose + ARCore + LiveKit Android SDK

---

## 1. Technology Stack

### Android App
| Layer | Library / Version | Notes |
|-------|------------------|-------|
| Language | Kotlin 1.9+ | Coroutines, Flow, sealed classes |
| UI | Jetpack Compose (BOM 2024.x) | No XML layouts except GLSurfaceView wrapper |
| AR | `com.google.ar:core:1.44.0` | ARCore; `SHARED_CAMERA` mode |
| AR Rendering | `io.github.sceneview:arsceneview:2.x` | Community SceneView (wraps ARCore + Filament) |
| Video / Audio | `io.livekit:livekit-android:2.x` | Native Kotlin LiveKit SDK v2 |
| Video Source | Custom `VideoCapturer` implementation | Reads I420 frames from ARCore's `ImageReader` |
| Auth client | `io.github.jan-tennert.supabase:auth-kt` | Supabase Kotlin SDK |
| Realtime | `io.github.jan-tennert.supabase:realtime-kt` | Supabase Realtime for annotations |
| HTTP | `io.github.jan-tennert.supabase:postgrest-kt` | Supabase REST calls (sessions) |
| JSON | `kotlinx.serialization` | Annotation message serialisation |
| DI | Hilt | ViewModel injection |
| Navigation | Jetpack Navigation Compose | |
| Image loading | Coil | Profile thumbnails |

### Backend (Unchanged from v1)
| Component | Tech | Notes |
|-----------|------|-------|
| API | Node.js + Express + TypeScript | Session create/join/end, LiveKit token gen |
| LiveKit | `livekit/livekit-server` Docker image | Self-hosted, `livekit.yaml` config |
| Database | Supabase PostgreSQL | Existing `profiles`, `sessions`, `models` schema |
| Auth | Supabase Auth + Google OAuth | Existing Google client IDs |
| Tunnels | `cloudflared` | API on 3000, LiveKit on 7880 |

---

## 2. Project Structure

```
CGSApple/
├── android-app/                    ← NEW: Native Kotlin app (this entire folder is new)
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── kotlin/com/cgsapple/remotear/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── RemoteArApplication.kt
│   │   │   │   ├── di/                        ← Hilt modules
│   │   │   │   ├── data/
│   │   │   │   │   ├── model/                 ← Data classes
│   │   │   │   │   ├── repository/            ← SessionRepository, AuthRepository
│   │   │   │   │   └── remote/                ← SupabaseClient, LiveKitClient
│   │   │   │   ├── ar/
│   │   │   │   │   ├── ARCoreManager.kt       ← ARCore session lifecycle
│   │   │   │   │   ├── ARCoreFrameCapturer.kt ← Custom VideoCapturer (ImageReader → I420 → LiveKit)
│   │   │   │   │   ├── AnchorManager.kt       ← Create, store, project, detach anchors
│   │   │   │   │   ├── AnnotationProjector.kt ← World → screen 2D projection per frame
│   │   │   │   │   └── ARFallbackManager.kt   ← Video-only fallback when ARCore fails
│   │   │   │   ├── livekit/
│   │   │   │   │   ├── LiveKitManager.kt      ← Room connect/disconnect, publish, subscribe
│   │   │   │   │   └── VideoRenderer.kt       ← Render remote video track to Surface
│   │   │   │   ├── realtime/
│   │   │   │   │   ├── AnnotationChannel.kt   ← Supabase Realtime broadcast send/receive
│   │   │   │   │   └── AnnotationMessage.kt   ← Serialisable data classes
│   │   │   │   ├── ui/
│   │   │   │   │   ├── theme/
│   │   │   │   │   ├── navigation/            ← NavGraph.kt
│   │   │   │   │   ├── auth/                  ← AuthScreen, AuthViewModel
│   │   │   │   │   ├── home/                  ← CustomerHomeScreen, TechnicianHomeScreen
│   │   │   │   │   ├── waiting/               ← WaitingScreen, WaitingViewModel
│   │   │   │   │   ├── call/
│   │   │   │   │   │   ├── customer/          ← CustomerCallScreen, CustomerCallViewModel
│   │   │   │   │   │   │   ├── ARView.kt      ← GLSurfaceView wrapper for ARCore
│   │   │   │   │   │   │   └── AnnotationOverlay.kt ← Canvas overlay (per-frame projection)
│   │   │   │   │   │   └── technician/        ← TechnicianCallScreen, TechnicianCallViewModel
│   │   │   │   │   │       ├── VideoView.kt   ← LiveKit remote video renderer
│   │   │   │   │   │       └── DrawingCanvas.kt ← Touch input → normalised coords
│   │   │   │   │   └── common/                ← ConnectionBanner, TrackingHud, etc.
│   │   │   │   └── util/                      ← CoordMapper, YuvConverter, etc.
│   │   └── res/
│   ├── build.gradle.kts
│   └── gradle.properties
├── backend/                        ← UNCHANGED
├── docker-compose.yml              ← UNCHANGED
└── livekit.yaml                    ← UNCHANGED
```

---

## 3. Camera + ARCore + LiveKit Integration

### 3.1 The Core Problem and Solution
Android's Camera2 API allows only one `CameraCaptureSession` at a time. ARCore and LiveKit both
need the camera. The solution is `Session.Feature.SHARED_CAMERA`, which lets ARCore open the
camera and share its frames with additional `Surface` consumers (e.g., an `ImageReader` for
LiveKit).

### 3.2 `ARCoreManager` Responsibilities
- Create `Session` with `Session.Feature.SHARED_CAMERA` in `onCreate`
- Set display geometry on every `onDisplayChanged`
- Call `session.resume()` in `onResume` after GL context is ready (this is critical — must be
  after `GLSurfaceView.onResume()`)
- Call `session.pause()` in `onPause`
- Call `session.close()` in `onDestroy`
- Expose `currentFrame: Frame?` — the most recent `session.update()` result, updated every GL frame
- Expose `trackingState: TrackingState` as a `StateFlow`
- On `session.resume()` throwing `FatalException` or `UnavailableException`, set
  `arFallbackActive = true` and emit to `ARFallbackManager`

### 3.3 `ARCoreFrameCapturer` — Custom LiveKit `VideoCapturer`
This is the heart of the integration. It implements LiveKit's `VideoCapturer` interface.

```
ARCore SHARED_CAMERA ImageReader (YUV_420_888; device config size, typically ~720×1280 portrait)
    ↓ ImageReader.OnImageAvailableListener (camera thread)
    ↓ YuvToI420Converter.convert(image) → ByteBuffer I420
    ↓ capturerObserver.onFrameCaptured(VideoFrame(I420, rotation, timestampNs))
LiveKit encodes → WebRTC VP8/H.264 stream → Technician's device
```

> **Wire / overlay coords:** Native app uses **9:16 portrait** video-normalized space (`VideoFrameCoords.kt`: `HOST_VIDEO_ASPECT = 9/16`). RN reference used 16:9 — see `learnings.md`.

Key constraints:
- Only the latest frame is ever queued (drop stale frames to prevent latency build-up)
- Rotation metadata must match the device's display rotation so the technician sees upright video
- `YUV_420_888` to I420 conversion must happen on the camera thread, not the GL thread

### 3.4 Capture Session Setup
The Camera2 capture session must include both ARCore's required surfaces and the `ImageReader`
surface for LiveKit:
```kotlin
val surfaces = sharedCamera.arCoreSurfaces +       // ARCore's GL texture surface(s)
               listOf(imageReader.surface)          // LiveKit feed surface
sharedCamera.setAppSurfaces(cameraId, listOf(imageReader.surface))
device.createCaptureSession(surfaces, captureCallback, handler)
```

The capture template must be `CameraDevice.TEMPLATE_RECORD` (not `PREVIEW`) to allow ImageReader
access simultaneously with ARCore.

---

## 4. Annotation Pipeline Technical Spec

### 4.1 Technician Side — Drawing Canvas
- Transparent `Canvas` composable overlaid on the video renderer
- Detects `PointerInput` drag events
- Records all points as `List<Offset>` normalised to the video frame dimensions:
  ```
  normX = rawX / videoViewWidth
  normY = rawY / videoViewHeight
  ```
- On stroke end (pointer up), broadcasts via `AnnotationChannel.sendStroke()`
- Stroke ID is a `UUID.randomUUID().toString()` so clear-by-ID is possible

### 4.2 Annotation Message Schema (Supabase Realtime Broadcast)

**Technician → Customer (`annotations:{sessionId}`):**
```json
{
  "event": "stroke",
  "id": "uuid",
  "tool": "freehand | circle | arrow",
  "color": "#00B4D8",
  "thickness": 3,
  "points": [[0.45, 0.62], [0.46, 0.63], ...]
}
```

**Customer → Technician (`annotation_sync:{sessionId}`):**
```json
{
  "event": "stroke_projected",
  "id": "uuid",
  "screenPoints": [[0.45, 0.62], [0.46, 0.63], ...]
}
```

**Either direction:**
```json
{ "event": "clear" }
```

### 4.3 Customer Side — Hit Test → Anchor Creation
On receipt of a stroke message from the technician:
```kotlin
// For each point in the stroke
val screenX = normX * arViewWidth
val screenY = normY * arViewHeight
val hits = currentFrame.hitTest(screenX, screenY)
val hit = hits.firstOrNull { it.trackable is Plane } ?: hits.firstOrNull()
if (hit != null) {
    val anchor = hit.createAnchor()
    anchorManager.addAnchor(strokeId, pointIndex, anchor)
} else {
    // Fallback: fixed 0.5m depth
    anchorManager.addFallbackPoint(strokeId, pointIndex, screenX, screenY)
}
```

### 4.4 Customer Side — Per-Frame Projection (Render Loop)
Every GL frame (inside `GLSurfaceView.Renderer.onDrawFrame`):
```kotlin
val frame = session.update()                    // Get latest ARCore frame
val camera = frame.camera
val projMatrix = FloatArray(16)
val viewMatrix = FloatArray(16)
camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100f)
camera.getViewMatrix(viewMatrix, 0)

for (stroke in anchorManager.allStrokes) {
    val projectedPoints = stroke.anchors.map { anchor ->
        projectWorldToScreen(anchor.pose, viewMatrix, projMatrix, viewWidth, viewHeight)
    }
    annotationOverlay.updateStroke(stroke.id, projectedPoints)   // triggers Canvas recompose
}
```

`projectWorldToScreen` is a standard OpenGL MVP transform:
```
worldPos (4D homogeneous) × viewMatrix × projMatrix → clip space → NDC → screen pixels
```

### 4.5 Customer → Technician Sync (Re-projection Broadcast)
After computing the projected screen positions, normalise back to 0–1:
```kotlin
val normPoints = projectedPoints.map { Pair(it.x / viewWidth, it.y / viewHeight) }
annotationChannel.sendProjectedStroke(strokeId, normPoints)
```
This is sent at the same rate as the render loop but throttled to max 20 fps using a
`System.currentTimeMillis()` gate to avoid flooding Supabase Realtime.

---

## 5. ARCore Session Lifecycle (Critical Ordering)

```
Activity.onCreate()
    → ARCoreManager.createSession()           // Session(context, SHARED_CAMERA)
    → GLSurfaceView created                   // GL context initialised

Activity.onResume()
    → GLSurfaceView.onResume()                // GL thread restarts FIRST
    → ARCoreManager.resumeSession()           // session.resume() SECOND (GL context now ready)
    → LiveKitManager.connectRoom()            // Connect after AR is ready
    → ARCoreFrameCapturer.startCapture()      // Start pushing frames to LiveKit

Activity.onPause()
    → ARCoreFrameCapturer.stopCapture()
    → ARCoreManager.pauseSession()            // session.pause()
    → GLSurfaceView.onPause()

Activity.onDestroy()
    → LiveKitManager.disconnect()
    → ARCoreManager.closeSession()            // session.close() — REQUIRED to free native heap
```

**Critical**: `session.resume()` must be called AFTER the GL surface has been fully initialised.
In previous React Native attempts, this ordering was not guaranteed. In a native Kotlin Activity,
`onResume()` is called after `onCreate()` has set up the GLSurfaceView, so calling
`session.resume()` in `onResume()` is always safe.

---

## 6. LiveKit Integration Details

### 6.1 Both Devices
- Use `io.livekit:livekit-android:2.x`
- Connect to LiveKit via the existing self-hosted server
- Room token obtained from the existing backend API (`POST /api/sessions`, `POST /api/sessions/join`)

### 6.2 Customer Device (Publisher)
```kotlin
val room = LiveKit.create(applicationContext)
room.connect(livekitUrl, token)

// Custom capturer — reads from ARCore's ImageReader
val capturer = ARCoreFrameCapturer(imageReader)
val localVideoTrack = room.localParticipant.createVideoTrack("ar-camera", capturer)
room.localParticipant.publishVideoTrack(localVideoTrack)

// Audio
val localAudioTrack = room.localParticipant.createAudioTrack("mic")
room.localParticipant.publishAudioTrack(localAudioTrack)
```

### 6.3 Technician Device (Subscriber)
```kotlin
val room = LiveKit.create(applicationContext)
room.connect(livekitUrl, token)

// Subscribe to customer's video
room.events.collect { event ->
    if (event is RoomEvent.TrackSubscribed && event.track is RemoteVideoTrack) {
        technicianVideoView.setVideoTrack(event.track as RemoteVideoTrack)
    }
}
```

### 6.4 LiveKit Server Config (Unchanged)
Existing `livekit.yaml` with `node_ip` set to the server's LAN IP. Both phones on same WiFi as
server laptop during development. Cloudflare tunnel for external access.

---

## 7. Supabase Integration

### 7.1 Authentication
Use `supabase-kt` auth library. Google OAuth via Intent / Chrome Custom Tab. Deep link scheme:
`remotear://auth-callback`. Same Google Client IDs and Supabase project as v1.

### 7.2 Session Management
REST calls via `supabase-kt` postgrest module to the existing `sessions` table. Same RLS policies.

### 7.3 Annotation Realtime Channel
Two channels per session:
- `annotations:{sessionId}` — technician publishes strokes; customer subscribes
- `annotation_sync:{sessionId}` — customer publishes projected-back points; technician subscribes

Both use Supabase Realtime `broadcast` (not `postgres_changes`). Broadcast does not write to the
database; it is ephemeral low-latency messaging. This was proven fast in the React Native version.

---

## 8. Permissions

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.VIBRATE" />

<!-- ARCore Required -->
<uses-feature android:name="android.hardware.camera.ar" android:required="true" />
<meta-data android:name="com.google.ar.core" android:value="required" />
```

Permissions are requested at runtime before `ARCoreManager.createSession()` is called.

---

## 9. Build Configuration

```kotlin
// app/build.gradle.kts
android {
    compileSdk = 35
    defaultConfig {
        applicationId = "com.cgsapple.remotear"
        minSdk = 26
        targetSdk = 35
        buildConfigField("String", "SUPABASE_URL", "\"${project.findProperty("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${project.findProperty("SUPABASE_ANON_KEY")}\"")
        buildConfigField("String", "LIVEKIT_URL", "\"${project.findProperty("LIVEKIT_URL")}\"")
        buildConfigField("String", "API_URL", "\"${project.findProperty("API_URL")}\"")
    }
}
```

Sensitive values are in `local.properties` (gitignored), read via `project.findProperty()`.

---

## 10. Key Libraries — Gradle Dependencies

```kotlin
// ARCore
implementation("com.google.ar:core:1.44.0")

// SceneView (ARCore + Filament rendering + GLB support)
implementation("io.github.sceneview:arsceneview:2.2.1")

// LiveKit Android SDK v2
implementation("io.livekit:livekit-android:2.9.0")

// Supabase Kotlin SDK
implementation(platform("io.github.jan-tennert.supabase:bom:2.x"))
implementation("io.github.jan-tennert.supabase:auth-kt")
implementation("io.github.jan-tennert.supabase:realtime-kt")
implementation("io.github.jan-tennert.supabase:postgrest-kt")

// Compose BOM
implementation(platform("androidx.compose:compose-bom:2024.09.00"))
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.material3:material3")
implementation("androidx.activity:activity-compose:1.9.x")

// Hilt
implementation("com.google.dagger:hilt-android:2.51")
kapt("com.google.dagger:hilt-compiler:2.51")

// Navigation
implementation("androidx.navigation:navigation-compose:2.8.x")

// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.x")

// Serialisation
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.x")
```

---

## 11. Differences from React Native Version

| Concern | React Native (Old) | Native Kotlin (New) |
|---------|-------------------|---------------------|
| Camera ownership | JS bridge; unpredictable timing | Activity lifecycle; deterministic |
| ARCore session | Native module bridged to JS | Direct Kotlin API calls |
| GL thread | Managed by Expo/Viro | Managed by `GLSurfaceView` |
| LiveKit capturer | `FramePushVideoCapturer` (custom RN module) | `ARCoreFrameCapturer` (native Kotlin) |
| UI | React Native + Expo Router | Jetpack Compose |
| Annotation transport | Supabase Realtime (proven) | Supabase Realtime (same, unchanged) |
| Backend | Node.js Docker (unchanged) | Node.js Docker (unchanged) |
| Session resume ordering | Not guaranteed (JS bridge timing) | Guaranteed (Activity `onResume` order) |
