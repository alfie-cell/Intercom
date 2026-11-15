# Intercom

Push-to-talk intercom for your own devices on your own Wi-Fi. No cloud, no accounts — audio goes device-to-device over the LAN and plays on every receiver simultaneously.

- [PLAN.md](PLAN.md) — architecture and platform reality check
- [PLAN-MAC.md](PLAN-MAC.md) / [PLAN-ANDROID.md](PLAN-ANDROID.md) — per-app implementation plans
- [PROTOCOL.md](PROTOCOL.md) — wire protocol (normative), with conformance vectors in [protocol/test-vectors.json](protocol/test-vectors.json)
- [TODO.md](TODO.md) — build progress

## Mac app

macOS 14+, menubar-only. All logic lives in the `IntercomCore` Swift package (reused by the future iOS app); the app target is a thin SwiftUI shell.

```sh
# Regenerate the Xcode project after changing project.yml (requires: brew install xcodegen)
cd apple && xcodegen generate

# Build + run
xcodebuild -project Intercom.xcodeproj -scheme Intercom -configuration Debug -derivedDataPath build build
open build/Build/Products/Debug/Intercom.app

# Core tests (protocol codec, HMAC, clock sync, conformance vectors)
cd IntercomCore && swift test

# Regenerate protocol/test-vectors.json after a deliberate protocol change
INTERCOM_WRITE_VECTORS=1 swift test
```

Setup lives in the menubar popover → Settings: pick Sender or Receiver, set a device name and a group code (same code on all devices).

**Single-Mac loopback test** (runs both roles in one process):

```sh
defaults write ~/Library/Containers/dev.intercom.Intercom/Data/Library/Preferences/dev.intercom.Intercom dualRole -bool YES
```

Relaunch, set role to Sender, hold the button and speak — you should hear yourself ~250 ms later. Grant the microphone and Local Network prompts on first use. Turn off with `defaults delete ~/Library/Containers/dev.intercom.Intercom/Data/Library/Preferences/dev.intercom.Intercom dualRole`.

## Android app

Android 8+ (minSdk 26, targetSdk 36), Kotlin + Jetpack Compose, no networking/audio libraries. Receivers run as a foreground service that survives reboots; senders are a single hold-to-speak screen. See [PLAN-ANDROID.md](PLAN-ANDROID.md) "As built" for the design decisions.

```sh
cd android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew testDebugUnitTest   # protocol (incl. shared vectors), clock sync, decimator, timeline
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # R8-minified, debug-signed for sideloading
adb install -r app/build/outputs/apk/debug/app-debug.apk

adb logcat -s IntercomRx IntercomPlay IntercomTx IntercomNsd   # per-talk diagnostics
```

First launch opens setup: pick Sender or Receiver, name, group code (same on every device), then work through the reliability checklist (notification, unrestricted battery, and on Samsung/Xiaomi/etc. the dontkillmyapp guide).

## Testing without a second device

[tools/fake_sender.py](tools/fake_sender.py) speaks the protocol from the Mac (standard library only):

```sh
# Mac → Android emulator receiver (emulator NAT needs a port redirect)
adb emu redir add udp:47474:47474
tools/fake_sender.py send --host 127.0.0.1 --port 47474 --group <code>

# Android emulator sender → Mac: add manual receiver 10.0.2.2:47475 in the app's Settings, then
tools/fake_sender.py listen --port 47475 --group <code>

# Also works against the Mac app's receiver (find its port with: dns-sd -L "<name>" _intercom._udp)
```

## Third-party assets

- App icons on both platforms (Android launcher and notification, Mac menubar): Material Symbols "cell_tower" by Google, [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) ([source](https://github.com/google/material-design-icons)).
