import XCTest
import Network
@testable import IntercomCore

final class KnownReceiversTests: XCTestCase {
    override func setUp() {
        UserDefaults.standard.removeObject(forKey: "knownReceivers")
    }

    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: "knownReceivers")
    }

    func testRemembersAndExpires() {
        let then = Date(timeIntervalSince1970: 1_000_000)
        KnownReceivers.remember(name: "Tab", grpTag: "abc", host: "192.168.1.20", port: 47474, now: then)
        let r = KnownReceivers.recent(now: then.addingTimeInterval(3600))
        XCTAssertEqual(r.count, 1)
        XCTAssertEqual(r.first?.name, "Tab")
        XCTAssertEqual(r.first?.grpTag, "abc")
        XCTAssertEqual(r.first?.live, false)
        XCTAssertEqual(r.first?.endpoint, .hostPort(host: "192.168.1.20", port: 47474))
        XCTAssertTrue(KnownReceivers.recent(now: then.addingTimeInterval(8 * 24 * 3600)).isEmpty)
    }

    func testLatestAddressWinsAndHostnamesAreIgnored() {
        KnownReceivers.remember(name: "Tab", grpTag: nil, host: "10.0.0.5", port: 1)
        KnownReceivers.remember(name: "Tab", grpTag: nil, host: "10.0.0.6", port: 2)
        KnownReceivers.remember(name: "Kitchen", grpTag: nil, host: "kitchen.local", port: 3)
        let r = KnownReceivers.recent()
        XCTAssertEqual(r.map(\.name), ["Tab"])
        XCTAssertEqual(r.first?.endpoint, .hostPort(host: "10.0.0.6", port: 2))
    }
}
