# Remote AR Assistant — UI/UX Design Philosophy

> Version 1.0 · MVP Scope

---

## 1. Design Foundation

Remote AR Assistant is a field-service tool. Its users operate in demanding real-world conditions — under-bonnet light, outdoor glare, roadside stress, greasy hands, and urgency. The design must work in those conditions, not in a calm studio environment.

The guiding principle is **zero cognitive friction at the moment of crisis**. Every pixel should either communicate something necessary or get out of the way. There are two very different interfaces to design: the Customer's AR view (full-screen, passive receiver of expert guidance) and the Technician's Director Panel (control-heavy, expert operator).

---

## 2. Design Principles

### 2.1 Legible in Outdoor Light

High contrast is non-negotiable. The customer may be filming an engine in direct sunlight. The technician may be looking at a video feed with strong glare reflections. The dark theme is mandatory — light UIs wash out in sunlight, and a white UI in an AR overlay murders contrast against bright backgrounds.

### 2.2 Glanceable Before Readable

Every status must be communicable through shape and color before the user reads a word. A pulsing cyan ring means "scanning for surface." A red banner means "something needs attention." Green means "live and working." Users under stress do not read — they scan.

### 2.3 One-Thumb Reachable (Technician)

The technician's control panel must be fully operable with the right thumb in portrait mode. All interactive controls (sliders, drawing tools, model selector) must live in the bottom 40–45% of the screen. Nothing critical should require two-handed operation.

### 2.4 The AR Overlay Is Not a UI — It Is Reality

The 3D model and annotations rendered in the customer's AR view are not UI elements. They are objects in the physical world, rendered by the device. Do not compete with them using UI chrome. The customer's HUD should be a thin, translucent strip at the top — nothing else.

### 2.5 Errors Explain and Offer an Exit

Never display a generic error. Every error message names what went wrong and offers the next step. "Reconnecting…" with a spinner is acceptable. "An error occurred" alone is not.

### 2.6 UX Copy is Conversational, Not Technical

The customer is not a developer. Do not expose technical concepts. "AR not supported" → "Video guidance continues instead." "Model load failed" → "Couldn't load the part diagram — tap to try again."

---

## 3. Color System

Dark theme only. Calibrated for outdoor readability, not polished office aesthetics.

| Token | Hex | Usage |
|---|---|---|
| `bg-primary` | `#0D1117` | Main backgrounds, scene chrome |
| `bg-surface` | `#161B22` | Cards, panels, bottom sheet |
| `bg-elevated` | `#21262D` | Control surfaces, sliders, drawer |
| `accent` | `#00B4D8` | AR overlay color, active sliders, annotation default color, interactive affordances |
| `accent-dim` | `#0096B3` | Pressed state for accent elements |
| `success` | `#2ECC71` | Connected indicator, plane detected |
| `warning` | `#F39C12` | Tracking limited, reconnecting |
| `danger` | `#FF4757` | Error states, disconnect |
| `text-primary` | `#F0F6FC` | All primary text |
| `text-secondary` | `#8B949E` | Labels, captions, helper text |
| `text-disabled` | `#484F58` | Inactive control labels |
| `border` | `#30363D` | Dividers, control borders |

**Accent rationale:** Electric cyan (`#00B4D8`) reads as "AR" to the user — it is the color of the annotation lines, the plane scanner ring, and the active state of every control. It is distinct from any real-world color the camera might capture, ensuring the overlay is always recognisable as digital.

---

## 4. Typography

| Role | Font | Weight | Size | Usage |
|---|---|---|---|---|
| UI Body | Inter | 400 | 14px | Control labels, error messages |
| UI Medium | Inter | 500 | 14px | Button labels, panel headings |
| UI Small | Inter | 400 | 12px | Captions, metadata |
| Data Values | JetBrains Mono | 400 | 13px | Degree values, scale values, session codes |
| HUD Status | Inter | 600 | 13px | Connection status, tracking state |

---

## 5. Customer AR View

The customer's screen is 95% camera feed. The phone is their window into the AR experience — the UI should never compete with it.

### 5.1 Layout

```
┌──────────────────────────────────────┐
│  [●] 04:32  •  Connected             │  ← HUD strip (44 px, semi-transparent dark bg)
├──────────────────────────────────────┤
│                                      │
│                                      │
│         LIVE CAMERA FEED             │
│                                      │
│    [3D model rendered in AR here]    │
│                                      │
│    [Annotation lines rendered here]  │
│                                      │
│                                      │
│                                      │
│                                      │
│                                      │
│                                      │
└──────────────────────────────────────┘
```

### 5.2 HUD Strip

- Height: 44 px
- Background: `rgba(13, 17, 23, 0.75)` — semi-transparent so the camera feed is visible behind it
- Left: recording indicator (pulsing red dot) + session duration
- Right: connection status pill (`● Connected` in success green / `⟳ Reconnecting` in warning yellow)
- No other controls on this screen — the customer is a receiver, not an operator

### 5.3 AR State Indicators

| State | Indicator |
|---|---|
| Scanning for surface | Pulsing cyan ring on detected feature cloud (ViroARPlane visualisation) |
| Surface detected | Ring solidifies briefly, then disappears |
| Model loading | Full-screen semi-transparent overlay with cyan progress ring + percentage |
| Tracking lost | Top-of-screen banner: "Move camera slowly to scan the surface" |
| AR not supported | Cyan-to-video transition animation; HUD changes to "Video mode" |

### 5.4 3D Model Visual Design

- The model renders with its native materials from the GLB
- A subtle cyan wireframe glow is added at 15% opacity via ViroMaterial to make the model distinguishable from real objects in challenging light
- No UI label or tooltip floats above the model — it would clutter the AR view

### 5.5 Annotation Visual Design

- Annotation lines render as glowing cyan ViroLine segments (`width: 0.003` in world units)
- Arrow annotations end with a small cone primitive
- Circle annotations use a closed loop of ViroLine segments (32 points)
- Annotations do not fade or animate — they are static until cleared

---

## 6. Technician Director Panel

The technician sees two zones: the customer's video (top, read-only window into the world) and the control panel (bottom, all the tools).

### 6.1 Layout

```
┌──────────────────────────────────────┐
│  [Customer Name]         04:32  [⚙]  │  ← Session header (48 px)
├──────────────────────────────────────┤
│                                      │
│                                      │
│      CUSTOMER LIVE VIDEO FEED        │  ← 55–60% of screen height
│                                      │
│   [annotation overlay on video]      │
│                                      │
│                                      │
├──────────────────────────────────────┤
│  [ Engine ][ Tire ][ Spark Plug ]   │  ← Model selector strip (horizontal scroll)
├──────────────────────────────────────┤
│  Rotation X  ●────────────── 45°    │  ← Rotation sliders
│  Rotation Y  ──────●──────── -12°   │
│  Scale       ─────────●───── 1.4×   │  ← Scale slider
├──────────────────────────────────────┤
│  [✏] [⭕] [→] [╱]   [🎨 ■]  [✕]   │  ← Drawing tools + color + clear
└──────────────────────────────────────┘
```

### 6.2 Model Selector Strip

- Horizontal scrollable row of cards
- Card: 80 × 80 px; rounded corners (8 px); `bg-elevated` background
- Content: small model thumbnail (top 60%) + model name text (bottom 40%)
- Selected state: cyan `accent` border (2 px) + subtle highlight on thumbnail
- Tap on a card sends `load_model` command immediately

### 6.3 Video Feed Zone

- Takes the top 55–60% of the screen below the session header
- The technician can tap anywhere on this zone to trigger tap-to-place
- During active placement: a subtle crosshair cursor appears at tap position, fades after 0.5 s
- Annotation overlay is composited on top: the returned projected 2D points are drawn on a transparent Canvas view positioned over the VideoView
- Annotation colors match what the technician drew

### 6.4 Rotation and Scale Sliders

- Three sliders stacked vertically
- Slider track: `bg-elevated`; filled portion: `accent` cyan
- Thumb: white circle (22 px diameter) with subtle drop shadow
- Value displayed right-aligned in JetBrains Mono (14 px)
- Rotation X: -180° to +180°, default 0°
- Rotation Y: -180° to +180°, default 0°
- Scale: 0.1× to 5.0×, default 1.0×
- Slider updates fire `transform` messages on every onChange (throttled to 60 fps max using `requestAnimationFrame`)

### 6.5 Drawing Tools Bar

- Single horizontal strip at bottom
- Four tool icons: ✏ Freehand, ⭕ Circle, → Arrow, ╱ Line
- Active tool: `accent` background behind icon
- Inactive tool: transparent background, `text-secondary` icon color
- Color swatch: small square showing current annotation color; tap to open a 6-color picker (cyan, red, yellow, green, white, orange)
- Clear button (✕): right-aligned; `danger` color; requires no confirmation (annotations are ephemeral)

### 6.6 Tap-to-Place Interaction

When the technician taps on the video zone:
1. A brief haptic tick fires (if available)
2. The tap position sends a `place_model` message with normalised coordinates
3. A small crosshair icon pulses at the tap location for 400 ms, then fades
4. The placed-model status ("Engine placed") appears as a toast for 2 s

### 6.7 Session Header

- Left: customer's name/identifier
- Centre: session duration counter (updates every second)
- Right: settings icon (opens a minimal sheet with "End call" and "Mute/Unmute")

---

## 7. Navigation Structure

```
App Entry
  └── Loading / Splash (auth check)
        ├── Auth Screen (Google sign-in button)
        └── Role Router
              ├── Customer Home
              │     └── Waiting Screen (session code display)
              │           └── AR Call Screen (ViroReact + HUD)
              │                 └── Session Ended Screen
              └── Technician Home
                    └── Join / Create Session Screen
                          └── Director Panel Screen
                                └── Session Ended Screen
```

---

## 8. Loading and Empty States

| State | UI Treatment |
|---|---|
| App cold start | Full-screen bg-primary with cyan logomark; auth state resolved before rendering |
| Waiting for technician to join | Pulsing cyan ring; session code displayed prominently in JetBrains Mono |
| Model loading | Percentage progress ring in cyan; model name below; no cancel option (prevents partial loads) |
| No models available | Inline message in model selector strip: "No models loaded yet" |
| Annotation canvas empty | No empty state — the canvas is invisible when empty |

---

## 9. Gesture Map

| Gesture | Zone | Action |
|---|---|---|
| Single tap | Video feed (technician) | Place model at tap position |
| Draw (pan) | Video feed (technician, drawing mode active) | Draw freehand annotation |
| Swipe (horizontal) | Model selector strip | Scroll model list |
| Slide (horizontal) | Rotation/scale sliders | Adjust value |
| Tap | Model card | Select and load model |
| Tap | Drawing tool icon | Switch active drawing tool |
| Tap | Color swatch | Open color picker |
| Tap | Clear button | Clear all annotations |

---

## 10. Accessibility Considerations (MVP)

- All interactive controls have a minimum touch target of 44 × 44 px
- Color is never the only differentiator — active states use both color and shape change
- Error messages are always text (not icon-only)
- Sliders include visible numeric value labels
- Session code is displayed in a large, monospaced font for readability under stress

---

## 11. Motion and Animation

Keep animation purposeful and brief. Under stress, animation is noise.

| Element | Animation | Duration |
|---|---|---|
| Plane scan ring | Pulse (scale 1.0 → 1.1 → 1.0) | 1.5 s loop |
| Model appear | Opacity 0 → 1 | 200 ms |
| Toast notifications | Slide up + fade in | 200 ms |
| Tap crosshair | Opacity 1 → 0 | 400 ms |
| Error banner | Slide down from top | 200 ms |
| Tool active state | No animation — instant | — |

All animations respect `prefers-reduced-motion` where the platform API supports it.
