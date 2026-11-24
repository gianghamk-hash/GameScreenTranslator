# GameScreenTranslator 3.0

Android screen OCR and Gemini game localization. Screen capture requires explicit MediaProjection consent; recognized text is sent to the configured Gemini model. The API key is masked in settings and excluded from app backup.

## Controls
- Start translation after granting overlay and screen capture permissions. Tap the search icon to translate manually; hold to change writing style.
- Play toggles automatic scanning; crop cycles full screen, bottom third and top third. Drag the overlay away from the dialogue area.
- History shows up to four recent translations; eye hides/shows the subtitle; close stops capture.
- Settings control subtitle font size and translucent background opacity. OCR currently uses Latin script only.
- Adaptive auto scan target 180–700 ms; OCR/API latency varies. Identical OCR text is skipped and periodic scans guard against missed pixel changes. No performance benchmark has yet been run.

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

## V3.0
- Smart dialogue: automatic full-screen mode ranks OCR blocks by position, width and sentence features, excludes common HUD labels. Manual/full screen and explicit crop preserve regular OCR.
- Long-term context: per user-selected game and style, stores up to 100 completed dialogue pairs, retrieves six recent plus four relevant pairs. Clear via main screen. Stop service before changing profile.
- Adaptive writing: Gemini prompt accounts for genre and current situation; not a separate classifier.
- Typing detection: requires two matching OCR observations and 280 ms stability; manual scan bypasses debounce.
- SSE streams partial Gemini output; only completed results are remembered. Latest stable request supersedes older stream and cancels the HTTP call. UI rendering guarded by generation.
- Hybrid opt-in: ML Kit English-to-Vietnamese model downloads over Wi-Fi. Short whitelisted menu labels use local translation if ready; cloud failures can use local translation marked [Offline]. No multilingual OCR in this release. API key required to start.
- Persistent exact cache: up to 400 entries per game/style, dialogue cache includes recent context and model. Stored privately; clear all via settings.
- Adaptive polling: 180/350/700 ms based on changes; periodic OCR recovery and automatic request backoff. Actual latency depends on device/network.
- Custom translation zone: long press crop icon, drag rectangular area; tap to cancel. Saved normalized by game and orientation; short press cycles presets. Restart screen capture after rotation; full-device capture recommended for accurate coordinates.
- Keep existing signing secrets and workflow; version 3.0.0 / code 30000. Test CI plus real-device capture, typing, cancellation, offline download/fallback, crop geometry, app restart, OTA installation. No latency claims until measured.

## V3.0.1 startup compatibility hotfix
V1.0 shipped a debug APK, whereas V2/V3 ship optimized release APKs. Disable release optimization to isolate a reported launch crash while preserving V3 features, application ID and existing signing/OTA workflow. This is a diagnostic compatibility fix, not a confirmed root cause without device logs. CI compiles debug and release variants, runs the startup instrumentation test on an Android 15 emulator, and launches the signed release APK on an emulator before publication. On a device with USB debugging enabled, capture the crash with `adb logcat -b crash -d` after reproducing it. Test the signed APK on the affected phone before treating the issue as resolved.
