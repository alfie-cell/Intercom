# Intercom Wire Protocol — v1 (normative)

Transport: UDP, unicast, one socket per app. All integers **little-endian**. All timestamps are **unsigned 64-bit microseconds on the stamping device's own monotonic clock** (no shared epoch — see Clock sync). Reference test vectors: [`protocol/test-vectors.json`](protocol/test-vectors.json); an implementation is conformant when it byte-exactly reproduces and parses every vector.

## Header (12 bytes, every packet)

| offset | size | field | value |
|---|---|---|---|
| 0 | 2 | magic | bytes `0x49 0x43` (`"IC"`) |
| 2 | 1 | ver | `1` |
| 3 | 1 | type | see below |
| 4 | 4 | talk_id | u32, random per talk, non-zero |
| 8 | 4 | seq | u32; AUDIO: frame index from 0 · PING/PONG: ping index · otherwise 0 |

Packets failing magic/ver checks are dropped silently.

## Types

### 1 — TALK_START (sender → each receiver)

| field | size | notes |
|---|---|---|
| ts | 8 | talk start `t0`, sender clock µs |
| fmt | 1 | `1` = PCM 24 000 Hz, mono, s16le, 20 ms frames |
| name_len | 1 | |
| name | name_len | sender device name, UTF-8 |
| hmac | 16 | see Group authentication |

### 2 — AUDIO (sender → each receiver)

| field | size | notes |
|---|---|---|
| ts | 8 | frame start, sender clock µs; MUST equal `t0 + seq × 20 000` |
| pcm | 960 | 480 samples, s16le mono |

### 3 — TALK_END (sender → each receiver)

| field | size | notes |
|---|---|---|
| last_seq | 4 | seq of final AUDIO frame |

### 4 — TIME_PING (receiver → sender, during a talk)

| field | size | notes |
|---|---|---|
| t1 | 8 | requester clock µs at send |

### 5 — TIME_PONG (sender → receiver, reply)

| field | size | notes |
|---|---|---|
| t1 | 8 | echoed from the PING |
| t2 | 8 | responder clock µs at reply |

## Group authentication

`key = SHA-256(UTF-8 group code)` (empty code is a valid key — "no code" still authenticates consistently).
`hmac = first 16 bytes of HMAC-SHA-256(key, talk_id_u32le ‖ ts_u64le ‖ fmt_u8 ‖ name_bytes)`.
Receivers verify on TALK_START and ignore the entire talk on mismatch. This is cross-talk prevention, not cryptography — audio is cleartext (see PLAN.md §7).

## Reliability rules

- TALK_START and TALK_END are sent **3×, 20 ms apart**; duplicates are idempotent by `talk_id`.
- AUDIO for an unknown/unverified `talk_id` is dropped.
- A talk with no AUDIO for **1 000 ms** is over (handles lost TALK_END / sender crash).
- Max talk length **60 s**; senders auto-stop, receivers may discard beyond it.
- Late frames are dropped at playout, never queued.

## Clock sync & playout

On TALK_START a receiver sends **5 TIME_PINGs, 50 ms apart**. Per PONG received at local time `t3`:
`offset = t2 − (t1 + t3) / 2` (sender clock − receiver clock), `rtt = t3 − t1`; keep the offset from the **lowest-rtt** sample.
Playout: frame stamped `ts` plays at receiver-local `ts − offset + D`, with **D = 250 ms** default (covers the ~100–160 ms latency spikes measured on real home Wi-Fi with a Mac sender). If no PONG arrives, fall back to `offset = t0 − talk_start_arrival_local` (i.e. assume zero transit: play-on-arrival + D).

A receiver whose output latency plus observed arrival lag (`arrival_local + offset − ts`) exceeds D MAY raise D for itself for the rest of the talk — playing late beats playing silence (e.g. Bluetooth output). It never lowers D below the configured value.

Offsets are per (receiver, sender) pair, re-estimated each talk — clock epochs never matter, only short-term stability. Suitable monotonic sources: `mach_absolute_time`/`CLOCK_UPTIME_RAW` (Apple), `SystemClock.elapsedRealtimeNanos()` (Android).

## Discovery

Receivers advertise Bonjour/mDNS service type **`_intercom._udp`** (port = their UDP socket) with TXT keys:
`name` (device name) · `ver` = `1` · `grp` = first 8 bytes, lowercase hex, of `SHA-256("intercom-grp:" + group code)`.
`grp` is for sender-side list filtering only; enforcement is the TALK_START hmac.
