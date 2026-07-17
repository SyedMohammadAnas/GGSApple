# Remote AR Assistant — Product Requirements Document

> Version 1.0 · MVP Scope · All decisions finalised

---

## 1. Executive Summary

Remote AR Assistant is a cross-platform mobile application (Android-first for MVP) that enables real-time video communication with superimposed, interactive 3D models. A remote expert (technician) guides an on-site user (customer) through complex mechanical repairs by overlaying and manipulating 3D models onto the customer's live AR camera feed. The technician operates in a pure "Director Mode" — no AR on their side — controlling everything through a video feed and control panel.

### The Core Problem

When a customer's vehicle breaks down, a standard video call is blind to hidden or obscured components. A hose beneath the engine, a clip behind a panel, or a fastener on the dark side of a component is invisible via video. The technician cannot point at what they cannot see, and the customer cannot understand what they cannot visualise.

### The Solution

The technician selects a 3D model of the relevant part, taps on the customer's live video feed to place it, and manipulates it (rotate, scale) in real time. The customer sees the model anchored to their real-world environment in AR. Annotations (circles, arrows, freehand lines) can be drawn on top. Both devices stay synchronised throughout the session via LiveKit's data channel.

---

## 2. Target Audience

| Persona | Description | Primary Needs |
|---|---|---|
| **Customer** | Vehicle owner on-site with a breakdown | Clear, unambiguous visual guidance; confidence to follow instructions safely |
| **Technician** | Remote expert (workshop mechanic, senior engineer) | Ability to describe hidden or obscured parts precisely; full control over the AR overlay |

> The **Coordinator** role (admin/session oversight) is deferred to Phase 2. Not in MVP scope.

---

## 3. Business Objectives

- Reduce roadside assistance response time by enabling guided self-repair of minor issues
- Reduce unnecessary towing and workshop visits
- Differentiate service offering through a demonstrably better remote support experience
- Improve first-call resolution rate

---

## 4. MVP Feature Set

### In Scope

| # | Feature | Description |
|---|---|---|
| F-01 | Google Authentication | Sign in via Google OAuth through Supabase; roles assigned at login |
| F-02 | One-to-one video call | Customer and technician connect via LiveKit; full audio and video |
| F-03 | Technician Director Panel | Technician sees customer's live video feed + control panel; no AR on technician device |
| F-04 | Customer AR view | Full-screen AR scene with live camera background. **Android MVP:** native `ARCameraView` (ARCore Shared Camera), not ViroReact on call path |
| F-05 | Model selection | Technician selects from 2–3 pre-loaded GLB models |
| F-06 | Tap-to-place | Technician taps on video feed; customer's device raycasts onto detected AR plane |
| F-07 | Model rotation (X, Y axes) | Technician rotates model via sliders; updates stream to customer in real time |
| F-08 | Model scaling | Technician scales model up/down via slider; customer sees update in real time |
| F-09 | Annotations — circle, arrow, freehand | Technician draws on video; annotations rendered in 3D AR space on customer's device |
| F-10 | Annotation sync | Technician sees their annotations overlaid on the customer's video feed |
| F-11 | Clear annotations | Technician clears all active annotations with a single tap |
| F-12 | Transform sync | All rotation and scale changes stream over LiveKit data channel (unreliable, low-latency) |
| F-13 | Fallback to video-only mode | If AR initialisation fails, session continues as a standard video call |
| F-14 | Model caching | Downloaded models cached on device; subsequent loads are instant |
| F-15 | Draco-compressed models | All GLB files compressed with Draco before serving |
| F-16 | Loading progress UI | Progress bar with percentage during model download |
| F-17 | Error handling | Full error state matrix with user-facing messages and graceful degradation |

### Out of Scope (MVP)

| Feature | Reason Deferred |
|---|---|
| Customer-side drawing | Technician annotation is sufficient for MVP demo |
| Annotation persistence across sessions | Sessions are ephemeral; no storage needed |
| Session history and replay | No storage layer needed for MVP |
| Coordinator role and dashboard | Deferred to Phase 2 |
| Screen sharing (technician) | Not essential for core demo |
| Full dynamic asset library UI | 2–3 pre-loaded models are sufficient |
| Undo / Redo for annotations | Nice-to-have |
| Multiple simultaneous models | One model at a time is sufficient |
| Animated 3D models | Static GLB/GLTF only |
| Model translation after placement | Tap-to-place + scale handles the MVP use case |
| iOS support | Android-only; iOS deferred to Phase 2 |
| Cloud Anchors | Replaced by customer-owned placement via raycast |

---

## 5. User Stories

### Authentication

| ID | Story |
|---|---|
| US-01 | As a user, I want to sign in with Google so that I can access the application without creating a new account |
| US-02 | As a user, I want my role (customer / technician) to be assigned so that I see the correct interface on login |

### Video Calling

| ID | Story |
|---|---|
| US-03 | As a customer, I want to start a session and share a code with the technician so that they can join my call |
| US-04 | As a technician, I want to join a session using the customer's code so that I can see their camera feed |
| US-05 | As a technician, I want to see the customer's live camera feed so that I can assess the problem |
| US-06 | As both users, I want audio to work bidirectionally so that we can talk throughout the session |

### AR Model Control

| ID | Story |
|---|---|
| US-07 | As a technician, I want to select a 3D model from a list so that I can show the customer what to look for |
| US-08 | As a technician, I want to tap on the customer's video feed to place the model so that it appears aligned with the real object |
| US-09 | As a customer, I want to see the 3D model appear in AR anchored to the surface I am filming so that I understand what the technician is showing |
| US-10 | As a technician, I want to rotate the model along the X and Y axes using sliders so that I can reveal hidden sides of the component |
| US-11 | As a customer, I want to see the model rotate in real time as the technician moves the sliders |
| US-12 | As a technician, I want to scale the model up or down so that it matches the proportions of the real part |

### Annotations

| ID | Story |
|---|---|
| US-13 | As a technician, I want to draw circles, arrows, and freehand lines on the customer's view so that I can point to specific areas |
| US-14 | As a customer, I want to see those annotations rendered in AR space, anchored to the plane, so that they stay in position as I move the camera |
| US-15 | As a technician, I want to clear all annotations with one tap so that I can start fresh |

### Error Handling

| ID | Story |
|---|---|
| US-16 | As a customer, if my device does not support AR, I want the session to fall back to a standard video call so that guidance can still be given |
| US-17 | As a customer, if a model fails to load, I want to see a clear error message with a retry option |
| US-18 | As both users, if the connection drops, I want it to reconnect automatically so that the session is not lost |

---

## 6. Functional Requirements

### Authentication (AUTH)

| ID | Requirement | Priority |
|---|---|---|
| AUTH-01 | Users shall authenticate via Google OAuth through Supabase Auth | High |
| AUTH-02 | On first login, users shall be assigned a role (customer or technician) stored in the profiles table | High |
| AUTH-03 | Session tokens shall be refreshed automatically | High |

### Video Calling (VC)

| ID | Requirement | Priority |
|---|---|---|
| VC-01 | The application shall support one-to-one video calls via LiveKit | High |
| VC-02 | Video and audio shall stream bidirectionally with latency below 500 ms | High |
| VC-03 | LiveKit data channels (Reliable and Unreliable) shall be established at call start | High |
| VC-04 | The call shall reconnect automatically on connection loss | High |
| VC-05 | The technician's view shall display the customer's camera stream as the primary feed | High |

### AR Model Overlay (AR)

| ID | Requirement | Priority |
|---|---|---|
| AR-01 | The customer's device shall initialise a native ARCore AR view on call start (Shared Camera pipeline) | High |
| AR-02 | The technician shall select a model from a pre-loaded list and send a `load_model` command | High |
| AR-03 | The customer's device shall download, Draco-decompress, and render the selected GLB model | High |
| AR-04 | The technician shall tap on the video feed to send normalised screen coordinates (0–1 range) via data channel | High |
| AR-05 | The customer's device shall perform `performARHitTestWithPoint` using those coordinates and place the model at the resulting 3D world position | High |
| AR-06 | If no plane is detected within 3 seconds of placement attempt, the model shall be placed at 0.5 m in front of the camera | Medium |
| AR-07 | The technician shall control X and Y rotation via sliders; values shall be sent over the Unreliable channel as `transform` messages | High |
| AR-08 | The technician shall control scale via a slider; values sent alongside rotation transforms | High |
| AR-09 | The customer's device shall apply received transforms to the rendered model within 100 ms | High |
| AR-10 | Only one model shall be active at a time; loading a new model replaces the current one | Medium |
| AR-11 | GLB models shall be cached on device after first download | High |
| AR-12 | A loading progress bar shall be shown during model download | Medium |

### Annotations (ANN)

| ID | Requirement | Priority |
|---|---|---|
| ANN-01 | The technician shall draw circle, arrow, and freehand annotation tools on their video view | High |
| ANN-02 | Annotation screen coordinates shall be sent via the Reliable data channel as normalised point arrays | High |
| ANN-03 | The customer's device shall raycast each received annotation point to 3D world space and render annotations using ViroLine primitives | High |
| ANN-04 | The customer's device shall project rendered 3D annotation points back to 2D screen coordinates and return them via the Reliable channel | High |
| ANN-05 | The technician's view shall overlay received projected coordinates on top of the customer's video feed | High |
| ANN-06 | The technician shall clear all annotations with a single control; a `clear_annotations` message shall be sent | High |
| ANN-07 | Annotations shall persist until cleared; they shall not expire automatically during a session | Medium |

### Error Handling (ERR)

| ID | Requirement | Priority |
|---|---|---|
| ERR-01 | If `ViroARScene.isSupported()` returns false, the customer app shall fall back to video-only mode | High |
| ERR-02 | If AR tracking is lost, the app shall wait for re-detection and display a guidance message | High |
| ERR-03 | Model load failures shall retry once; if retry fails, an error with retry option is displayed | High |
| ERR-04 | Data channel disconnection shall trigger automatic reconnection with LiveKit's built-in reconnect logic | High |
| ERR-05 | Camera permission denial shall surface a guidance message directing to device settings | High |

---

## 7. Non-Functional Requirements

| Category | Requirement |
|---|---|
| Performance | Video latency < 500 ms end-to-end |
| Performance | AR model load time < 3 s for Draco-compressed files under 10 MB |
| Performance | Model transform applied on customer's device within 100 ms of technician slider move |
| Compatibility | Android 7.0+ (API 24) with ARCore support (Google Play Services for AR installed) |
| Compatibility | Technician device: any Android 7.0+, no ARCore requirement |
| Security | Google OAuth with Supabase-managed tokens |
| Security | LiveKit room tokens are short-lived and generated server-side |
| Security | All WebRTC traffic is encrypted (DTLS/SRTP by default) |
| Usability | All technician controls reachable with one thumb in portrait orientation |
| Usability | AR model visible and usable in bright outdoor light (high-contrast overlay design) |
| Usability | Error messages shall describe what went wrong and offer a recovery action |

---

## 8. Success Metrics (MVP Demo)

| Metric | Target |
|---|---|
| Session connection success rate | > 95% in demo conditions |
| Model load success rate (Draco compressed, < 10 MB) | > 98% |
| AR plane detection on a flat surface (engine bonnet, floor) | < 5 s from camera movement |
| Transform sync latency (technician slider → customer screen) | < 150 ms |
| Demo session completion without crash | 100% of planned demo runs |
| First-impression clarity (demo observer understands the value) | Subjective, but the 3D model must visibly align with the real object |

---

## 9. Demo Day Acceptance Checklist

- [ ] Customer can authenticate with Google and land on the AR call screen
- [ ] Technician can authenticate with Google and land on the Director Panel
- [ ] A session can be initiated and joined using a session code
- [ ] Technician can see the customer's live video feed
- [ ] Technician can select a model (e.g., engine) from the list
- [ ] Technician can tap on the video to place the model
- [ ] Customer sees the model appear in AR anchored to the surface
- [ ] Technician can rotate the model and customer sees it update in real time
- [ ] Technician can scale the model and customer sees it update in real time
- [ ] Technician can draw a circle annotation
- [ ] Technician can draw an arrow annotation
- [ ] Technician can draw freehand
- [ ] Customer sees annotations in 3D AR space anchored to the plane
- [ ] Technician can clear all annotations
- [ ] If AR fails on customer device, session continues as a video call
- [ ] Both users can speak and hear each other throughout

---

## 10. Risks (MVP-Scoped)

| Risk | Impact | Mitigation |
|---|---|---|
| AR plane detection fails on engine surface (dark, textureless) | High | Guide customer to scan a brighter surface first; fall back to fixed-distance placement |
| Annotation 3D→2D projection misaligns as camera moves | Medium | Acceptable for MVP; note as known limitation |
| Model download slow over mobile data | Medium | Draco compression targets < 10 MB; caching eliminates repeat downloads |
| Expo Dev Build native compilation errors | Medium | Follow ViroReact Expo starter kit exactly; test build early in Phase 0 |
| LiveKit data channel message ordering | Low | Reliable channel for critical commands; Unreliable channel accepts out-of-order transform updates |
