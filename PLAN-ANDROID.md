# Android App — Implementation Plan

Receiver-first Android app (the risky half of the product), then the sender screen. Companion to [PLAN.md](PLAN.md) (architecture) and [PLAN-MAC.md](PLAN-MAC.md).

**Definition of done:** a phone set up as Receiver plays talks from a Mac sender with screen off, overnight on battery, and still responds at 7 am; it survives a reboot unattended (service auto-restarts); as Sender it reaches Mac and Android receivers identically. Receivers stay within 40 ms of a Mac receiver on the same talk.

## Targets & tooling

- Kotlin + Jetpack Compose, **minSdk 26, targetSdk 36** (Play's 2026 floor). Single `:app` module. Dependencies: Compose BOM, DataStore, coroutines — nothing else (no networking or audio libraries; `java.net`, `AudioRecord`/`AudioTrack`, `NsdManager` are the stack).
- Protocol unit tests consume the shared `protocol/test-vectors.json` authored by the Mac implementation — the two hand-written codecs must pass the identical vectors.

## File map

```
android/app/src/main/java/.../intercom/
  protocol/
    Packet.kt              ~150  packet types, ByteBuffer encode/decode (little-endian), HMAC
    ClockSync.kt            ~80  ping burst, offset = midpoint of min-RTT sample
  net/
    Discovery.kt           ~140  NsdManager wrapper: register (receiver) / discover+resolve
                                 (sender); serialized resolves, retry on ALREADY_ACTIVE
    ReceiverSocket.kt      ~120  bound DatagramSocket loop on its own thread; demux by
                                 talk_id; answers TIME_PING; 1 s silence = talk over
    SenderTransport.kt     ~110  fan-out DatagramSocket sends; 3× TALK_START/END resend
  audio/
    Capture.kt             ~120  AudioRecord @48 kHz VOICE_COMMUNICATION → 2:1 half-band
                                 decimator (~19-tap FIR, the only DSP in the app) → 20 ms frames
    Playback.kt            ~170  per-talk AudioTrack @24 kHz MODE_STREAM; playout thread:
                                 wait until sender_t0+offset+D, then paced writes; late
                                 frames dropped, gaps filled with silence
  service/
    ReceiverService.kt     ~180  the foreground service (details below); owns socket,
                                 discovery registration, locks, playback, notification
    BootReceiver.kt         ~30  BOOT_COMPLETED → start ReceiverService (receiver role only)
    Locks.kt                ~60  partial wake lock + WifiLock(LOW_LATENCY) + MulticastLock,
                                 acquired/released as a unit
  data/
    Config.kt               ~70  DataStore: role, name, group code, delay, chirp
  ui/
    MainActivity.kt         ~60  routes to Setup (first run) / Sender / Receiver screen
    SetupScreen.kt         ~200  wizard: role → name → group code → permission walk
    SenderScreen.kt        ~120  hold-to-speak button + receiver list
    ReceiverScreen.kt      ~100  service status, mute, last-talk info, health warnings
```

≈ 2,200 lines. Same rule as the Mac plan: a file growing past ~2× its estimate means the design is drifting.

## The receiver service (the part that must not be wrong)

This is where the §1 reality-check from PLAN.md becomes code:

- **Foreground service type: `specialUse`** (`FOREGROUND_SERVICE_SPECIAL_USE` permission + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE = "local-network intercom receiver"`). Chosen over the more obvious `mediaPlayback` for one decisive reason: **Android 15+ forbids BOOT_COMPLETED receivers from launching `mediaPlayback`/`microphone`/`dataSync` (etc.) services** — it throws `ForegroundServiceStartNotAllowedException` — while `specialUse` is not on the restricted list. Auto-restart after reboot is non-negotiable for a wall-intercom device, so `specialUse` it is. The FGS type gates policy, not capability — audio playback works fine from any foreground service. (Play Console requires a short declaration for `specialUse`; our use case is a textbook fit. `mediaPlayback` stays as the documented fallback if review ever balks, at the cost of a "tap to restart" notification after reboots instead of silent auto-start.)
- **Lifecycle:** `START_STICKY`; started by the setup flow (foreground app, always allowed) and by `BootReceiver`. Stopped only by the explicit "Intercom off" action.
- **Locks:** `MulticastLock` for the service's lifetime (so senders' mDNS queries reach us and browse results don't expire); `FULL_HIGH_PERF` + `FULL_LOW_LATENCY` Wi-Fi locks (effective with screen off only below Android 14 — see "As built"); a partial wake lock held **per talk**, not permanently.
- **Notification** is the health UI: "Intercom listening — last heard \<sender\> \<time\>" + mute and off actions. If the notification vanishes, the OEM killed us — visible failure beats silent failure.
- **Audio focus:** request transient-may-duck focus at talk start, abandon at end. Playback at media-stream volume.
- Reboot nicety (later): service is not `directBootAware`, so after a reboot the intercom resumes at first unlock. Acceptable v1; note in setup copy.

## Setup wizard — permissions as product

First-run flow, one screen per step, each with an honest sentence on *why*:

1. Role, device name, group code.
2. `POST_NOTIFICATIONS` (receiver can't run without its service notification being visible).
3. Battery-optimization exemption via `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (core function is background reception — the legitimate case for this intent).
4. OEM step, shown only on brands that need it (Samsung/Xiaomi/Huawei/OnePlus/Oppo detection by `Build.MANUFACTURER`): link to the matching dontkillmyapp.com page.
5. `RECORD_AUDIO` — sender role only, and requested lazily on first button-press if skipped.
6. Receiver on battery shows a persistent "works best plugged in" hint.

ReceiverScreen re-surfaces any regression (notification disabled, exemption revoked, Wi-Fi off) as a warning row — the screen a user checks when "it stopped working."

## Audio decisions

- **Capture at 48 kHz always** (44.1 is the only rate *guaranteed* by the API, but 48 k is the native rate of effectively all modern hardware); decimate 2:1 to the 24 kHz wire format with a fixed small FIR. One deterministic path beats a "try 24 kHz, fall back" matrix. `VOICE_COMMUNICATION` source buys hardware echo/noise processing for free.
- **Playback via `AudioTrack` at 24 kHz directly** — AudioFlinger resamples to hardware rate; no app-side upsampling.
- **Sync playout:** monotonic clock is `SystemClock.elapsedRealtimeNanos()` (ticks through deep sleep — required, since talks arrive mid-Doze). Playout thread sleeps until `t0 + offset + D` (coarse sleep + short spin for the last ~2 ms), then blocking `AudioTrack.write` paces itself against the DAC clock. Clock drift over a ≤ 60 s talk is ~1–2 ms — no drift correction code.
- Multi-sender: one `AudioTrack` per active `talk_id`, mixed by the OS. Same free-mix property as the Mac app.

## NsdManager notes (known-flaky API, contained in one file)

- Receiver: `registerService("_intercom._udp")` with TXT records; re-register on Wi-Fi network change (`ConnectivityManager.NetworkCallback` on the Wi-Fi transport).
- Sender: discover + resolve with resolves strictly serialized through a queue (concurrent resolves are the classic `NsdManager` failure); on API 34+ prefer `registerServiceInfoCallback` for live updates.
- All of this behind a `Discovery` interface so the mess never leaks into the rest of the app.

## Build order

1. **Scaffold.** Project, Compose shell, Config/DataStore, setup wizard UI (no permission logic yet). *Check: role chosen in wizard routes to the right placeholder screen after restart.*
2. **Protocol.** `Packet.kt` + `ClockSync.kt` passing `protocol/test-vectors.json`. *Check: tests green against the Mac-authored vectors unchanged.*
3. **Receiver service skeleton.** FGS (`specialUse`) + locks + notification + `BootReceiver`; no audio yet, log inbound packets. *Check: service survives screen-off for an hour; `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED <pkg>` (with `FGS_BOOT_COMPLETED_RESTRICTIONS` compat enabled) starts it without exception.*
4. **Receiver audio.** Playback + clock sync wired to the socket; Mac is the sender. *Check: Mac → Android talk, screen off, audible and clean; Mac + Android receivers within 40 ms (waveform comparison).*
5. **Soak.** Overnight on battery, screen off: scheduled test talks (Mac `caffeinate` + cron script) + morning manual talk. *Check: zero missed talks on a Pixel; document results on one aggressive-OEM device (Samsung) with and without the OEM whitelist step.*
6. **Sender.** Capture + decimator + `SenderTransport` + SenderScreen (touch-down/up on the big button). *Check: Android → Mac and Android → Android; mic permission flow; talk auto-stops at 60 s and on app background.*
7. **Productize.** Chirp, mute action, health warnings, Wi-Fi-change re-registration, battery-hint, OEM step, release build with R8. *Check: definition-of-done checklist top of this doc, run on a clean device.*

Steps 1–3 need nothing from the Mac app but the vectors file, so Android work can start as soon as Mac step 2 lands. Step 4 onward wants a working Mac sender — which is exactly Mac build-order step 5.

## As built (2026-11-15)

Steps 1–7 are implemented in `android/`; ~2,000 lines of Kotlin. Where the build differs from the plan above, and why:

| Plan said | Built | Why |
|---|---|---|
| DataStore for config | SharedPreferences behind a `StateFlow` (`data/Config.kt`) | BootReceiver needs a synchronous read; one fewer dependency |
| One-screen-per-step setup wizard | One Setup/Settings screen with a live reliability checklist (`ui/Checklist.kt`) | Same "why" copy, less code; the checklist re-checks on every resume and failing items also appear on the receiver screen |
| compileSdk 36 | compileSdk 37.2, **targetSdk 36** | Current AndroidX libraries require compiling against 37. Target stays 36 so Android 17's `ACCESS_LOCAL_NETWORK` permission doesn't apply yet |
| Permanent partial wake lock | Wake lock held per talk (max 75 s) | A permanent CPU lock is the real battery cost; incoming packets wake the CPU anyway. Verify in the overnight soak |
| `LOW_LATENCY` Wi-Fi lock fixes screen-off deafness | It doesn't (screen-on/foreground only; HIGH_PERF maps to it on 14+) | Mitigated by measured-lag adaptive delay, below |
| Fixed playout delay D | D is raised **per device** when output latency + measured arrival lag don't fit | A 150 ms D left Bluetooth speakers (and the emulator, ~170 ms output latency) playing silence. "In sync where feasible": that one device plays slightly late, everything else stays synced |
| Play at the 24 kHz wire rate | Upsample to 48 kHz (`Interpolator2x`) before the AudioTrack | Only native-rate tracks get the FastMixer low-latency output. Galaxy Tab S10 Lite: ~128 → ~95 ms output latency (the rest is its speaker DSP) |
| D = 150 ms | **D = 250 ms default** (both platforms) | Real home Wi-Fi with a Mac sender: ping min 9 ms but spikes to 160 ms (Mac AWDL/AirDrop channel hopping). At 150 ms the tablet had to raise its own D (desync) and a Mac receiver would drop frames. At 250 ms, five idle-gap talks all stayed at the shared D |
| Arrival lag from every frame | Frames in the first 300 ms of a talk don't raise D | An idle receiver's power-save holds the first packets 100–190 ms; that's pre-speech silence, so drop it rather than desync the whole talk |
| Manual IP "worth including" | Implemented: Settings → Manual receivers (`host:port, …`) | Escape hatch for mDNS-hostile networks; also how the emulator was tested |

**Verified on the API 36 emulator:** 15/15 JVM tests incl. byte-exact conformance with the Swift vectors; receiver FGS (`specialUse`) starts, advertises, survives app update (MY_PACKAGE_REPLACED) and a real reboot (BOOT_COMPLETED → "Background started FGS: Allowed"); Mac → emulator talks play 1.87–1.98 s of 2 s with screen on **and** screen off in forced deep Doze; wrong group code rejected; emulator sender → Mac listener 149/149 frames, HMAC ok; mic permission flow; release build (R8) 1.3 MB.

**Diagnostics for the soak test:** every talk logs `IntercomRx: … max arrival jitter, ping rtt, implied first-frame transit` and `IntercomPlay: played X ms of Y ms received, N timing corrections, delay Z ms` — `adb logcat -s IntercomRx IntercomPlay` overnight answers "did any talk get lost or clipped".

**Before moving to targetSdk 37:** declare and request `ACCESS_LOCAL_NETWORK` (NEARBY_DEVICES group) in the setup checklist; receivers need it for inbound UDP. Don't declare it while targeting ≤ 36.
