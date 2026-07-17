# Remote AR Assistant — App Flow

> Version 1.0 · MVP Scope
>
> Covers all user journeys from authentication through session end, including error paths.

---

## 1. Application Entry and Auth Check

```
App Launch
    │
    ▼
Check Supabase session (async)
    │
    ├── Session valid ──────────────────────────► Role Router
    │                                               │
    └── No session / expired ──────────────────►   Auth Screen
                                                    │
                                                    ▼
                                            Google Sign-In button
                                                    │
                                            Supabase OAuth redirect
                                                    │
                                            Deep-link callback (remote-ar://auth-callback)
                                                    │
                                            Resolve user profile from `profiles` table
                                                    │
                                            ┌───────┴────────┐
                                     First login?           Returning user?
                                            │                       │
                                    Role assignment          Load existing role
                                    (API call sets role       from profiles table
                                     based on invite or
                                     manual assignment)
                                            │                       │
                                            └───────────┬───────────┘
                                                        ▼
                                                   Role Router
```

---

## 2. Role Router

```
Role Router
    │
    ├── role === 'customer' ────────────────────► Customer Home Screen
    │
    └── role === 'technician' ─────────────────► Technician Home Screen
```

---

## 3. Customer Journey — Creating a Session

```
Customer Home Screen
    │
    ▼
Tap "Start Session"
    │
    ▼
POST /api/sessions  (Bearer: Supabase JWT)
    │
    ▼
Receive: { sessionId, roomName, joinCode, livekitToken }
    │
    ▼
Session Store: setSessionId(sessionId)
Call Store: setRoom(room), setStatus('connecting')
    │
    ▼
Waiting Screen
- Displays join code in large monospaced font (e.g. "ABC-123")
- "Share this code with your technician"
- Pulsing cyan ring while waiting
    │
    ▼
room.on(RoomEvent.ParticipantConnected)  ← Technician joins
    │
    ▼
Navigate to: AR Call Screen
```

---

## 4. Technician Journey — Joining a Session

```
Technician Home Screen
    │
    ▼
Enter 6-digit join code (keyboard input, JetBrains Mono)
    │
    ▼
Tap "Join Session"
    │
    ▼
POST /api/sessions/:id/join  (Bearer: Supabase JWT)
    │
    ├── Session not found / expired ──────────► Error toast: "Session not found. Check the code."
    │                                            Return to Home Screen
    │
    └── Success ──────────────────────────────► Receive livekitToken
                                                        │
                                                   Call Store: setRoom, setStatus('connecting')
                                                        │
                                                   Connect to LiveKit room
                                                        │
                                                   Navigate to: Director Panel Screen
```

---

## 5. Video Call Connection Flow

```
Both devices:
    │
    ▼
Room.connect(LIVEKIT_URL, token)
    │
    ├── Success ───────────────────────────────► setStatus('connected')
    │                                            Start publishing audio
    │                                            Customer: also publishes camera
    │                                            Technician: subscribes to customer camera
    │
    └── Failure ──────────────────────────────► Retry × 3 (exponential backoff: 1s, 2s, 4s)
                                                        │
                                                   Still failing after 3 retries?
                                                        │
                                                   Error screen: "Couldn't connect to session.
                                                                   Check your internet and try again."
                                                   [Retry] [Go Home]
```

### Reconnection on Mid-Session Drop

```
RoomEvent.Disconnected (mid-session)
    │
    ▼
setStatus('reconnecting')
Show reconnecting banner (warning yellow, top of screen)
    │
    ▼
LiveKit auto-reconnect (built-in)
    │
    ├── Reconnected ───────────────────────────► setStatus('connected')
    │                                            Hide banner
    │                                            AR scene and model state preserved in Zustand
    │
    └── Failed after timeout ─────────────────► setStatus('disconnected')
                                                 Show full-screen error: "Session lost.
                                                                           Tap to return home."
```

---

## 6. Customer AR Initialisation Flow

> **v1.2:** Native Shared Camera replaces Viro on the call path. See `docs/native-shared-camera-architecture.md`.

```
AR Call Screen mounts
    │
    ▼
isSharedCameraBridgeAvailable()?
    │
    ├── Not available (emulator build / wrong APK) ─► arStore.setARSupported(false)
    │                                                Send AR_NOT_SUPPORTED via data channel
    │                                                Video-only fallback + HUD
    │
    └── Phone build ───────────────────────────────► unpublish default LiveKit camera
                                                        │
                                                   ARCameraModule.startSharedCameraSession()
                                                        │
                                                   ├─ ARCore SHARED_CAMERA + Camera2
                                                   ├─ Frames → ARCameraView (customer preview)
                                                   └─ Frames → WebRTC track → LiveKit publish
                                                        │
                                                   onTrackingStateChanged / onPlaneDetected
                                                        │
                                                   ┌────┴────────────────────┐
                                              tracking normal          limited / unavailable
                                                   │                   Show guidance banner
                                                   ▼
                                              Technician sees live video throughout (no ar_mode pause)
                                              Ready to receive commands (Phase 4+ native raycast)
```

---

## 7. Model Load Flow

```
Technician taps a model card in Director Panel
    │
    ▼
sendReliable({
  type: 'load_model',
  payload: { modelId, url, name },
  timestamp, id
})
    │
Customer device receives 'load_model'
    │
    ▼
getOrDownloadModel(url, modelId)
    │
    ├── Cached ────────────────────────────────► Return local file path immediately
    │                                            No loading UI (< 50 ms)
    │
    └── Not cached ──────────────────────────► Show loading overlay (progress ring + %)
                                                        │
                                                   FileSystem.createDownloadResumable(...)
                                                        │
                                                   onProgress: arStore.setLoadProgress(pct)
                                                        │
                                                   ┌────┴────────────────────────┐
                                              Download OK                  Download error
                                                   │                              │
                                              Set modelUrl                  Retry once (automatic)
                                              in ARStore                          │
                                              Hide loading UI             ┌───────┴────────────┐
                                                                     Retry OK          Retry failed
                                                                          │                  │
                                                                     Continue       Error toast:
                                                                                   "Couldn't load model.
                                                                                    Tap to retry."
                                                                                   [Retry] button
                                                                                   sends 'load_model'
                                                                                   again on tap
```

---

## 8. Model Placement Flow

```
Technician sees customer's video feed
    │
    ▼
Technician taps on the video zone
    │
    ▼
Technician app:
- Record tap position (x_screen, y_screen)
- Normalise: normX = x_screen / videoWidth, normY = y_screen / videoHeight
- sendReliable({
    type: 'place_model',
    payload: { normX, normY },
    timestamp, id
  })
- Show brief crosshair at tap position (400 ms fade)
    │
Customer device receives 'place_model'
    │
    ▼
const results = await arSceneRef.current.performARHitTestWithPoint(
  normX * screenWidth,
  normY * screenHeight
)
    │
    ├── Hit on ExistingPlaneUsingExtent ───────► arStore.setPlacement(hit.transform.position)
    │                                            3D model renders at that world position
    │
    ├── Hit on FeaturePoint only ──────────────► arStore.setPlacement(featureHit.transform.position)
    │                                            3D model renders; less stable but acceptable
    │
    └── No hit detected ────────────────────►   arStore.setPlacement([0, 0, -0.5])
                                                 Model renders 0.5 m in front of camera
                                                 Toast to technician: "Placed at default distance.
                                                                        Adjust scale as needed."
```

---

## 9. Transform Sync Flow

```
Technician moves Rotation X slider
    │
    ▼
onChange: arStore.setRotation(x, currentY)
Throttled (requestAnimationFrame, ~60 fps):
    sendUnreliable({
      type: 'transform',
      payload: { rotationX: x, rotationY: currentY, scale: currentScale },
      timestamp
    })
    │
Customer device receives 'transform' (Unreliable channel)
    │
    ▼
arStore.setRotation(rotationX, rotationY)
arStore.setScale(scale)
    │
    ▼
ViroNode re-renders with new rotation/scale props
    │
    ▼
Target: < 100 ms from slider move to visible model update on customer screen

NOTE: Unreliable channel means individual packets can be dropped under congestion.
This is acceptable — the next transform update will arrive within ~16 ms at 60 fps.
The model never "sticks" at a wrong angle; it self-corrects on next received packet.
```

---

## 10. Annotation Flow

```
Technician selects a drawing tool (e.g., Freehand)
    │
    ▼
Technician draws on video feed (pan gesture)
    │
    ▼
On gesture end:
- Collect point array: [[x1,y1], [x2,y2], ...]  (screen coordinates)
- Normalise all points relative to video view dimensions
- Generate annotation id (UUID)
- sendReliable({
    type: 'annotation',
    payload: {
      id,
      points: [[normX1, normY1], [normX2, normY2], ...],
      color: '#00B4D8',
      thickness: 3,
      tool: 'freehand'
    },
    timestamp, id
  })
    │
Customer device receives 'annotation'
    │
    ▼
For each normalised point:
  performARHitTestWithPoint(normX * screenW, normY * screenH)
    → collect worldPosition from first hit
    │
    ▼
Render annotation in AR scene:
  <ViroLine
    fromPoint={worldPoints[0]}
    toPoint={worldPoints[n]}
    ...additional segments...
    width={0.003}
    color="#00B4D8"
  />
    │
    ▼
arStore.addAnnotation({ id, worldPoints })
    │
    ▼
Project world points back to screen coordinates:
  const screenPoints = worldPoints.map(wp =>
    arSceneRef.current.projectPoint(wp)
  )
    │
    ▼
sendReliable({
  type: 'annotation_sync',
  payload: { id, screenPoints },
  timestamp
})
    │
Technician receives 'annotation_sync'
    │
    ▼
Draw annotation_sync screenPoints on Canvas overlay
positioned on top of VideoView
    │
    ▼
Technician sees their annotation mirrored on the video feed
```

### Circle Annotation

```
Technician selects Circle tool, taps + drags on video
    │
    ▼
Record center (tap start) and radius (drag distance)
Convert to 32-point closed loop:
  for i in 0..31:
    x = centerX + radius * cos(i * 2π / 32)
    y = centerY + radius * sin(i * 2π / 32)
    points.push([normX, normY])
    │
Continue → same annotation flow as above
```

### Arrow Annotation

```
Technician taps (tail) and lifts (head)
    │
    ▼
Two points: [[tailNormX, tailNormY], [headNormX, headNormY]]
tool: 'arrow'
    │
Customer renders:
  - ViroLine from tail worldPoint to head worldPoint
  - Small ViroSphere or ViroCone at head position for arrowhead
    │
Continue → same annotation sync flow
```

---

## 11. Clear Annotations Flow

```
Technician taps Clear (✕) button
    │
    ▼
sendReliable({ type: 'clear_annotations', payload: {}, timestamp, id })
arStore.clearAnnotations()  (technician canvas also clears immediately)
    │
Customer receives 'clear_annotations'
    │
    ▼
arStore.clearAnnotations()
ViroARScene re-renders with empty annotations array
All ViroLine elements unmounted
```

---

## 12. Error and Fallback Flows

### AR Session Failure (mid-session)

```
onTrackingUpdated: TRACKING_UNAVAILABLE (sustained > 5 s)
    │
    ▼
Show guidance banner: "Move camera slowly to scan the surface"
Wait up to 10 s for re-detection
    │
    ├── Tracking restored ────────────────────► Hide banner, resume normally
    │
    └── Still unavailable ───────────────────► Show toast to technician:
                                                "Customer's AR has been lost.
                                                 Switch to video guidance."
                                                Technician can continue voice guidance.
                                                Model remains rendered at last known position.
```

### Model Load Failure Flow

```
Model download fails (network error or server error)
    │
    ▼
Retry automatically once (after 1 s)
    │
    ├── Retry succeeds ───────────────────────► Continue normally
    │
    └── Retry fails ─────────────────────────► arStore.setError('MODEL_LOAD_FAILED', ...)
                                                Show error toast on customer:
                                                "Couldn't load the part diagram. Tap to retry."
                                                [Retry] → re-sends 'load_model' request
                                                Notify technician via data channel error message
```

### Data Channel Error

```
DataChannel message parse fails (malformed JSON)
    │
    ▼
Log error to console (dev only)
Discard message silently (do not crash)
    │
    NOTE: Never propagate a parse error to UI.
    The next valid message will recover the state.
```

---

## 13. Session End Flow

```
Technician taps Settings → "End Call"
    │
    ▼
Confirmation bottom sheet: "End session for both users?" [Cancel] [End Session]
    │
    ▼
sendReliable({ type: 'session_end', payload: {}, timestamp })
PATCH /api/sessions/:id/end
room.disconnect()
    │
    ▼
Navigate to: Session Ended Screen
"Session complete. Duration: 12:34"
[Start New Session] [Go Home]
    │
Customer receives 'session_end'
    │
    ▼
room.disconnect()
Navigate to: Session Ended Screen
"Your session has ended. Thank you."
[Go Home]
```

### Customer-Initiated End (Back button / Physical back gesture)

```
Customer presses back
    │
    ▼
Confirmation dialog: "Leave session?" [Stay] [Leave]
    │
    ▼
If Leave:
  sendReliable({ type: 'session_end', payload: {}, timestamp })
  room.disconnect()
  Navigate to Customer Home
    │
  Technician receives 'session_end'
  Toast: "Customer has left the session."
  Director Panel shows disconnected state.
  [End Session] button returns to Technician Home
```

---

## 14. Complete State Diagram (Customer Device)

```
                    ┌────────────────────────────────────┐
                    │              IDLE                   │
                    │  (Waiting Screen, code displayed)   │
                    └────────────────┬───────────────────┘
                                     │ Technician joins
                                     ▼
                    ┌────────────────────────────────────┐
                    │           AR SCANNING               │
                    │  (ViroARScene active, no model yet) │
                    └────────────────┬───────────────────┘
                          ┌──────────┼──────────┐
                     AR fails    AR ok       AR ok
                          │          │           │
                          ▼          ▼           │
                    VIDEO ONLY   READY          │
                                (model loading   │
                                 on load_model   │
                                 command)        │
                                     │           │
                                     ▼           │
                    ┌────────────────────────────────────┐
                    │          MODEL ACTIVE               │
                    │  (3D object placed + transforms)    │
                    └────────────────┬───────────────────┘
                                     │
                         ┌───────────┼───────────┐
                    +annotations  clear_annotations  tracking lost
                         │           │                │
                         ▼           ▼                ▼
                   ANNOTATED       MODEL ACTIVE    TRACKING LOST
                                                   (guidance shown)
                                                        │
                                                   ┌────┴────┐
                                              Restored    Still lost > 10s
                                                   │         │
                                              Resume    Video-only mode
```
