import Foundation

public enum Role: String, CaseIterable, Identifiable {
    case sender, receiver

    public var id: String { rawValue }
    public var label: String { self == .sender ? "Sender" : "Receiver" }
}

/// UserDefaults-backed settings. Reads supply defaults; writes persist immediately.
public enum ConfigStore {
    private static let d = UserDefaults.standard

    public static var role: Role {
        get { Role(rawValue: d.string(forKey: "role") ?? "") ?? .receiver }
        set { d.set(newValue.rawValue, forKey: "role") }
    }

    public static var deviceName: String {
        get {
            if let name = d.string(forKey: "deviceName"), !name.isEmpty { return name }
            return Host.current().localizedName ?? "Mac"
        }
        set { d.set(newValue, forKey: "deviceName") }
    }

    public static var groupCode: String {
        get { d.string(forKey: "groupCode") ?? "" }
        set { d.set(newValue, forKey: "groupCode") }
    }

    public static var delayMillis: Int {
        get { d.object(forKey: "delayMillis") == nil ? Wire.defaultPlayoutDelayMillis : d.integer(forKey: "delayMillis") }
        set { d.set(newValue, forKey: "delayMillis") }
    }

    public static var chirp: Bool {
        get { d.object(forKey: "chirp") == nil ? true : d.bool(forKey: "chirp") }
        set { d.set(newValue, forKey: "chirp") }
    }

    public static var muted: Bool {
        get { d.bool(forKey: "muted") }
        set { d.set(newValue, forKey: "muted") }
    }

    /// Debug: run sender and receiver stacks in one process for single-machine
    /// loopback testing. Enable with: defaults write <bundle id> dualRole -bool YES
    public static var dualRole: Bool {
        d.bool(forKey: "dualRole")
    }
}
