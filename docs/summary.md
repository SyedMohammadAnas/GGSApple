# Remote AR Assistant — Project Summary

> Last updated: 2026-06-24
> Single source of truth for project context.

---

## Current status

| Phase | Focus | Status |
|-------|-------|--------|
| **0–2** | Env, auth, LiveKit video call | ✅ Complete |
| **3** | Native shared camera (ARCore + LiveKit) | ✅ Complete (with CPU fallback on Samsung M14) |
| **4** | World-relative annotations | 🔴 **Unverified on device** — sync transport works; plane anchoring + shared-ar video still failing per user (2026-06-24) |
| **5** | 3D model in AR | ⏳ Next |
| **6** | Error handling & polish | ⏳ Pending |
| **7** | Integration testing & demo | ⏳ Pending |

**Latest (2026-06-24):** Two-phone LAN testing. **Proven:** `livekit` mode video + Supabase annotation sync speed. **Not proven / still broken:** `shared-ar` technician video timing and true ARCore world-anchored annotations. See [`complaints.md`](complaints.md) and [`learnings.md`](learnings.md#2026-06-24-debugging-marathon--video--world-annotations-unverified).

**Open on Samsung SM-M146B:** ARCore `session.resume()` still throws `FatalException` (reproduced in `native-spike/` — device/environment, not RN). App runs in **video + pseudo-AR fallback** until tested on second ARCore phone.

---

## Test setup (always use this)

| Device | Metro | Build | Role |
|--------|-------|-------|------|
| Phone 1 | `npm run start:phone` → **8081** | `npm run android:phone` | **Customer** — Start Session, AR call |
| Phone 2 | same Metro (**8081**) | `npm run android:phone2` | **Technician** — Join Session, director view |

**One Metro serves both phones** — they share the same `phoneDebug` flavor and `.env`. Dual Metro was only required for the old phone + emulator setup.

**Flow:** Phone 1 **Start Session** → Phone 2 **Join** → customer AR call + technician live video + annotations → **End Session**.

```powershell
# Terminal 1 — Metro (leave running)
cd remote-ar
npm run start:phone

# Terminal 2 — install / launch (only needed after native changes)
npm run android:phone
npm run android:phone2
```

---

## What works now (MVP path)

| Area | Status |
|------|--------|
| Auth, sessions, Docker API + LiveKit | ✅ |
| Phone ↔ phone video + audio + data channel | ✅ |
| Native shared camera → LiveKit stream | ✅ |
| Customer AR preview (GLES when ARCore resumes; **ImageView CPU** on fallback) | ✅ |
| Technician director screen + drawing tools | ✅ |
| Annotations: technician draw → customer overlay → sync back | ✅ |
| Pseudo-3D annotations (sensor-based raycast/project on fallback) | ✅ best-effort |
| True ARCore plane hit-test | ⏳ needs device where `session.resume()` succeeds |

---

## Key architecture docs

| Doc | Purpose |
|-----|---------|
| [`native-shared-camera-architecture.md`](native-shared-camera-architecture.md) | Native module design, frame pipeline, APIs |
| [`learnings.md`](learnings.md) | Debugging notes, gotchas, changelog |
| [`remote-ar-implementation-plan.md`](remote-ar-implementation-plan.md) | Full phase checklist |
| [`instructions.md`](instructions.md) | Setup, tunnels, build, test commands |
| [`native-spike/README.md`](../native-spike/README.md) | Isolated ARCore spike (reference only) |

---

## Active tunnel URLs

Keep both `cloudflared` processes running (see `scripts/cloudflare-tunnel.md`). Update `.env` when tunnels restart.

| Service | URL |
|---------|-----|
| API | `https://plains-challenging-brass-barcelona.trycloudflare.com` |
| LiveKit | `wss://salt-win-looksmart-additionally.trycloudflare.com` |

---

## Retired / not on call path

- Viro `CustomerARView` on call screen — **not mounted**; native `ARCameraView` replaces it
- `useARCameraHandoff` — **removed**
- `ar_mode` technician video pause — **removed**
- JS-only camera handoff — **failed**; do not revisit
- Emulator dual-Metro workflow — **retired**; two physical phones share one Metro on port 8081
