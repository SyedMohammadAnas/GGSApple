# Remote AR Assistant — Technical Requirements Document

> Version 1.0 · MVP Scope · All architectural decisions finalised

---

## 1. Confirmed Tech Stack

| Layer | Technology | Version / Notes |
|---|---|---|
| **Client Framework** | React Native + Expo | Expo SDK (Development Builds — not Expo Go) |
| **Language** | TypeScript | Strict mode enabled |
| **AR/3D Library** | ViroReact (`@reactvision/react-viro`) | v2.55.0 (latest as of Jun 2026; actively maintained by ReactVision Inc.) |
| **Video Calling** | LiveKit (`@livekit/react-native`, `livekit-client`) | v2 SDK |
| **State Management** | Zustand | 3 stores: AR, Call, Session |
| **Backend Server** | Node.js (Dockerized) | Running on spare server laptop; exposed via Cloudflare Tunnel |
| **Database & Auth** | Supabase | Google OAuth + PostgreSQL |
| **Object Storage** | Local disk (dev/MVP) | Served via Node.js; Draco-compressed GLBs |
| **Tunneling** | Cloudflared | Exposes Node.js + LiveKit to public internet |
| **Testing Platform** | Two physical Android phones | ARCore-capable (customer) + any Android (technician) |

---

## 2. System Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                          CUSTOMER DEVICE                             │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  ViroReact AR Scene                                          │    │
│  │  - ViroARScene (plane detection, hit testing)               │    │
│  │  - Viro3DObject (GLB model)                                 │    │
│  │  - ViroLine (3D annotations)                                │    │
│  └─────────────────────────────────────────────────────────────┘    │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  LiveKit RN SDK                                              │    │
│  │  - VideoTrack (publishes camera)                            │    │
│  │  - DataChannel Reliable (receives commands)                 │    │
│  │  - DataChannel Unreliable (receives transforms)             │    │
│  │  - DataChannel Reliable (sends annotation projections back) │    │
│  └─────────────────────────────────────────────────────────────┘    │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  Zustand Stores: ARStore | CallStore | SessionStore         │    │
│  └─────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
                  │  WebRTC (Video + Data Channels)
                  ▼
┌──────────────────────────────────────────────────────────────────────┐
│                       BACKEND (Server Laptop)                        │
│  ┌───────────────────────────────┐  ┌──────────────────────────┐    │
│  │  LiveKit Server (Docker)      │  │  Node.js REST API        │    │
│  │  - SFU / signalling           │  │  - Token generation      │    │
│  │  - Data channel routing       │  │  - Model asset endpoints │    │
│  └───────────────────────────────┘  │  - Session management    │    │
│                                     │  - Supabase client       │    │
│  ┌───────────────────────────────┐  └──────────────────────────┘    │
│  │  Local Disk Storage           │                                   │
│  │  - Draco-compressed GLBs      │                                   │
│  └───────────────────────────────┘                                   │
└──────────────────────────────────────────────────────────────────────┘
                  │  Cloudflare Tunnel
                  ▼
┌──────────────────────────────────────────────────────────────────────┐
│                         SUPABASE                                     │
│  - Auth (Google OAuth)                                               │
│  - PostgreSQL (profiles, sessions, models metadata)                  │
└──────────────────────────────────────────────────────────────────────┘
                  │
┌──────────────────────────────────────────────────────────────────────┐
│                       TECHNICIAN DEVICE                              │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  LiveKit RN SDK                                              │    │
│  │  - VideoTrack (subscribes to customer camera)               │    │
│  │  - DataChannel Reliable (sends commands)                    │    │
│  │  - DataChannel Unreliable (sends transforms)                │    │
│  │  - DataChannel Reliable (receives annotation projections)   │    │
│  └─────────────────────────────────────────────────────────────┘    │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  Director Panel (React Native — NO ViroReact)               │    │
│  │  - VideoView (customer feed)                                │    │
│  │  - Touch handler (tap-to-place)                             │    │
│  │  - Canvas overlay (received annotation projections)         │    │
│  │  - Sliders (rotation X/Y, scale)                            │    │
│  │  - Drawing tools (circle, arrow, freehand)                  │    │
│  └─────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

### Critical Architectural Decisions (Confirmed)

| Decision | Choice | Rationale |
|---|---|---|
| AR sync strategy | Customer-owned placement (not Cloud Anchors) | Cloud Anchors require both devices to physically see the same space — impossible for remote use |
| Technician side | Director Mode only — no ViroReact on technician device | Simplifies technician app significantly; technician is a controller, not an AR participant |
| Transform sync | LiveKit Unreliable data channel | Best-effort, low-latency; dropping a frame is acceptable for continuous transforms |
| Command sync | LiveKit Reliable data channel | Ordered, guaranteed delivery for model load, placement, annotations, clear commands |
| Platform | Android-only (MVP) | No Mac required; faster iteration; ViroReact fully supports ARCore |

---

## 3. Expo Development Build Configuration

ViroReact **cannot run in Expo Go**. A custom Development Build is required.

### Build Commands

```bash
# Initial setup
npx create-expo-app remote-ar --template expo-template-blank-typescript
cd remote-ar
npm install @reactvision/react-viro
npm install @livekit/react-native @livekit/react-native-webrtc livekit-client
npm install zustand
npm install @supabase/supabase-js

# Build and install on connected Android phone (USB Debugging enabled)
npx expo run:android

# Subsequent development — Fast Refresh over USB
# No rebuild needed for JS changes
# Rebuild only when native dependencies change
```

### Required Permissions (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-feature android:name="android.hardware.camera.ar" android:required="true" />
```

### app.json Additions

```json
{
  "expo": {
    "plugins": [
      "@reactvision/react-viro",
      "@livekit/react-native"
    ],
    "android": {
      "minSdkVersion": 24,
      "permissions": ["CAMERA", "RECORD_AUDIO"]
    }
  }
}
```

### ARCore Compatibility Check

Not all Android 7.0+ devices support ARCore. At app launch (customer device only):

```typescript
import { ViroARScene } from '@reactvision/react-viro';

const supported = await ViroARScene.isSupported();
if (!supported) {
  // Trigger fallback to video-only mode
  callStore.setARSupported(false);
}
```

---

## 4. ViroReact Integration

### 4.1 Scene Structure (Customer App)

```typescript
// CustomerARScene.tsx
import {
  ViroARScene,
  ViroARSceneNavigator,
  Viro3DObject,
  ViroNode,
  ViroLine,
  ViroARPlane,
} from '@reactvision/react-viro';

// Root navigator — no cloudAnchorProvider needed
<ViroARSceneNavigator
  autofocus={true}
  initialScene={{ scene: CustomerARScene }}
  style={{ flex: 1 }}
/>
```

### 4.2 Tap-to-Place (Customer Side)

The customer's `ViroARScene` receives a `placement` command from the technician via the data channel. The placement payload contains normalised screen coordinates (0–1 in both X and Y). The scene performs an AR hit test:

```typescript
// Inside ViroARScene component
const arSceneRef = useRef<ViroARScene>(null);

const handlePlacement = async (normX: number, normY: number) => {
  if (!arSceneRef.current) return;

  const screenX = normX * screenWidth;
  const screenY = normY * screenHeight;

  try {
    const results = await arSceneRef.current.performARHitTestWithPoint(screenX, screenY);

    // Prefer ExistingPlaneUsingExtent (detected surface)
    const planeHit = results.find(r => r.type === 'ExistingPlaneUsingExtent');
    const featureHit = results.find(r => r.type === 'FeaturePoint');

    const hit = planeHit || featureHit;

    if (hit) {
      arStore.setPlacement(hit.transform.position);
    } else {
      // Fallback: place 0.5 m in front of camera
      arStore.setPlacement([0, 0, -0.5]);
    }
  } catch {
    arStore.setPlacement([0, 0, -0.5]);
  }
};
```

### 4.3 3D Model Rendering (Customer Side)

```typescript
// Inside ViroARScene render
{placement && modelUrl && (
  <ViroNode position={placement}>
    <Viro3DObject
      source={{ uri: modelUrl }}
      type="GLB"
      scale={[scale, scale, scale]}
      rotation={[rotationX, rotationY, 0]}
      onLoadStart={() => arStore.setLoading(true)}
      onLoadEnd={() => arStore.setLoading(false)}
      onError={(e) => arStore.setError('MODEL_LOAD_FAILED', e)}
    />
  </ViroNode>
)}
```

### 4.4 AR Plane Detection Events

```typescript
<ViroARScene
  ref={arSceneRef}
  onTrackingUpdated={(state, reason) => {
    if (state === ViroTrackingStateConstants.TRACKING_NORMAL) {
      arStore.setTrackingState('normal');
    } else if (state === ViroTrackingStateConstants.TRACKING_UNAVAILABLE) {
      arStore.setTrackingState('unavailable');
    }
  }}
  onAnchorFound={(anchor) => arStore.addAnchor(anchor)}
  onAnchorUpdated={(anchor) => arStore.updateAnchor(anchor)}
  onAnchorRemoved={(anchor) => arStore.removeAnchor(anchor)}
>
```

### 4.5 Annotation Rendering (Customer Side)

Annotations arrive as arrays of normalised screen coordinates. Each point is raycasted to 3D world space, rendered with `ViroLine`, then projected back to 2D screen coordinates and returned to the technician.

```typescript
const renderAnnotation = async (points: [number, number][], id: string) => {
  const worldPoints: [number, number, number][] = [];

  for (const [nx, ny] of points) {
    const results = await arSceneRef.current!.performARHitTestWithPoint(
      nx * screenWidth,
      ny * screenHeight
    );
    const hit = results.find(r => r.type === 'ExistingPlaneUsingExtent') || results[0];
    if (hit) worldPoints.push(hit.transform.position);
  }

  arStore.addAnnotation({ id, worldPoints });

  // Project world points back to screen and return to technician
  const screenPoints = worldPoints.map(wp =>
    arSceneRef.current!.projectPoint(wp)
  );
  dataChannel.sendAnnotationSync(id, screenPoints);
};
```

---

## 5. LiveKit Integration

### 5.1 Dependencies

```bash
npm install @livekit/react-native @livekit/react-native-webrtc livekit-client
```

`registerGlobals()` must be called in `index.js` before any LiveKit usage:

```typescript
import { registerGlobals } from '@livekit/react-native';
registerGlobals();
```

### 5.2 Room Connection

```typescript
import { Room, RoomEvent, DataPacket_Kind } from 'livekit-client';

const room = new Room({
  adaptiveStream: true,
  dynacast: true,
  audioCaptureDefaults: { echoCancellation: true, noiseSuppression: true },
});

await room.connect(LIVEKIT_URL, token);
await room.localParticipant.setMicrophoneEnabled(true);
await room.localParticipant.setCameraEnabled(true); // Customer only
```

### 5.3 Token Generation (Backend)

Server-side token generation using `livekit-server-sdk`:

```typescript
// POST /api/livekit/token
import { AccessToken } from 'livekit-server-sdk';

const token = new AccessToken(LIVEKIT_API_KEY, LIVEKIT_API_SECRET, {
  identity: userId,
  name: displayName,
});
token.addGrant({
  roomJoin: true,
  room: sessionId,
  canPublish: true,
  canSubscribe: true,
});
return token.toJwt();
```

### 5.4 Data Channel — Sending

```typescript
// Reliable channel (commands, annotations, session events)
const sendReliable = (message: DataMessage) => {
  const encoded = new TextEncoder().encode(JSON.stringify(message));
  room.localParticipant.publishData(encoded, { reliable: true });
};

// Unreliable channel (transforms — high frequency, loss-tolerant)
const sendUnreliable = (message: DataMessage) => {
  const encoded = new TextEncoder().encode(JSON.stringify(message));
  room.localParticipant.publishData(encoded, { reliable: false });
};
```

### 5.5 Data Channel — Receiving

```typescript
room.on(RoomEvent.DataReceived, (payload: Uint8Array, participant, kind) => {
  const message: DataMessage = JSON.parse(new TextDecoder().decode(payload));
  dataChannelRouter(message);
});
```

### 5.6 Reconnection

LiveKit handles reconnection automatically. Subscribe to events for UI updates:

```typescript
room.on(RoomEvent.Reconnecting, () => callStore.setStatus('reconnecting'));
room.on(RoomEvent.Reconnected, () => callStore.setStatus('connected'));
room.on(RoomEvent.Disconnected, (reason) => callStore.setStatus('disconnected'));
```

---

## 6. Data Channel Message Schema

All messages share a common envelope:

```typescript
interface DataMessage {
  type: MessageType;
  payload: Record<string, unknown>;
  timestamp: number;   // Date.now()
  id?: string;         // UUID, optional — used for idempotency on Reliable channel
}
```

### 6.1 Reliable Channel Messages

| `type` | Direction | Description |
|---|---|---|
| `load_model` | Tech → Customer | Instruct customer to download and cache a model |
| `place_model` | Tech → Customer | Place currently loaded model at screen coordinates |
| `remove_model` | Tech → Customer | Remove current model from scene |
| `annotation` | Tech → Customer | Send annotation point array |
| `annotation_sync` | Customer → Tech | Return projected 2D points for technician overlay |
| `clear_annotations` | Both → Other | Clear all active annotations |
| `session_start` | Both → Other | Signal session is live |
| `session_end` | Both → Other | Signal session has ended |
| `error` | Both → Other | Report an error state |

#### Message Payloads

```typescript
// load_model
{ modelId: string, url: string, name: string }

// place_model
{ normX: number, normY: number }  // 0–1 normalised screen coordinates

// remove_model
{}

// annotation
{
  id: string,               // UUID for this annotation stroke
  points: [number, number][], // normalised screen coordinates
  color: string,            // hex e.g. "#00B4D8"
  thickness: number,        // 1–5
  tool: 'freehand' | 'circle' | 'arrow'
}

// annotation_sync
{
  id: string,                         // matches annotation id
  screenPoints: [number, number][]    // projected 2D screen coordinates
}

// clear_annotations
{}

// error
{
  code: ErrorCode,
  message: string,
  severity: 'info' | 'warning' | 'error',
  details?: Record<string, unknown>
}
```

### 6.2 Unreliable Channel Messages

| `type` | Direction | Description |
|---|---|---|
| `transform` | Tech → Customer | Real-time rotation and scale update |

```typescript
// transform payload
{
  rotationX: number,   // degrees, -180 to 180
  rotationY: number,   // degrees, -180 to 180
  scale: number        // multiplier, e.g. 1.0
}
```

### 6.3 Error Codes

```typescript
type ErrorCode =
  | 'AR_NOT_SUPPORTED'
  | 'AR_SESSION_FAILED'
  | 'AR_TRACKING_LOST'
  | 'MODEL_LOAD_FAILED'
  | 'MODEL_PARSE_ERROR'
  | 'DATA_CHANNEL_LOST'
  | 'PERMISSION_DENIED_CAMERA'
  | 'PERMISSION_DENIED_MICROPHONE'
  | 'PLACEMENT_NO_SURFACE';
```

---

## 7. Zustand State Management

### 7.1 AR Store

```typescript
// stores/arStore.ts
import { create } from 'zustand';

interface ARState {
  // Model
  modelUrl: string | null;
  modelId: string | null;
  placement: [number, number, number] | null;
  rotation: { x: number; y: number };
  scale: number;
  isLoading: boolean;
  loadProgress: number;

  // AR state
  isARSupported: boolean;
  trackingState: 'normal' | 'limited' | 'unavailable';
  detectedAnchors: Map<string, AnchorData>;

  // Annotations
  annotations: Annotation[];

  // Error
  error: { code: ErrorCode; message: string } | null;

  // Actions
  setModel: (id: string, url: string) => void;
  setPlacement: (position: [number, number, number]) => void;
  setRotation: (x: number, y: number) => void;
  setScale: (scale: number) => void;
  setLoading: (loading: boolean) => void;
  setLoadProgress: (progress: number) => void;
  setARSupported: (supported: boolean) => void;
  setTrackingState: (state: 'normal' | 'limited' | 'unavailable') => void;
  addAnchor: (anchor: AnchorData) => void;
  updateAnchor: (anchor: AnchorData) => void;
  removeAnchor: (anchor: AnchorData) => void;
  addAnnotation: (annotation: Annotation) => void;
  clearAnnotations: () => void;
  setError: (code: ErrorCode, details?: unknown) => void;
  clearError: () => void;
  resetScene: () => void;
}
```

### 7.2 Call Store

```typescript
// stores/callStore.ts
interface CallState {
  status: 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'disconnected';
  room: Room | null;
  sessionId: string | null;
  remoteParticipantId: string | null;
  isMuted: boolean;

  setRoom: (room: Room) => void;
  setStatus: (status: CallStatus) => void;
  setSessionId: (id: string) => void;
  setMuted: (muted: boolean) => void;
  disconnect: () => void;
}
```

### 7.3 Session Store

```typescript
// stores/sessionStore.ts
interface SessionState {
  userId: string | null;
  email: string | null;
  displayName: string | null;
  role: 'customer' | 'technician' | null;
  isAuthenticated: boolean;

  setUser: (user: UserProfile) => void;
  setRole: (role: 'customer' | 'technician') => void;
  signOut: () => void;
}
```

---

## 8. Error State Matrix

| Error Scenario | Detection Method | Action | User-Facing Message |
|---|---|---|---|
| AR not supported | `ViroARScene.isSupported()` on launch | Activate video-only mode | "AR not available on this device. Video call continues normally." |
| AR tracking unavailable | `onTrackingUpdated` with `TRACKING_UNAVAILABLE` | Show guidance overlay; retry 3× then offer video-only | "Move camera slowly to scan the surface." |
| AR plane not found (tap-to-place) | No `ExistingPlaneUsingExtent` result | Fall back to 0.5 m fixed placement | "Placed model at default distance. Adjust scale as needed." |
| Model download fails (HTTP) | `fetch()` rejection / timeout | Retry once; show retry button | "Couldn't load model. Check connection and try again." |
| Model parse error | `onError` on `Viro3DObject` | Remove model from scene; notify technician | "Model couldn't display. Try selecting a different one." |
| Data channel disconnected | `RoomEvent.Disconnected` | Auto-reconnect (LiveKit); show banner | "Reconnecting…" |
| Camera permission denied | Permissions API | Surface settings link | "Camera access is needed. Enable it in device settings." |
| Microphone permission denied | Permissions API | Audio-only fallback | "Microphone access needed for voice. Enable in settings." |

### Graceful Degradation Hierarchy

```
Full AR + Video + Annotations (best case)
      ↓  (if AR fails)
Video + 2D overlay annotations (medium)
      ↓  (if annotations fail)
Plain video call (minimum viable)
```

---

## 9. Model Asset Pipeline

### 9.1 Draco Compression

All GLB files must be Draco-compressed before being placed in the server's asset directory.

```bash
npm install -g gltf-pipeline

# Compress a GLB file
gltf-pipeline -i engine.glb -o engine_draco.glb -d

# Expected results
# engine.glb      ~45 MB → engine_draco.glb  ~8 MB  (82% reduction)
# tire.glb        ~20 MB → tire_draco.glb     ~4 MB  (80% reduction)
# sparkplug.glb   ~8 MB  → sparkplug_draco.glb ~1.5 MB
```

### 9.2 File Size Targets

| Priority | Target | Action |
|---|---|---|
| Ideal | < 5 MB | Draco + polygon reduction in Blender |
| Acceptable | 5–10 MB | Draco compression sufficient |
| Unacceptable | > 10 MB | Additional polygon reduction required |

### 9.3 Client-Side Caching

```typescript
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as FileSystem from 'expo-file-system';

const CACHE_DIR = FileSystem.cacheDirectory + 'ar-models/';

async function getOrDownloadModel(url: string, modelId: string): Promise<string> {
  const localPath = CACHE_DIR + modelId + '.glb';

  const info = await FileSystem.getInfoAsync(localPath);
  if (info.exists) return localPath;

  await FileSystem.makeDirectoryAsync(CACHE_DIR, { intermediates: true });

  const download = FileSystem.createDownloadResumable(
    url,
    localPath,
    {},
    (progress) => {
      const pct = progress.totalBytesWritten / progress.totalBytesExpectedToWrite;
      arStore.setLoadProgress(pct);
    }
  );

  await download.downloadAsync();
  return localPath;
}
```

### 9.4 Free Model Sources for MVP

| Source | License | URL |
|---|---|---|
| Poly Pizza | CC0 (Public Domain) | https://poly.pizza |
| Sketchfab | Filter: Free + Royalty-Free | https://sketchfab.com |
| Khronos Sample Models | Various | https://github.com/KhronosGroup/glTF-Sample-Models |

---

## 10. Backend API Endpoints

Base URL: `https://<your-cloudflare-tunnel>.trycloudflare.com`

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| `POST` | `/api/sessions` | Bearer JWT | Create a new session; returns `sessionId` and LiveKit room token |
| `POST` | `/api/sessions/:id/join` | Bearer JWT | Join an existing session; returns LiveKit room token |
| `GET` | `/api/sessions/:id` | Bearer JWT | Get session status and participant list |
| `PATCH` | `/api/sessions/:id/end` | Bearer JWT | End a session |
| `GET` | `/api/models` | Bearer JWT | List available 3D models (id, name, url, thumbnail) |
| `GET` | `/api/models/:id` | Bearer JWT | Get model metadata |
| `GET` | `/assets/models/:filename` | None | Serve compressed GLB file |

### Session Creation Response

```json
{
  "sessionId": "sess_abc123",
  "roomName": "sess_abc123",
  "token": "<livekit-jwt>",
  "joinCode": "ABC-123"
}
```

### Model List Response

```json
{
  "models": [
    {
      "id": "engine_v1",
      "name": "Car Engine",
      "url": "https://<tunnel>/assets/models/engine_draco.glb",
      "thumbnail": "https://<tunnel>/assets/thumbnails/engine.png",
      "fileSizeBytes": 8200000
    }
  ]
}
```

---

## 11. Supabase Configuration

### Tables Required (see Backend Schema document for full SQL)

- `profiles` — extends `auth.users`; stores `role`, `display_name`
- `sessions` — stores `session_id`, `customer_id`, `technician_id`, `room_name`, `status`, `created_at`
- `models` — metadata only; actual files on disk

### Row-Level Security

- `profiles`: Users can read/write only their own profile
- `sessions`: Participants can read their own sessions; only creators can end them
- `models`: All authenticated users can read; only server can write

### Auth Setup

```typescript
import { createClient } from '@supabase/supabase-js';

const supabase = createClient(SUPABASE_URL, SUPABASE_ANON_KEY);

// Sign in with Google
const { data, error } = await supabase.auth.signInWithOAuth({
  provider: 'google',
  options: { redirectTo: 'remote-ar://auth-callback' }
});

// Get current session
const { data: { session } } = await supabase.auth.getSession();
```

---

## 12. Security Requirements

| Area | Requirement |
|---|---|
| Authentication | All API calls require a valid Supabase JWT in `Authorization: Bearer` header |
| LiveKit tokens | Tokens are short-lived (1 hour); generated server-side using `LIVEKIT_API_SECRET` (never exposed to client) |
| Media encryption | WebRTC enforces DTLS/SRTP by default; no additional config needed |
| Model assets | GLB files are served without auth (URLs are not guessable; session-scoped model list); acceptable for MVP |
| Environment variables | `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`, `SUPABASE_SERVICE_ROLE_KEY` stored in `.env`; never committed |

---

## 13. Performance Requirements

| Metric | Target | How Achieved |
|---|---|---|
| Video latency | < 500 ms | LiveKit adaptive stream + SFU routing |
| AR model load | < 3 s for < 10 MB GLB | Draco compression + local caching |
| Transform apply latency | < 100 ms | Unreliable channel + direct Zustand update → Viro re-render |
| AR plane detection | < 5 s on textured surface | ViroARPlane with `alignment="Horizontal"` + user guidance |
| Annotation render (3D) | < 200 ms per stroke | Batched world-point computation after stroke completes |

---

## 14. Development Environment

### Device Setup

1. Enable Developer Options on both Android phones
2. Enable USB Debugging
3. Connect Customer Phone (ARCore-compatible) to development machine
4. Install ARCore from Google Play Store if not already present
5. Run `npx expo run:android` — builds and installs dev client
6. Connect Technician Phone (any Android 7+)
7. Repeat build for technician app variant

### Environment Variables

```env
# .env (never commit)
SUPABASE_URL=https://xxxx.supabase.co
SUPABASE_ANON_KEY=eyJ...
LIVEKIT_URL=wss://your-livekit.trycloudflare.com
LIVEKIT_API_KEY=APIxxxx
LIVEKIT_API_SECRET=xxxx
NODE_SERVER_URL=https://your-node.trycloudflare.com
```

### Docker Compose (Server Laptop)

```yaml
version: '3.8'
services:
  livekit:
    image: livekit/livekit-server:latest
    ports:
      - "7880:7880"
      - "7881:7881"
    volumes:
      - ./livekit.yaml:/livekit.yaml
    command: --config /livekit.yaml

  api:
    build: ./backend
    ports:
      - "3000:3000"
    volumes:
      - ./backend:/app
      - ./assets:/app/assets
    env_file: .env
```
