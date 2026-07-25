# GGSApple Instant demo — Tailscale + AR fix (2026-07-21)

## Install
1. Tailscale on both phones (same account as Homelab)
2. Uninstall old Instant if present
3. Install demo\app-instant-debug.apk
4. On customer phone: install/update "Google Play Services for AR" if Play Store opens
5. Confirm http://100.70.151.71:3000/health

## Roles
- Customer (field) = AR mode — toggle Customer on home
- Expert = remote video only (no AR by design)

## Baked URLs
- Supabase cloud + API http://100.70.151.71:3000 + LiveKit ws://100.70.151.71:7880

## AR fix in this build
Previous APK treated ARCore INSTALL_REQUESTED as unsupported and forced video fallback.
This build waits for Play Services for AR install, then resumes AR on return.
