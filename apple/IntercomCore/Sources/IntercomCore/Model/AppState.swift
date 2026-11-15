import Foundation
import Observation
import AVFoundation

/// Observable hub: owns the per-role engine/network stacks and exposes UI state.
/// Main-actor; the network/audio layers call back in via Task { @MainActor }.
@Observable @MainActor
public final class AppState {
    // MARK: Settings (persisted)

    public var role: Role {
        didSet {
            guard oldValue != role else { return }
            ConfigStore.role = role
            rebuild()
        }
    }
    public private(set) var deviceName: String
    public private(set) var groupCode: String
    public var delayMillis: Int {
        didSet { ConfigStore.delayMillis = delayMillis; playback?.playoutDelayMillis = delayMillis }
    }
    public var chirp: Bool {
        didSet { ConfigStore.chirp = chirp; playback?.chirpEnabled = chirp }
    }
    public var muted: Bool {
        didSet { ConfigStore.muted = muted; playback?.muted = muted }
    }

    // MARK: Live state

    public private(set) var receivers: [DiscoveredReceiver] = []
    public private(set) var isTalking = false
    public private(set) var activeTalkCount = 0
    public private(set) var lastTalk: String?
    public private(set) var micDenied = false
    public private(set) var statusError: String?

    /// Asset-catalog template image for the menubar (Material Symbols "cell_tower").
    public var menuIcon: String {
        isTalking || activeTalkCount > 0 ? "CellTowerActive" : "CellTower"
    }

    // MARK: Stacks

    private var browser: ReceiverBrowser?
    private var discovered: [DiscoveredReceiver] = []
    private var transport: SenderTransport?
    private var capture: CaptureEngine?
    private var listener: ReceiverListener?
    private var playback: PlaybackEngine?
    private var talkCapTimer: Timer?
    private var activity: NSObjectProtocol?
    private var pressGeneration = 0

    public init() {
        role = ConfigStore.role
        deviceName = ConfigStore.deviceName
        groupCode = ConfigStore.groupCode
        delayMillis = ConfigStore.delayMillis
        chirp = ConfigStore.chirp
        muted = ConfigStore.muted
        rebuild()
    }

    /// Name/group changes rebuild the network stacks, so Settings commits them
    /// explicitly (on Return) rather than per keystroke.
    public func setIdentity(deviceName newName: String, groupCode newCode: String) {
        let name = newName.trimmingCharacters(in: .whitespacesAndNewlines)
        let nameChanged = !name.isEmpty && name != deviceName
        guard nameChanged || newCode != groupCode else { return }
        if nameChanged {
            deviceName = name
            ConfigStore.deviceName = name
        }
        groupCode = newCode
        ConfigStore.groupCode = newCode
        rebuild()
    }

    // MARK: Talk button

    public func buttonDown() {
        guard transport != nil, !isTalking else { return }
        pressGeneration += 1
        let gen = pressGeneration
        Task { @MainActor in
            let granted = await AVCaptureDevice.requestAccess(for: .audio)
            guard granted else {
                self.micDenied = true
                return
            }
            self.micDenied = false
            guard gen == self.pressGeneration, !self.isTalking, let transport = self.transport else { return }
            let capture = CaptureEngine()
            capture.onFrame = { [weak transport] frame in transport?.sendFrame(frame) }
            do {
                try capture.start()
            } catch {
                self.statusError = error.localizedDescription
                return
            }
            self.statusError = nil
            self.capture = capture
            self.isTalking = true
            transport.startTalk()
            self.beginActivityIfNeeded()
            self.talkCapTimer = Timer.scheduledTimer(withTimeInterval: Wire.maxTalkSeconds, repeats: false) { [weak self] _ in
                Task { @MainActor in self?.buttonUp() }
            }
        }
    }

    public func buttonUp() {
        pressGeneration += 1
        guard isTalking else { return }
        isTalking = false
        talkCapTimer?.invalidate()
        talkCapTimer = nil
        capture?.stop()
        capture = nil
        transport?.endTalk()
        endActivityIfIdle()
    }

    // MARK: Stack lifecycle

    private func rebuild() {
        teardown()
        if role == .sender || ConfigStore.dualRole { buildSender() }
        if role == .receiver || ConfigStore.dualRole { buildReceiver() }
    }

    private func teardown() {
        if isTalking { buttonUp() }
        browser?.stop()
        browser = nil
        transport = nil
        listener?.stop()
        listener = nil
        playback?.stop()
        playback = nil
        receivers = []
        discovered = []
        activeTalkCount = 0
        statusError = nil
        endActivityIfIdle()
    }

    private func buildSender() {
        let transport = SenderTransport(deviceName: deviceName, groupCode: groupCode)
        transport.onResolved = { r, host, port in
            Task { @MainActor in KnownReceivers.remember(name: r.name, grpTag: r.grpTag, host: host, port: port) }
        }
        self.transport = transport
        let browser = ReceiverBrowser()
        browser.onUpdate = { [weak self] list in
            MainActor.assumeIsolated {
                self?.discovered = list
                self?.publishTargets()
            }
        }
        self.browser = browser
        browser.start()
        publishTargets()
    }

    /// Live receivers plus remembered ones that have stopped answering discovery.
    private func publishTargets() {
        let mine = GroupAuth.grpTag(groupCode: groupCode)
        func inGroup(_ r: DiscoveredReceiver) -> Bool { r.grpTag == nil || r.grpTag == mine }
        let live = discovered.filter(inGroup)
        let liveNames = Set(live.map(\.name))
        let standby = KnownReceivers.recent().filter { inGroup($0) && !liveNames.contains($0.name) }
        receivers = live + standby
        transport?.updateReceivers(receivers)
    }

    private func buildReceiver() {
        let playback = PlaybackEngine()
        playback.playoutDelayMillis = delayMillis
        playback.chirpEnabled = chirp
        playback.muted = muted
        self.playback = playback
        do {
            try playback.start()
        } catch {
            statusError = "Audio output failed: \(error.localizedDescription)"
        }

        let listener = ReceiverListener(deviceName: deviceName, groupCode: groupCode)
        listener.onTalkStart = { [weak self, weak playback] talk in
            playback?.beginTalk(talkID: talk.talkID, t0: talk.t0)
            Task { @MainActor in
                guard let self else { return }
                self.activeTalkCount += 1
                self.lastTalk = "\(talk.senderName) · \(Date.now.formatted(date: .omitted, time: .shortened))"
                self.beginActivityIfNeeded()
            }
        }
        listener.onAudio = { [weak playback] id, ts, pcm in
            playback?.addFrame(talkID: id, ts: ts, pcm: pcm)
        }
        listener.onOffset = { [weak playback] id, offset in
            playback?.setOffset(talkID: id, offsetMicros: offset)
        }
        listener.onTalkEnd = { [weak self, weak playback] id in
            playback?.endTalk(talkID: id)
            Task { @MainActor in
                guard let self else { return }
                self.activeTalkCount = max(0, self.activeTalkCount - 1)
                self.endActivityIfIdle()
            }
        }
        listener.onStateChange = { [weak self] running, error in
            Task { @MainActor in
                guard let self else { return }
                if let error { self.statusError = "Network: \(error)" }
                else if running { self.statusError = nil }
            }
        }
        self.listener = listener
        do {
            try listener.start()
        } catch {
            statusError = "Can't listen: \(error.localizedDescription)"
        }
    }

    // MARK: App Nap suppression during live audio

    private func beginActivityIfNeeded() {
        guard activity == nil else { return }
        activity = ProcessInfo.processInfo.beginActivity(options: [.userInitiated, .latencyCritical],
                                                         reason: "Live intercom audio")
    }

    private func endActivityIfIdle() {
        guard !isTalking, activeTalkCount == 0, let token = activity else { return }
        ProcessInfo.processInfo.endActivity(token)
        activity = nil
    }
}
