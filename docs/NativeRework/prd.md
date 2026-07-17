# Remote AR Assistant — Product Requirements Document (Native Rewrite)

> Version 2.0 · Native Android (Kotlin) · React Native fully retired

---

## 1. Executive Summary

Remote AR Assistant is a native Android application (Kotlin + Jetpack Compose) that enables a
remote expert (technician) to guide an on-site user (customer) through tasks using live video and
world-anchored AR annotations. The experience mirrors TeamViewer Assist AR and Vuforia Chalk:
the customer's phone runs ARCore, the technician draws on a video feed, and annotations stick to
physical surfaces in the customer's real-world environment.

### Why the React Native Version Was Abandoned
React Native could not reliably share the rear camera between ARCore and LiveKit's WebRTC stack.
Every approach tried — JS handoff, shared camera native module, IMU pseudo-AR — either produced
broken video, screen-fixed annotations, or both. The fundamental issue is that camera ownership
transfer across the JS bridge has unpredictable timing and lifecycle. A native Kotlin app gives
full, deterministic control over the camera pipeline, the GL thread, and the ARCore session
lifecycle. This is how TeamViewer Assist AR is built; this is how we will build ours.

---

## 2. Target Users

| Role | Device | AR Required | Description |
|------|--------|-------------|-------------|
| Customer (field worker) | Android phone (ARCore-capable) | Yes | On-site; shares rear camera; sees AR overlays |
| Technician (remote expert) | Any Android phone | No | Remote; views customer video; draws annotations |

---

## 3. Business Goals
- Provide a remote guidance experience equivalent to TeamViewer Assist AR for vehicle roadside assistance
- Enable technicians to draw annotations that stay locked to physical surfaces as the customer moves their phone
- Eliminate travel and unnecessary towing by enabling guided self-repair of minor issues

---

## 4. MVP Feature Set

### In Scope

| # | Feature | Notes |
|---|---------|-------|
| F-01 | Google Authentication | Supabase Auth; same as existing backend |
| F-02 | Role-based routing | Customer sees "Start Session"; Technician sees "Join Session" |
| F-03 | One-to-one LiveKit video call | Audio + video, bidirectional |
| F-04 | Customer AR camera view | Full-screen ARCore scene; rear camera; plane detection running |
| F-05 | Technician director view | Customer video feed + drawing canvas overlay; no AR on technician device |
| F-06 | World-anchored freehand annotations | Technician draws → customer's ARCore converts to 3D anchors → sticks to surface |
| F-07 | World-anchored circle annotations | Same anchor mechanism as freehand |
| F-08 | World-anchored arrow annotations | Same anchor mechanism; arrow head rendered as canvas element |
| F-09 | Annotation sync back to technician | Customer re-projects 2D anchor positions and sends to technician overlay |
| F-10 | Clear all annotations | Technician taps clear; all anchors detached on customer device |
| F-11 | Annotation color picker | 6 colors: cyan, red, yellow, green, white, orange |
| F-12 | Session code flow | Customer generates 6-char code; technician enters it to join |
| F-13 | AR fallback to video-only | If ARCore unavailable or `session.resume()` fails, session continues without AR; annotations become screen-fixed |
| F-14 | End session from either side | Both devices return to home on session end |
| F-15 | Reconnect on network drop | LiveKit handles ICE reconnect; Supabase Realtime reconnects automatically |
| F-16 | Plane detection HUD | Small indicator on customer screen showing tracking state: Scanning / Surface detected / Tracking |

### Out of Scope (Post-MVP)

| Feature | Reason |
|---------|--------|
| 3D GLB model placement | Next phase after annotations are verified working |
| Model rotation / scale sliders | Part of 3D model phase |
| iOS / ARKit | Android-first; iOS after Android is stable |
| Session recording | Post-MVP |
| Cloud Anchors (multi-device shared AR) | Not needed for 1-to-1 remote assistance pattern |
| Customer-side drawing | Technician-only for MVP |
| Undo / redo | Post-MVP polish |
| Coordinator/admin role | Post-MVP |

---

## 5. User Stories

### Authentication
| ID | Story |
|----|-------|
| US-01 | As a user, I want to sign in with Google so I don't need a separate account |
| US-02 | As a user, I want to be routed to the correct screen (Customer Home or Technician Home) based on my role |

### Session Management
| ID | Story |
|----|-------|
| US-03 | As a customer, I want to start a session and see a join code to share with the technician |
| US-04 | As a technician, I want to enter the join code and immediately join the call |
| US-05 | As either user, I want the other side to be notified and return to home when I end the session |

### Video
| ID | Story |
|----|-------|
| US-06 | As a technician, I want to see the customer's rear camera feed as a live video stream with minimal latency |
| US-07 | As both users, I want to hear each other via full-duplex audio throughout the session |
| US-08 | As a technician, I want to see the customer's video resume quickly after a brief network hiccup |

### AR Annotations
| ID | Story |
|----|-------|
| US-09 | As a technician, I want to draw a freehand stroke on the customer's video feed and have it appear on their physical surface |
| US-10 | As a customer, I want to move my phone after an annotation is drawn and see the annotation stay locked to the surface I pointed at |
| US-11 | As a technician, I want to draw a circle or arrow annotation, not just freehand |
| US-12 | As a technician, I want to choose the annotation color |
| US-13 | As a technician, I want to clear all annotations with one tap |
| US-14 | As a technician, I want to see my own drawn annotations reflected back on the video feed so I know they landed correctly |

### AR State
| ID | Story |
|----|-------|
| US-15 | As a customer, I want to see a clear indicator telling me to move my phone slowly to detect surfaces before annotations can be placed |
| US-16 | As a customer, if my device does not support ARCore, I want the session to continue as a plain video call |

---

## 6. Functional Requirements

### Authentication (AUTH)
| ID | Requirement |
|----|------------|
| AUTH-01 | Users authenticate via Google OAuth through Supabase Auth |
| AUTH-02 | Roles are stored in the `profiles` table and assigned on first login |
| AUTH-03 | The existing Supabase project (`suuellchcoegerddqyjb.supabase.co`) and schema are reused unchanged |

### Video (VC)
| ID | Requirement |
|----|------------|
| VC-01 | Customer device streams rear camera via LiveKit using a custom `VideoCapturer` fed by ARCore's `ImageReader` surface |
| VC-02 | Technician device subscribes to customer's video track and renders it full-screen (or near-full-screen) |
| VC-03 | Audio is bidirectional via LiveKit; both participants can speak and hear throughout |
| VC-04 | LiveKit's native Kotlin SDK v2 (`io.livekit:livekit-android`) is used on both devices |
| VC-05 | Video latency target: < 500 ms end-to-end on LAN |
| VC-06 | LiveKit reconnect is handled automatically; UI shows "Reconnecting…" banner during disruption |

### AR Core (AR)
| ID | Requirement |
|----|------------|
| AR-01 | Customer app creates an ARCore `Session` with `Session.Feature.SHARED_CAMERA` |
| AR-02 | ARCore's `ImageReader` surface outputs YUV_420_888 frames; these are converted to I420 and pushed to LiveKit's custom `VideoCapturer` |
| AR-03 | ARCore runs plane detection continuously; the customer sees the camera feed through ARCore's GL renderer |
| AR-04 | On first `session.resume()` failure (FatalException), the app falls back to video-only mode: opens Camera2 directly for streaming, disables AR anchoring, annotations become screen-fixed |
| AR-05 | Customer screen shows tracking state: "Scanning…" (no planes) → "Surface found" (planes detected) → "Tracking" (stable) |

### Annotations (ANN)
| ID | Requirement |
|----|------------|
| ANN-01 | Technician draws on a transparent canvas layer overlaid on the customer video feed view |
| ANN-02 | Each stroke point is recorded in normalised video-frame coordinates (0.0–1.0 on both axes) |
| ANN-03 | Normalised coordinates are broadcast via Supabase Realtime channel `annotations:{sessionId}` |
| ANN-04 | On receipt, customer app calls `frame.hitTest(normX * viewWidth, normY * viewHeight)` for each point |
| ANN-05 | If hit test returns a plane hit, `hitResult.createAnchor()` is called; anchor stored in a local `MutableList<Anchor>` keyed by annotation stroke ID |
| ANN-06 | If hit test returns no plane hit (e.g., pointed at sky), the annotation point falls back to a feature-point hit or a fixed 0.5 m depth placement |
| ANN-07 | Every render frame, customer app iterates all active anchors, gets their world-space pose, projects to 2D screen coords using `camera.getProjectionMatrix()` and `camera.getViewMatrix()`, and draws strokes on a canvas overlay |
| ANN-08 | Customer app broadcasts projected-back 2D normalised screen coords to technician via Supabase Realtime (`annotation_sync:{sessionId}`) |
| ANN-09 | Technician app receives projected-back coords and draws them as an overlay on the video feed |
| ANN-10 | `clear_annotations` message: customer detaches all anchors; technician clears overlay |
| ANN-11 | Annotation transport is Supabase Realtime (not LiveKit DataChannel); this is proven to be faster and survives ICE restarts |

### Error Handling (ERR)
| ID | Requirement |
|----|------------|
| ERR-01 | ARCore unavailable → video-only fallback; annotations remain screen-fixed with a banner "AR not available on this device" |
| ERR-02 | Tracking lost → banner "Move camera slowly to scan surface"; annotations freeze in last position |
| ERR-03 | Network drop → LiveKit reconnects automatically; Supabase Realtime reconnects automatically; annotations state is preserved in memory |
| ERR-04 | Camera permission denied → shown a rationale dialog; session cannot proceed without camera permission |
| ERR-05 | Technician's "no video" state → show spinner "Waiting for customer camera…" until the first video frame arrives |

---

## 7. Non-Functional Requirements

| Category | Requirement |
|----------|-------------|
| Video latency | < 500 ms end-to-end on LAN |
| Annotation round-trip | < 300 ms from technician draw to customer anchor render |
| AR plane detection | < 5 s from camera start on a textured flat surface |
| Annotation anchor stability | Annotation must stay within 1 cm of original placement after 1 m camera movement |
| Crash rate | 0% during demo conditions |
| Minimum Android API | 26 (Android 8.0) — required for ARCore |
| ARCore requirement | `arcore` as required (`AR Required` app) |
| Build tool | Android Studio, Gradle 8+, Kotlin 1.9+ |

---

## 8. Demo Day Acceptance Checklist

- [ ] Customer signs in with Google, lands on Customer Home
- [ ] Technician signs in with Google, lands on Technician Home
- [ ] Customer taps "Start Session"; join code appears on screen
- [ ] Technician enters code; both devices show the AR call screen
- [ ] Technician sees customer's live rear-camera feed within 3 seconds
- [ ] Customer tracking state shows "Surface found" after scanning a flat surface
- [ ] Technician draws a freehand stroke on the video
- [ ] Customer sees the stroke appear on the physical surface and stay there when moving the phone
- [ ] Technician draws a circle annotation; same result
- [ ] Technician draws an arrow annotation; same result
- [ ] Technician taps Clear; all annotations disappear on both screens
- [ ] Both users can hear each other throughout
- [ ] Either user ends the session; both return to home

---

## 9. What Is Kept from the Old Project

| Asset | Status | Notes |
|-------|--------|-------|
| Supabase database schema | ✅ Keep unchanged | `profiles`, `sessions`, `models` tables |
| Backend Node.js API | ✅ Keep unchanged | Session create/join/end, LiveKit token generation |
| Docker Compose setup | ✅ Keep unchanged | LiveKit + API |
| Cloudflare tunnel scripts | ✅ Keep unchanged | |
| LiveKit self-hosted server config | ✅ Keep unchanged | `livekit.yaml` |
| Supabase Realtime annotation transport | ✅ Keep (proven) | Broadcast on `annotations:{sessionId}` |
| React Native / Expo project | ❌ Fully retired | Do not modify; do not run |
