import Foundation
import AVFoundation

/// Mic capture → 24 kHz mono s16le, emitted as exact 960-byte / 20 ms frames,
/// in order, on the audio tap thread.
public final class CaptureEngine {
    public var onFrame: ((Data) -> Void)?

    private let engine = AVAudioEngine()
    private var converter: AVAudioConverter?
    private var pending = Data()
    private let outFormat = AVAudioFormat(commonFormat: .pcmFormatInt16,
                                          sampleRate: Wire.sampleRate,
                                          channels: 1, interleaved: true)!

    public init() {}

    public func start() throws {
        pending.removeAll(keepingCapacity: true)
        let input = engine.inputNode
        let inFormat = input.outputFormat(forBus: 0)
        guard inFormat.sampleRate > 0, inFormat.channelCount > 0 else {
            throw NSError(domain: "Intercom", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "No audio input device"])
        }
        guard let conv = AVAudioConverter(from: inFormat, to: outFormat) else {
            throw NSError(domain: "Intercom", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "Unsupported input format"])
        }
        converter = conv
        input.installTap(onBus: 0, bufferSize: 1024, format: inFormat) { [weak self] buffer, _ in
            self?.consume(buffer)
        }
        engine.prepare()
        try engine.start()
    }

    public func stop() {
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        converter = nil
    }

    private func consume(_ buffer: AVAudioPCMBuffer) {
        guard let conv = converter else { return }
        let ratio = outFormat.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 64
        guard let out = AVAudioPCMBuffer(pcmFormat: outFormat, frameCapacity: capacity) else { return }
        var fed = false
        var convError: NSError?
        let status = conv.convert(to: out, error: &convError) { _, outStatus in
            if fed {
                outStatus.pointee = .noDataNow
                return nil
            }
            fed = true
            outStatus.pointee = .haveData
            return buffer
        }
        guard status != .error, out.frameLength > 0, let samples = out.int16ChannelData else { return }
        pending.append(Data(bytes: samples[0], count: Int(out.frameLength) * 2))
        while pending.count >= Wire.frameBytes {
            onFrame?(Data(pending.prefix(Wire.frameBytes)))
            pending.removeFirst(Wire.frameBytes)
        }
    }
}
