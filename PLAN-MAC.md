# Mac App — Implementation Plan

Menubar-only macOS app; the first app built, and the reference implementation of the protocol. Companion to [PLAN.md](PLAN.md) (architecture) and [PLAN-ANDROID.md](PLAN-ANDROID.md).

**Definition of done:** two Macs on the same Wi-Fi, one Sender one Receiver, hold-to-speak works end-to-end with ≤ 200 ms latency; with a third Mac added as a second receiver, both receivers play within 40 ms of each other. Notarized build runs on a clean machine with only the expected permission prompts (mic on sender, Local Network on both).

## Targets & tooling

- macOS 14+, Xcode 16+, Swift 5.10+, SwiftUI. Zero third-party dependencies.
- One Xcode project `apple/Intercom.xcodeproj` containing the `MacApp` target plus a local Swift package `IntercomCore` (all logic lives in the package; the app target is a thin shell — this is what the iOS app reuses later).
- Unit tests live in the package; protocol tests consume the shared vectors file `protocol/test-vectors.json` at repo root (same file the Kotlin tests use — this is how two hand-written implementations stay in lockstep).

## File map

```
apple/IntercomCore/Sources/IntercomCore/
  Protocol/
    Packet.swift            ~150  packet structs, binary encode/decode, HMAC check
    WireFormat.swift         ~40  constants: magic, version, audio format ids
  Net/
    Discovery.swift         ~120  NWBrowser (sender side) + advertise config (receiver side)
    ReceiverListener.swift  ~150  NWListener(.udp) + Bonjour advertise; demux inbound
                                  packets per peer; talk timeout (1 s silence = end)
    SenderTransport.swift   ~120  NWConnection per discovered receiver; fan-out;
                                  3× resend of TALK_START/END
    ClockSync.swift          ~80  TIME_PING/PONG; offset = midpoint of min-RTT sample
                                  out of 5 pings; refresh every 10 s during a talk
  Audio/
    CaptureEngine.swift     ~130  AVAudioEngine input tap → AVAudioConverter →
                                  24 kHz mono Int16 → 20 ms framer
    PlaybackEngine.swift    ~170  AVAudioEngine; one AVAudioPlayerNode per active
                                  talk_id (multi-sender mix for free); schedules each
                                  frame at AVAudioTime(hostTime:) = sender_ts + offset + D
  Model/
    Config.swift             ~60  role, device name, group code, sync delay, chirp —
                                  UserDefaults-backed
    AppState.swift          ~120  @Observable hub: role, discovered receivers,
                                  active talks, permission states; owns engine lifecycle
apple/MacApp/
  IntercomApp.swift          ~50  @main: MenuBarExtra (.window style) + Settings scene
  MenuContent.swift         ~120  sender popover (big button) / receiver status view
  SettingsView.swift        ~150  role, name, group code, delay, chirp, login item,
                                  live receiver list
  HoldToSpeakButton.swift    ~80  press/release semantics (below)
  Info.plist, Intercom.entitlements
```

≈ 1,500 lines total. If a file wants to grow past ~2× its estimate, the design is drifting — stop and simplify.

## Key implementation decisions

**Menubar shell.** `MenuBarExtra` with `.menuBarExtraStyle(.window)` — a `.menu` style can't host a press-and-hold control. `LSUIElement = YES` (no Dock icon). Icon reflects state via SF Symbols: idle / N receivers online / sending / receiving (receiver role).

**Hold to speak.** A `.menu`-style button can't do it; inside the window-style popover use `DragGesture(minimumDistance: 0)` — `onChanged` first fire = button-down (start talk), `onEnded` = release (stop). Also install an `NSEvent` local monitor while the popover is open so holding the spacebar talks. Guard rails: max talk length 60 s (auto-stop), and a talk that gets no button-up (popover dismissed mid-talk) stops immediately via `onDisappear`.

**Audio capture.** `inputNode.installTap` in the node's native format; `AVAudioConverter` to 24 kHz mono Int16; accumulate into exact 480-sample (20 ms) frames. Start the engine on button-down, stop on release — mic indicator lights only while actually talking.

**Playback scheduling (the sync feature).** Per frame: target host time = `machTime(senderTimestamp + clockOffset + D)` with D = 150 ms default. Schedule the buffer on that talk's `AVAudioPlayerNode` with `play(at:)` semantics; frames whose slot has passed are dropped, not queued (late audio must blip, never drift). Player node runs at 24 kHz mono Float32; the mixer handles the hardware rate. Node is detached 2 s after talk end. If clock-sync pings all fail (lost/blocked), degrade to play-on-arrival + D buffering — unsynced but working.

**Clocks.** All timestamps are µs from `mach_absolute_time()` (converted via timebase). No shared epoch needed: offsets are estimated per receiver-sender pair at each talk start, so clock bases are irrelevant — only short-term stability matters.

**Networking.** Pure Network.framework. Receiver: `NWListener` over UDP with `NWListener.Service(name: deviceName, type: "_intercom._udp", txtRecord:)` — advertise and listen are one object; inbound packets arrive as per-peer `NWConnection`s. Sender: `NWBrowser` for the service type, one UDP `NWConnection` per receiver, browse runs whenever the app is running. Role switch = tear down and rebuild the whole stack (no dual-role state machine).

**Debug dual-role flag.** A hidden toggle (`-dualRole YES` launch arg) runs listener and sender in one process so the whole path — discovery through synced playback — can be exercised on a single Mac during development. Worth its ~10 lines many times over.

**App Nap.** Take `ProcessInfo.beginActivity(.latencyCritical)` for the duration of each active talk (both roles); release after. Sockets wake the process regardless, so no permanent assertion needed.

**Sleep warning.** Receiver role shows a settings-pane warning if the Mac is configured to sleep (checked via `pmset -g` / IOPMCopyActivePMPreferences): "This Mac can't play intercom while asleep."

**Permissions & signing.**
- Info.plist: `NSMicrophoneUsageDescription`, `NSLocalNetworkUsageDescription`, `NSBonjourServices = ["_intercom._udp"]`, `LSUIElement`.
- Entitlements (sandboxed): `network.client`, `network.server`, `device.audio-input`.
- Mic permission requested on first button-down, not at launch; Local Network prompt (macOS 15+) triggers on first advertise/browse — setup flow explains it beforehand.
- Launch at login via `SMAppService.mainApp` toggle in settings.
- Distribution: Developer ID signing + `notarytool` notarization; zip or dmg. App Store deferred (nothing blocks it later).

## Build order

Each step ends with something runnable/verifiable — no step depends on a later one.

1. **Scaffold.** Project + package + menubar shell with static icon, Settings scene, Config/AppState. *Check: app lives in menubar, settings persist across relaunch.*
2. **Protocol.** `Packet` encode/decode + HMAC + `protocol/test-vectors.json` authored alongside. *Check: package tests green; vectors file committed for Android to consume.*
3. **Discovery.** Receiver advertises; sender browses; settings shows live receiver list. *Check: `dns-sd -B _intercom._udp` sees the service; two Macs see each other; Local Network prompt appears exactly once.*
4. **Audio engines in isolation.** Capture→framer feeding a loopback into Playback on one machine (dual-role flag), play-on-arrival, no sync yet. *Check: hold-to-speak on a single Mac produces audible, glitch-free self-loopback.*
5. **End-to-end transport.** Wire SenderTransport + ReceiverListener; two Macs talking, play-on-arrival. *Check: cross-room speech, latency felt < ½ s, talk teardown clean on Wi-Fi drop (yank one Mac's Wi-Fi mid-talk).*
6. **Clock sync + scheduled playback.** TIME_PING + `play(at:)` scheduling + late-frame drop. *Check with 2 receivers: record both with a phone, align waveforms in Audacity, skew ≤ 40 ms.*
7. **Productize.** Chirp, icon states, mic-permission flow, sleep warning, login item, 60 s talk cap, notarized build on a clean account. *Check: definition-of-done checklist top of this doc.*

Step 2's vectors file is the hand-off point — Android work (PLAN-ANDROID.md) can start the moment it's committed, in parallel with steps 3–7.
