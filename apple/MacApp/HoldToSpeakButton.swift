import SwiftUI
import IntercomCore

/// Big press-and-hold talk control: mouse/trackpad hold via a zero-distance drag
/// gesture, plus spacebar hold while the popover is open.
struct HoldToSpeakButton: View {
    @Environment(AppState.self) private var state
    @State private var pressed = false
    @State private var keyMonitor: Any?

    var body: some View {
        Circle()
            .fill(state.isTalking ? Color.red : Color.accentColor)
            .frame(width: 130, height: 130)
            .overlay {
                VStack(spacing: 6) {
                    Image(systemName: "mic.fill")
                        .font(.system(size: 28))
                    Text(state.isTalking ? "Talking…" : "Hold to Speak")
                        .font(.callout.bold())
                }
                .foregroundStyle(.white)
            }
            .scaleEffect(pressed ? 0.94 : 1)
            .animation(.spring(duration: 0.15), value: pressed)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        guard !pressed else { return }
                        pressed = true
                        state.buttonDown()
                    }
                    .onEnded { _ in
                        pressed = false
                        state.buttonUp()
                    }
            )
            .onAppear(perform: installKeyMonitor)
            .onDisappear {
                removeKeyMonitor()
                pressed = false
                state.buttonUp() // popover dismissed mid-talk
            }
            .accessibilityLabel("Hold to speak")
    }

    private func installKeyMonitor() {
        guard keyMonitor == nil else { return }
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: [.keyDown, .keyUp]) { event in
            guard event.keyCode == 49 else { return event } // spacebar
            MainActor.assumeIsolated {
                if event.type == .keyDown {
                    if !event.isARepeat { state.buttonDown() }
                } else {
                    state.buttonUp()
                }
            }
            return nil
        }
    }

    private func removeKeyMonitor() {
        if let keyMonitor { NSEvent.removeMonitor(keyMonitor) }
        keyMonitor = nil
    }
}
