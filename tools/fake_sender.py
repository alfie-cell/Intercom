#!/usr/bin/env python3
"""Intercom protocol test harness (PROTOCOL.md v1), standard library only.

send   — act as a sender: TALK_START, a test tone as AUDIO frames, TALK_END, and
         answer the receiver's TIME_PINGs. Reports how many pings were answered.
listen — act as a receiver: verify TALK_START HMACs, count frames, report per talk.

Examples:
  # Mac → Android emulator (after: adb emu redir add udp:47474:47474)
  tools/fake_sender.py send --host 127.0.0.1 --port 47474 --group kitchen
  # Android emulator sender → this Mac (manual receiver in app: 10.0.2.2:47475)
  tools/fake_sender.py listen --port 47475 --group kitchen
"""
import argparse
import hashlib
import hmac
import math
import os
import socket
import struct
import threading
import time

MAGIC, VER = b"IC", 1
T_START, T_AUDIO, T_END, T_PING, T_PONG = 1, 2, 3, 4, 5
FRAME_SAMPLES, FRAME_US, RATE = 480, 20_000, 24_000


def now_us() -> int:
    return time.monotonic_ns() // 1_000


def header(ptype: int, talk_id: int, seq: int) -> bytes:
    return MAGIC + struct.pack("<BBII", VER, ptype, talk_id, seq)


def talk_hmac(code: str, talk_id: int, ts: int, fmt: int, name: bytes) -> bytes:
    key = hashlib.sha256(code.encode()).digest()
    msg = struct.pack("<IQB", talk_id, ts, fmt) + name
    return hmac.new(key, msg, hashlib.sha256).digest()[:16]


def send(args) -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", 0))
    dest = (args.host, args.port)
    talk_id = struct.unpack("<I", os.urandom(4))[0] or 1
    name = args.name.encode()[:255]
    pongs = []
    stop = threading.Event()

    def pong_loop():
        sock.settimeout(0.2)
        while not stop.is_set():
            try:
                data, addr = sock.recvfrom(2048)
            except socket.timeout:
                continue
            if len(data) >= 20 and data[:2] == MAGIC and data[3] == T_PING:
                tid, seq = struct.unpack_from("<II", data, 4)
                (t1,) = struct.unpack_from("<Q", data, 12)
                sock.sendto(header(T_PONG, tid, seq) + struct.pack("<QQ", t1, now_us()), addr)
                pongs.append(addr)

    threading.Thread(target=pong_loop, daemon=True).start()

    t0 = now_us()
    start = header(T_START, talk_id, 0) + struct.pack("<QBB", t0, 1, len(name)) + name \
        + talk_hmac(args.group, talk_id, t0, 1, name)
    sock.sendto(start, dest)  # the 2 resends ride along with frames 1 and 2, like real senders

    frames = int(args.seconds * 1_000_000 / FRAME_US)
    phase = 0.0
    step = 2 * math.pi * args.freq / RATE
    begin = time.monotonic()
    for seq in range(frames):
        if seq in (1, 2):
            sock.sendto(start, dest)
        samples = []
        for _ in range(FRAME_SAMPLES):
            # 0.5 s tone / 0.25 s gap pattern makes skew between two receivers easy to hear.
            on = (seq * FRAME_SAMPLES / RATE) % 0.75 < 0.5
            samples.append(int(math.sin(phase) * 8_000) if on else 0)
            phase += step
        pkt = header(T_AUDIO, talk_id, seq) + struct.pack("<Q", t0 + seq * FRAME_US) \
            + struct.pack(f"<{FRAME_SAMPLES}h", *samples)
        sock.sendto(pkt, dest)
        delay = begin + (seq + 1) * FRAME_US / 1e6 - time.monotonic()
        if delay > 0:
            time.sleep(delay)

    end = header(T_END, talk_id, 0) + struct.pack("<I", frames - 1)
    for _ in range(3):
        sock.sendto(end, dest)
        time.sleep(0.02)
    time.sleep(0.1)
    stop.set()
    print(f"talk {talk_id:#010x}: sent {frames} frames to {dest}, answered {len(pongs)} clock-sync pings")


def listen(args) -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", args.port))
    print(f"listening on UDP {args.port} (group code {args.group!r}) — Ctrl-C to stop")
    talks = {}
    while True:
        data, addr = sock.recvfrom(2048)
        if len(data) < 12 or data[:2] != MAGIC or data[2] != VER:
            continue
        ptype = data[3]
        tid, seq = struct.unpack_from("<II", data, 4)
        if ptype == T_START and tid not in talks:
            ts, fmt, nlen = struct.unpack_from("<QBB", data, 12)
            name = data[22:22 + nlen]
            ok = hmac.compare_digest(data[22 + nlen:22 + nlen + 16], talk_hmac(args.group, tid, ts, fmt, name))
            talks[tid] = {"frames": 0, "ok": ok, "start": time.monotonic()}
            print(f"TALK_START {tid:#010x} from {name.decode(errors='replace')!r} {addr} hmac={'ok' if ok else 'BAD'}")
        elif ptype == T_AUDIO and tid in talks:
            talks[tid]["frames"] += 1
        elif ptype == T_END and tid in talks and "done" not in talks[tid]:
            t = talks[tid]
            t["done"] = True
            (last_seq,) = struct.unpack_from("<I", data, 12)
            dur = time.monotonic() - t["start"]
            print(f"TALK_END   {tid:#010x}: {t['frames']} frames received of {last_seq + 1} sent, {dur:.1f}s")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="mode", required=True)
    s = sub.add_parser("send")
    s.add_argument("--host", required=True)
    s.add_argument("--port", type=int, default=47474)
    s.add_argument("--group", default="")
    s.add_argument("--name", default="Test Harness")
    s.add_argument("--seconds", type=float, default=3.0)
    s.add_argument("--freq", type=float, default=660.0)
    l = sub.add_parser("listen")
    l.add_argument("--port", type=int, default=47475)
    l.add_argument("--group", default="")
    args = p.parse_args()
    send(args) if args.mode == "send" else listen(args)


if __name__ == "__main__":
    main()
