import Foundation
import Network

/// Sender side: one UDP NWConnection per discovered receiver, audio fan-out,
/// TALK_START/END 3× resends, and TIME_PONG replies to receivers' pings.
public final class SenderTransport {
    public var deviceName: String
    public var groupCode: String
    /// A discovered receiver's connection resolved to this IP address and port (transport queue).
    public var onResolved: ((DiscoveredReceiver, String, UInt16) -> Void)?

    private let queue = DispatchQueue(label: "intercom.sender")
    private var connections: [String: NWConnection] = [:]
    private var currentTalkID: UInt32?
    private var t0: UInt64 = 0
    private var seq: UInt32 = 0

    public init(deviceName: String, groupCode: String) {
        self.deviceName = deviceName
        self.groupCode = groupCode
    }

    deinit {
        for c in connections.values { c.cancel() }
    }

    public func updateReceivers(_ receivers: [DiscoveredReceiver]) {
        queue.async {
            let wanted = Dictionary(uniqueKeysWithValues: receivers.map { ($0.id, $0) })
            for (id, conn) in self.connections where wanted[id] == nil {
                conn.cancel()
                self.connections[id] = nil
            }
            for (id, r) in wanted where self.connections[id] == nil {
                let conn = NWConnection(to: r.endpoint, using: .udp)
                if r.live {
                    conn.stateUpdateHandler = { [weak self, weak conn] state in
                        guard case .ready = state,
                              case .hostPort(let host, let port)? = conn?.currentPath?.remoteEndpoint else { return }
                        self?.onResolved?(r, Self.literal(host), port.rawValue)
                    }
                }
                self.connections[id] = conn
                conn.start(queue: self.queue)
                self.receiveLoop(conn)
            }
        }
    }

    public func startTalk() {
        queue.async {
            guard self.currentTalkID == nil else { return }
            let id = UInt32.random(in: 1...UInt32.max)
            self.currentTalkID = id
            self.seq = 0
            self.t0 = Monotonic.nowMicros()
            let start = Packet.makeTalkStart(talkID: id, ts: self.t0,
                                             name: self.deviceName, groupCode: self.groupCode)
            self.broadcast(start, resends: Wire.startEndResends)
        }
    }

    /// pcm must be exactly one 960-byte frame; called in capture order.
    public func sendFrame(_ pcm: Data) {
        queue.async {
            guard let id = self.currentTalkID else { return }
            let p = Packet.audio(talkID: id, seq: self.seq,
                                 ts: self.t0 + UInt64(self.seq) * Wire.frameDurationMicros,
                                 pcm: pcm)
            self.seq += 1
            self.broadcast(p, resends: 1)
        }
    }

    public func endTalk() {
        queue.async {
            guard let id = self.currentTalkID else { return }
            let last = self.seq > 0 ? self.seq - 1 : 0
            self.currentTalkID = nil
            self.broadcast(.talkEnd(talkID: id, lastSeq: last), resends: Wire.startEndResends)
        }
    }

    private func broadcast(_ packet: Packet, resends: Int) {
        let data = packet.encode()
        for i in 0..<resends {
            queue.asyncAfter(deadline: .now() + .milliseconds(i * Wire.resendSpacingMillis)) {
                for c in self.connections.values {
                    c.send(content: data, completion: .contentProcessed { _ in })
                }
            }
        }
    }

    private static func literal(_ host: NWEndpoint.Host) -> String {
        switch host {
        case .ipv4(let a): return "\(a)"
        case .ipv6(let a): return "\(a)"
        case .name(let n, _): return n
        @unknown default: return "\(host)"
        }
    }

    private func receiveLoop(_ conn: NWConnection) {
        conn.receiveMessage { [weak self, weak conn] data, _, _, _ in
            guard let self, let conn else { return }
            if case .cancelled = conn.state { return }
            if case .failed = conn.state { return }
            if let data, case .timePing(let talkID, let pingSeq, let t1)? = Packet.decode(data) {
                let pong = Packet.timePong(talkID: talkID, seq: pingSeq, t1: t1, t2: Monotonic.nowMicros())
                conn.send(content: pong.encode(), completion: .contentProcessed { _ in })
            }
            self.receiveLoop(conn)
        }
    }
}
