# AR Assist — Feature Matrix (Master vs Instant)

> **Master** = premium (`IS_PREMIUM=true`). **Instant** = free tier.

**Last updated:** 2026-07-08

---

## Shared core (Master + Instant)

| Area | Features |
|------|----------|
| Auth | Google sign-in, Supabase session |
| Home | Pure black UI, large preview, ID display, expert/customer toggle |
| Customer flow | Auto waiting session on home; share opens system share sheet only |
| Expert flow | Join by 11-digit customer ID |
| Incoming session | Orange status dot + "Incoming session connection" on home |
| Call | LiveKit video, annotations (pointer, arrow, freehand, circle) |
| 3D | Model library search + AR placement (when models exist on backend) |
| Tutorial | Local video tutorial recording |
| Debug | Backend URL override sheet |
| Cache | Clear cache (preserves Google/Supabase login) |

---

## Master only (stripped in Instant)

| Feature | Notes |
|---------|-------|
| In-call chat | Text messages via data channel |
| File share | Upload + open shared files |
| Screen recording | MediaProjection capture |
| Session menu | Recording / chat / files entry points |
| Speaker toggle | Route audio to speakerphone |
| Video pause | Freeze last remote frame |

---

## Instant simplified call UI

| Control | Master | Instant |
|---------|--------|---------|
| Mute | Yes | Yes |
| End call | Yes | Yes |
| Speaker | Yes | Hidden |
| Pause video | Yes | Hidden |
| Chat / files / record | Yes | Hidden |
| 3D asset sheet | Yes | Yes |

---

## Build commands

```powershell
.\gradlew installMasterDebug   # app label: Master
.\gradlew installInstantDebug   # app label: Instant
```

---

## Backend

Both flavors use the same API (`/api/sessions`, `/api/models`, etc.). Asset uploads are managed via the web dashboard at `/dashboard/`.
