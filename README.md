# glass-ble-bridge

Bluetooth LE bridge between an iPhone and a rooted Google Glass XE (Android 4.4.4, API 19).

- `ios/`: SwiftUI app, CoreBluetooth peripheral, no third-party dependencies. Generated with XcodeGen (`ios/project.yml`).
- `glass/`: Android API 19 Java app, GATT central, dark Glass-style card UI.
- `docs/PROTOCOL.md`: GATT layout, framing, auth, messages.
- `ci/workflow-ci.yml`: GitHub Actions workflow; copy to `.github/workflows/ci.yml` (pushing workflow files needs a token with the `workflow` scope).
- `scripts/test-glass.sh`: plain-Java tests of framing, auth, scan-record parsing and backoff (needs only a JDK or JRE with jdk.compiler).

## Build

Glass: `cd glass && ANDROID_HOME=/path/to/sdk ./gradlew assembleDebug` (needs platform android-19 and build-tools 30.0.3).

iPhone: GitHub Actions (macOS runner) builds an **unsigned** IPA, artifact `GlassBridge-unsigned-ipa`. Re-sign it with AltStore/AltServer (or any sideloader) using your own Apple ID.

## Use

1. Pick a secret string. Enter it in the iPhone app (Save secret). Put it on the Glass: `adb shell "mkdir -p /sdcard/glass-bridge"` then write it to `/sdcard/glass-bridge/secret`.
2. `adb install -r glass/app/build/outputs/apk/debug/app-debug.apk` and launch GlassBridge on Glass.
3. Keep the iPhone app open; accept the Bluetooth pairing prompt on first connect.
4. URL scheme on the phone: `glassbridge://send?text=Hello&title=Note`, `glassbridge://now_playing?title=T&artist=A&state=playing`.

Status: protocol logic is unit-tested on both sides; BLE behavior on real hardware is untested.

MIT licensed.
