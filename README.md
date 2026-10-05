# Proview Camera

A point-and-shoot Android camera built on computational photography. Every shot is a multi-frame
burst merged on the phone, with natural Hasselblad-style colour. The goal is image quality close
to a flagship Vivo X200, on as many Android phones as possible.

**Status:** milestone M0. Project skeleton, a device-check screen and the first pipeline stage (tile alignment) with tests.

## Get the app
**[Download the latest build (APK)](https://github.com/shibam-apple/Proview-camera.-/releases/latest/download/proview-debug.apk)**.
No GitHub sign-in is needed. Every push to `main` publishes a new build to
[Releases](https://github.com/shibam-apple/Proview-camera.-/releases).

## Build it yourself
```sh
./gradlew :app:assembleDebug          # needs the Android SDK
./gradlew :pipeline-ref:test          # pure Kotlin, no Android SDK needed
```

## Layout
- `app/`: Compose UI
- `camera/`: Camera2 device probing and tiers (capture comes in M1)
- `pipeline-ref/`: pure-Kotlin reference image pipeline (alignment so far)
- `docs/`: specs, design source files, font licences

## Docs

- [Product & engineering spec](docs/SPEC.md): pipeline, colour science, speed budgets, testing, milestones
- [UI spec](docs/UI_SPEC.md): exact layout, tokens, gestures and motion from the design
- [Design files](docs/design/): source of truth for the UI (Camera, Library, Photo detail)
