import SharedLogic
import SwiftUI
import UIKit

/// The app's one object: the shared logic (IosPtt) with its host, and each controller's state for the screens.
/// The screens render state and call controller methods; nothing is decided here.
@MainActor
final class TalkModel: ObservableObject {
    let ptt: IosPtt
    let connection: ConnectionStore
    let enrollment: EnrollmentStore
    let chats: ChatStore
    let pairing: PairingStore
    let settings: SettingsStore
    let messages = Messages()
    let pictures: Pictures

    /// The enrollment wizard is on screen: a phone without an identity, or Register again.
    @Published var showWizard: Bool

    /// Pages over home, pushed by a link or a notification (HomeScreen follows it).
    @Published var opened: Route?

    private var inFront = false

    init() {
        let screen = UIScreen.main.nativeBounds.size
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        let ptt = IosPtt(
            host: TalkHost(),
            deviceName: UIDevice.current.name,
            appVersion: version,
            screenWidthPx: Int32(screen.width),
            screenHeightPx: Int32(screen.height)
        )
        self.ptt = ptt
        connection = Store(ptt.connection.currentState) { ptt.connection.observe(onChange: $0) }
        enrollment = Store(ptt.enrollment.currentState) { ptt.enrollment.observe(onChange: $0) }
        chats = Store(ptt.messaging.currentState) { ptt.messaging.observe(onChange: $0) }
        pairing = Store(ptt.pairing.currentState) { ptt.pairing.observe(onChange: $0) }
        settings = Store(ptt.settingsController.currentState) { ptt.settingsController.observe(onChange: $0) }
        pictures = Pictures(ptt: ptt)
        showWizard = !ptt.connection.currentState.enrolled
        // In its last 30 days the certificate is renewed; offline, it is tried again at the next start.
        ptt.renewal.renewIfDue(onDone: { result in
            if let failed = result as? RenewalResultFailed { NSLog("Talk: renewing the certificate: %@", failed.message) }
        })
    }

    var enrolled: Bool { connection.state.enrolled }

    /// The app came to the front: the connection check, keys and /ws.
    func cameToFront() {
        inFront = true
        guard ptt.connection.currentState.enrolled, !showWizard else { return }
        ptt.connection.connect()
        ptt.messaging.start()
    }

    /// The app left the screen: /ws closes (a push wakes the phone, once notifications are built).
    func leftFront() {
        inFront = false
        ptt.messaging.stop()
        ptt.connection.stop()
    }

    /// The wizard's last button: on to Chats.
    func openApp() {
        showWizard = false
        ptt.enrollment.reset()
        if inFront {
            ptt.connection.connect()
            ptt.messaging.start()
        }
    }

    /// Settings › Register again, and home's card when the server refuses this phone.
    func registerAgain() {
        ptt.enrollment.reset()
        showWizard = true
    }

    /// A `ptt://` link opened from the camera, a mail or a chat: an enrollment link, an invite or a Link a phone code.
    func open(url: URL) {
        // A link from a mail or a chat is the web form of a ptt:// link (AppLinks).
        let text = AppLinks.shared.toApp(text: url.absoluteString)
        if !ptt.connection.currentState.enrolled {
            if ptt.enrollment.openPasted(text: text) != PasteResult.nothing { showWizard = true }
            return
        }
        // An enrolled phone: an invite of this server pairs with its inviter; an enrollment link is ignored (it
        // would show the wizard with its code used).
        if ptt.pairing.openInvite(text: text) { opened = .addContact }
    }
}

/// Contacts' and groups' pictures, read once from the shared store and kept while the app runs.
@MainActor
final class Pictures: ObservableObject {
    private let ptt: IosPtt
    private var images: [String: UIImage] = [:]
    private var asked: Set<String> = []
    @Published private var version = 0

    init(ptt: IosPtt) { self.ptt = ptt }

    /// The picture of `user` whose id is `id` (it changes when the picture does); nil until read, or when none.
    func image(user: String, id: String?) -> UIImage? {
        guard let id else { return nil }
        let key = user + "/" + id
        if let image = images[key] { return image }
        if asked.insert(key).inserted {
            ptt.pictureOf(user: user) { [weak self] data in
                guard let data, let image = UIImage(data: data) else { return }
                MainActor.assumeIsolated {
                    self?.images[key] = image
                    self?.version += 1
                }
            }
        }
        return nil
    }
}

/// The pages over home.
enum Route: Hashable {
    case conversation(String)
    case contact(String)
    case safety(String)
    case newChat
    case addContact
    case invite
    case newGroup
    case group(String)
    case settings
    case devices
    case linkPhone
}
