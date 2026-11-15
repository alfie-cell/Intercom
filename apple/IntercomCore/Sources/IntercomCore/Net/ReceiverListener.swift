import Foundation
import Network

public struct IncomingTalk {
    public let talkID: UInt32
    public let senderName: String
    public let t0: UInt64
}

/// Receiver side: UDP NWListener advertised over Bonjour; verifies TALK_START,
/// runs the per-talk clock-sync ping burst, demuxes audio, and expires dead talks.
/// All callbacks fire on the listener's internal queue — downstream must be thread-safe.
public final class ReceiverListener {
    public var onTalkStart: ((IncomingTalk) -> Void)?
    public var onAudio: ((_ talkID: UInt32, _ ts: UInt64, _ pcm: Data) -> Void)?
    public var onOffset: ((_ talkID: UInt32, _ offsetMicros: Int64) -> Void)?
    public var onTalkEnd: ((_ talkID: UInt32) -> Void)?
    public var onStateChange: ((_ running: Bool, _ error: String?) -> Void)?

    private let deviceName: String
    private let groupCode: String
    private let queue = DispatchQueue(label: "intercom.listener")
    private var listener: NWListener?
    private var sweepTimer: DispatchSourceTimer?

    private final class Talk {
        let conn: NWConnection
        var sync = ClockSync()
        var pingSeq: UInt32 = 0
        var lastAudioAt: UInt64
        var ended = false
        init(conn: NWConnection, now: UInt64) {
            self.conn = conn
            self.lastAudioAt = now
        }
    }

    private var talks: [UInt32: Talk] = [:]
    private var connLastSeen: [ObjectIdentifier: (conn: NWConnection, at: UInt64)] = [:]

    public init(deviceName: String, groupCode: String) {
        self.deviceName = deviceName
        self.groupCode = groupCode
    }

    public func start() throws {
        stop()
        let listener = try NWListener(using: .udp)
        let txt = TXTEncoder.encode([
            ("name", deviceName),
            ("ver", String(Wire.version)),
            ("grp", GroupAuth.grpTag(groupCode: groupCode)),
        ])
        listener.service = NWListener.Service(name: deviceName, type: Wire.serviceType,
                                              domain: nil, txtRecord: txt)
        listener.newConnectionHandler = { [weak self] conn in
            guard let self else { return }
            conn.start(queue: self.queue)
            self.connLastSeen[ObjectIdentifier(conn)] = (conn, Monotonic.nowMicros())
            self.receiveLoop(conn)
        }
        listener.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready: self?.onStateChange?(true, nil)
            case .failed(let err): self?.onStateChange?(false, err.localizedDescription)
            case .cancelled: self?.onStateChange?(false, nil)
            default: break
            }
        }
        self.listener = listener
        listener.start(queue: queue)
        startSweepTimer()
    }

    public func stop() {
        listener?.cancel()
        listener = nil
        sweepTimer?.cancel()
        sweepTimer = nil
        queue.async {
            for (_, entry) in self.connLastSeen { entry.conn.cancel() }
            self.connLastSeen.removeAll()
            self.talks.removeAll()
        }
    }

    // MARK: - Inbound

    private func receiveLoop(_ conn: NWConnection) {
        conn.receiveMessage { [weak self, weak conn] data, _, _, _ in
            guard let self, let conn else { return }
            if case .cancelled = conn.state { return }
            if case .failed = conn.state {
                self.connLastSeen[ObjectIdentifier(conn)] = nil
                return
            }
            if let data, let pkt = Packet.decode(data) {
                self.connLastSeen[ObjectIdentifier(conn)]?.at = Monotonic.nowMicros()
                self.handle(pkt, from: conn)
            }
            self.receiveLoop(conn)
        }
    }

    private func handle(_ pkt: Packet, from conn: NWConnection) {
        switch pkt {
        case .talkStart(let id, let ts, let format, let name, _):
            guard talks[id] == nil,                     // resends are idempotent
                  format == Wire.formatPCM24k,
                  pkt.verifyTalkStart(groupCode: groupCode) else { return }
            let talk = Talk(conn: conn, now: Monotonic.nowMicros())
            talks[id] = talk
            onTalkStart?(IncomingTalk(talkID: id, senderName: name, t0: ts))
            schedulePings(talkID: id, talk: talk)

        case .audio(let id, _, let ts, let pcm):
            guard let talk = talks[id], !talk.ended, pcm.count == Wire.frameBytes else { return }
            talk.lastAudioAt = Monotonic.nowMicros()
            onAudio?(id, ts, pcm)

        case .talkEnd(let id, _):
            endTalk(id)

        case .timePong(let id, _, let t1, let t2):
            guard let talk = talks[id], !talk.ended else { return }
            if talk.sync.addSample(t1: t1, t2: t2, t3: Monotonic.nowMicros()),
               let offset = talk.sync.offsetMicros {
                onOffset?(id, offset)
            }

        case .timePing:
            break // receivers don't answer pings
        }
    }

    private func schedulePings(talkID: UInt32, talk: Talk) {
        for i in 0..<Wire.pingCount {
            queue.asyncAfter(deadline: .now() + .milliseconds(i * Wire.pingSpacingMillis)) { [weak self, weak talk] in
                guard self != nil, let talk, !talk.ended else { return }
                let ping = Packet.timePing(talkID: talkID, seq: talk.pingSeq, t1: Monotonic.nowMicros())
                talk.pingSeq += 1
                talk.conn.send(content: ping.encode(), completion: .contentProcessed { _ in })
            }
        }
    }

    // MARK: - Expiry

    private func endTalk(_ id: UInt32) {
        guard let talk = talks[id], !talk.ended else { return }
        talk.ended = true
        onTalkEnd?(id)
        // Keep the entry briefly so straggler packets and resends stay idempotent.
        queue.asyncAfter(deadline: .now() + 2) { [weak self] in
            self?.talks[id] = nil
        }
    }

    private func startSweepTimer() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 0.5, repeating: 0.5)
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            let now = Monotonic.nowMicros()
            for (id, talk) in self.talks where !talk.ended && now - talk.lastAudioAt > Wire.talkTimeoutMicros {
                self.endTalk(id)
            }
            // Drop UDP flows idle for 5 minutes (senders that restarted get new flows).
            for (key, entry) in self.connLastSeen where now - entry.at > 300_000_000 {
                entry.conn.cancel()
                self.connLastSeen[key] = nil
            }
        }
        timer.resume()
        sweepTimer = timer
    }
}
