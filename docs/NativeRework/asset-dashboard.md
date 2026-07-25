# AR Assist — 3D Asset Dashboard (Vercel)

Standalone web app in `asset-dashboard/` — **not** bundled with the Docker API.

## Live deployment

| | URL |
|---|-----|
| **Production** | https://ggs-asset-dashboard.vercel.app |
| **Vercel project** | `syedmohammadanas-projects/ggs-asset-dashboard` |

API routes on Vercel proxy to your tunneled Docker backend (`BACKEND_API_URL`). The dashboard key never ships to the browser.

---

## Repository layout

```
asset-dashboard/
  public/index.html      # Dashboard UI
  api/models.js          # GET list (proxy)
  api/models/upload.js   # POST upload (proxy)
  api/models/[id].js     # DELETE (proxy)
  vercel.json
  package.json
```

---

## Vercel environment variables (production)

| Variable | Value (current dev setup) |
|----------|---------------------------|
| `BACKEND_API_URL` | `https://plains-challenging-brass-barcelona.trycloudflare.com` |
| `PUBLIC_API_URL` | Same as above |
| `DASHBOARD_KEY` | `dev-dashboard` (match backend `.env`) |
| `SUPABASE_URL` | `https://suuellchcoegerddqyjb.supabase.co` |
| `SUPABASE_ANON_KEY` | Supabase anon key |
| `LIVEKIT_PUBLIC_URL` | `wss://salt-win-looksmart-additionally.trycloudflare.com` |

Update `BACKEND_API_URL` whenever you restart the Cloudflare quick tunnel.

---

## GitHub connection (manual step)

Local git repo is initialized in `asset-dashboard/`. `gh` CLI was not available in this environment.

1. Create repo on GitHub (e.g. `ggs-asset-dashboard`)
2. ```powershell
   cd asset-dashboard
   git remote add origin https://github.com/<you>/ggs-asset-dashboard.git
   git push -u origin master
   ```
3. In Vercel → Project Settings → Git → Connect repository

---

## Local dev

```powershell
cd asset-dashboard
npx vercel dev
```

Create `.env.local`:

```
BACKEND_API_URL=http://localhost:3000
DASHBOARD_KEY=dev-dashboard
```

---

## Backend

The Docker API still serves:

- `GET /api/models` — mobile apps (Bearer JWT)
- `/api/admin/models/*` — dashboard proxy target (`x-dashboard-key`)

The old `/dashboard` static route was removed from `backend/src/index.ts`.
