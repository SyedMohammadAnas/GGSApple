# AR Assist — Branching Strategy

> How we maintain **Master** (premium) and **Instant** (free) from one Android tree.

**Last updated:** 2026-07-08

---

## Product names vs flavors

| Gradle flavor | App display name | Package suffix | `IS_PREMIUM` |
|---------------|------------------|----------------|--------------|
| `master` | **Master** | (none) | `true` |
| `instant` | **Instant** | `.instant` | `false` |

Build/install:

```powershell
cd android-app
.\gradlew installMasterDebug    # com.ggsapple.remotear — "Master"
.\gradlew installInstantDebug   # com.ggsapple.remotear.instant — "Instant"
```

---

## Branches

| Branch | Product | Purpose |
|--------|---------|---------|
| `master` | Master | Primary development; full feature set |
| `free` | Instant | Optional lean branch; flavors on `master` are preferred |

---

## Gradle product flavors

```kotlin
productFlavors {
    create("master") {
        buildConfigField("boolean", "IS_PREMIUM", "true")
    }
    create("instant") {
        applicationIdSuffix = ".instant"
        buildConfigField("boolean", "IS_PREMIUM", "false")
    }
}
```

---

## Merge policy

1. **Shared fixes** (home UX, ID join, models, tutorial, safe areas) → land on `master` first.
2. **Cherry-pick or merge** `master` → `free` when using a separate git branch.
3. **Master-only features** → gate with `BuildConfig.IS_PREMIUM`; never expose in Instant UI.
4. **Backend** → single API serves both flavors.

---

## What Instant strips

- Chat, files, recording, session menu, speaker, pause controls
- Legacy 6-char join UI (Master only)

**Not stripped:** unified home, ID join/share, video tutorial, 3D library + placement, annotations, debug URL, clear cache.

---

## Release checklist

1. Tag `v1.x.x-master` on `master`
2. `installMasterDebug` → test on both phones
3. `installInstantDebug` → test on both phones
4. Update `progress.md` and `feature-matrix.md`
