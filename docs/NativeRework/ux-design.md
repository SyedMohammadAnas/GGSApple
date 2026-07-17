# Remote AR Assistant — UI/UX Design Philosophy (Native Android)

> Version 2.0 · Jetpack Compose · Dark Theme · Field-Service Optimised

---

## 1. Design Foundation

This is a field-service tool. Customers are under-bonnet in direct sunlight with greasy hands.
Technicians are in a workshop or office managing a call. The design must work in those conditions.

The guiding principle is **zero cognitive friction at the moment of crisis**. The AR canvas is
not a UI — it is a window into the physical world. The technician's controls exist to guide, not
to impress. Every element must earn its place.

Reference product: TeamViewer Assist AR. Study its layout and interaction model. The technician's
view is a video feed with a drawing canvas. The customer's view is full-screen camera with minimal
chrome. That is the target.

---

## 2. Design Principles

### 2.1 The Camera Feed Is Sacred
On the customer's screen, the AR camera feed occupies 100% of the display. No UI element sits
on top of it except a 44 dp status strip at the top and a thin tracking-state bar. The technician's
drawn annotations are the only overlay on the camera surface — and those are the product.

### 2.2 Outdoor Readability Is Mandatory
Dark theme only. Electric cyan (`#00B4D8`) for AR-specific elements — it does not appear in the
real world, so it is always distinguishable. High-contrast white text on dark surfaces. No light
backgrounds anywhere in the app.

### 2.3 Technician Controls: Bottom 45% in Portrait
All interactive controls on the technician's call screen must be reachable with the right thumb
in portrait mode. The customer's video feed occupies the top 55%. Sliders, tools, and buttons
live below it. Nothing critical requires two-handed operation.

### 2.4 One Action Per Screen
Auth screen: one button. Customer home: one button. Waiting screen: one code display, one cancel.
Technician home: one input, one button. Call screens are the only complex views. Keep it simple.

### 2.5 Status Communicates Before Words
Pulsing cyan ring = scanning. Solid green = tracking. Red banner = error. Users under stress do
not read paragraphs. They respond to colour and shape before they read text.

### 2.6 Errors Name the Problem and Offer a Way Out
"AR not available on this device" + "Continue without AR" button.
"Camera permission needed" + "Open Settings" button.
Never: "An error occurred."

---

## 3. Color System (Compose `MaterialTheme`)

| Token | Hex | Usage |
|-------|-----|-------|
| `background` | `#0D1117` | All screen backgrounds |
| `surface` | `#161B22` | Cards, panels, bottom sheet |
| `surfaceVariant` | `#21262D` | Elevated controls, slider track, drawer |
| `primary` (accent) | `#00B4D8` | AR scan ring, active sliders, annotation default, active tool highlight |
| `primaryContainer` | `#003F4F` | Pressed state for primary elements |
| `secondary` | `#2ECC71` | Connected indicator, plane detected, success states |
| `tertiary` (warning) | `#F39C12` | Tracking limited, reconnecting |
| `error` | `#FF4757` | Error states, disconnect, danger actions |
| `onBackground` | `#F0F6FC` | Primary text |
| `onSurfaceVariant` | `#8B949E` | Secondary text, labels, captions |
| `outline` | `#30363D` | Dividers, control borders |

Applied via Compose `MaterialTheme` with a custom `darkColorScheme`.

---

## 4. Typography

Applied via Compose `MaterialTheme.typography`.

| Role | Font | Weight | Size | Usage |
|------|------|--------|------|-------|
| `bodyMedium` | Inter | 400 | 14sp | Control labels, messages |
| `labelLarge` | Inter | 500 | 14sp | Button text, panel headings |
| `labelSmall` | Inter | 400 | 12sp | Captions, metadata |
| `titleMedium` | Inter | 600 | 16sp | Screen titles, session code |
| `headlineLarge` | Inter | 700 | 32sp | Join code display (monospace feel) |

Session codes displayed in `Monospace` font family at `titleMedium` weight for readability.

---

## 5. Customer Call Screen

### 5.1 Full-Screen AR View
The `GLSurfaceView` hosting ARCore's camera renderer fills the entire screen — edge to edge,
including under the status bar (using `WindowInsetsCompat` to go edge-to-edge). No system
padding is applied to the GL surface.

```
┌──────────────────────────────────────┐
│  [●] 04:32  •  Connected             │  ← HUD strip (44dp, 75% transparent dark bg)
│──────────────────────────────────────│
│                                      │
│                                      │
│         LIVE AR CAMERA FEED          │
│         (GLSurfaceView 100%)         │
│                                      │
│    [annotation canvas overlay]       │
│                                      │
│                                      │
│                                      │
│ [● Scanning... move camera slowly]   │  ← Tracking state bar (bottom, translucent)
└──────────────────────────────────────┘
```

### 5.2 HUD Strip (top 44dp)
- Background: `Color(0xFF0D1117).copy(alpha = 0.75f)`
- Left: pulsing red dot (recording indicator) + session duration timer
- Right: connection status pill (green "Connected" / yellow "Reconnecting" / red "Disconnected")
- Rendered as a Compose `Box` with `Modifier.align(Alignment.TopStart)` over the GL surface

### 5.3 Tracking State Bar (bottom)
- Full-width translucent bar, 32dp height
- States:
  - "Scanning… move camera slowly" (no planes detected) — accent cyan text
  - "Surface found" (plane detected) — success green, disappears after 2 s
  - "Tracking lost — move camera slowly" — warning yellow
  - Hidden when tracking is stable and at least one plane is detected
- This is the customer's only prompt to scan the environment before annotations can land

### 5.4 Annotation Canvas Overlay
- Compose `Canvas` with `Modifier.fillMaxSize()` layered above the GL view
- Draws lines between projected screen positions of active anchors
- Colors match what the technician drew
- Per-frame update triggered via `mutableStateOf` updated from the render loop
- Lines use `Paint.strokeCap = Round`, `strokeWidth = thickness.dp.toPx()`

### 5.5 AR State Scan Indicator
- When tracking state is `PAUSED` (no planes found): a pulsing cyan ring drawn via the canvas
  overlay at screen centre, scale-pulsing 1.0→1.1→1.0 on a 1.5 s loop
- When first plane detected: ring dissolves (fade out, 300 ms)

---

## 6. Technician Call Screen

### 6.1 Layout
```
┌──────────────────────────────────────┐
│  Customer Name         04:32   [⚙]  │  ← Session header (48dp)
├──────────────────────────────────────┤
│                                      │
│      CUSTOMER LIVE VIDEO FEED        │  ← 55% of height; SurfaceView
│   [annotation overlay on video]      │
│                                      │
│                                      │
├──────────────────────────────────────┤
│  [✏] [⭕] [→]   [■ Color]   [✕ Clear] │  ← Drawing tools (48dp bar)
└──────────────────────────────────────┘
```

### 6.2 Video Feed Zone
- `TextureView` rendering the remote LiveKit video track (**native implementation** — not SurfaceView; see `learnings.md`)
- **Native build (verified 2026-06-29):** **9:16 portrait** stack centered on screen (`fillMaxHeight` + `aspectRatio(9/16)`) — matches mobile customer capture, not landscape 16:9
- Original UX spec used 16:9 letter-boxing; native intentionally uses portrait 9:16 on phone hardware
- Touch events captured by transparent drawing layer at highest zIndex (full panel including letterbox)
- No tap-to-place in annotations MVP (that is the 3D model phase)

### 6.3 Drawing Canvas (Transparent Overlay on Video)
- `Modifier.pointerInput` with `detectDragGestures` and `detectTapGestures`
- During drag: records point list as `List<Offset>`
- On drag end: stroke committed and broadcast to customer via Supabase Realtime
- Current in-progress stroke is drawn in real-time as the technician drags (preview)
- Past strokes stored in `technicianAnnotations: List<AnnotationStroke>`, redrawn each frame

### 6.4 Drawing Tools Bar
- Horizontal strip at the bottom
- Tool buttons: Freehand (✏), Circle (⭕), Arrow (→)
- Active tool has `primary` background behind icon, inactive is transparent
- Color swatch: small filled `Box` showing current color; tap opens a `DropdownMenu` or
  bottom sheet with 6 color swatches (cyan, red, yellow, green, white, orange)
- Clear button: right-aligned, `error` color, immediate action (no confirmation dialog)

### 6.5 Session Header
- `Text` showing customer's `display_name` (from profiles table)
- Timer: coroutine updating every second
- Settings `IconButton`: opens a bottom sheet with "End Call" + "Mute/Unmute"

---

## 7. Navigation Structure

```
App Entry → SplashScreen (auth check)
    ├── AuthScreen
    │     └── Google Sign-In → Role Router
    └── Role Router
          ├── CustomerHomeScreen
          │     └── WaitingScreen (showing join code)
          │           └── CustomerCallScreen (AR + HUD + annotation canvas)
          │                 └── SessionEndedScreen
          └── TechnicianHomeScreen
                └── JoinSessionScreen (enter code)
                      └── TechnicianCallScreen (video feed + drawing canvas)
                            └── SessionEndedScreen
```

Navigation uses Jetpack Navigation Compose. Each destination is a sealed class route.
Deep link from auth callback: `remotear://auth-callback` handled in `MainActivity`.

---

## 8. Waiting Screen (Customer)

```
┌──────────────────────────────────────┐
│                                      │
│        Waiting for technician        │
│                                      │
│    ┌─────────────────────────┐       │
│    │   ABC - 123             │       │  ← Join code, large monospace
│    └─────────────────────────┘       │
│                                      │
│        [Copy code]  [Share]          │
│                                      │
│    ●●● (pulsing cyan ring)           │  ← Waiting animation
│                                      │
│        [Cancel Session]              │
│                                      │
└──────────────────────────────────────┘
```

- Session code in large `headlineLarge` monospace text; easy to read and share
- Copy button: writes code to clipboard
- Share button: Android share sheet
- Pulsing cyan ring: 80dp diameter, scale 1.0→1.15→1.0, 2 s loop

---

## 9. Auth Screen

Minimal. Brand logo centred. One button.

```
┌──────────────────────────────────────┐
│                                      │
│         [Logo / App Name]            │
│                                      │
│         Remote AR Assistant          │
│                                      │
│                                      │
│    ┌─────────────────────────┐       │
│    │  [G]  Sign in with Google│       │
│    └─────────────────────────┘       │
│                                      │
└──────────────────────────────────────┘
```

Google Sign-In button follows Google's branding guidelines (white button, Google "G" logo).

---

## 10. Motion and Animation

| Element | Animation | Duration |
|---------|-----------|----------|
| Scan ring pulse | Scale 1.0 → 1.1 → 1.0 | 1.5 s loop |
| Waiting ring pulse | Scale 1.0 → 1.15 → 1.0 | 2 s loop |
| Surface found bar | Fade in then fade out | 300 ms fade in, 2 s hold, 300 ms fade out |
| Toast notifications | Slide up + fade in | 200 ms |
| Screen transitions | Compose default slide | 300 ms |
| Annotation draw | Immediate (no animation) | — |
| Clear annotations | Immediate (no animation) | — |
| Error banner | Slide down from top | 200 ms |

No gratuitous animation. Users under stress do not need choreography.

---

## 11. Accessibility (MVP)

- All interactive controls: minimum touch target 48 × 48 dp (Material Design 3 guideline)
- All buttons have `contentDescription` for TalkBack
- Color is never the only differentiator (active tool uses both color and shape)
- Session code displayed in large, high-contrast monospace
- Error messages always include text, never icon-only

---

## 12. Compose Implementation Notes

### Edge-to-Edge on Customer Screen
```kotlin
// In CustomerCallActivity / ComposeView
WindowCompat.setDecorFitsSystemWindows(window, false)
// GLSurfaceView fills the full screen including under status bar
// Compose overlays use Modifier.systemBarsPadding() only for HUD strip
```

### Canvas Overlay Composition
The annotation overlay is a `Box` stacking:
1. `AndroidView { GLSurfaceView }` — the AR camera feed
2. `Canvas(modifier = Modifier.fillMaxSize())` — annotation lines (recomposed per frame)
3. `Box` at top with HUD strip
4. `Box` at bottom with tracking state bar

Recomposition of the annotation canvas is triggered by `derivedStateOf { anchorProjections }` where
`anchorProjections` is a `SnapshotStateList` updated from the GL render thread via
`Handler(Looper.getMainLooper()).post { ... }`.
