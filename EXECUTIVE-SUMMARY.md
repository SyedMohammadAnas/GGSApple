# GGSApple — Executive Summary

**Remote AR Assist** · Last updated: August 7, 2026

---

## Overview

GGSApple is a **remote augmented-reality assistance platform**. A **customer** on a phone uses a native AR app to share their live camera view. A **remote expert** joins from a web browser, sees that feed, and draws annotations that appear anchored in the customer's AR space — as if the expert were looking over their shoulder.

The product is built for field support, tutorials, and guided repair scenarios where seeing what the customer sees matters more than a plain video call.

---

## Product model

| Role | Client | Responsibility |
|------|--------|----------------|
| **Customer** | Native mobile app (iOS and Android) | Runs AR, streams POV video, receives annotations and 3D models |
| **Expert** | Web app (Assist AR) | Joins by customer ID, views video, annotates, places 3D assets |
| **Admin** | Same web app | Manages expert/admin roles |

**Core rule:** The AR session owns the camera. Realtime video transport reads frames from AR — it does not open a competing camera on the shared-AR path.

**Session flow:**

1. Customer signs in and sees their public ID on the home screen.
2. Expert opens the web app, signs in with Google, enters the customer ID, and connects.
3. Customer sees an incoming-session prompt and enters the call.
4. Customer streams a POV composite; expert draws annotations (pointer, arrow, circle, freehand) and can place 3D models.
5. Either side can end the call; both clients return to idle.

Offline **video tutorials** (record AR on-device without a live session) are supported on the customer app.

---

## Architecture

```
┌─────────────────────────┐         ┌─────────────────────────┐
│  Customer (iOS/Android) │         │  Expert (Web browser)   │
│  ARKit / ARCore           │         │  Assist AR (Next.js)    │
│  POV video → LiveKit      │◄───────►│  Views video + draws    │
└───────────┬───────────────┘         └───────────┬─────────────┘
            │                                     │
            └──── LiveKit data: annotations ──────┘
                  (draw, place, 3D models, end)

Control plane     Next.js on Vercel — session APIs, auth, tokens
                  https://ggsexpert.vercel.app

Media plane       Self-hosted LiveKit (WebRTC)
                  Secure WebSocket signaling + realtime AV

Data              Supabase — authentication, profiles, roles, sessions

Assets            3D model catalog (USDZ for iOS AR, served from backend)
```

| Plane | Technology | Purpose |
|-------|------------|---------|
| Customer apps | Swift/SwiftUI + ARKit (iOS), Kotlin/Compose + ARCore (Android) | AR, UI, offline mode |
| Expert web | Next.js 15, React 19, LiveKit client | Expert UI, session management |
| Realtime AV | LiveKit | Video, audio, annotation data channel |
| Auth & DB | Supabase | Google OAuth, user profiles, session records |
| Legacy API | Node/Express (TypeScript) | Model asset hosting; session APIs migrating to Vercel |

---

## Repository structure

Monorepo at `SyedMohammadAnas/GGSApple`:

| Directory | Description |
|-----------|-------------|
| `ios-app/` | **Active development** — customer-only Instant app (SwiftUI, ARKit, LiveKit) |
| `expert-web/` | Assist AR expert web app (also published as standalone repo `GGSApple-expert-web`) |
| `android-app/` | Android Instant customer app — proven reference implementation |
| `backend/` | Express API + static 3D model assets |
| `docs/` | Product specs, native rework plans, architecture notes |
| `docker-compose.yml` | LiveKit (+ optional API) for self-hosted media stack |

**Expert web** deploys to Vercel on push to `main` → production at **https://ggsexpert.vercel.app**.

---

## Technology stack

| Layer | Choices |
|-------|---------|
| iOS | Swift, SwiftUI, ARKit, LiveKit Swift SDK, Supabase Swift, Google Sign-In |
| Android | Kotlin, Jetpack Compose, ARCore shared-camera pipeline, LiveKit |
| Expert web | Next.js 15, React 19, Tailwind CSS, LiveKit client, Supabase SSR |
| Backend | Express, TypeScript, LiveKit server SDK |
| Infrastructure | Vercel (web + session API), self-hosted LiveKit, Supabase cloud |

---

## Current status

### Shipped

| Capability | Platforms |
|------------|-----------|
| Google authentication + user profiles | iOS, Android, Web |
| Customer home UI with public ID sharing | iOS, Android |
| Expert web (Assist AR) on Vercel | Web |
| Live session join (expert by customer ID) | iOS ↔ Web, Android ↔ Web |
| Realtime POV video over LiveKit | iOS, Android |
| Annotation sync (pointer, arrow, circle, freehand) | iOS ↔ Web |
| Bilateral end-call | iOS ↔ Web |
| Offline AR Assist + world-anchored annotations | iOS |
| 3D model catalog + placement (USDZ) | iOS ↔ Web |
| Liquid Glass call chrome | iOS |

### In progress / next

| Item | Notes |
|------|-------|
| ARKit → LiveKit frame pipeline hardening | Replace interim camera bridge with native AR frame feed |
| Audio and call-control polish | Mic, speaker, stability under load |
| Android alignment | Remove legacy native expert UI; match iOS customer-only model |
| Session lifecycle | 5-minute idle auto-end, rejoin rules |
| Assets UI | Wire model picker fully to catalog API on customer app |
| Future | Chat, file sharing, session recording on web |

---

## 3D models in AR

- iOS RealityKit requires **USDZ** format (GLB is not supported for in-scene AR placement).
- Catalog includes sample assets (e.g. rubber duck, car engine, tyre, spark plug).
- Both expert (web) and customer (app) can request placement; transforms sync over LiveKit.
- Model metadata is bundled with the expert web app; binary files are served from the backend asset store.

---

## Key design decisions

1. **Web-only expert** — Experts never need a native app. Reduces maintenance and enables desktop workflows.
2. **Customer-only native apps** — One app per platform focused on AR and field use.
3. **LiveKit for annotations** — Drawing commands travel on a dedicated data topic alongside video, keeping latency low without a separate sync layer (for now).
4. **Vercel for control plane** — Session creation, tokens, and expert UI deploy globally without managing app servers.
5. **Self-hosted LiveKit for media** — WebRTC media stays on infrastructure we control; signaling uses secure WebSockets.

---

## Demo / production endpoints

| Service | URL |
|---------|-----|
| Expert web | https://ggsexpert.vercel.app |
| Supabase (auth/data) | https://suuellchcoegerddqyjb.supabase.co |

Customer apps ship with the Vercel API URL as default. LiveKit URL is configured for the deployed media server.

---

## Documentation in this repo

| Path | Contents |
|------|----------|
| `docs/NativeRework/` | Technical requirements, app flows, feature matrix |
| `docs/native-shared-camera-architecture.md` | Android shared-camera design (ARCore + LiveKit) |
| `docs/remote-ar-implementation-plan.md` | Implementation planning |

---

## Summary

GGSApple delivers **live remote AR assistance**: a customer streams their real-world view from a phone while a remote expert guides them through a browser with drawings and 3D overlays. Android and the expert web are production-ready; **iOS is the active build target**, with core flows working end-to-end and hardening ongoing on video pipeline, audio, and session lifecycle.

For technical deep-dives, start with `docs/NativeRework/app-flow-v3.md` and the module layout under `ios-app/GGSApple/` and `expert-web/src/`.
