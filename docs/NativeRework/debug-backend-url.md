# Debug Backend URL Override

> Paste Cloudflare tunnel URLs at runtime — no rebuild required when your dev laptop IP or tunnel changes.

**Last updated:** 2026-07-08

---

## Why

`local.properties` bakes `API_URL` and `LIVEKIT_URL` at compile time. When using Cloudflare quick tunnels, URLs change on every restart. The debug sheet lets the whole team test without rebuilding.

---

## How to use

1. Open **AR Assist** → kebab menu (⋮) → **Debug backend URL**
2. Paste your tunnel URLs:
   - **API URL:** `https://xxxx.trycloudflare.com` (no trailing slash)
   - **LiveKit URL:** `wss://yyyy.trycloudflare.com` (separate tunnel for LiveKit)
3. Tap **Save**
4. Start/join a session — app uses overridden URLs

Tap **Reset** to fall back to `BuildConfig` values from `local.properties`.

---

## Starting tunnels (dev machine)

See [`scripts/cloudflare-tunnel.md`](../../scripts/cloudflare-tunnel.md).

Typical setup — two terminals:

```powershell
# API (port 3000)
cloudflared tunnel --url http://localhost:3000

# LiveKit (port 7880) — separate terminal
cloudflared tunnel --url http://localhost:7880
```

Ensure Docker is running:

```powershell
cd "d:\GitHub Projects\GGSApple"
docker compose up -d
```

---

## Technical details

| Component | File |
|-----------|------|
| DataStore | `data/local/RuntimeConfigStore.kt` |
| Repository | `data/repository/RuntimeConfigRepository.kt` |
| UI | `AssistHomeScreen` → Debug sheet |
| API calls | `SessionApiService`, `ModelsApiService` read `runtimeConfig.apiUrlBlocking()` |
| LiveKit | `CallViewModel.startLiveKitIfReady()` reads `runtimeConfig.livekitUrlBlocking()` |

Overrides persist across app restarts until Reset.

---

## Both product tiers

Premium and Free builds both include the debug URL sheet. The **same backend** serves both flavors.
