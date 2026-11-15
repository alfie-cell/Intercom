import Foundation

/// Protocol constants. Normative reference: PROTOCOL.md at repo root.
public enum Wire {
    public static let magic0: UInt8 = 0x49 // "I"
    public static let magic1: UInt8 = 0x43 // "C"
    public static let version: UInt8 = 1

    public static let serviceType = "_intercom._udp"

    /// Audio format id 1: PCM 24 kHz mono s16le, 20 ms frames.
    public static let formatPCM24k: UInt8 = 1
    public static let sampleRate: Double = 24_000
    public static let frameSamples = 480
    public static let frameBytes = 960
    public static let frameDurationMicros: UInt64 = 20_000

    public static let startEndResends = 3
    public static let resendSpacingMillis = 20
    public static let talkTimeoutMicros: UInt64 = 1_000_000
    public static let maxTalkSeconds: TimeInterval = 60
    public static let defaultPlayoutDelayMillis = 250
    public static let pingCount = 5
    public static let pingSpacingMillis = 50
}

/// Monotonic microsecond clock (CLOCK_UPTIME_RAW == mach_absolute_time timebase),
/// plus conversions to/from mach host ticks for AVAudioTime scheduling.
public enum Monotonic {
    private static let timebase: mach_timebase_info_data_t = {
        var tb = mach_timebase_info_data_t()
        mach_timebase_info(&tb)
        return tb
    }()

    public static func nowMicros() -> UInt64 {
        clock_gettime_nsec_np(CLOCK_UPTIME_RAW) / 1_000
    }

    public static func hostTicks(fromMicros us: UInt64) -> UInt64 {
        us * 1_000 * UInt64(timebase.denom) / UInt64(timebase.numer)
    }

    public static func micros(fromHostTicks ticks: UInt64) -> UInt64 {
        ticks * UInt64(timebase.numer) / UInt64(timebase.denom) / 1_000
    }
}
