# GameScreenTranslator 2.0

Android screen OCR and Gemini game localization. Screen capture requires explicit MediaProjection consent; recognized text is sent to the configured Gemini model. The API key is masked in settings and excluded from app backup.

## Controls
- Start translation after granting overlay and screen capture permissions. Tap the search icon to translate manually; hold to change writing style.
- Play toggles automatic scanning; crop cycles full screen, bottom third and top third. Drag the overlay away from the dialogue area.
- History shows up to four recent translations; eye hides/shows the subtitle; close stops capture.
- Settings control subtitle font size and translucent background opacity. OCR currently uses Latin script only.
- Auto scan target 250 ms; OCR/API latency varies. Identical OCR text is skipped and periodic scans guard against missed pixel changes. No performance benchmark has yet been run.

## CI and OTA
On PRs and pushes to master, Actions builds a debug APK and runs unit tests. For a new version on master it publishes a signed release APK, only after CI passes. Increment both versionName (numeric x.y.z) and versionCode in app/build.gradle.kts for each release. Existing tags are not overwritten.

Configure repository Actions secrets with a **stable private signing key**:
- OTA_KEYSTORE_B64: base64 encoded JKS or PKCS12 keystore
- OTA_STORE_PASSWORD
- OTA_KEY_ALIAS
- OTA_KEY_PASSWORD

The prior V1.0 release used a debug APK. If its original signing key is unavailable, in-place OTA from that APK is impossible: export/record settings as needed, uninstall, and install the first consistently signed APK manually. Subsequent releases must use the same key. Never commit keystores or passwords.

The app checks the latest GitHub release at startup and on demand, compares numeric versions, then verifies package name, version code and signing certificate before launching Android's installer. The user must confirm installation.

## Verification checklist
- CI: unit tests and assembleDebug.
- Real Android device: grant/revoke permissions, manual and automatic OCR, switch styles, crop, overlay drag, history, opacity, rotate and resume (rotation still needs device validation).
- OTA: install two successive APKs signed by the same key; verify update retains settings, rejects older and differently signed APKs.
- Benchmark OCR, API and end-to-end P50/P95 on representative games before claiming latency gains.
