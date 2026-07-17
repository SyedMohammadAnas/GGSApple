# Remote AR Assistant — Setup & Testing Instructions

> For the human operator. Updated after each milestone.

---

## Environment Setup

### Prerequisites

- **Node.js 22+** and npm
- **Docker Desktop** (server laptop) for LiveKit + API containers
- **Cloudflared** CLI for public tunnel URLs
- **Android Studio** with SDK 24+ and platform tools (`adb` in PATH)
- **Physical Android phone** — ARCore-capable for customer testing; any Android 7+ for technician
- **Supabase project** — already created at `https://suuellchcoegerddqyjb.supabase.co`

### 1. Clone and install

```powershell
cd "d:\GitHub Projects\CGSApple"

# Backend dependencies
cd backend
npm install

# Expo app dependencies
cd ..\remote-ar
npm install
```

### 2. Environment variables

```powershell
# Server (project root)
copy .env.example .env
# Edit .env — add SUPABASE_SERVICE_ROLE_KEY from Supabase dashboard

# Client (Expo app)
cd remote-ar
copy .env.example .env
# Edit .env after Cloudflare Tunnel is running
```


| Variable                        | Where         | Value                                                              |
| ------------------------------- | ------------- | ------------------------------------------------------------------ |
| `SUPABASE_URL`                  | Server `.env` | `https://suuellchcoegerddqyjb.supabase.co`                         |
| `SUPABASE_SERVICE_ROLE_KEY`     | Server `.env` | Supabase Dashboard → Settings → API                                |
| `EXPO_PUBLIC_SUPABASE_URL`      | Client `.env` | Same Supabase URL                                                  |
| `EXPO_PUBLIC_SUPABASE_ANON_KEY` | Client `.env` | See `.env.example` (anon key)                                      |
| `EXPO_PUBLIC_NODE_SERVER_URL`   | Client `.env` | `https://plains-challenging-brass-barcelona.trycloudflare.com` |
| `EXPO_PUBLIC_LIVEKIT_URL`       | Client `.env` | `wss://salt-win-looksmart-additionally.trycloudflare.com`   |
| `PUBLIC_API_URL`                | Server `.env` | Same API tunnel URL as above                                       |


### 3. Start backend (Docker — recommended for server laptop)

```powershell
cd "d:\GitHub Projects\CGSApple"
docker compose up -d --build
docker compose ps
Invoke-RestMethod http://localhost:3000/health
```

Expected:

- `cgsapple-api-1` and `cgsapple-livekit-1` show **Up**
- Health returns `{ "status": "ok", "timestamp": "..." }`

Do **not** run `npm run dev` at the same time — both bind port 3000.

### 4. Start backend (local development — alternative)

Use this only when Docker is **not** running the API container:

```powershell
cd "d:\GitHub Projects\CGSApple\backend"
npm run dev
```

Expected output:

```
Remote AR API listening on port 3000
Health: http://localhost:3000/health
```

### 5. Cloudflare Tunnel

See `scripts/cloudflare-tunnel.md` for full steps.

Quick version (two terminals, after Docker is up):

```powershell
# Terminal 1 — API
cloudflared tunnel --url http://localhost:3000

# Terminal 2 — LiveKit
cloudflared tunnel --url http://localhost:7880
```

Current tunnel URLs (re-copy into `.env` when tunnels restart):


| Service | URL                                                                |
| ------- | ------------------------------------------------------------------ |
| API     | `https://plains-challenging-brass-barcelona.trycloudflare.com` |
| LiveKit | `wss://salt-win-looksmart-additionally.trycloudflare.com`   |


Update model URLs in Supabase after tunnel is live:

```sql
UPDATE public.models SET url = REPLACE(url, 'builder-charges-charging-split.trycloudflare.com', 'plains-challenging-brass-barcelona.trycloudflare.com');
UPDATE public.models SET thumbnail_url = REPLACE(thumbnail_url, 'builder-charges-charging-split.trycloudflare.com', 'plains-challenging-brass-barcelona.trycloudflare.com');
```

### 6. Supabase Google OAuth

Google sign-in needs **three places** configured: Google Cloud Console, Supabase Dashboard, and the app deep link (`remote-ar://auth-callback`).

#### Step A — Google Cloud Console

1. Open [Google Cloud Console](https://console.cloud.google.com/) → create or select a project.
2. **APIs & Services → OAuth consent screen**
  - User type: **External** (for testing) or Internal if you use Google Workspace
  - App name, support email, developer contact — fill required fields
  - Add test users (your Gmail) while app is in **Testing** mode
3. **APIs & Services → Credentials → Create credentials**

**Credential 1 — Web application** (used by Supabase):


| Field                    | Value                                                       |
| ------------------------ | ----------------------------------------------------------- |
| Name                     | `Remote AR Supabase`                                        |
| Authorized redirect URIs | `https://suuellchcoegerddqyjb.supabase.co/auth/v1/callback` |


Copy the **Client ID** and **Client secret** — paste into Supabase in Step B.

**Credential 2 — Android** (required for native Google sign-in on device):


| Field                         | Value                                                         |
| ----------------------------- | ------------------------------------------------------------- |
| Name                          | `Remote AR Android Debug`                                     |
| Package name                  | `com.cgsapple.remotear`                                       |
| SHA-1 certificate fingerprint | `5E:8F:16:06:2E:A3:CD:2C:4A:0D:54:78:76:BA:A6:F3:8C:AB:F6:25` |


> Debug SHA-1 above is from `remote-ar/android/app/debug.keystore`. For a **release** build later, add a second Android credential with your release keystore SHA-1.

Re-run to print SHA-1 anytime:

```powershell
keytool -list -v -keystore "d:\GitHub Projects\CGSApple\remote-ar\android\app\debug.keystore" -alias androiddebugkey -storepass android -keypass android
```

#### Step B — Supabase Dashboard

Project: `https://suuellchcoegerddqyjb.supabase.co`

1. **Authentication → Providers → Google** → Enable
2. Paste **Web client** Client ID and Client secret from Step A
3. **Client IDs field** — paste **both** IDs comma-separated (required for Android):
  ```
   664161950009-qp2d5g0qdejma6m2o6c1g3pq5gl4l84e.apps.googleusercontent.com,664161950009-t7dsi2ft5oajrm4i7htseg7idadtpcm1.apps.googleusercontent.com
  ```
4. **Authentication → URL Configuration**
  - Site URL: `remote-ar://` (change from `http://localhost:3000` for mobile)
  - **Redirect URLs** — add all of:
    ```
    remote-ar://auth-callback
    exp+remote-ar://auth-callback
    ```

#### Step C — App (already in repo)

`remote-ar/app.json` has `"scheme": "remote-ar"` — matches the redirect above.

Client env vars in `remote-ar/.env`:


| Variable                        | Purpose                                   |
| ------------------------------- | ----------------------------------------- |
| `EXPO_PUBLIC_SUPABASE_URL`      | Supabase project URL                      |
| `EXPO_PUBLIC_SUPABASE_ANON_KEY` | Anon/publishable key (never service role) |


#### Step D — Verify (after Phase 1 auth UI exists)

1. Tap Google sign-in on a **physical phone** (`npm run android:phone`)
2. Browser / account picker opens → select Google account
3. App returns to `remote-ar://auth-callback` and session appears in Supabase **Authentication → Users**
4. On **Home**, choose **Start Session** (host) or **Join Session** (enter code) — the same Google account can do either on any device; there is no fixed customer/technician role at login.

**Session test (two devices):**


| Device          | Action                                                                              |
| --------------- | ----------------------------------------------------------------------------------- |
| A               | Sign in → **Start Session** → share join code on Waiting screen                     |
| B               | Sign in → enter code → **Join Session**                                             |
| Either          | **Cancel Session** (host on Waiting) or **End Session** (after join) → back to Home |
| Either (remote) | Other device sees **Session ended** alert and returns to Home within ~3 seconds     |


Rebuild API after backend changes: `docker compose up -d --build api` from repo root.

#### Step E — Phase 2 video call (LiveKit) ✅ Verified

**Prerequisites:** Docker + both `cloudflared` tunnels running (see `scripts/cloudflare-tunnel.md`). Restart Metro after any `.env` URL change.


| Device | Metro | Build | Role |
|--------|-------|-------|------|
| Phone 1 (customer) | `npm run start:phone` → port **8081** | `npm run android:phone` | **Host** — camera + mic publish, AR call |
| Phone 2 (technician) | same Metro (**8081**) | `npm run android:phone2` | **Technician** — sees host video, mic, drawing tools |


**Single Metro workflow:**

```powershell
# Terminal 1 — Metro (leave running)
cd remote-ar
npm run start:phone

# Terminal 2 — install / launch both phones (after native changes)
npm run android:phone
npm run android:phone2
```

Both phones use `phoneDebug` and the same `.env` — one Metro on **8081** is enough. Dual Metro was only needed for the old phone + emulator setup (different Gradle flavors).

**Test steps:**

1. Phone 1 → **Start Session** → allow camera + mic → confirm **Camera and microphone are live**
2. Phone 2 → **Join Session** with code → allow mic
3. Phone 1 auto-navigates to connected screen with **local camera preview**
4. Phone 2 shows **customer live video** + **Data channel: connected**
5. **End Session** on either device → both return Home

**Common failures:**


| Symptom                                     | Fix                                                                                                 |
| ------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| Phone 2 can't load JS / red screen          | Reinstall with `npm run android:phone2` (must use Metro port **8081**, not 8082)                    |
| Phone tries `ws://10.0.2.2:7880`            | Rebuild phone flavor (`npm run android:phone`); phone must use Cloudflare `wss://` URL              |
| `redirect_uri_mismatch`                     | Web client redirect URI must be exactly `https://suuellchcoegerddqyjb.supabase.co/auth/v1/callback` |
| Google sign-in works on web but not Android | Add Android OAuth client with package `com.cgsapple.remotear` + correct SHA-1                       |
| App doesn't return after login              | Add `remote-ar://auth-callback` to Supabase redirect URLs                                           |
| `Access blocked`                            | Add your Gmail under OAuth consent screen → Test users                                              |
| Technician hears audio but no video         | Both on `phoneDebug` build; check Docker LiveKit logs for `mediaTrack published`                    |
| Session/API errors after reboot             | Restart `cloudflared` tunnels and update `.env` + Supabase `models` URLs                            |


### 7. Android Development Build — two phones


| Script | Command | Device | Notes |
| ------ | -------------------------- | -------------------- | --------------------------------- |
| Phone 1 | `npm run android:phone` | First USB phone | Customer / AR host |
| Phone 2 | `npm run android:phone2` | Second USB phone | Technician / join |


Both phones connect to **one Metro** on port **8081**.

```powershell
# Terminal 1 — Metro
npm run start:phone

# Terminal 2 — install both (after native changes)
npm run android:phone
npm run android:phone2
```

If Gradle fails with `Unable to delete directory` under `react-native-screens`, run `npm run android:clean` before rebuilding.

**One-time:** Android SDK is at `D:\AndroidStudio` (not the default `%LOCALAPPDATA%\Android\Sdk`). Run:

```powershell
cd "d:\GitHub Projects\CGSApple"
.\scripts\setup-android-env.ps1
```

Restart your terminal (or Cursor), then verify:

```powershell
adb devices   # should show your phone or emulator
```

```powershell
# Phone 1 (customer / AR)
cd "d:\GitHub Projects\CGSApple\remote-ar"
npm run android:phone

# Phone 2 (technician)
npm run android:phone2
```

First build per device takes 10–20 minutes. Subsequent JS changes use Fast Refresh without rebuild.

> **Note:** Both phones use the `phoneDebug` flavor (full AR). The `emulator` Gradle flavor still exists for optional x86 emulator testing but is not used in the daily two-phone workflow.

---

## Testing Instructions

### Test: Backend health endpoint (local)

```powershell
Invoke-RestMethod http://localhost:3000/health
```

**Expected:** `{ "status": "ok", "timestamp": "..." }`

### Test: Model asset serving (local)

```powershell
curl.exe -s -o NUL -w "%{http_code}" http://localhost:3000/assets/models/engine_draco.glb
curl.exe -s -o NUL -w "%{http_code}" http://localhost:3000/assets/models/tire_draco.glb
curl.exe -s -o NUL -w "%{http_code}" http://localhost:3000/assets/models/sparkplug_draco.glb
```

**Expected:** All three return `200`.

### Test: Model file sizes (Draco compression)

```powershell
Get-ChildItem "d:\GitHub Projects\CGSApple\backend\assets\models\*.glb" | Select-Object Name, @{N='MB';E={[math]::Round($_.Length/1MB,2)}}
```

**Expected:** All files under 10 MB.


| File                  | Size    |
| --------------------- | ------- |
| `engine_draco.glb`    | ~0.4 MB |
| `tire_draco.glb`      | ~2.3 MB |
| `sparkplug_draco.glb` | ~7.9 MB |


### Test: Backend health via Cloudflare Tunnel

From a phone on **mobile data** (not same WiFi):

```bash
curl https://plains-challenging-brass-barcelona.trycloudflare.com/health
```

**Expected:** `{"status":"ok",...}`

### Test: Model downloads via Cloudflare Tunnel

From phone mobile data:

```bash
curl -o engine.glb https://plains-challenging-brass-barcelona.trycloudflare.com/assets/models/engine_draco.glb
curl -o tire.glb https://plains-challenging-brass-barcelona.trycloudflare.com/assets/models/tire_draco.glb
curl -o sparkplug.glb https://plains-challenging-brass-barcelona.trycloudflare.com/assets/models/sparkplug_draco.glb
```

**Expected:** All three files download successfully with non-zero size.

### Test: Expo dev build on device

After `npm run android:phone`:

**Expected:** App opens, Google sign-in, Home with Start / Join Session.

### Test: Supabase schema

In Supabase Dashboard → Table Editor:

**Expected:** Tables `profiles`, `sessions`, `models` exist with RLS enabled. `models` has 3 rows.

---

## Troubleshooting

### `adb` not recognized / Android SDK path not found

Expo defaults to `C:\Users\<you>\AppData\Local\Android\Sdk`. This project uses `**D:\AndroidStudio`** as the SDK root.

**Fix (one-time):**

```powershell
cd "d:\GitHub Projects\CGSApple"
.\scripts\setup-android-env.ps1
```

Restart the terminal, then `adb devices` should work.


| Variable           | Value                                                                                                     |
| ------------------ | --------------------------------------------------------------------------------------------------------- |
| `ANDROID_HOME`     | `D:\AndroidStudio`                                                                                        |
| `ANDROID_SDK_ROOT` | `D:\AndroidStudio`                                                                                        |
| `JAVA_HOME`        | `C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot` (JDK 17 required; JDK 25 breaks Gradle native builds) |
| PATH (append)      | `D:\AndroidStudio\platform-tools`, `D:\AndroidStudio\emulator`                                            |


Gradle also reads `remote-ar/android/local.properties` (`sdk.dir=D:/AndroidStudio`), recreated on `npx expo prebuild`.

**Gradle 9 + JDK 25:** First `npx expo run:android` may fail with `JvmVendorSpec IBM_SEMERU`. The project auto-patches this on `npm install` (`scripts/patch-gradle-foojay.mjs`). If it persists, install **JDK 17** and set `JAVA_HOME` to it (see `scripts/setup-android-env.ps1`).

If Android Studio is installed elsewhere, set `$androidHome` in `scripts/setup-android-env.ps1` to your SDK folder (the one that contains `platform-tools`, `platforms`, `build-tools`).

### ViroReact crash on emulator (`libviro_renderer.so`)

Use the `**emulator` flavor** — it excludes Viro native libraries entirely:

```powershell
npm run android:emulator
```

Do **not** use `phone` flavor or plain `npx expo run:android` on the x86 emulator.

For AR testing, use a physical phone:

```powershell
npm run android:phone
```

### ViroReact / Expo version conflict

Project uses **Expo SDK 55** + **React Native 0.83.6**. Do not upgrade to SDK 56 until ViroReact supports it.

### `@livekit/react-native` config plugin warning

LiveKit has no Expo config plugin. It was removed from `app.json`. If camera/mic permissions fail in Phase 2, add permissions manually to `AndroidManifest.xml` after prebuild.

### Docker API container keeps restarting

If `docker compose logs api` shows `Cannot find module '/app/dist/index.js'`, the image build was overwritten by a full `./backend:/app` bind mount. The compose file mounts **assets only** (`./backend/assets:/app/assets`). Rebuild:

```powershell
docker compose up -d --build api
```

### Docker LiveKit won't start

Check ports 7880, 7881, 7900–7999 are free. Verify `livekit.yaml` keys match `.env` `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET`.

### Cloudflare tunnel URL changes on restart

Quick tunnels get new URLs each run. Update `.env` files and Supabase `models` table URLs each time, or set up a named persistent tunnel.

### Port 3000 already in use (`EADDRINUSE`)

Only one backend can listen on port 3000 at a time. Common causes:

- A previous `npm run dev` still running in another terminal
- Docker API container: `docker compose up -d` also binds port 3000

**Fix:** Stop the other process, then start dev again.

```powershell
# Find what is using port 3000
netstat -ano | findstr :3000

# Stop by PID (replace 12345 with the PID from the last column)
taskkill /PID 12345 /F

# Or stop Docker backend if you started it that way
docker compose down
```

Do not run `npm run dev` and `docker compose up` at the same time unless you change `PORT` in `.env` for one of them.

### Model download 404

Ensure backend is running and files exist in `backend/assets/models/`. Check filename matches URL exactly (case-sensitive).

---

## Demo Script (draft)

1. Customer signs in → **Start Session** → share code
2. Technician joins → sees live rear-camera feed
3. Technician draws circle + arrow on video
4. Customer sees overlay anchored (moves with phone on M14 pseudo-AR)
5. **End Session** on either device

**Before demo:** Pre-flight Docker + tunnels; confirm `.env` URLs; one Metro on 8081; `phoneDebug` on both phones.

**Known demo caveat:** Samsung M14 uses CPU preview fallback — mention “plane AR pending second device test.”

---

## Phase 3 & 4 — AR call + annotations (current gate)

### Prerequisites

1. Docker + both Cloudflare tunnels running
2. **One Metro** on port **8081** (`npm run start:phone`)
3. Google Play Services for AR on customer phone
4. Native rebuild after any Kotlin change: `npm run android:phone` and `npm run android:phone2`

### What you should see

| Device | Expected |
|--------|----------|
| Phone 1 (customer) | Live camera preview (upright, not stretched); HUD timer; annotations overlay when technician draws |
| Phone 2 (technician) | Continuous live customer video; drawing toolbar; strokes sync back on video |

### If preview is wrong or black

```powershell
adb logcat -s SharedCamera ARCameraModule ARCameraRenderer ReactNativeJS
```

Confirm `phoneDebug` installed. Reinstall:

```powershell
cd "d:\GitHub Projects\CGSApple\remote-ar\android"
$env:ANDROID_SERIAL="RZCWA1ZL43K"   # your phone serial
.\gradlew app:installPhoneDebug -PreactNativeDevServerPort=8081
```

### Optional: native-spike isolation test

See [`native-spike/README.md`](../native-spike/README.md). Emulator can join spike room via dev **Join Spike Test Room** button when `EXPO_PUBLIC_SPIKE_TECH_TOKEN` is in `.env`.

### End-to-end test (4 terminals)

```powershell
# 1 — Docker
cd "d:\GitHub Projects\CGSApple"
docker compose up -d --build

# 2 — API tunnel
cloudflared tunnel --url http://localhost:3000

# 3 — LiveKit tunnel
cloudflared tunnel --url http://localhost:7880

# 4 — Metro + both phones
cd "d:\GitHub Projects\CGSApple\remote-ar"
npm run start:phone
# In another terminal (after Metro is up):
npm run android:phone
npm run android:phone2
```

**Flow:** Phone 1 **Start Session** → Phone 2 **Join** → verify video both ways → technician draws → customer sees overlay → **End Session**.

**Next validation:** Second ARCore phone to confirm `session.resume()` + true plane hit-test (replaces pseudo-3D).

---

## Commands Quick Reference

```powershell
# Start backend (Docker — recommended)
cd "d:\GitHub Projects\CGSApple"
docker compose up -d --build
docker compose ps
Invoke-RestMethod http://localhost:3000/health

# Stop Docker backend
docker compose down

# Start backend (local dev — only when Docker API is stopped)
cd backend && npm run dev

# Start Expo Metro (both phones)
cd remote-ar && npm run start:phone

# Install on phone 1 (customer) or phone 2 (technician)
cd remote-ar && npm run android:phone
cd remote-ar && npm run android:phone2

# Compress a new model
gltf-pipeline -i input.glb -o backend/assets/models/output_draco.glb -d
```
