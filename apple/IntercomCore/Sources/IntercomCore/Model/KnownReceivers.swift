import Foundation
import Network

/// Receivers this sender has resolved before, persisted. Many receivers (Samsung
/// tablets with the screen off, for one) stop answering mDNS while still receiving
/// unicast audio perfectly, so a receiver that drops out of discovery keeps getting
/// talks at its last known address until it's unseen for `maxAge`.
public enum KnownReceivers {
    static let maxAge: TimeInterval = 7 * 24 * 3600
    private static let key = "knownReceivers"

    /// Only IP literals are stored, so reconnecting never depends on name resolution.
    public static func remember(name: String, grpTag: String?, host: String, port: UInt16, now: Date = .now) {
        guard IPv4Address(host) != nil || IPv6Address(host) != nil else { return }
        var all = load().filter { ($0["name"] as? String) != name }
        all.append(["name": name, "grp": grpTag ?? "", "host": host, "port": Int(port),
                    "seen": now.timeIntervalSince1970])
        UserDefaults.standard.set(all, forKey: key)
    }

    /// Remembered receivers seen within `maxAge`, as standby (live == false) entries.
    public static func recent(now: Date = .now) -> [DiscoveredReceiver] {
        load().compactMap { e in
            guard let name = e["name"] as? String, let host = e["host"] as? String,
                  let port = (e["port"] as? Int).flatMap({ NWEndpoint.Port(rawValue: UInt16(clamping: $0)) }),
                  let seen = e["seen"] as? Double,
                  now.timeIntervalSince1970 - seen < maxAge else { return nil }
            let grp = (e["grp"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            return DiscoveredReceiver(id: "known:\(name)", name: name, grpTag: grp,
                                      endpoint: .hostPort(host: NWEndpoint.Host(host), port: port), live: false)
        }
    }

    private static func load() -> [[String: Any]] {
        UserDefaults.standard.array(forKey: key) as? [[String: Any]] ?? []
    }
}
