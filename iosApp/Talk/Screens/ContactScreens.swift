import SharedLogic
import SwiftUI
import UIKit

/// New chat: Add contact, New group, and the contacts to write to (docs/screens/chats.md).
struct NewChatScreen: View {
    @Binding var path: [Route]
    @EnvironmentObject var chats: ChatStore

    var body: some View {
        let contacts = chats.state.contacts.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        Page(title: L("new_chat_title")) {
            Card {
                CardRow(title: L("new_chat_add"), detail: L("new_chat_add_body"), action: { path.append(.addContact) }) {
                    Image(systemName: "qrcode").foregroundStyle(Palette.muted)
                }
                RowDivider()
                CardRow(title: L("new_chat_group"), detail: L("new_chat_group_body"), action: { path.append(.newGroup) }) {
                    Image(systemName: "person.2").foregroundStyle(Palette.muted)
                }
            }
            SectionLabel(L("new_chat_contacts"))
            if contacts.isEmpty {
                PageNote(L("new_chat_none"))
            } else {
                Card {
                    ForEach(Array(contacts.enumerated()), id: \.element.user) { index, contact in
                        Button {
                            // The chat takes New chat's place, so Back goes to Chats.
                            path = [.conversation(contact.user)]
                        } label: {
                            HStack(spacing: 12) {
                                Avatar(user: contact.user, name: contact.name, size: 40)
                                Text(contact.name).font(.rowTitle).foregroundStyle(Palette.onBackground)
                                Spacer()
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 10)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        if index < contacts.count - 1 { RowDivider() }
                    }
                }
            }
        }
    }
}

/// Add contact (docs/screens/add-contact.md): this phone's QR code to be scanned, or scanning theirs; one scan
/// pairs both once each confirmed. The steps are the shared PairingController's.
struct AddContactScreen: View {
    @Binding var path: [Route]
    @EnvironmentObject var model: TalkModel
    @EnvironmentObject var pairing: PairingStore
    @State var scanning = false

    private var controller: PairingController { model.ptt.pairing }

    var body: some View {
        let state = pairing.state
        Page(title: L("add_title")) {
            switch state.step {
            case let step as PairingStepScanned:
                result(title: L("add_scanned_title", step.user), text: L("add_scanned_body")) {
                    Button(L("add_pair")) { controller.pair() }.buttonStyle(.primary)
                    Button(L("add_cancel")) { controller.back() }.buttonStyle(.secondary)
                }
            case let step as PairingStepSending:
                result(title: L("add_sending", step.user), text: "") { ProgressView().frame(maxWidth: .infinity) }
            case let step as PairingStepWaiting:
                result(title: L("add_waiting_title", step.user), text: L("add_waiting_body")) {
                    Button(L("add_done")) { path.removeLast() }.buttonStyle(.primary)
                }
            case let step as PairingStepPaired:
                result(title: L("add_paired_title", step.user), text: L("add_paired_body")) {
                    Button(L("add_done")) { path = [.conversation(step.user)] }.buttonStyle(.primary)
                }
            case let step as PairingStepReview:
                result(title: L("add_review_title", step.user), text: L("add_review_body", step.user)) {
                    Button(L("add_pair")) { controller.accept() }.buttonStyle(.primary)
                    Button(L("add_decline")) { controller.decline() }.buttonStyle(.danger)
                }
            case let step as PairingStepFailed:
                result(title: L("add_failed_title"), text: Self.message(step.error)) {
                    Button(L("add_back")) { controller.back() }.buttonStyle(.primary)
                }
            default:
                showing(state)
            }
        }
        .onAppear {
            // A request being reviewed, or an invite link just opened, stays; else the screen shows this phone's code.
            if state.step is PairingStepShowing { controller.open() }
        }
        .onDisappear { if !path.contains(.addContact) { controller.leave() } }
        .sheet(isPresented: $scanning) {
            QrScanner { controller.scanned(text: $0) }
        }
    }

    @ViewBuilder
    private func showing(_ state: PairingState) -> some View {
        PageNote(L("add_intro"))
        VStack(spacing: 12) {
            if let code = state.code {
                QrCode(text: code).accessibilityLabel(L("add_code_label"))
                if let expires = state.codeExpiresAt {
                    Text(L("add_code_expires", Dates.time(expires.int64Value))).font(.caption13).foregroundStyle(Palette.muted)
                }
            } else if state.codeLoading {
                ProgressView().frame(width: 268, height: 268)
            } else {
                if let error = state.codeError {
                    PageNote(Self.message(error), color: Palette.error)
                }
                Button(L("add_code_retry")) { controller.open() }.buttonStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
        Button { scanning = true } label: { Label(L("add_scan"), systemImage: "qrcode.viewfinder") }.buttonStyle(.primary)
        Button { path.append(.invite) } label: { Label(L("add_invite"), systemImage: "envelope") }.buttonStyle(.secondary)
    }

    private func result<Actions: View>(title: String, text: String, @ViewBuilder actions: () -> Actions) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(.pageTitle).foregroundStyle(Palette.onBackground).padding(.top, 24)
            if !text.isEmpty { PageNote(text) }
            VStack(spacing: 10) { actions() }.padding(.top, 16)
        }
    }

    static func message(_ error: PairingError) -> String {
        switch error {
        case .notACode: return L("pair_error_not_a_code")
        case .newerVersion: return L("pair_error_newer_version")
        case .ownCode: return L("pair_error_own_code")
        case .otherServer: return L("pair_error_other_server")
        case .expired: return L("pair_error_expired")
        case .keysDiffer: return L("pair_error_keys_differ")
        case .noDevices: return L("pair_error_no_devices")
        case .blocked: return L("pair_error_blocked")
        case .notFound: return L("pair_error_not_found")
        case .rateLimited: return L("pair_error_rate_limited")
        case .notReady: return L("pair_error_not_ready")
        case .inviteForSomeoneElse: return L("pair_error_invite_someone_else")
        case .invalidEmail: return L("pair_error_invalid_email")
        case .invitesOff: return L("pair_error_invites_off")
        default: return L("pair_error_unreachable")
        }
    }
}

/// Invite a friend (docs/screens/invite.md): one link and a six-digit code that put the app on this server for
/// them and pair them with this user. The phone sends them itself (the share sheet); the server sends no mail.
struct InviteScreen: View {
    @EnvironmentObject var model: TalkModel
    @EnvironmentObject var pairing: PairingStore
    @EnvironmentObject var messages: Messages
    @State var email = ""
    @State var canceling: String?

    private var controller: PairingController { model.ptt.pairing }

    var body: some View {
        let state = pairing.state
        Page(title: L("invite_title")) {
            if let invite = state.invite {
                made(invite)
            } else {
                PageNote(L("invite_intro"))
                LabeledField(
                    label: L("invite_for_email"),
                    text: $email,
                    help: L("invite_for_email_help"),
                    error: state.inviteError.map(AddContactScreen.message),
                    keyboard: .emailAddress,
                    content: .emailAddress,
                    leftToRight: true
                )
                LoadingButton(title: L("invite_make"), loading: state.inviteLoading) { controller.makeInvite(email: email) }
            }
            waiting(state.waiting)
        }
        .onAppear { controller.refreshWaiting() }
        .onDisappear { controller.clearInvite() }
    }

    @ViewBuilder
    private func made(_ invite: MadeInvite) -> some View {
        let until = Dates.full(invite.expiresAt)
        let message = invite.message(until: until)
        VStack(spacing: 12) {
            QrCode(text: AppLinks.shared.web(link: invite.faceToFace)).accessibilityLabel(L("invite_code_label"))
            Text(L("invite_qr_note")).font(.caption13).foregroundStyle(Palette.muted).multilineTextAlignment(.center)
            Text(invite.email.map { L("invite_made_for", $0, until) } ?? L("invite_made_anyone", until))
                .font(.bodyText).foregroundStyle(Palette.onBackground).multilineTextAlignment(.center)
            Text(L("invite_passcode_label")).font(.caption13).foregroundStyle(Palette.muted).multilineTextAlignment(.center)
            Text(message.spacedPasscode)
                .font(.system(size: 30, weight: .bold, design: .monospaced))
                .foregroundStyle(Palette.onBackground)
                .environment(\.layoutDirection, .leftToRight)
        }
        .frame(maxWidth: .infinity)
        ShareLink(item: message.body, subject: Text(message.subject)) {
            Label(L("invite_share"), systemImage: "square.and.arrow.up")
        }
        .buttonStyle(.primary)
        Button {
            UIPasteboard.general.string = message.body
            messages.show(L("invite_copied"))
        } label: { Label(L("invite_copy"), systemImage: "doc.on.doc") }
            .buttonStyle(.secondary)
        Button(L("invite_another")) {
            email = ""
            controller.clearInvite()
            controller.refreshWaiting()
        }
        .buttonStyle(.secondary)
        Text(L("invite_note")).font(.caption13).foregroundStyle(Palette.muted)
    }

    @ViewBuilder
    private func waiting(_ invites: [WaitingInvite]) -> some View {
        if !invites.isEmpty {
            SectionLabel(L("invite_waiting"))
            Card {
                ForEach(Array(invites.enumerated()), id: \.element.id) { index, invite in
                    CardRow(
                        title: invite.email ?? L("invite_waiting_anyone"),
                        detail: L("invite_waiting_line", Dates.full(invite.createdAt), Dates.full(invite.expiresAt))
                    ) {
                        Button(L("invite_waiting_cancel")) { canceling = invite.id }.buttonStyle(TextButtonStyle(color: Palette.warning))
                    }
                    if index < invites.count - 1 { RowDivider() }
                }
            }
            .confirmationDialog(L("invite_waiting_cancel"), isPresented: Binding(get: { canceling != nil }, set: { if !$0 { canceling = nil } }), titleVisibility: .hidden) {
                Button(L("invite_waiting_cancel"), role: .destructive) {
                    if let id = canceling {
                        controller.cancelInvite(id: id)
                        messages.show(L("invite_waiting_canceled"))
                    }
                    canceling = nil
                }
            }
        }
    }
}

/// A contact's page (docs/screens/contacts.md): their name here, the safety number, block and remove.
struct ContactScreen: View {
    let user: String
    @Binding var path: [Route]
    @EnvironmentObject var model: TalkModel
    @EnvironmentObject var chats: ChatStore
    @EnvironmentObject var messages: Messages
    @State var renaming = false
    @State var nickname = ""
    @State var removing = false

    private var controller: MessagingController { model.ptt.messaging }

    var body: some View {
        let state = chats.state
        Page(title: "") {
            if let contact = IosViews.shared.contact(state: state, user: user) {
                page(contact, state: state)
            } else {
                PageNote(L("contact_gone"))
            }
        }
    }

    @ViewBuilder
    private func page(_ contact: Contact, state: MessagingState) -> some View {
        VStack(spacing: 8) {
            Avatar(user: contact.user, name: contact.name, size: 96)
            Text(contact.name).font(.pageTitle).foregroundStyle(Palette.onBackground)
            if let line = Lines.presence(IosViews.shared.presence(state: state, user: contact.user)) {
                Text(line).font(.caption13).foregroundStyle(Palette.muted)
            }
            Text(statusLine(contact)).font(.caption13).foregroundStyle(Palette.muted)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)

        if contact.changedKey != nil {
            HomeCard(title: L("contact_key_changed_title", contact.name), text: L("contact_key_changed_body"), action: L("contact_key_changed_action"), warning: true) {
                controller.acceptKeyChange(user: contact.user)
            }
            .padding(.horizontal, -16)
        }

        if contact.paired && !contact.blocked {
            Button { path = [.conversation(contact.user)] } label: {
                Label(L("contacts_action_message", contact.name), systemImage: "bubble.left")
            }
            .buttonStyle(.primary)
        }

        Card {
            if renaming {
                // The name is changed in its place on the page (style.md: no dialogs for what a row can hold).
                VStack(alignment: .leading, spacing: 10) {
                    LabeledField(label: L("contact_rename"), text: $nickname, help: L("contact_rename_none"), capitalization: .words)
                    HStack(spacing: 16) {
                        Button(L("contact_rename_save")) {
                            controller.renameContact(user: contact.user, name: nickname)
                            renaming = false
                        }
                        .buttonStyle(.quiet)
                        Button(L("contact_rename_cancel")) { renaming = false }.buttonStyle(TextButtonStyle(color: Palette.muted))
                    }
                }
                .padding(16)
            } else {
                CardRow(title: L("contact_rename"), detail: contact.nickname?.nilIfBlank ?? L("contact_rename_none"), action: {
                    nickname = contact.nickname ?? ""
                    renaming = true
                }) { Image(systemName: "pencil").foregroundStyle(Palette.muted) }
            }
            RowDivider()
            CardRow(title: L("contact_email"), detail: contact.shownEmail ?? L("contact_email_none"))
            RowDivider()
            CardRow(
                title: L("contact_safety"),
                detail: L(contact.verified ? "contact_safety_verified" : "contact_safety_unverified"),
                action: { path.append(.safety(contact.user)) }
            ) {
                Image(systemName: contact.verified ? "checkmark.shield" : "exclamationmark.shield")
                    .foregroundStyle(contact.verified ? Palette.accent : Palette.warning)
            }
        }

        Card {
            CardRow(
                title: L(contact.blocked ? "contact_unblock" : "contact_block", contact.name),
                detail: contact.blocked ? nil : L("contact_block_body"),
                titleColor: Palette.error,
                action: { setBlocked(contact, !contact.blocked) }
            )
            RowDivider()
            CardRow(title: L("contact_unpair", contact.name), detail: L("contact_unpair_body"), titleColor: Palette.error, action: { removing = true })
        }
        .confirmationDialog(L("contact_unpair_confirm", contact.name), isPresented: $removing, titleVisibility: .visible) {
            Button(L("contact_unpair_action"), role: .destructive) { unpair(contact) }
        }
    }

    private func statusLine(_ contact: Contact) -> String {
        if contact.blocked { return L("contact_blocked", contact.name) }
        if !contact.paired { return L("contact_waiting") }
        return LP("contact_paired_devices", Int(contact.devices))
    }

    private func setBlocked(_ contact: Contact, _ blocked: Bool) {
        let name = contact.name
        controller.setBlocked(user: contact.user, blocked: blocked) { result in
            if let failed = result as? ContactResultFailed {
                messages.error(L("contact_failed", failed.message))
            } else {
                messages.show(L(blocked ? "contact_done_blocked" : "contact_done_unblocked", name))
            }
        }
    }

    private func unpair(_ contact: Contact) {
        let name = contact.name
        controller.unpair(user: contact.user) { result in
            if let failed = result as? ContactResultFailed {
                messages.error(L("contact_failed", failed.message))
            } else {
                messages.show(L("contact_done_unpaired", name))
                path = []
            }
        }
    }
}

/// The safety number with a contact: sixty digits to compare in person, and their code to scan.
struct SafetyNumberScreen: View {
    let user: String
    @EnvironmentObject var model: TalkModel
    @EnvironmentObject var chats: ChatStore
    @EnvironmentObject var messages: Messages
    @State var number: SafetyNumberView?
    @State var failed = false
    @State var scanning = false

    private var controller: MessagingController { model.ptt.messaging }

    var body: some View {
        let contact = IosViews.shared.contact(state: chats.state, user: user)
        let name = contact?.name ?? user
        Page(title: L("safety_title")) {
            PageNote(L("safety_intro", name))
            if let number {
                VStack(spacing: 16) {
                    QrCode(text: number.code, size: 200)
                    // The digits are read left to right, in rows of four groups, in Hebrew too.
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 8) {
                        ForEach(Array(number.groups.enumerated()), id: \.offset) { _, group in
                            Text(group).font(.system(size: 18, weight: .medium, design: .monospaced)).foregroundStyle(Palette.onBackground)
                        }
                    }
                    .environment(\.layoutDirection, .leftToRight)
                }
                .frame(maxWidth: .infinity)
                .padding(16)
                .background(Palette.raised, in: RoundedRectangle(cornerRadius: Corner.card))
                if contact?.verified == true {
                    Label(L("safety_verified"), systemImage: "checkmark.shield").font(.rowTitle).foregroundStyle(Palette.accent)
                }
                Button { scanning = true } label: { Label(L("safety_scan"), systemImage: "qrcode.viewfinder") }.buttonStyle(.primary)
                Button(L(contact?.verified == true ? "safety_unmark" : "safety_mark")) {
                    controller.setVerified(user: user, verified: !(contact?.verified ?? false))
                }
                .buttonStyle(.secondary)
            } else if failed {
                PageNote(L("safety_loading_failed"), color: Palette.error)
            } else {
                ProgressView().frame(maxWidth: .infinity).padding(.top, 40)
            }
        }
        .onAppear {
            controller.safetyNumber(user: user) { view in
                number = view
                failed = view == nil
            }
        }
        .sheet(isPresented: $scanning) {
            QrScanner { text in
                controller.verifyScanned(user: user, text: text) { match in
                    if match.boolValue {
                        messages.show(L("safety_match", name))
                    } else {
                        messages.error(L("safety_mismatch", name))
                    }
                }
            }
        }
    }
}
