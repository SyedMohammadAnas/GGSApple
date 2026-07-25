# Google OAuth — Remote AR
> **Do not commit `client_secret_*.json` files** — they are gitignored. Store secrets only in Supabase Dashboard.
## Client IDs (safe to reference in code/env)
| Client | ID | Used in |
|--------|-----|---------|
| Web (Supabase) | `664161950009-qp2d5g0qdejma6m2o6c1g3pq5gl4l84e.apps.googleusercontent.com` | Supabase Google provider, `EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID` |
| Android (debug) | `664161950009-t7dsi2ft5oajrm4i7htseg7idadtpcm1.apps.googleusercontent.com` | Google Cloud Android credential, Supabase Client IDs list |
## Supabase checklist
- [x] Google provider enabled
- [x] Web client secret in Supabase (not in repo)
- [x] **Client IDs** = Web + Android (comma-separated)
- [x] Redirect URLs: `remote-ar://auth-callback`, `exp+remote-ar://auth-callback`
- [x] Site URL: `remote-ar://` (recommended over `localhost:3000`)
- [x] Test users added in Google Cloud OAuth consent screen
- [x] Confirm email disabled (for dev)
## Package / SHA-1 (Android debug)
- Package: `com.ggsapple.remotear`
- SHA-1: `5E:8F:16:06:2E:A3:CD:2C:4A:0D:54:78:76:BA:A6:F3:8C:AB:F6:25`
