# Intercom — Build Plan

A push-to-talk intercom for your own devices on your own network. Install on every device, set each one up as a **Sender** or a **Receiver**. Hold the button on a sender, talk, and every receiver on the LAN plays it — at the same time, live, like a wall intercom.

> Per-app implementation plans: **[PLAN-MAC.md](PLAN-MAC.md)** · **[PLAN-ANDROID.md](PLAN-ANDROID.md)**

Design principles, in priority order:

1. **LAN-first.** Audio never leaves the local network. No cloud, no accounts, no server to run.
2. **Native everywhere.** SwiftUI on Apple, Kotlin/Compose on Android. System frameworks only — no third-party audio or networking libraries.
3. **Minimal code, full function.** Simplicity is achieved by cutting scope (no codec in v1, no encryption in v1, no mesh relay), never by half-implementing a feature that ships.

---

## 1. Platform reality check (read this first)

You asked specifically about mobile backgrounding/battery limits. Here is the honest picture; it shapes the whole plan.

### Android receivers: possible, with a known recipe

A receiver must hold a socket open and play audio at any moment, including screen-off. Android allows this, but only via:

- A **persistent foreground service** (`mediaPlayback` type, permanent notification). Plain background services are killed since Android 8.
- A **per-talk partial wake lock** + **Wi-Fi locks**. *Correction found during the build:* `WIFI_MODE_FULL_LOW_LATENCY` only works with the screen on and app in foreground, and on Android 14+ `FULL_HIGH_PERF` is silently mapped to it — so no app can keep Wi-Fi out of power-save with the screen off on 14+. Effect: the first packets of a talk can arrive up to a beacon interval (~100–300 ms) late. The receiver measures arrival lag and absorbs it (see PLAN-ANDROID.md "As built"); emulator tests in forced Doze played normally.
- A **multicast lock** (`WifiManager.MulticastLock`) so mDNS discovery packets reach us.
- A requested **battery-optimization exemption** (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). Legitimate under Play policy since background reception *is* the core feature.
- **OEM caveat:** Samsung/Xiaomi/Huawei/OnePlus task killers can still murder the service regardless. Setup screen should link the per-OEM instructions at dontkillmyapp.com. This is an ecosystem problem; every intercom/baby-monitor app has it.

**Battery cost:** holding low-latency Wi-Fi all day is real but modest (single-digit %/day on most hardware). The receiver role is ideal for a plugged-in device — an old phone on a wall charger is effectively a dedicated intercom panel. The setup screen should say so, and show a "plug me in" hint when a receiver is on battery.

### iOS receivers: genuinely limited — plan around it

iOS has **no persistent foreground service**. A suspended app cannot keep a LAN socket listening, and every wake-from-background mechanism fails our constraints:

- **Local Push Connectivity (`NEAppPushProvider`)** is Apple's purpose-built API for exactly this (LAN voice on networks without internet). But it requires a special entitlement granted by Apple, aimed at enterprise/managed-network deployments, and the request process is slow and uncertain. Not viable for v1; worth applying for in parallel.
- **Push To Talk framework (iOS 16+)** gives the system PTT UI and background audio — but incoming talk is delivered via **APNs**, i.e. over the internet through a server you run. Contradicts LAN-first.
- **Background audio mode** only keeps an app alive while it is *actively playing*; playing silence to stay resident is an App Store rejection.

**Consequence:** the iOS app ships **sender-first** (sending is foreground by nature — you're holding the button). An iOS *receiver* mode works only while the app is foreground with the screen on — which is still useful as a deliberate "dock mode": iPad on a stand in the kitchen, Guided Access optional, screen dimmed by the app. We ship that honestly labelled, and never pretend a pocketed iPhone will play intercom audio.

### macOS: no real limits

Menubar apps can listen and play indefinitely. Two footnotes: a **sleeping** Mac won't play (setup screen should mention "Prevent sleep when display is off" / `caffeinate` guidance for dedicated receivers), and we take an `NSProcessInfo` activity assertion while receiving to avoid App Nap throttling. macOS 15+ shows the Local Network privacy prompt, same as iOS.

---

## 2. Architecture

### Roles and topology

- Every install is configured in setup as **Sender** or **Receiver**. Exclusive: senders never play incoming talk (per spec), receivers never capture.
- **Pure peer-to-peer.** No coordinator. Receivers advertise themselves; senders discover receivers and fan audio out to each directly.
- A **group code** (short passphrase, same on all devices, set during setup) partitions households/offices sharing a LAN and keeps strangers' senders out.

### Discovery — mDNS/Bonjour

Receivers advertise `_intercom._udp.local.` with TXT records: `name` (user-chosen device name), `ver` (protocol version), `grp` (salted hash of group code), `port`.

- Apple: `NWListener` with Bonjour service (advertise) / `NWBrowser` (browse). Both built into Network.framework; zero extra code for the actual mDNS.
- Android: `NsdManager` (advertise on receivers, discover if a sender). Hold the multicast lock while resolving.
- Senders keep a browser running while the app is open and maintain a live receiver list (also shown in setup — "3 receivers online" is the main health indicator).

### Transport — unicast UDP fan-out

One UDP socket per app; all control and audio messages on it.

**Why not multicast for the audio** (the "obvious" intercom transport): Wi-Fi APs transmit multicast at low legacy rates with no link-layer retries, mesh systems and many routers mangle or drop it, and phones in power-save miss it entirely. mDNS gets away with it for tiny occasional packets; continuous audio does not. Unicast to each receiver costs the sender `N ×` bandwidth, which at our bitrate is trivial for any realistic N (see §3), and is reliable on every network that allows peer-to-peer traffic at all.

**Why UDP, not TCP/WebSocket:** head-of-line blocking turns one lost packet into a growing delay; for live voice we'd rather drop 20 ms than lag 500 ms. A lost UDP frame = one inaudible blip.

### Protocol (v1 — keep it this small)

All packets: 12-byte header `magic (2) | ver (1) | type (1) | talk_id (4) | seq (4)`, then type-specific payload. Little-endian. One Swift file, one Kotlin file, each ~150 lines.

| Type | Direction | Payload |
|---|---|---|
| `TALK_START` | sender → each receiver | sender name, audio format id, group-code HMAC, sender timestamp (µs) |
| `AUDIO` | sender → each receiver | sender timestamp (µs) + PCM frame (20 ms) |
| `TALK_END` | sender → each receiver | last seq |
| `TIME_PING` / `TIME_PONG` | receiver ↔ sender | timestamps for clock-offset estimation (§4) |

- Receivers validate the group HMAC on `TALK_START` and ignore the whole talk otherwise.
- `TALK_START` and `TALK_END` are sent 3× (spaced 20 ms) since UDP; duplicates are idempotent by `talk_id`.
- Missing `TALK_END` or 1 s without audio ⇒ talk considered over (handles sender crash/Wi-Fi drop).
- Two senders talking at once: receivers keep one playback stream per active `talk_id` and mix. This falls out of the design for free — do not add arbitration logic.

## 3. Audio pipeline

**Format v1: 24 kHz, mono, 16-bit PCM, 20 ms frames.** That's 960 bytes of audio per packet (fits any MTU), 384 kbit/s per receiver — 10 receivers ≈ 3.8 Mbit/s, noise on any Wi-Fi from the last decade. 24 kHz is noticeably better than telephone quality for voice.

**Deliberately no codec in v1.** Opus would cut bandwidth 10× but adds encoder/decoder state, packetization and platform codec quirks (Android `MediaCodec` Opus *encoding* only from API 29, Apple via AudioToolbox) for bandwidth we don't need on a LAN. The format id in `TALK_START` exists so Opus can be added later without a protocol break.

**Capture (senders):** mic → 24 kHz mono 16-bit → 20 ms packets, started on button-down, stopped on release.
- Apple: `AVAudioEngine` input node + `AVAudioConverter`.
- Android: `AudioRecord` (`VOICE_COMMUNICATION` source — free echo/noise processing).

**Playback (receivers):** jitter-buffered, schedule-at-timestamp (next section).
- Apple: `AVAudioEngine` + one `AVAudioPlayerNode` per active talk, scheduling each frame at its target host time.
- Android: `AudioTrack` low-latency stream; the jitter buffer feeds it on a dedicated thread.
- Audio focus/ducking: Android takes transient-may-duck focus per talk; iOS dock-mode uses `.playback` + `.duckOthers`. macOS just plays.
- **Start chirp:** receivers play a short tone at talk start (classic intercom cue, and it masks the buffering delay). Config toggle.

## 4. "Play on all receivers at the same time"

The feature that makes multi-room feel right — without it, adjacent rooms echo 100–300 ms apart.

Mechanism (simple, ~100 lines per platform):

1. Every `AUDIO` packet carries the sender's monotonic timestamp.
2. On `TALK_START`, each receiver fires 3–5 `TIME_PING`s and estimates clock offset NTP-style (offset = midpoint, keep lowest-RTT sample; refresh lazily during long talks).
3. Receiver plays frame stamped `T` at local time `T + offset + D`, where **D = 150 ms** fixed playout delay (config: "Sync delay").

That single constant is simultaneously the jitter buffer and the sync mechanism. Frames arriving after their slot are dropped; a receiver that can't keep up blips instead of drifting. Expected cross-device skew: ±10–30 ms — inaudible as echo between rooms. 150 ms end-to-end latency is imperceptible for one-way PTT (it's not a phone call).

## 5. The apps

### macOS (first) — menubar only

- **SwiftUI `MenuBarExtra`**, `LSUIElement` (no Dock icon). Target macOS 14+.
- Menubar icon = state: idle / receiving (animated) / sending. Icon highlights when receivers are discovered.
- **Sender popover:** one big **Hold to Speak** button (mouse-down to talk, mouse-up to stop; spacebar-hold works while popover is open) + receiver count. That's the whole UI.
- **Receiver mode:** no popover UI beyond status + volume + mute.
- **Setup window** (standard Settings scene): role picker, device name, group code, sync delay, chirp toggle, launch-at-login (`SMAppService`), receiver list with live status.
- Sandboxed; entitlements: network client + server, audio input. Mic + Local Network permission prompts handled with proper usage strings.
- Distribution: Developer ID + notarization outside the store first (App Store later if wanted; nothing here is store-hostile).

### Android — receiver first, then sender

- Kotlin + Jetpack Compose, minSdk 26, target current. Single `:app` module; packages `protocol/`, `audio/`, `net/`, `ui/`.
- **Receiver** = the foreground-service recipe from §1 verbatim: `mediaPlayback` foreground service holding socket + locks, NSD advertise, notification shows "Intercom active — N senders seen". Setup flow walks through notification permission, battery exemption, and OEM whitelist link.
- **Sender:** plain foreground activity — big hold-to-speak button (touch-down/up), receiver list. No service needed; you're looking at it while using it.
- Permissions: `RECORD_AUDIO` (sender only), `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, wake/Wi-Fi/multicast locks.

### iOS (later)

- Shares the Apple core package (§6) and most SwiftUI views with the Mac app.
- **Sender:** fully works, foreground PTT screen, identical protocol.
- **Receiver = explicit "Dock mode"** only: screen-on foreground, `isIdleTimerDisabled`, labelled honestly ("iPhone/iPad plays intercom only while this screen is open — best on a stand or charger").
- In parallel: request the **Local Push Connectivity** entitlement from Apple; if ever granted, true background iOS receivers become possible with an `NEAppPushProvider` extension and no protocol changes.

## 6. Code layout

```
Intercom/
  PROTOCOL.md          # the §2 spec, normative
  apple/               # one Xcode workspace
    IntercomCore/      # Swift package: protocol, discovery, clock sync,
                       #   capture & playback engines (all platform-neutral)
    MacApp/            # MenuBarExtra shell + settings
    iOSApp/            # (later) PTT screen + dock mode shell
  android/             # Gradle project, Kotlin port of the same core
```

No KMP/Rust shared core: the entire shared surface is ~500 lines of protocol + sync logic, far cheaper to write twice idiomatically than to maintain cross-language tooling. `PROTOCOL.md` is the single source of truth both implementations are tested against.

## 7. Security posture (stated, not hand-waved)

v1 trusts the LAN: audio is unencrypted; the group code (HMAC over talk metadata with a per-talk nonce) is access control against *accidental* cross-talk and casual neighbours, not a cryptographic boundary. Anyone who can capture your Wi-Fi traffic can hear intercom audio. Acceptable for a household tool; README says so plainly. Protocol `ver` field leaves room for a Noise/DTLS upgrade if it ever ships beyond personal use.

## 8. Known risks & edge cases

| Risk | Handling |
|---|---|
| OEM kills Android service | Setup checklist + dontkillmyapp link; notification makes death visible (it disappears) |
| AP client isolation / guest Wi-Fi blocks P2P | Troubleshooting doc; "0 receivers found" hint in sender UI names this cause explicitly |
| mDNS across subnets/VLANs fails | Out of scope v1; document. (Manual IP entry in setup is the cheap escape hatch — worth including.) |
| Sleeping Mac receiver | Setup hint re: sleep settings; receiving Macs show a warning if display-sleep implies system sleep |
| Wi-Fi roaming mid-talk | Frames drop for ~1 s, talk resumes (UDP + timeout design already absorbs it) |
| Clock-sync ping lost | Fall back to play-on-arrival + D (still jitter-buffered, just unsynced) rather than silence |

## 9. Milestones

1. **M1 — Protocol + Mac app.** `PROTOCOL.md`; Mac app with both roles; two Macs talking. *Proves: discovery, audio path, sync playback.*
2. **M2 — Android receiver.** Foreground-service recipe, Mac → Android talk, screen-off soak test overnight. *Proves: the risky Android half.*
3. **M3 — Android sender.** Android → everything. Full Mac+Android product; this is a shippable v1.
4. **M4 — Polish.** Start chirp, mute/DND schedule on receivers, per-receiver volume, setup-flow refinement, multi-sender mix testing, latency/skew measurement harness (record two receivers side by side).
5. **M5 — iOS.** Sender + dock-mode receiver from the shared Apple core; LPC entitlement request submitted at the start of this phase, not the end.

Each milestone ends with the real-device test named in it — intercoms fail in hallways, not unit tests. Unit tests cover the protocol codec and clock-offset math (the two pieces with exact expected outputs); everything else is device soak testing.
