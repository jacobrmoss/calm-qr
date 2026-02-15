# QR

A minimal, calm QR/barcode scanner for the **Mudita Kompakt** (and similar degoogled Android devices). Decoding is handled entirely in Rust via [rxing](https://github.com/rxing-core/rxing), called from Kotlin/Compose over JNI — no Google Play Services required.

## Features

- **Camera-based QR & barcode scanning** — powered by CameraX + Rust (`rxing`)
- **Scan history** — scans are persisted locally with Room
- **E-ink optimised UI** — built with Mudita's Mindful Design (MMD) Compose components
- **Fully offline** — no network permissions, no analytics, no Google dependencies

## Architecture

```
app/          Kotlin/Compose Android application
  ui/           Screens & components (Jetpack Compose)
  data/         Room database (SavedCode)
  navigation/   Compose Navigation
  rust/         JNI bridge to native library
rust/         Rust cdylib (rxing barcode decoding & QR generation)
docs/         Project documentation & assets
```

## Prerequisites

| Tool | Version |
|---|---|
| Android SDK | API 35 (compile) / 28+ (min) |
| Android NDK | 29.0.14206865 |
| JDK | 17 |
| Rust | stable (see `rust/rust-toolchain.toml`) |
| cargo-ndk | latest (`cargo install cargo-ndk`) |

Rust targets must be installed:

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi
```

## Building

### Debug

```bash
./gradlew assembleDebug
```

### Release

1. Create a keystore (or use an existing one).
2. Add signing info to `app/local.properties` (git-ignored):

```properties
RELEASE_STORE_FILE=/path/to/release.jks
RELEASE_STORE_PASSWORD=yourpassword
RELEASE_KEY_ALIAS=youralias
RELEASE_KEY_PASSWORD=yourpassword
```

3. Build:

```bash
./gradlew assembleRelease
```

The signed APK is written to `app/build/outputs/apk/release/calm-qr-v{version}.apk`.

### Versioning

Version info lives in `version.properties` at the project root:

```properties
VERSION_MAJOR=1
VERSION_MINOR=0
VERSION_PATCH=0
VERSION_CODE=1
```

Bump the version before building a new release:

```bash
./gradlew bumpPatch          # 1.0.0 → 1.0.1
./gradlew bumpMinor          # 1.0.x → 1.1.0
./gradlew bumpMajor          # 1.x.x → 2.0.0
```

Then build as usual — the APK filename updates automatically.

## Installing on Mudita Kompakt

```bash
adb install -r app/build/outputs/apk/release/calm-qr-v1.0.0.apk
```

## License

All rights reserved © Caravan Fire Music.
