# Phase 9 — Annotation fixes, UI polish, Vercel dashboard (2026-07-08)

## 3D asset dashboard (Vercel)

- Moved from `backend/public/dashboard/` → **`asset-dashboard/`** (standalone repo)
- Deployed: **https://cgs-asset-dashboard.vercel.app**
- Serverless `/api/*` proxies to tunneled Docker backend with `DASHBOARD_KEY` server-side
- Env vars configured on Vercel (Supabase, tunnel URLs, API URL)

See `asset-dashboard.md` for GitHub connect steps (manual — `gh` not installed locally).

---

## Expert annotation fix

**Root cause:** `PointerTouchLayer` was always stacked above `DrawingTouchLayer` (zIndex 5 vs 4), intercepting touches even when the pointer tool was inactive.

**Fix:**
- Compose touch layers **only when that tool is active** (`drawToolActive` / `pointerActive`)
- Move touch layers **above** `AssistCallChrome` (zIndex 9) so they are not blocked by sidebar/bottom sheet

Files: `CallScreens.kt`

---

## Undo fix (both roles)

**Root cause:**
- Customer undo was explicitly blocked in `CallViewModel`
- Customer commits never pushed stroke IDs onto `strokeOrderStack`
- Undo button was disabled when `drawingEnabled == false`

**Fix:**
- Track customer strokes on `strokeOrderStack` in `AnnotationController.commitDraftStroke`
- Allow undo for both roles in `AnnotationController.undoLastStroke`
- Remove customer guard in `CallViewModel.undoLastStroke`
- Keep UNDO sidebar button always enabled (`AssistCallUi.kt`)

---

## Seamless black chrome

- `MainActivity`: `enableEdgeToEdge` with black status + navigation bar styles
- Root `Box` background `#000000`
- `themes.xml`: black `windowBackground`, `statusBarColor`, `navigationBarColor`
- `SessionEndedScreen`, customer call root use `Background` black

---

## Tutorial recording (no MediaProjection)

**Problem:** Android 14+ requires `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` before `createVirtualDisplay`. Tutorial hit this on API 35.

**Approach chosen:** **ARCore frame encoder** — records the AR camera feed (YUV from `ImageReader`) to MP4 via `MediaCodec` + `MediaMuxer`. No screen-capture consent dialog.

- `TutorialVideoRecorder` — encodes ~15 fps NV21 frames
- `ARCoreManager.setTutorialFrameSink()` — taps `onImageAvailable`
- `LocalTutorialViewModel` — start/stop without MediaProjection intent

**Trade-off:** Tutorial video is the **AR camera feed**, not the full screen with Compose buttons. Good for walkthrough capture; not WYSIWYG of UI chrome.

**Session recording (Master premium)** still uses MediaProjection with new `ScreenCaptureForegroundService` (FGS + manifest service declaration).

Files:
- `TutorialVideoRecorder.kt`
- `ScreenCaptureForegroundService.kt`
- `ScreenRecordingManager.kt` (FGS for in-call recording)
- `LocalTutorialViewModel.kt`

---

## Install

```powershell
cd android-app
.\gradlew installMasterDebug
.\gradlew installInstantDebug
```

Both flavors installed on SM-F936B + SM-N980F after this phase.
