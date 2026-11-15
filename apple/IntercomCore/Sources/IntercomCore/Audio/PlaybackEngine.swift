import Foundation
import AVFoundation

/// Receiver playback: one time-indexed ring buffer + AVAudioSourceNode per active
/// talk (simultaneous senders mix in the main mixer), with playout scheduled at
/// sender_ts − clock_offset + D per PROTOCOL.md. Control methods are safe to call
/// from any thread; engine graph mutations hop to the main queue.
public final class PlaybackEngine {
    public var playoutDelayMillis = Wire.defaultPlayoutDelayMillis
    public var chirpEnabled = true
    public var muted = false {
        didSet { engine.mainMixerNode.outputVolume = muted ? 0 : 1 }
    }

    private let engine = AVAudioEngine()
    private let chirpPlayer = AVAudioPlayerNode()
    private let format = AVAudioFormat(standardFormatWithSampleRate: Wire.sampleRate, channels: 1)!
    private lazy var chirpBuffer = Self.makeChirp(format: format)
    private var streams: [UInt32: TalkStream] = [:]
    private let streamsLock = NSLock()

    public init() {}

    public func start() throws {
        engine.attach(chirpPlayer)
        engine.connect(chirpPlayer, to: engine.mainMixerNode, format: format)
        try engine.start()
        chirpPlayer.play()
    }

    public func stop() {
        engine.stop()
        streamsLock.lock()
        streams.removeAll()
        streamsLock.unlock()
    }

    public func beginTalk(talkID: UInt32, t0: UInt64) {
        let stream = TalkStream(t0: t0, delayMicros: Int64(playoutDelayMillis) * 1_000, format: format)
        streamsLock.lock()
        streams[talkID] = stream
        streamsLock.unlock()
        let chirp = chirpEnabled
        DispatchQueue.main.async {
            guard self.engine.isRunning else { return }
            self.engine.attach(stream.node)
            self.engine.connect(stream.node, to: self.engine.mainMixerNode, format: self.format)
            if chirp {
                self.chirpPlayer.scheduleBuffer(self.chirpBuffer, completionHandler: nil)
            }
        }
    }

    public func setOffset(talkID: UInt32, offsetMicros: Int64) {
        stream(talkID)?.updateOffset(offsetMicros)
    }

    public func addFrame(talkID: UInt32, ts: UInt64, pcm: Data) {
        stream(talkID)?.write(ts: ts, pcm: pcm)
    }

    public func endTalk(talkID: UInt32) {
        guard let stream = stream(talkID) else { return }
        // Let the playout tail (delay + margin) drain before tearing down the node.
        let grace = Double(playoutDelayMillis + 500) / 1_000
        DispatchQueue.main.asyncAfter(deadline: .now() + grace) {
            self.streamsLock.lock()
            self.streams[talkID] = nil
            self.streamsLock.unlock()
            if self.engine.attachedNodes.contains(stream.node) {
                self.engine.detach(stream.node)
            }
        }
    }

    private func stream(_ talkID: UInt32) -> TalkStream? {
        streamsLock.lock()
        defer { streamsLock.unlock() }
        return streams[talkID]
    }

    /// Classic intercom lead-in: two short tones, faded, ~130 ms total.
    private static func makeChirp(format: AVAudioFormat) -> AVAudioPCMBuffer {
        let sr = format.sampleRate
        let toneLen = Int(sr * 0.065)
        let total = toneLen * 2
        let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(total))!
        buf.frameLength = AVAudioFrameCount(total)
        let out = buf.floatChannelData![0]
        for (tone, freq) in [880.0, 1174.66].enumerated() {
            for i in 0..<toneLen {
                let t = Double(i) / sr
                let fade = min(1, min(Double(i), Double(toneLen - i)) / (sr * 0.008))
                out[tone * toneLen + i] = Float(sin(2 * .pi * freq * t) * 0.25 * fade)
            }
        }
        return buf
    }
}

/// One talk's audio: a ring buffer indexed on the sender's sample timeline, drained
/// by an AVAudioSourceNode that maps render host time → sender time via the synced
/// clock offset. The offset locks at the first audible sample so later ping results
/// can't cause a mid-talk jump.
final class TalkStream {
    let node: AVAudioSourceNode

    private static let capacity = Int(Wire.sampleRate) * 4
    private let ring: UnsafeMutablePointer<Int16>
    private let t0: UInt64
    private let delayMicros: Int64
    private var offsetMicros: Int64        // sender clock − receiver clock
    private var offsetLocked = false
    private var highestWritten = 0         // sample index on the talk timeline
    private var readPos: Int?              // nil until playout begins
    private var lock = os_unfair_lock_s()

    init(t0: UInt64, delayMicros: Int64, format: AVAudioFormat) {
        self.t0 = t0
        self.delayMicros = delayMicros
        // Fallback offset (play-on-arrival): assume TALK_START arrived instantly,
        // i.e. sender time t0 == receiver time now.
        self.offsetMicros = Int64(bitPattern: t0) - Int64(bitPattern: Monotonic.nowMicros())
        let ring = UnsafeMutablePointer<Int16>.allocate(capacity: Self.capacity)
        ring.initialize(repeating: 0, count: Self.capacity)
        self.ring = ring

        var render: AVAudioSourceNodeRenderBlock = { _, _, _, _ in noErr }
        self.node = AVAudioSourceNode(format: format) { isSilence, timestamp, frameCount, audioBufferList in
            render(isSilence, timestamp, frameCount, audioBufferList)
        }
        render = { [unowned self] isSilence, timestamp, frameCount, audioBufferList in
            self.render(isSilence: isSilence, timestamp: timestamp,
                        frameCount: Int(frameCount), audioBufferList: audioBufferList)
        }
    }

    deinit {
        ring.deallocate()
    }

    func updateOffset(_ newOffset: Int64) {
        os_unfair_lock_lock(&lock)
        if !offsetLocked { offsetMicros = newOffset }
        os_unfair_lock_unlock(&lock)
    }

    func write(ts: UInt64, pcm: Data) {
        guard ts >= t0 else { return }
        let start = Int((ts - t0) * 3 / 125) // µs → samples at 24 kHz
        pcm.withUnsafeBytes { raw in
            let src = raw.bindMemory(to: Int16.self)
            os_unfair_lock_lock(&lock)
            if start > highestWritten {
                // Lost frames become silence, and stale ring content from a
                // previous lap never leaks into gaps.
                for i in 0..<min(start - highestWritten, Self.capacity) {
                    ring[(highestWritten + i) % Self.capacity] = 0
                }
            }
            for i in 0..<src.count {
                ring[(start + i) % Self.capacity] = src[i]
            }
            highestWritten = max(highestWritten, start + src.count)
            os_unfair_lock_unlock(&lock)
        }
    }

    private func render(isSilence: UnsafeMutablePointer<ObjCBool>,
                        timestamp: UnsafePointer<AudioTimeStamp>,
                        frameCount: Int,
                        audioBufferList: UnsafeMutablePointer<AudioBufferList>) -> OSStatus {
        let buffers = UnsafeMutableAudioBufferListPointer(audioBufferList)
        guard let mData = buffers[0].mData else { return noErr }
        let out = mData.assumingMemoryBound(to: Float.self)

        os_unfair_lock_lock(&lock)
        if readPos == nil {
            let hostTicks = timestamp.pointee.mFlags.contains(.hostTimeValid)
                ? timestamp.pointee.mHostTime : mach_absolute_time()
            let localMicros = Int64(bitPattern: Monotonic.micros(fromHostTicks: hostTicks))
            // Frame playing now is stamped ts = local + offset − delay (PROTOCOL.md).
            let senderMicros = localMicros + offsetMicros - delayMicros
            let idx = Int(((senderMicros - Int64(bitPattern: t0)) * 3).quotientAndRemainder(dividingBy: 125).quotient)
            if idx + frameCount > 0 {
                readPos = idx
                offsetLocked = true
            }
        }
        if let pos = readPos {
            for k in 0..<frameCount {
                let i = pos + k
                out[k] = (i >= 0 && i < highestWritten)
                    ? Float(ring[i % Self.capacity]) / 32_768
                    : 0
            }
            readPos = pos + frameCount
            isSilence.pointee = ObjCBool(pos >= highestWritten)
        } else {
            for k in 0..<frameCount { out[k] = 0 }
            isSilence.pointee = true
        }
        os_unfair_lock_unlock(&lock)
        return noErr
    }
}
