import SwiftUI
import IntercomCore

struct MenuContent: View {
    @Environment(AppState.self) private var state

    var body: some View {
        VStack(spacing: 12) {
            if state.role == .sender {
                sender
            } else {
                receiver
            }
            Divider()
            HStack {
                SettingsLink {
                    Text("Settings…")
                }
                Spacer()
                Button("Quit") { NSApp.terminate(nil) }
            }
            .controlSize(.small)
        }
        .padding(14)
        .frame(width: 250)
    }

    @ViewBuilder private var sender: some View {
        HoldToSpeakButton()
        if state.micDenied {
            Text("Microphone access denied — enable Intercom in System Settings → Privacy & Security → Microphone.")
                .font(.caption)
                .foregroundStyle(.red)
                .multilineTextAlignment(.center)
        }
        if let error = state.statusError {
            Text(error)
                .font(.caption)
                .foregroundStyle(.red)
                .multilineTextAlignment(.center)
        }
        Text(state.receivers.isEmpty
             ? "No receivers found.\nSame Wi-Fi? Client isolation off?"
             : "\(state.receivers.count) receiver\(state.receivers.count == 1 ? "" : "s")"
                + (state.receivers.contains { !$0.live }
                   ? " · \(state.receivers.filter { !$0.live }.count) in standby" : ""))
            .font(.caption)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
    }

    @ViewBuilder private var receiver: some View {
        @Bindable var state = state
        Image(state.activeTalkCount > 0 ? "CellTowerActive" : "CellTower")
            .resizable()
            .scaledToFit()
            .frame(width: 40, height: 40)
            .foregroundStyle(state.activeTalkCount > 0 ? Color.green : Color.secondary)
            .frame(height: 44)
        Text(state.activeTalkCount > 0 ? "Receiving…" : "Listening as “\(state.deviceName)”")
            .font(.callout)
            .multilineTextAlignment(.center)
        if let last = state.lastTalk {
            Text("Last: \(last)")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        if let error = state.statusError {
            Text(error)
                .font(.caption)
                .foregroundStyle(.red)
                .multilineTextAlignment(.center)
        }
        Toggle("Mute", isOn: $state.muted)
            .toggleStyle(.switch)
            .controlSize(.small)
    }
}
