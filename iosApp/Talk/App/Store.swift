import SharedLogic
import SwiftUI

/// One shared controller's state, for SwiftUI: the screens that read it are drawn again when it changes. Each
/// controller has its own, so a change in one (someone typing) doesn't redraw screens that show another (Settings).
@MainActor
final class Store<Value>: ObservableObject {
    @Published private(set) var state: Value
    private var observation: ObservationHandle?

    /// `follow` is the controller's `observe`: it calls back on the main thread with every new state.
    init(_ current: Value, follow: (@escaping (Value) -> Void) -> ObservationHandle) {
        state = current
        observation = follow { [weak self] next in
            MainActor.assumeIsolated { self?.state = next }
        }
    }

    deinit {
        observation?.cancel()
    }
}

typealias ConnectionStore = Store<ConnectionState>
typealias EnrollmentStore = Store<EnrollmentState>
typealias ChatStore = Store<MessagingState>
typealias PairingStore = Store<PairingState>
typealias SettingsStore = Store<SettingsState>

/// A Kotlin group; SwiftUI has a `Group` of its own.
typealias TalkGroup = SharedLogic.Group
