# GGSApple Instant demo — customer app (2026-07-27)

## Install
1. Uninstall old packages if present:
   - `adb uninstall com.ggsapple.remotear.instant`
   - `adb uninstall com.ggsapple.remotear`
2. From repo: `cd android-app && .\gradlew installDebug`
3. Or install a staged APK from `demo/` when refreshed
4. Customer phone: install/update "Google Play Services for AR" if prompted

## Product shape
- Instant = **customer only** (share public ID, wait for web expert)
- Expert = **browser** (`https://ggs-macmini.tail3bc01f.ts.net`, Vercel backup `https://ggsexpert.vercel.app`)
- No Customer/Expert toggle on the phone

## Baked / default URLs (override in Debug backend URL)
- API: `https://ggs-macmini.tail3bc01f.ts.net:8443`
- LiveKit: `wss://ggs-macmini.tail3bc01f.ts.net:7880` (GGS Mac Mini Tailscale Serve)
- Auth/data: cloud Supabase
- Requires Tailscale on phone + Mac Mini stack awake (Podman LiveKit)
