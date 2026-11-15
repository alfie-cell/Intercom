import Foundation
import Network

public struct DiscoveredReceiver: Identifiable, Equatable {
    public let id: String
    public let name: String
    public let grpTag: String?
    public let endpoint: NWEndpoint
    /// Currently answering mDNS. False for remembered receivers that have gone quiet.
    public var live: Bool = true
}

/// Sender-side Bonjour browse for `_intercom._udp` receivers.
/// Callbacks are delivered on the main queue.
public final class ReceiverBrowser {
    public var onUpdate: (([DiscoveredReceiver]) -> Void)?
    private var browser: NWBrowser?
    private let queue = DispatchQueue(label: "intercom.browser")
    private var generation = 0

    public init() {}

    public func start() {
        stop()
        generation += 1
        let gen = generation
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: Wire.serviceType, domain: nil),
                          using: NWParameters())
        b.browseResultsChangedHandler = { [weak self] results, _ in
            self?.publish(results)
        }
        b.stateUpdateHandler = { [weak self] state in
            // Bonjour browses can fail on interface churn (Wi-Fi off/on); restart after a beat.
            if case .failed = state {
                self?.queue.asyncAfter(deadline: .now() + 2) {
                    DispatchQueue.main.async {
                        guard let self, self.generation == gen else { return }
                        self.start()
                    }
                }
            }
        }
        browser = b
        b.start(queue: queue)
    }

    public func stop() {
        browser?.cancel()
        browser = nil
    }

    private func publish(_ results: Set<NWBrowser.Result>) {
        let list = results.compactMap { r -> DiscoveredReceiver? in
            guard case .service(let name, let type, let domain, _) = r.endpoint else { return nil }
            var grp: String?
            if case .bonjour(let txt) = r.metadata { grp = txt.dictionary["grp"] }
            return DiscoveredReceiver(id: "\(name).\(type)\(domain)", name: name, grpTag: grp, endpoint: r.endpoint)
        }
        .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        DispatchQueue.main.async { [weak self] in self?.onUpdate?(list) }
    }
}

enum TXTEncoder {
    /// RFC 6763 TXT record wire format: length-prefixed "key=value" strings.
    static func encode(_ entries: [(String, String)]) -> Data {
        var d = Data()
        for (k, v) in entries {
            let entry = Data("\(k)=\(v)".utf8).prefix(255)
            d.append(UInt8(entry.count))
            d.append(entry)
        }
        return d
    }
}
