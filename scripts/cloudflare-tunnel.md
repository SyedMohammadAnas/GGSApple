# Cloudflare Tunnel Setup (Server Laptop)

Run these commands on the server laptop **after** `docker compose up -d` is healthy.

## Prerequisites

```powershell
cd "d:\GitHub Projects\GGSApple"
docker compose ps          # api + livekit should be Up
Invoke-RestMethod http://localhost:3000/health   # { "status": "ok", ... }
```

Do **not** run `npm run dev` while Docker is using port 3000.

## Quick tunnel (development)

Open **two terminals** and keep both running:

```powershell
# Terminal 1 — expose Node.js API (Docker port 3000)
cloudflared tunnel --url http://localhost:3000

# Terminal 2 — expose LiveKit HTTP/WebSocket (Docker port 7880)
cloudflared tunnel --url http://localhost:7880
```

Copy the generated `*.trycloudflare.com` URLs and update:

| File | Variable | Value |
|------|----------|-------|
| Root `.env` | `PUBLIC_API_URL` | `https://<api-tunnel>.trycloudflare.com` |
| `remote-ar/.env` | `EXPO_PUBLIC_NODE_SERVER_URL` | Same API tunnel URL (`https://`) |
| `remote-ar/.env` | `EXPO_PUBLIC_LIVEKIT_URL` | LiveKit tunnel URL with `wss://` prefix |
| Supabase `models` | `url`, `thumbnail_url` | Replace tunnel hostname in seeded URLs |

### Current quick tunnels (2026-06-24)

| Service | Tunnel URL |
|---------|------------|
| API | `https://plains-challenging-brass-barcelona.trycloudflare.com` |
| LiveKit | `wss://salt-win-looksmart-additionally.trycloudflare.com` |

Quick tunnels get a **new URL every restart**. Re-copy into `.env` files and Supabase `models` when tunnels restart.

Supabase model URL update:

```sql
UPDATE public.models SET url = REPLACE(url, 'builder-charges-charging-split.trycloudflare.com', 'plains-challenging-brass-barcelona.trycloudflare.com');
UPDATE public.models SET thumbnail_url = REPLACE(thumbnail_url, 'builder-charges-charging-split.trycloudflare.com', 'plains-challenging-brass-barcelona.trycloudflare.com');
```

## Verify from phone (mobile data, not WiFi)

```bash
curl https://plains-challenging-brass-barcelona.trycloudflare.com/health
curl -o /tmp/engine.glb https://plains-challenging-brass-barcelona.trycloudflare.com/assets/models/engine_draco.glb
curl -o /tmp/tire.glb https://plains-challenging-brass-barcelona.trycloudflare.com/assets/models/tire_draco.glb
curl -o /tmp/sparkplug.glb https://plains-challenging-brass-barcelona.trycloudflare.com/assets/models/sparkplug_draco.glb
```

Expected: `/health` returns `{"status":"ok",...}` and all three GLB downloads succeed.

## Persistent tunnel (optional, production-like)

See [Cloudflare Tunnel docs](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/) for named tunnels with stable hostnames.
