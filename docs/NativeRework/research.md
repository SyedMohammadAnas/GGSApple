# Remote AR — Research & Technical Foundation

> This document explains how TeamViewer Assist AR and Vuforia Chalk work, why our React Native
> approach failed, and exactly what architectural choices the native rewrite must make to achieve
> the same result.

---

## 1. How TeamViewer Assist AR Works

### What You See as a User
TeamViewer Assist AR (formerly TeamViewer Pilot) is powered by ARCore on Android and ARKit on iOS.
The on-site user (field worker / customer) opens the app, shares their camera feed via a live
video stream, and the remote expert (technician) watches that stream on their own device or desktop.
The expert taps or draws on their view of the video feed. Those annotations appear on the field
worker's screen as 3D objects that are world-anchored — they stick to the physical surface they
were placed on and do not drift when the phone moves.

### The Technical Architecture Behind It

**Step 1 — ARCore owns the camera on the customer's device.**
The field worker's device runs a full ARCore session (`Session.Feature.SHARED_CAMERA` or exclusive
mode). ARCore performs SLAM (Simultaneous Localisation and Mapping) continuously, building a
feature-point map and detecting horizontal/vertical planes. This is the source of truth for
world coordinates on the customer device.

**Step 2 — The camera feed is streamed to the technician via WebRTC.**
A separate video stream (H.264/VP8 encoded) is published from the customer device to the expert's
device. The expert sees the customer's camera view as a live video feed. There is no AR session on
the expert's side — they are operating in "director mode" only.

**Step 3 — The expert draws on the video feed.**
When the expert draws an annotation on their screen, the app records the 2D normalised coordinates
(0–1 range) of each stroke point relative to the video frame dimensions. This is transmitted via
a low-latency data channel (WebRTC DataChannel or a signalling side-channel) to the customer device.

**Step 4 — The customer device converts 2D touch coordinates to 3D world anchors.**
On receipt of each normalised 2D point, the customer app calls `frame.hitTest(normX * screenWidth,
normY * screenHeight)`. ARCore's hit test casts a ray from the camera through that screen position
into 3D world space and returns a `HitResult` with a world-space `Pose` where the ray intersected
a detected plane or feature-point cluster. The app then calls `hitResult.createAnchor()` to create
a persistent ARCore `Anchor` at that pose. Each annotation stroke point becomes one or more anchors.

**Step 5 — Annotations are rendered attached to their anchors.**
Every render frame, the app asks each anchor for its current world-space pose (ARCore updates
anchor poses as it refines its world model), then projects that 3D pose back to 2D screen
coordinates using the camera's intrinsic matrix and current `ViewMatrix` + `ProjectionMatrix`.
The annotation lines are drawn as 2D overlays on the screen surface at those projected positions.
Because the anchors track with ARCore's world model, the annotations appear to stick to the surface.

**Step 6 — The expert sees the annotations too.**
The customer app re-projects the 2D screen positions of the rendered anchors back to the expert's
normalised video frame coordinate space and sends them back via the data channel. The expert's app
renders these as a 2D canvas overlay on the video feed. The expert sees their own annotations
reflected back on the stream.

**Why it appears "world-stuck":**
The illusion of sticking to the surface is entirely produced by the customer's ARCore session.
The anchor's pose is updated by ARCore every frame as it refines its SLAM map. The 2D re-projection
of that pose moves with the surface as the phone moves. The expert receives projected-back 2D points
which are a mirror of what the customer sees — also moving correctly.

### Key Insight: The Expert Does NOT Need AR
TeamViewer Assist AR's expert side is just a video viewer with a drawing canvas overlay. There is
no ARCore or ARKit running on the expert's device. The world-anchoring is entirely done by the
customer's ARCore session. The expert draws 2D coordinates, the customer's ARCore converts them to
3D anchors, and the customer re-projects them back to 2D for the expert. This is the correct
architecture.

### Source
- Google Play listing explicitly states: "TeamViewer Assist AR (powered by ARCore)"
- TeamViewer blog / Auganix: confirmed integration of ARCore Depth API for occlusion of 3D markers
- Architecture inferred from ARCore documentation on hit testing, anchors, and shared camera

---

## 2. How Vuforia Chalk Works

Vuforia Chalk (by PTC, now merged into ServiceMax Zinc) uses the same conceptual architecture but
was originally iOS-first (ARKit), with Android support (ARCore) added later.

### Key Differences from TeamViewer
- Both participants can draw (bidirectional annotation), not just the expert
- Uses Vuforia's Fusion engine as a compatibility layer — extends AR plane detection to devices
  that don't support ARKit natively (flat-plane sensing via camera-only methods)
- Annotations are called "Chalk Marks" and are described as "anchored to objects and surfaces in
  the environment, as if drawn on the objects and surfaces themselves"
- The mechanism is identical: 2D touch → AR hit test → 3D anchor → per-frame projection back to 2D

### Vuforia Fusion
Vuforia Fusion is a software layer that selects the best available AR tracking backend:
- ARKit on iOS 11+ (full motion tracking + plane detection)
- ARCore on compatible Android devices
- Camera-only flat-plane sensing on older/incompatible devices (less accurate but functional)

This is why Chalk works on more devices than pure ARKit/ARCore: the fusion layer degrades
gracefully.

### Source
- PTC press release (2017): "Chalk Marks appear anchored to objects and surfaces in the
  environment, as if drawn on the objects and surfaces themselves"
- Tom's Guide review: "Chalk uses Vuforia's Fusion engine for object detection and environment
  scanning"
- PTC has discontinued Chalk (merged into ServiceMax Zinc) but the AR architecture it pioneered
  is the industry standard for remote AR annotation

---

## 3. Why Our React Native Approach Failed

### Root Cause: Two Incompatible Camera Consumers
Android's Camera2 API enforces exclusive access per CaptureSession by default. When LiveKit's
`@livekit/react-native-webrtc` opens the rear camera for WebRTC streaming, it holds a Camera2
`CameraCaptureSession`. ARCore needs the same camera for its SLAM tracking. These two sessions
cannot co-exist without explicit coordination.

ARCore provides `Session.Feature.SHARED_CAMERA` specifically to allow one app to share the camera
between ARCore and other consumers (e.g., a WebRTC capturer). However, this requires:
1. Creating the ARCore session with `SHARED_CAMERA` flag
2. Opening the camera via ARCore's wrapped `CameraDevice.StateCallback`
3. Creating the capture session through ARCore's shared surface list
4. Implementing a custom `VideoCapturer` that reads frames from an `ImageReader` surface appended
   to ARCore's capture session, rather than opening a separate camera

In React Native, all of this happens across the JS bridge. The existing RN libraries (ViroReact,
`@livekit/react-native-webrtc`) each try to own the full camera pipeline independently. The
`SharedCameraController.kt` native module we built was a correct approach but had lifecycle
issues:
- The JS navigation layer (React Navigation + Expo Router) would unmount/remount the native view
  at the wrong times, causing the Camera2 session to drop mid-call
- The `session.resume()` `FatalException` on the Samsung M14 was not React Native-specific
  (reproduced in the `native-spike/`) but may indicate device-level ARCore compatibility issues
  that proper native lifecycle management would handle differently
- No clear ownership of the GL thread: ARCore's renderer and the RN JS thread both competed

### Why Native Kotlin Solves This
In a native Kotlin Android app:
- The Activity lifecycle directly maps to the ARCore session lifecycle (`onResume`/`onPause`)
- A single Activity can own both the ARCore session and the LiveKit room without any bridge
- The `SharedCamera` pipeline can be set up once in `onCreate`, resumed in `onResume`, and torn
  down cleanly in `onDestroy`
- LiveKit's native Kotlin SDK (`io.livekit:livekit-android`) accepts a custom `VideoCapturer`
  implementation, allowing ARCore's `ImageReader` frames to be pushed directly to LiveKit without
  any camera re-open
- Jetpack Compose handles all UI; GLSurfaceView (for ARCore rendering) is embedded as an
  `AndroidView` composable with proper lifecycle callbacks

---

## 4. The Correct Architecture for This App

### The One Golden Rule
**ARCore owns the camera on the customer device. LiveKit reads frames from ARCore's ImageReader
surface. They never compete.**

This is achieved via `Session.Feature.SHARED_CAMERA`:
```
ARCore Session (SHARED_CAMERA)
    ├── GL Surface → AR scene rendering (customer sees world)
    ├── ImageReader (YUV_420_888) → YuvToI420Converter → LiveKit custom VideoCapturer → WebRTC stream
    └── Plane detection + hit testing → Anchor creation
```

The LiveKit Android SDK accepts any object implementing `VideoCapturer`. We implement
`ARCoreFrameCapturer` which receives frames from the `ImageReader`, converts YUV to I420, and
calls `capturerObserver.onFrameCaptured()`. LiveKit encodes and streams these frames as normal.

### Annotation Flow (The TeamViewer Pattern)
```
Technician draws stroke on video feed (2D normalised coords [0-1])
    ↓ LiveKit DataChannel (or Supabase Realtime)
Customer ARCore app receives normalised coords
    ↓ frame.hitTest(normX * viewWidth, normY * viewHeight)
ARCore returns HitResult with world-space Pose on detected plane
    ↓ hitResult.createAnchor()
Anchor stored in local list
    ↓ Every render frame:
    ↓ anchor.pose → project to screen 2D (camera intrinsics + viewProjectionMatrix)
    ↓ Draw annotation line segment at 2D screen position
    ↓ Send projected screen 2D coords back to technician
Technician's app receives projected-back 2D coords
    ↓ Draw annotation overlay on video feed at those positions
```

### Why `session.resume()` FatalException Happened
ARCore's `session.resume()` requires:
1. Camera permission granted (`CAMERA`)
2. `WRITE_EXTERNAL_STORAGE` not conflicting (older APIs)
3. OpenGL ES 3.0+ context active on the GL thread BEFORE `resume()` is called
4. No other Camera2 session open at the time of resume

In our React Native approach, the GL thread was managed by Expo/ViroReact's GL context, which
may not have been fully initialised before the native module called `session.resume()`. In a
native Kotlin app, the developer controls the exact order: GL context created → ARCore session
created → `session.resume()` → Camera2 session opened. This eliminates the race condition.

---

## 5. Key Technology Choices for the Native Rewrite

### Android
| Component | Choice | Reason |
|-----------|--------|--------|
| Language | Kotlin | Modern Android standard, full coroutine support |
| UI framework | Jetpack Compose | Native, lifecycle-aware, no bridge overhead |
| AR | ARCore (`com.google.ar:core:1.44+`) | Industry standard; what TeamViewer uses |
| Video streaming | LiveKit Android SDK v2 (`io.livekit:livekit-android:2.x`) | Native Kotlin, accepts custom `VideoCapturer` |
| Camera sharing | `Session.Feature.SHARED_CAMERA` + custom `VideoCapturer` | Eliminates the camera conflict permanently |
| Signalling / session | Supabase (existing, keep) | Already proven working |
| Annotation transport | Supabase Realtime broadcast | Already proven faster than LiveKit DataChannel |
| Auth | Supabase Google OAuth (existing, keep) | Already proven working |
| Backend API | Node.js / Express in Docker (existing, keep) | Already proven working |
| AR rendering | Custom `GLSurfaceView` renderer OR Sceneform (deprecated) / SceneView | SceneView (community fork of Sceneform) is recommended |
| 3D models | SceneView `ModelNode` (GLB) | Handles Draco decompression, lighting, anchoring |

### SceneView vs Raw OpenGL
Google deprecated Sceneform in 2021 but the community fork **SceneView** (`io.github.sceneview:arsceneview`) is actively maintained and is the easiest way to get ARCore + GLB model rendering + anchor-based placement in a Kotlin app. It wraps ARCore, handles the GL thread, provides `ArNode` for anchor-parented objects, and supports Draco-compressed GLB natively via Filament.

However, for the annotation rendering (lines/arrows/circles), SceneView's node system is less suited. Annotations should be rendered as a 2D Canvas overlay on top of the AR view, with positions recomputed per-frame from ARCore's anchor projection. This is the approach used by TeamViewer: AR anchors are the source of truth, but the visual rendering is a 2D canvas layer, not 3D geometry.

---

## 6. Reference Resources

### ARCore
- ARCore Fundamentals (motion tracking, planes, anchors, hit testing):
  https://developers.google.com/ar/develop/fundamentals
- Shared Camera guide:
  https://developers.google.com/ar/develop/java/camera-sharing
- SharedCamera Java sample (reference for Camera2 + ARCore co-ownership):
  https://github.com/google-ar/arcore-android-sdk/tree/main/samples/shared_camera_java
- HitResult API:
  https://developers.google.com/ar/reference/java/com/google/ar/core/HitResult
- Working with Anchors:
  https://developers.google.com/ar/develop/anchors
- ARCore supported devices list:
  https://developers.google.com/ar/devices
- ARCore Depth API (for future occlusion, not MVP):
  https://developers.google.com/ar/develop/depth
- Session class (SHARED_CAMERA feature):
  https://developers.google.com/ar/reference/java/com/google/ar/core/Session

### LiveKit Android SDK
- Android SDK GitHub (native Kotlin, custom VideoCapturer):
  https://github.com/livekit/client-sdk-android
- LiveKit Android SDK docs:
  https://docs.livekit.io/client-sdk-android/
- Custom video source discussion (SurfaceTexture → VideoCapturer):
  https://github.com/livekit/client-sdk-android/issues/843

### SceneView (community ARCore + GLB rendering)
- SceneView GitHub:
  https://github.com/SceneView/sceneview-android
- ARSceneView setup:
  https://sceneview.github.io/
- Filament (rendering engine behind SceneView):
  https://google.github.io/filament/

### TeamViewer Assist AR
- Product page:
  https://www.teamviewer.com/en/products/add-ons/assist-ar/
- Google Play (confirms ARCore usage):
  https://play.google.com/store/apps/details?id=com.teamviewer.pilot
- ARCore Depth API integration announcement:
  https://www.auganix.org/teamviewer-pilot-app-now-integrated-with-googles-arcore-depth-api/

### Vuforia Chalk (architecture reference)
- PTC announcement (world-anchored "Chalk Marks" concept):
  https://www.ptc.com/en/news/2017/vuforia-chalk
- Vuforia Fusion engine (ARKit/ARCore/camera-only fallback):
  https://www.tomsguide.com/us/chalk-ar-app-vuforia,news-25918.html
- Chalk remote annotation concept:
  https://techflok.com/vuforia-chalk/

### MobiDev (ARCore + WebRTC shared AR reference)
- Remote assistance with ARCore + WebRTC (architecture overview):
  https://mobidev.biz/blog/remote-assistance-augmented-reality-webrtc-demo-video

### Supabase Realtime (annotation transport)
- Supabase Realtime broadcast docs:
  https://supabase.com/docs/guides/realtime/broadcast

### Existing Infrastructure (carry forward)
- Supabase project: https://suuellchcoegerddqyjb.supabase.co
- LiveKit Docker self-hosted setup: existing `docker-compose.yml`
- Backend API: existing Node.js / Express on port 3000
- SDK path: `D:\AndroidStudio`
- Project root: `d:\GitHub Projects\CGSApple`

---

## 7. Lessons Learned from React Native Attempt (Never Repeat)

| Mistake | Lesson |
|---------|--------|
| Using ViroReact on the call path | ViroReact and LiveKit both claim the camera; no coordination possible |
| JS-based camera handoff (`useARCameraHandoff`) | The JS bridge cannot guarantee timing for camera ownership transfer |
| `Session.Feature.SHARED_CAMERA` from a RN native module | Works in isolation but lifecycle is owned by Expo/RN, causing resume race |
| Testing only on Samsung M14 | M14 has known ARCore issues; always test on a certified ARCore device |
| Pseudo-AR (IMU / `FallbackPoseTracker`) as a shortcut | IMU-based tracking is not world-relative; annotations drift; users notice immediately |
| Supabase Realtime for annotations | ✅ This worked and should be kept. Annotation transport is proven. |
| LiveKit for video | ✅ LiveKit itself works; the problem was sharing the camera with ARCore from JS |
| Dual-Metro, emulator testing | Wasted time; always use two physical phones on the same WiFi for AR testing |
