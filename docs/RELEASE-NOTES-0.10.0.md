# ScreenBox 0.10.0 (12) — First Public Preview

Your screenshots, finally organized.

This is the **first public release** of ScreenBox — a local-first Android screenshot organizer. It is a **Preview**: core flows are verified on Android 15 real hardware and covered by 115 unit tests, but Android 10–14 are not yet verified on real devices (see Compatibility).

## What's New

This is the first public release. It packages the complete 0.10.0 feature set into the first build offered to the public.

## Key Features

- **Find by content** — on-device OCR reads the text inside screenshots; search by a word, date, amount, or order number that appears in one.
- **Automatic categorization** — chat, order, QR code, game, social. When unsure, screenshots are left uncategorized instead of guessing.
- **Filter by source, time, and content** — category, source app, time window, file size, and recognition state, with live facet counts. Filters can be saved and restored.
- **Preview and batch management** — grid/timeline browsing, zoomable preview, batch favorite / protect / prepare-for-delete.
- **Evidence-based cleanup** — exact duplicates, possibly-expired, near-blank, one-time codes, stale notifications, old game/social screenshots. Each suggestion carries a reason and evidence.
- **Protected deletion** — favorites and protected items are excluded at three checkpoints; deletion uses Android's system authorization flow.
- **Local-first** — no `INTERNET` permission; the OCR model is bundled in the APK.

## Privacy & Safety

- ScreenBox requests **no network permission**. It cannot upload your screenshots or the text extracted from them.
- OCR runs on-device with a model bundled in the APK. ML Kit's bundled telemetry is disabled at startup.
- No root, no account, no cloud sync.
- Deletion is never silent: it goes through `MediaStore.createDeleteRequest`, and a result the system claims but the file still present is recorded as a failure, not a false success.

## Compatibility

| Android version | Status |
|---|---|
| 15 (API 35) | ✅ Verified on real hardware (OnePlus PJX110) |
| 10–14 (API 29–34) | ⚠️ Not yet verified on real hardware — permission branches are unit-tested, but system dialog / OEM behavior is unverified |

Minimum: Android 10 (API 29). No root required.

## Installation

> **Heads up — the APK ships as 14 parts.** The maintainer's uplink drops single uploads larger than ~30 MiB, so the 105 MB APK is split into `ScreenBox-0.10.0-12.apk.part00` … `.part13`. These are plain byte-splits of the original APK; reassembled they are byte-identical (same SHA-256). Download **all 14 parts + `reassemble.sh` + `sha256sum.txt`**, put them in one folder, then:

   ```bash
   sh reassemble.sh          # cat parts -> APK, then verify SHA-256
   ```

   `reassemble.sh` prints the SHA-256 and checks it equals `5f8fb60c…ae462140`. A single-file APK asset will be attached once the upload path allows.

3. Install:

   ```bash
   adb install -r ScreenBox-0.10.0-12.apk
   ```

   Or install directly from the file on your phone. Grant photo/media authorization on first launch.

### Signing certificate

| Digest | Value |
|---|---|
| SHA-256 | `c2b69717b9e230fac3ce74b544f33a792e8a6c97da058d5345cbb53cb4c7319e` |
| SHA-1 | `6617f9d394d6133bbf9927474c5ef1f2c66cbbfc` |

## Known Issues

- **Android 10–14 not verified on real hardware.** Compatibility is documented honestly; reports are welcome via the compatibility issue template.
- **Line-level coordinate backfill is partial.** Completing the full set requires keeping the app in the foreground for a while; the task is resumable and survives process death.
- **Splash screen** does not yet show the icon on cold start (plain color transition).
- **Landscape layout** is declared (`configChanges`) but not fully walked through.

## Checksums

```
SHA-256: <filled by CI in sha256sum.txt>
```

APK size: 109,695,995 bytes (~105 MB, mostly the bundled ML Kit Chinese OCR model).

## Build & Test

- 115 unit tests, all passing (`./gradlew testDebugUnitTest`).
- Built with AGP 8.9.2, Gradle 8.11.1, Kotlin 2.1.0, JDK 17.
- Corresponding source commit: tagged `v0.10.0`.

## License

Apache License 2.0.
