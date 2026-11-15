import Foundation
import CryptoKit

public enum Packet: Equatable {
    case talkStart(talkID: UInt32, ts: UInt64, format: UInt8, name: String, hmac: Data)
    case audio(talkID: UInt32, seq: UInt32, ts: UInt64, pcm: Data)
    case talkEnd(talkID: UInt32, lastSeq: UInt32)
    case timePing(talkID: UInt32, seq: UInt32, t1: UInt64)
    case timePong(talkID: UInt32, seq: UInt32, t1: UInt64, t2: UInt64)

    public var talkID: UInt32 {
        switch self {
        case .talkStart(let id, _, _, _, _), .audio(let id, _, _, _), .talkEnd(let id, _),
             .timePing(let id, _, _), .timePong(let id, _, _, _):
            return id
        }
    }

    private var typeByte: UInt8 {
        switch self {
        case .talkStart: return 1
        case .audio: return 2
        case .talkEnd: return 3
        case .timePing: return 4
        case .timePong: return 5
        }
    }

    private var seqField: UInt32 {
        switch self {
        case .audio(_, let seq, _, _), .timePing(_, let seq, _), .timePong(_, let seq, _, _):
            return seq
        case .talkStart, .talkEnd:
            return 0
        }
    }

    // MARK: - Encode

    public func encode() -> Data {
        var d = Data(capacity: 1024)
        d.append(Wire.magic0)
        d.append(Wire.magic1)
        d.append(Wire.version)
        d.append(typeByte)
        d.appendLE(talkID)
        d.appendLE(seqField)
        switch self {
        case .talkStart(_, let ts, let format, let name, let hmac):
            let nameBytes = Packet.utf8Truncated(name)
            d.appendLE(ts)
            d.append(format)
            d.append(UInt8(nameBytes.count))
            d.append(nameBytes)
            d.append(hmac.prefix(16))
        case .audio(_, _, let ts, let pcm):
            d.appendLE(ts)
            d.append(pcm)
        case .talkEnd(_, let lastSeq):
            d.appendLE(lastSeq)
        case .timePing(_, _, let t1):
            d.appendLE(t1)
        case .timePong(_, _, let t1, let t2):
            d.appendLE(t1)
            d.appendLE(t2)
        }
        return d
    }

    /// UTF-8 bytes cut to at most `max` bytes without splitting a code point,
    /// so receivers can always decode the name.
    static func utf8Truncated(_ s: String, max: Int = 255) -> Data {
        let bytes = Array(s.utf8)
        guard bytes.count > max else { return Data(bytes) }
        var cut = max
        while cut > 0 && bytes[cut] & 0xC0 == 0x80 { cut -= 1 }
        return Data(bytes[..<cut])
    }

    // MARK: - Decode

    public static func decode(_ raw: Data) -> Packet? {
        let d = Data(raw) // rebase indices, guarantee contiguity
        guard d.count >= 12, d[0] == Wire.magic0, d[1] == Wire.magic1, d[2] == Wire.version else {
            return nil
        }
        let talkID: UInt32 = d.loadLE(at: 4)
        let seq: UInt32 = d.loadLE(at: 8)
        let body = 12
        switch d[3] {
        case 1:
            guard d.count >= body + 10 else { return nil }
            let ts: UInt64 = d.loadLE(at: body)
            let format = d[body + 8]
            let nameLen = Int(d[body + 9])
            guard d.count == body + 10 + nameLen + 16 else { return nil }
            guard let name = String(data: d.subdata(in: (body + 10)..<(body + 10 + nameLen)), encoding: .utf8) else { return nil }
            let hmac = d.suffix(16)
            return .talkStart(talkID: talkID, ts: ts, format: format, name: name, hmac: Data(hmac))
        case 2:
            guard d.count > body + 8 else { return nil }
            let ts: UInt64 = d.loadLE(at: body)
            return .audio(talkID: talkID, seq: seq, ts: ts, pcm: d.subdata(in: (body + 8)..<d.count))
        case 3:
            guard d.count >= body + 4 else { return nil }
            return .talkEnd(talkID: talkID, lastSeq: d.loadLE(at: body))
        case 4:
            guard d.count >= body + 8 else { return nil }
            return .timePing(talkID: talkID, seq: seq, t1: d.loadLE(at: body))
        case 5:
            guard d.count >= body + 16 else { return nil }
            return .timePong(talkID: talkID, seq: seq, t1: d.loadLE(at: body), t2: d.loadLE(at: body + 8))
        default:
            return nil
        }
    }
}

// MARK: - Group authentication (PROTOCOL.md "Group authentication")

public enum GroupAuth {
    static func key(groupCode: String) -> SymmetricKey {
        SymmetricKey(data: Data(SHA256.hash(data: Data(groupCode.utf8))))
    }

    public static func talkHMAC(groupCode: String, talkID: UInt32, ts: UInt64, format: UInt8, name: String) -> Data {
        var msg = Data()
        msg.appendLE(talkID)
        msg.appendLE(ts)
        msg.append(format)
        msg.append(Packet.utf8Truncated(name))
        let mac = HMAC<SHA256>.authenticationCode(for: msg, using: key(groupCode: groupCode))
        return Data(mac).prefix(16)
    }

    /// TXT-record tag: first 8 bytes of SHA-256("intercom-grp:" + code), lowercase hex.
    public static func grpTag(groupCode: String) -> String {
        let digest = SHA256.hash(data: Data(("intercom-grp:" + groupCode).utf8))
        return Data(digest).prefix(8).map { String(format: "%02x", $0) }.joined()
    }
}

public extension Packet {
    static func makeTalkStart(talkID: UInt32, ts: UInt64, name: String, groupCode: String) -> Packet {
        .talkStart(talkID: talkID, ts: ts, format: Wire.formatPCM24k, name: name,
                   hmac: GroupAuth.talkHMAC(groupCode: groupCode, talkID: talkID, ts: ts,
                                            format: Wire.formatPCM24k, name: name))
    }

    /// Valid only for .talkStart; false for anything else.
    func verifyTalkStart(groupCode: String) -> Bool {
        guard case .talkStart(let talkID, let ts, let format, let name, let hmac) = self else { return false }
        let expected = GroupAuth.talkHMAC(groupCode: groupCode, talkID: talkID, ts: ts, format: format, name: name)
        return hmac == expected
    }
}

// MARK: - Little-endian Data helpers

extension Data {
    mutating func appendLE(_ v: UInt32) {
        Swift.withUnsafeBytes(of: v.littleEndian) { append(contentsOf: $0) }
    }

    mutating func appendLE(_ v: UInt64) {
        Swift.withUnsafeBytes(of: v.littleEndian) { append(contentsOf: $0) }
    }

    func loadLE(at offset: Int) -> UInt32 {
        UInt32(littleEndian: withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: UInt32.self) })
    }

    func loadLE(at offset: Int) -> UInt64 {
        UInt64(littleEndian: withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: UInt64.self) })
    }
}
