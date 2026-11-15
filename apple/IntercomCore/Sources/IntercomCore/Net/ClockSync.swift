import Foundation

/// NTP-style offset estimator over TIME_PING/PONG samples (PROTOCOL.md "Clock sync & playout").
/// Keeps the offset from the lowest-RTT sample; pure math, no I/O.
public struct ClockSync: Equatable {
    /// Responder clock minus requester clock, microseconds. nil until a sample lands.
    public private(set) var offsetMicros: Int64?
    public private(set) var bestRTTMicros: UInt64 = .max

    public init() {}

    /// t1: requester send time, t2: responder reply time, t3: requester receive time.
    /// Returns true if this sample improved the estimate.
    @discardableResult
    public mutating func addSample(t1: UInt64, t2: UInt64, t3: UInt64) -> Bool {
        guard t3 >= t1 else { return false }
        let rtt = t3 - t1
        guard rtt < bestRTTMicros else { return false }
        bestRTTMicros = rtt
        offsetMicros = Int64(bitPattern: t2) - Int64(bitPattern: (t1 / 2) + (t3 / 2) + (t1 & t3 & 1))
        return true
    }
}
