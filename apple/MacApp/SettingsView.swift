import SwiftUI
import ServiceManagement
import IntercomCore

struct SettingsView: View {
    @Environment(AppState.self) private var state
    @State private var name = ""
    @State private var code = ""
    @State private var launchAtLogin = SMAppService.mainApp.status == .enabled

    var body: some View {
        @Bindable var state = state
        Form {
            Section("This device") {
                Picker("Role", selection: $state.role) {
                    ForEach(Role.allCases) { Text($0.label).tag($0) }
                }
                .pickerStyle(.segmented)
                TextField("Device name", text: $name)
                    .onSubmit(commit)
                TextField("Group code", text: $code)
                    .onSubmit(commit)
                Text("Name and group code apply when you press Return. All your devices must share the same group code.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("Audio") {
                HStack {
                    Slider(value: Binding(get: { Double(state.delayMillis) },
                                          set: { state.delayMillis = Int($0) }),
                           in: 50...500, step: 10) {
                        Text("Sync delay")
                    }
                    Text("\(state.delayMillis) ms")
                        .monospacedDigit()
                        .frame(width: 56, alignment: .trailing)
                }
                Toggle("Chirp before each message", isOn: $state.chirp)
                if state.role == .receiver {
                    Toggle("Mute", isOn: $state.muted)
                }
            }

            Section {
                Toggle("Launch at login", isOn: $launchAtLogin)
                    .onChange(of: launchAtLogin) { _, enable in
                        do {
                            if enable {
                                try SMAppService.mainApp.register()
                            } else {
                                try SMAppService.mainApp.unregister()
                            }
                        } catch {
                            launchAtLogin = SMAppService.mainApp.status == .enabled
                        }
                    }
            }

            if state.role == .sender {
                Section("Receivers: \(state.receivers.count)") {
                    if state.receivers.isEmpty {
                        Text("None found. Check that every device is on the same Wi-Fi and the network allows device-to-device traffic (no client isolation / guest network).")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(state.receivers) { receiver in
                            Label(receiver.live ? receiver.name : "\(receiver.name) (standby)",
                                  systemImage: "hifispeaker.fill")
                        }
                    }
                }
            } else {
                Section {
                    if let error = state.statusError {
                        Label(error, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.red)
                    }
                    Text("A sleeping Mac can't play intercom. For a dedicated receiver, enable System Settings → Displays → Advanced → “Prevent automatic sleeping when the display is off”.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .formStyle(.grouped)
        .frame(width: 440)
        .onAppear {
            name = state.deviceName
            code = state.groupCode
        }
        .onDisappear(perform: commit)
    }

    private func commit() {
        state.setIdentity(deviceName: name, groupCode: code)
    }
}
