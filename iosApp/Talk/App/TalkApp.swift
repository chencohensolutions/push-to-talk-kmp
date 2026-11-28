import SwiftUI

/// Talk on iOS: SwiftUI screens over the shared Kotlin logic (docs/ios.md). Everything the app knows and does is in
/// `sharedLogic`; these screens draw its state and pass on what the user does.
@main
struct TalkApp: App {
    @StateObject private var model = TalkModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .environmentObject(model.connection)
                .environmentObject(model.enrollment)
                .environmentObject(model.chats)
                .environmentObject(model.pairing)
                .environmentObject(model.settings)
                .environmentObject(model.messages)
                .environmentObject(model.pictures)
                .tint(Palette.inverse)
                .onOpenURL { model.open(url: $0) }
                .onChange(of: phase, initial: true) { _, now in
                    switch now {
                    case .active: model.cameToFront()
                    case .background: model.leftFront()
                    default: break
                    }
                }
        }
    }
}

/// The wizard for a phone that isn't registered, else home; the message bar over both.
struct RootView: View {
    @EnvironmentObject private var model: TalkModel

    var body: some View {
        ZStack(alignment: .bottom) {
            if model.showWizard {
                EnrollmentWizard()
                    .transition(.opacity)
            } else {
                HomeScreen()
                    .transition(.opacity)
            }
            MessageBar()
        }
        .animation(.easeInOut(duration: 0.2), value: model.showWizard)
        .background(Palette.background.ignoresSafeArea())
    }
}
