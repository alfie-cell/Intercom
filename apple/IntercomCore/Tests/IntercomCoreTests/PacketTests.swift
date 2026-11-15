import XCTest
@testable import IntercomCore

final class PacketTests: XCTestCase {
    func testRoundtripAllTypes() {
        let pcm = Data((0..<Wire.frameBytes).map { UInt8(($0 &* 7) & 0xFF) })
        let packets: [Packet] = [
            .makeTalkStart(talkID: 0xDEAD_BEEF, ts: 123_456_789, name: "Kitchen Mac", groupCode: "pw"),
            .audio(talkID: 0xDEAD_BEEF, seq: 41, ts: 123_456_789 + 41 * 20_000, pcm: pcm),
            .talkEnd(talkID: 0xDEAD_BEEF, lastSeq: 41),
            .timePing(talkID: 0xDEAD_BEEF, seq: 2, t1: 777),
            .timePong(talkID: 0xDEAD_BEEF, seq: 2, t1: 777, t2: 999),
        ]
        for p in packets {
            XCTAssertEqual(Packet.decode(p.encode()), p, "\(p)")
        }
    }

    func testHeaderLayout() {
        let data = Packet.talkEnd(talkID: 0x0102_0304, lastSeq: 0x0A0B_0C0D).encode()
        XCTAssertEqual(Array(data.prefix(4)), [0x49, 0x43, 1, 3])
        XCTAssertEqual(Array(data[4..<8]), [0x04, 0x03, 0x02, 0x01]) // talk_id LE
        XCTAssertEqual(Array(data[8..<12]), [0, 0, 0, 0])            // seq
        XCTAssertEqual(Array(data[12..<16]), [0x0D, 0x0C, 0x0B, 0x0A]) // last_seq LE
    }

    func testRejectsGarbage() {
        XCTAssertNil(Packet.decode(Data()))
        XCTAssertNil(Packet.decode(Data(repeating: 0, count: 64)))
        var bad = Packet.talkEnd(talkID: 1, lastSeq: 1).encode()
        bad[2] = 99 // wrong version
        XCTAssertNil(Packet.decode(bad))
        XCTAssertNil(Packet.decode(bad.prefix(13))) // truncated
    }

    func testDecodeIgnoresSliceOffsets() {
        let p = Packet.timePing(talkID: 5, seq: 1, t1: 42)
        var padded = Data([0xFF, 0xFF, 0xFF])
        padded.append(p.encode())
        XCTAssertEqual(Packet.decode(padded.dropFirst(3)), p)
    }

    func testHMACVerification() {
        let p = Packet.makeTalkStart(talkID: 7, ts: 100, name: "Study", groupCode: "kitchen")
        XCTAssertTrue(p.verifyTalkStart(groupCode: "kitchen"))
        XCTAssertFalse(p.verifyTalkStart(groupCode: "KITCHEN"))
        XCTAssertFalse(p.verifyTalkStart(groupCode: ""))
        let empty = Packet.makeTalkStart(talkID: 7, ts: 100, name: "Study", groupCode: "")
        XCTAssertTrue(empty.verifyTalkStart(groupCode: ""))
        XCTAssertFalse(Packet.talkEnd(talkID: 7, lastSeq: 0).verifyTalkStart(groupCode: "kitchen"))
    }

    func testNameTruncatedTo255Bytes() {
        let long = String(repeating: "x", count: 300)
        let p = Packet.makeTalkStart(talkID: 1, ts: 1, name: long, groupCode: "")
        guard case .talkStart(_, _, _, let name, _)? = Packet.decode(p.encode()) else {
            return XCTFail("decode failed")
        }
        XCTAssertEqual(name.utf8.count, 255)
        XCTAssertTrue(Packet.decode(p.encode())!.verifyTalkStart(groupCode: ""))
    }

    func testNameTruncationKeepsValidUTF8() {
        // 2-byte chars: a 255-byte cut would split one, so it must stop at 254.
        let p = Packet.makeTalkStart(talkID: 1, ts: 1, name: String(repeating: "é", count: 200), groupCode: "")
        guard case .talkStart(_, _, _, let name, _)? = Packet.decode(p.encode()) else {
            return XCTFail("decode failed")
        }
        XCTAssertEqual(name, String(repeating: "é", count: 127))
        XCTAssertTrue(Packet.decode(p.encode())!.verifyTalkStart(groupCode: ""))
    }
}

final class ClockSyncTests: XCTestCase {
    func testSymmetricPathOffset() {
        var cs = ClockSync()
        // Requester at 1_000_000 sends; responder clock is +3s ahead; 1 ms each way.
        XCTAssertTrue(cs.addSample(t1: 1_000_000, t2: 4_002_000, t3: 1_002_000))
        XCTAssertEqual(cs.offsetMicros, 3_001_000)
        XCTAssertEqual(cs.bestRTTMicros, 2_000)
    }

    func testKeepsLowestRTTSample() {
        var cs = ClockSync()
        cs.addSample(t1: 0, t2: 10_000, t3: 8_000)          // rtt 8000, offset 6000
        XCTAssertFalse(cs.addSample(t1: 100_000, t2: 110_000, t3: 109_000)) // rtt 9000: worse
        XCTAssertEqual(cs.offsetMicros, 6_000)
        XCTAssertTrue(cs.addSample(t1: 200_000, t2: 203_500, t3: 201_000))  // rtt 1000: better
        XCTAssertEqual(cs.offsetMicros, 3_000)
    }

    func testNegativeOffset() {
        var cs = ClockSync()
        cs.addSample(t1: 5_000_000, t2: 2_000_500, t3: 5_001_000)
        XCTAssertEqual(cs.offsetMicros, -3_000_000)
    }

    func testRejectsNonMonotonicSample() {
        var cs = ClockSync()
        XCTAssertFalse(cs.addSample(t1: 100, t2: 50, t3: 90))
        XCTAssertNil(cs.offsetMicros)
    }
}
