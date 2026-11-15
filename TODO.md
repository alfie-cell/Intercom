# Build TODOs

## Mac app (PLAN-MAC.md build order)

- [x] 0. Environment check (Xcode 26.2, Developer ID cert present, XcodeGen installed)
- [x] 1. Scaffold: repo layout, PROTOCOL.md, IntercomCore package skeleton
- [x] 2. Protocol: Packet codec + HMAC + unit tests + `protocol/test-vectors.json` generated
- [x] 3. Net: Discovery (browse), ReceiverListener (advertise + demux + ping burst), SenderTransport, ClockSync (+ tests)
- [x] 4. Audio: CaptureEngine (mic → 24 kHz frames), PlaybackEngine (ring-buffer source nodes, synced playout, chirp)
- [x] 5. Model: Config + AppState wiring (incl. `dualRole` debug flag, 60 s talk cap, App Nap assertion)
- [x] 6. MacApp shell: MenuBarExtra, hold-to-speak (mouse + spacebar), Settings, Info.plist, entitlements
- [x] 7. Xcode project (XcodeGen) + build — BUILD SUCCEEDED, sandbox/mic/network entitlements verified on the signed app
- [x] 8. `swift test` green (12 tests: packet codec, HMAC, clock sync, vectors)
- [x] 9. Launched: menubar app running, `dns-sd -B _intercom._udp` sees the receiver advertisement

### Remaining (needs hands/second device)

- [ ] Single-Mac loopback audio test: `defaults write ~/Library/Containers/dev.intercom.Intercom/Data/Library/Preferences/dev.intercom.Intercom dualRole -bool YES`, relaunch, switch role to Sender, hold-to-speak → should hear yourself ~250 ms delayed (grant mic + Local Network prompts)
- [ ] Two-Mac end-to-end test + Wi-Fi-drop teardown check (PLAN-MAC.md step 5 checks)
- [ ] Two-receiver skew measurement ≤ 40 ms (record both, align waveforms)
- [ ] Release build signed with Developer ID + notarization (pass `DEVELOPMENT_TEAM=<your team ID>` to xcodebuild; `xcrun notarytool` needs your App Store Connect credentials/keychain profile)

## Android app (PLAN-ANDROID.md build order)

- [x] 0. Environment: JDK 21 (Android Studio JBR), SDK 36 + build-tools 36.1, emulators API 33 / 36.1, Gradle 9.9.1; AGP 9.5.1, Kotlin 2.4.21, Compose BOM 2026.09.00
- [x] 0b. Research: Wi-Fi low-latency lock is screen-on/foreground only (HIGH_PERF maps to it on 14+); Android 17 local-network permission only applies at targetSdk 37
- [x] 1. Scaffold: Gradle project, manifest, Application, Config, theme, icons
- [x] 2. Protocol: Packet + GroupAuth + ClockSync + JVM tests — byte-exact against `protocol/test-vectors.json`
- [x] 3. Audio DSP: 2:1 half-band decimator (>50 dB alias rejection) + talk timeline ring, unit-tested
- [x] 4. Net: ReceiverSocket, SenderTransport, NSD Advertiser/Browser
- [x] 5. Audio I/O: Capture, Playback (AudioTrack per talk, timestamp-aligned synced playout, per-device adaptive delay, chirp, audio focus)
- [x] 6. Receiver service: `specialUse` FGS, locks, per-talk wake lock, notification + Mute/Turn off, BootReceiver, Wi-Fi change re-advertise
- [x] 7. Sender controller: discovery + transport + capture, manual receiver addresses, 60 s cap, stop on background
- [x] 8. UI: Sender (hold to speak), Receiver status, Setup/Settings with reliability checklist
- [x] 9. Build green: 15/15 unit tests, debug + release (R8, 1.3 MB), zero compiler warnings
- [x] 10. Emulator: setup → receiver FGS running (type specialUse), NSD advertising, notification actions
- [x] 11. Emulator end-to-end: Mac harness → emulator plays 1.87–1.98 s of 2 s, screen on and in forced Doze; wrong group code rejected; emulator sender → Mac 149/149 frames; survives app update and real reboot
- [x] 12. Docs: README, PLAN.md / PLAN-ANDROID.md corrections + "As built", PROTOCOL.md adaptive-delay note

### Fixed along the way (Mac side, found while building Android)

- [x] Mac playback fallback clock offset had the wrong sign — two-Mac talks could lock to a garbage offset and play silence (single-Mac loopback hid it)
- [x] Long multi-byte device names could be cut mid-character, making receivers drop the talk (both platforms now cut on a character boundary)

### Real-device results (Galaxy Tab S10 Lite, Android 16, home Wi-Fi)

- [x] Mac Bonjour discovers the tablet; Mac → tablet talks play over the real LAN (clock-sync RTT 8–17 ms)
- [x] 48 kHz output reaches the FastMixer path; default D raised to 250 ms; tablet holds the shared D across idle-gap talks
- [x] Installed on Redmi Note 10 Pro (Android 13 / MIUI 14) — launches cleanly; still on an older build (reinstall when reconnected)

- [x] "Tablet drops offline": with the screen off the tablet sends **no mDNS replies** (none 16:35→17:05 despite our multicast lock) yet still receives unicast talks fine (verified while Dozing). Fix: senders remember resolved receivers for 7 days and keep sending; shown as "standby" (Android `KnownReceivers.kt`, Mac `KnownReceivers.swift`)
- [x] Android intercom volume: per-device level applied for each message, user's media volume restored after (verified: normal, muted, and user-changed-mid-message cases)
- [ ] Update the Redmi (the sender that needs the standby fix) when it's reconnected

### Remaining (needs real devices)

- [ ] Real phone as receiver: overnight screen-off soak on battery (`adb logcat -s IntercomRx IntercomPlay`), Pixel + one Samsung with/without the OEM step
- [ ] Real phone ↔ Mac both directions on the same Wi-Fi (Bonjour discovery across platforms; the emulator's NAT can't test this)
- [ ] Two receivers side by side: record, measure skew ≤ 40 ms
- [ ] Mac parity: Mac receivers don't yet compensate for output-device latency (Bluetooth speakers play late there, but they do play)
- [ ] Play Store: upload key, `specialUse` declaration, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` justification; later targetSdk 37 + `ACCESS_LOCAL_NETWORK`
