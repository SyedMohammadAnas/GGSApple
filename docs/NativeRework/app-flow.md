# Remote AR Assistant — App Flow (Native Android)

> End-to-end user flows for both roles. Every state transition, screen, and side effect documented.

---

## 1. App Entry & Authentication

### 1.1 Cold Start
```
App launches
    → SplashScreen (check Supabase auth session)
        ├── Session valid → RoleRouter
        └── No session → AuthScreen
```

### 1.2 AuthScreen Flow
```
AuthScreen
    → User taps "Sign in with Google"
    → Google OAuth Intent / Chrome Custom Tab opens
    → User selects Google account
    → OAuth callback → deep link `remotear://auth-callback`
    → Supabase exchanges code for session token
    → Profile fetched from `profiles` table
        ├── Profile exists → RoleRouter
        └── No profile → create profile row (trigger fires automatically) → RoleRouter
```

### 1.3 RoleRouter
```
RoleRouter
    ├── role == "customer" → CustomerHomeScreen
    └── role == "technician" → TechnicianHomeScreen
```

---

## 2. Customer Flow

### 2.1 CustomerHomeScreen
- Single screen with user greeting and one button: "Start Session"
- Displays user's name and profile photo (from Google OAuth)

### 2.2 Start Session
```
CustomerHomeScreen
    → User taps "Start Session"
    → POST /api/sessions (Supabase JWT in Authorization header)
        → Backend creates session row in Supabase (status: "waiting")
        → Backend creates LiveKit room
        → Backend generates LiveKit token for customer
        → Returns: { sessionId, joinCode, roomName, livekitToken }
    → Navigate to WaitingScreen
```

### 2.3 WaitingScreen
```
WaitingScreen (customer)
    → Displays join code (e.g. "ABC-123") prominently
    → Customer shares code with technician (copy / share sheet)
    → Subscribes to Supabase Realtime on `sessions` table for this session ID
    → Polls / listens for status change to "active"
        ├── status == "active" → Navigate to CustomerCallScreen
        └── User taps "Cancel" → DELETE session (or PATCH status to "ended") → CustomerHomeScreen
```

### 2.4 CustomerCallScreen
```
CustomerCallScreen
    → Initialise components (concurrent, in this order):
        1. Request CAMERA + RECORD_AUDIO permissions (if not already granted)
        2. Create ARCoreManager → Session(context, SHARED_CAMERA)
        3. Connect LiveKit room (livekitToken from session creation)
        4. Attach ARCoreFrameCapturer to LiveKit room → publish video track
        5. Subscribe to AnnotationChannel ("annotations:{sessionId}")

    → GLSurfaceView renderer starts
        → session.resume() called after GL context ready
            ├── Success → ARCore tracking begins; plane detection running
            └── FatalException / UnavailableException → ARFallbackManager activates:
                    Open Camera2 directly (no ARCore)
                    Show banner: "AR not available — video mode only"
                    Annotations become screen-fixed (no hit test)

    → Ongoing render loop (60 fps target):
        → frame = session.update()
        → trackingState emitted to UI (Scanning / Tracking / Tracking Limited / Stopped)
        → For each active anchor: project world → screen → update AnnotationOverlay

    → Ongoing Supabase Realtime subscription:
        → On "stroke" event:
            → For each point: frame.hitTest(normX * w, normY * h)
                → Create anchor on plane hit
                → Use fallback (0.5m depth) if no plane hit
        → On "clear" event:
            → Detach all anchors
            → Clear AnnotationOverlay

    → Broadcast annotation re-projections to "annotation_sync:{sessionId}"
      (throttled to 20 fps)

    → User taps "End Session" (from Settings sheet):
        → PATCH session status to "ended"
        → LiveKit room.disconnect()
        → session.pause() + session.close()
        → Navigate to SessionEndedScreen
```

### 2.5 SessionEndedScreen (Customer)
- Short message: "Session ended"
- Single button: "Back to Home" → CustomerHomeScreen

---

## 3. Technician Flow

### 3.1 TechnicianHomeScreen
- User greeting and one button: "Join Session"
- Optionally shows recent session history (post-MVP)

### 3.2 JoinSessionScreen
```
TechnicianHomeScreen
    → User taps "Join Session"
    → JoinSessionScreen: text field for code entry
    → User enters 6-char code (e.g. "ABC-123"), taps "Join"
    → POST /api/sessions/join { joinCode }
        → Backend finds session row, sets status = "active", sets technician_id
        → Backend generates LiveKit token for technician
        → Returns: { sessionId, roomName, livekitToken }
    → Navigate to TechnicianCallScreen
```

### 3.3 TechnicianCallScreen
```
TechnicianCallScreen
    → Connect LiveKit room (livekitToken from join)
    → Subscribe to remote participant (customer) video + audio tracks
    → Show "Waiting for customer camera..." spinner until first video frame arrives
    → Subscribe to AnnotationChannel ("annotation_sync:{sessionId}") for projected-back coords
    → Set up DrawingCanvas overlay on video feed

    → Ongoing drawing interaction:
        → User selects tool (freehand / circle / arrow)
        → User selects color
        → User draws on video feed (PointerInput drag)
            → Points normalised to video frame dims
            → On stroke end: broadcast to "annotations:{sessionId}"
        → Receives projected-back coords from customer:
            → Render annotation overlay on video feed at those positions

    → User taps Clear:
        → Clear local overlay
        → Broadcast "clear" event to "annotations:{sessionId}"

    → User taps "End Session" (from Settings sheet):
        → PATCH session status to "ended"
        → LiveKit room.disconnect()
        → Navigate to SessionEndedScreen
```

### 3.4 SessionEndedScreen (Technician)
Same as customer version. "Back to Home" → TechnicianHomeScreen.

---

## 4. Reconnection Flows

### 4.1 LiveKit Reconnect
```
LiveKit ICE failure / network drop
    → LiveKit SDK internally attempts reconnect
    → UI: show "Reconnecting..." banner (warning yellow)
    → On reconnect success:
        → Customer re-publishes video track (ARCoreFrameCapturer continues pushing frames)
        → Technician re-subscribes to video track
        → Banner hides
    → On reconnect failure (> 30 s):
        → Show "Connection lost" banner (error red)
        → Offer "Try again" button → attempts room.connect() again
```

### 4.2 Supabase Realtime Reconnect
```
Supabase Realtime WebSocket drops
    → Client SDK reconnects automatically
    → No user action required
    → Annotation state preserved in memory (MutableList<Anchor> on customer, DrawingState on technician)
    → On reconnect: full state is in memory; new annotations broadcast normally
```

### 4.3 Remote Session Ended by Other Party
```
Either side ends session via PATCH status = "ended"
    → Supabase Realtime `sessions` listener fires on the other device
    → Other device: show "Session ended by [remote party]" banner
    → Navigate to SessionEndedScreen after 2 s
```

---

## 5. AR State Transitions (Customer Device)

```
Session created (SHARED_CAMERA)
    ↓
resume() called
    ├── FatalException → Fallback mode (Camera2 only, no ARCore)
    └── Success → TrackingState.PAUSED (no planes yet)
            ↓
        User moves camera slowly over flat surface
            ↓
        TrackingState.TRACKING + planes detected
            ↓
        Technician draws annotation
            ↓
        hitTest() returns Plane hit → Anchor created → sticky annotation
            ↓
        User moves phone → annotation stays on surface (ARCore updates anchor pose)
            ↓
        (Occasional) TrackingState.PAUSED (fast motion, low light)
            ↓
        TrackingState.TRACKING restored → anchors resume correct poses
```

---

## 6. Permission Flow

```
CustomerCallScreen is about to start
    → Check CAMERA permission
        ├── Granted → proceed
        └── Not granted:
            → Show rationale dialog: "This app needs camera access to share your view with the technician"
            → Request permission
                ├── Granted → proceed
                └── Denied → show "Camera permission required" screen with "Open Settings" button
    → Check RECORD_AUDIO permission
        → Same pattern
```

---

## 7. Data Flow Summary Diagram

```
[Technician Phone]                          [Customer Phone]
       │                                           │
 Draw stroke on video                       ARCore tracking
 Record normalised 2D coords                Plane detection
       │                                           │
       └──── Supabase Realtime broadcast ─────────►│
             "annotations:{sessionId}"        Receive stroke
                                              hitTest() each point
                                              Create Anchor per point
                                              Per-frame: project Anchor → 2D
                                              Draw annotation on canvas overlay
                                                   │
       │◄──── Supabase Realtime broadcast ─────────┘
             "annotation_sync:{sessionId}"   Send projected 2D coords
  Receive projected coords
  Draw annotation overlay on video feed
       │
 LiveKit video subscription                LiveKit video publish
 (sees customer's camera feed)             (ARCoreFrameCapturer → LiveKit)
       │◄──────── LiveKit WebRTC ────────────────── │
```
