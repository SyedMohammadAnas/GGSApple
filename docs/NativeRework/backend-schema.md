# Remote AR Assistant — Backend & Data Schema

> Version 2.0 · Backend is UNCHANGED from v1 · Schema additions noted

---

## 1. What Stays the Same

The entire backend — Node.js API, Docker LiveKit, Supabase database, Cloudflare tunnels — is
unchanged from the React Native version. The native Android app consumes the same API endpoints,
connects to the same LiveKit server, and reads/writes the same Supabase tables.

**Do not rebuild the backend. Do not change the API. Do not change the schema.**

The only schema change in v2 is adding a `ar_fallback` boolean column to the `sessions` table
so the app can log whether the session ran in video-only mode. This is optional for MVP.

---

## 2. Database Schema (Supabase PostgreSQL)

### 2.1 `profiles` Table (v3 — added `public_id`)

```sql
ALTER TABLE profiles ADD COLUMN public_id char(11) UNIQUE;
-- Auto-assigned on insert via trigger; backfilled for existing users
```

Display format in app: `X-XXX-XXX-XXX` (11 digits).

| Column | Type | Notes |
|--------|------|-------|
| `public_id` | `char(11)` UNIQUE | Personal ID for ID-based session join |

Other columns unchanged: `id`, `email`, `display_name`, `role`, `avatar_url`, timestamps.

### 2.2 `sessions` Table (UNCHANGED for MVP; optional v2 addition noted)
```sql
CREATE TABLE sessions (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  join_code      text NOT NULL UNIQUE,
  room_name      text NOT NULL,
  customer_id    uuid NOT NULL REFERENCES profiles(id),
  technician_id  uuid REFERENCES profiles(id),
  status         text NOT NULL DEFAULT 'waiting'
                   CHECK (status IN ('waiting', 'active', 'ended')),
  created_at     timestamptz NOT NULL DEFAULT now(),
  ended_at       timestamptz,
  -- Optional v2 addition (add via migration, not required for MVP):
  ar_fallback    boolean DEFAULT false
);
```

RLS:
- SELECT: customer can read their own sessions; technician can read sessions they joined
- INSERT: customer only
- UPDATE: customer or technician participant; server role (for status changes)

### 2.3 `models` Table (UNCHANGED — for future 3D model phase)
```sql
CREATE TABLE models (
  id             text PRIMARY KEY,
  name           text NOT NULL,
  url            text NOT NULL,
  thumbnail_url  text,
  file_size_bytes bigint NOT NULL,
  is_active      boolean NOT NULL DEFAULT true,
  created_at     timestamptz NOT NULL DEFAULT now()
);
```

RLS: all authenticated users can read active models; server role only for write.

---

## 3. API Endpoints (UNCHANGED)

Base URL: `https://<cloudflare-tunnel>.trycloudflare.com`
Auth: `Authorization: Bearer <Supabase JWT>` on all protected routes.

### POST /api/sessions
Creates a session. Called by customer.

Request body: (empty)

Response 201:
```json
{
  "sessionId": "uuid",
  "roomName": "uuid",
  "joinCode": "ABC-123",
  "livekitToken": "eyJ...",
  "expiresAt": "2026-06-24T..."
}
```

Server actions:
1. Verify JWT; confirm role == "customer"
2. Generate 6-char alphanumeric join code (unique)
3. Create LiveKit room via LiveKit server API
4. Insert session row (status: "waiting")
5. Generate LiveKit token (room join grant, 1 h expiry)
6. Return response

### POST /api/sessions/join-by-id (v3 — primary join path)
Joins a customer's **waiting** session by their 11-digit public ID. Called by expert.

Request body:
```json
{ "targetPublicId": "12345678901" }
```

Response 200: same shape as join-by-code (`sessionId`, `roomName`, `joinCode`, `status`, `token`).

Errors: 400 invalid/self-join, 404 no waiting session, 409 already active.

### POST /api/sessions/:joinCode/join (legacy — premium fallback)
Joins by 6-char code. Unchanged.

### GET /api/users/me (v3)
Returns profile including `publicId`.

### POST /api/sessions/join (deprecated doc — use join-by-id or :joinCode/join)
Joins an existing session. Called by technician.

Request body: `{ "joinCode": "ABC-123" }`

Response 200:
```json
{
  "sessionId": "uuid",
  "roomName": "uuid",
  "livekitToken": "eyJ...",
  "expiresAt": "2026-06-24T..."
}
```

Server actions:
1. Verify JWT
2. Find session by join_code (status must be "waiting")
3. Set technician_id = current user, status = "active"
4. Generate LiveKit token for technician
5. Return response

### PATCH /api/sessions/:id/end
Ends a session. Called by either participant.

Request body: `{ "initiatedBy": "customer" | "technician" }`

Response 200: `{ "ok": true }`

Server actions:
1. Verify JWT; confirm caller is customer_id or technician_id for this session
2. Set status = "ended", ended_at = now()
3. Optionally delete LiveKit room

### GET /api/models
Lists 3D models from `backend/assets/models/manifest.json`. Requires Bearer JWT.

Response 200:
```json
{
  "models": [
    { "id": "uuid", "name": "Car Engine", "url": "...", "thumbnailUrl": "...", "fileSizeBytes": 8200000 }
  ]
}
```

### Admin dashboard API (`/api/admin/models`)
Used by the web dashboard at `/dashboard/`. Auth via `x-dashboard-key` header (`DASHBOARD_KEY` env, default `dev-dashboard`).

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/admin/models` | List all models |
| POST | `/api/admin/models/upload` | Upload GLB (+ optional thumbnail) |
| DELETE | `/api/admin/models/:id` | Remove model |

See `asset-dashboard.md`.

### GET /health
No auth. Returns `{ "status": "ok", "timestamp": "..." }`.

---

## 4. Supabase Realtime Channels

These are ephemeral broadcast channels — no data is written to the database. The native app
subscribes via `supabase-kt` Realtime.

### `annotations:{sessionId}` — Technician → Customer
Used by the technician to send annotation strokes to the customer.

**Events:**
```json
// stroke event
{
  "event": "stroke",
  "id": "uuid-v4",
  "tool": "freehand",
  "color": "#00B4D8",
  "thickness": 3,
  "points": [[0.45, 0.62], [0.46, 0.63]]
}

// circle event (center + radius normalised)
{
  "event": "circle",
  "id": "uuid-v4",
  "color": "#FF4757",
  "thickness": 3,
  "center": [0.50, 0.50],
  "radius": 0.08
}

// arrow event (start + end normalised coords)
{
  "event": "arrow",
  "id": "uuid-v4",
  "color": "#F39C12",
  "thickness": 3,
  "start": [0.30, 0.40],
  "end": [0.55, 0.65]
}

// clear event
{ "event": "clear" }
```

### `annotation_sync:{sessionId}` — Customer → Technician
Used by the customer to send projected-back 2D positions to the technician.

**Events:**
```json
// projected stroke
{
  "event": "stroke_projected",
  "id": "uuid-v4",
  "screenPoints": [[0.45, 0.62], [0.46, 0.63]]
}

// projected circle
{
  "event": "circle_projected",
  "id": "uuid-v4",
  "screenPoints": [[0.45, 0.50], [0.46, 0.51], ...]
}
```

Broadcast rate: throttled to 20 fps maximum from the customer's render loop.

---

## 5. LiveKit Configuration (UNCHANGED)

### `livekit.yaml`
```yaml
port: 7880
rtc:
  tcp_port: 7881
  port_range_start: 7900
  port_range_end: 7999
  node_ip: <server-LAN-IP>           # Update to current ipconfig value
  use_external_ip: false
keys:
  <LIVEKIT_API_KEY>: <LIVEKIT_API_SECRET>
turn:
  enabled: true
  udp_port: 3478
logging:
  level: info
room:
  empty_timeout: 300
  max_participants: 2
```

### Token Grants
```json
{
  "roomJoin": true,
  "canPublish": true,
  "canSubscribe": true,
  "room": "<roomName>",
  "identity": "<userId>",
  "exp": <now + 3600>
}
```

---

## 6. Docker Compose (UNCHANGED)

```yaml
services:
  api:
    build: ./backend
    ports: ["3000:3000"]
    env_file: .env
    volumes:
      - ./backend/assets:/app/assets   # Model files served from here
  livekit:
    image: livekit/livekit-server:latest
    ports:
      - "7880:7880"
      - "7881:7881/tcp"
      - "3478:3478/udp"
      - "7900-7999:7900-7999/udp"
    volumes:
      - ./livekit.yaml:/livekit.yaml
    command: --config /livekit.yaml
```

---

## 7. Environment Variables

### Server (.env)
```
SUPABASE_URL=https://suuellchcoegerddqyjb.supabase.co
SUPABASE_SERVICE_ROLE_KEY=<from Supabase dashboard>
LIVEKIT_API_KEY=<from livekit.yaml>
LIVEKIT_API_SECRET=<from livekit.yaml>
LIVEKIT_URL=http://localhost:7880
PORT=3000
PUBLIC_API_URL=https://<tunnel>.trycloudflare.com
```

### Android App (local.properties — gitignored)
```
SUPABASE_URL=https://suuellchcoegerddqyjb.supabase.co
SUPABASE_ANON_KEY=<anon key>
LIVEKIT_URL=wss://<tunnel>.trycloudflare.com
API_URL=https://<tunnel>.trycloudflare.com
```

These are injected into `BuildConfig` via `buildConfigField` in `build.gradle.kts`.

---

## 8. Cloudflare Tunnels (UNCHANGED)

```powershell
# Terminal 1
cloudflared tunnel --url http://localhost:3000     # API tunnel

# Terminal 2
cloudflared tunnel --url http://localhost:7880     # LiveKit tunnel
```

Copy the generated URLs into `local.properties` on each rebuild of the tunnels.
For LAN development, you can also use the server's LAN IP directly:
`ws://192.168.x.x:7880` for LiveKit (no tunnel needed on same WiFi).

---

## 9. Existing Supabase Google OAuth Config (UNCHANGED)

- Supabase project: `https://suuellchcoegerddqyjb.supabase.co`
- Google Client IDs (both web + Android registered):
  ```
  664161950009-qp2d5g0qdejma6m2o6c1g3pq5gl4l84e.apps.googleusercontent.com
  664161950009-t7dsi2ft5oajrm4i7htseg7idadtpcm1.apps.googleusercontent.com
  ```
- Android package name: `com.ggsapple.remotear`
- Debug SHA-1: `5E:8F:16:06:2E:A3:CD:2C:4A:0D:54:78:76:BA:A6:F3:8C:AB:F6:25`
- Supabase redirect URL: `remotear://auth-callback`
- App deep link scheme: `remotear`

The native Android app uses the same package name and SHA-1 as before. No Google Cloud Console
changes required.
