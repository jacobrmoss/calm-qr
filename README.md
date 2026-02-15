<img align="left" src="scan-icon.png" width="100" height="100" alt="QR Logo">
<a href="https://buymeacoffee.com/jacobmoss" target="_blank">
  <img align="right" src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" height="41" width="174">
</a>

<br clear="all" />

# QR

**QR** is a minimal, calm barcode & QR code scanner for the **Mudita Kompakt** and similar degoogled E-ink Android devices. Decoding is handled entirely in Rust via [rxing](https://github.com/rxing-core/rxing) — no Google Play Services required.

The interface and philosophy aspires to follow that of [Mudita Mindful Design](https://mudita.com/community/blog/introducing-mudita-mindful-design/)

---

## Background
I've been living with my **Mudita Kompakt** for a little while now, and one of the pain points I keep encountering is simply needing QR codes -- both to scan and to keep for tickets, returns, etc.

I found some open source QR scanning apps (it's a fairly rudimentary thing) but they just all felt clunky on the Kompakt. I've scraped this little thing together to hopefully solve that pain point for myself and others as well.


## Screenshots

| Home | Scan Result | Saved Code | Delete Confirm |
|---|---|---|---|
| ![Home](screenshots/calm-qr-homescreen-list.png) | ![Scan Result](screenshots/calm-qr-saving-qr.png) | ![Saved Code](screenshots/calm-qr-simple-qr.png) | ![Delete Confirm](screenshots/calm-qr-delete-confirmation.png) |

| Barcode | Browser Link | Empty Home |
|---|---|---|
| ![Barcode](screenshots/calm-qr-simple-barcode.png) | ![Browser Link](screenshots/calm-qr-browser-link.png) | ![Empty Home](screenshots/calm-qr-homescreen-blank.png) |

---

## Features

- **Scan QR codes & barcodes** — powered by CameraX + Rust (rxing)
- **Focus mode** — overlay with center crop for difficult scans
- **Save & manage scans** — persisted locally with Room
- **Open links directly** — tap to launch URLs in the browser
- **E-ink optimized UI** — built with Mudita Mindful Design (MMD) components
- **Fully offline** — no network permissions, no analytics, no trackers

---

## Supported Formats

QR Code, Code 128, Code 39, Code 93, EAN-13, EAN-8, UPC-A, UPC-E, ITF, Codabar, PDF 417, Aztec, Data Matrix

---

## Tech Stack

- **Language:** Kotlin + Rust
- **UI:** Jetpack Compose with Mudita MMD
- **Barcode Engine:** [rxing](https://github.com/rxing-core/rxing) (Rust, via JNI)
- **Camera:** CameraX
- **Database:** Room
- **Min SDK:** 28 · **Target SDK:** 35

---

## Building

```bash
# Debug
./gradlew assembleDebug

# Release (requires signing config in app/local.properties)
./gradlew assembleRelease
```

Rust cross-compilation targets are required:

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi
```

---

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.

---

**Built with mindfulness for a calmer digital experience.**
