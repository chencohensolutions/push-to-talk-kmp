import SharedLogic
import SwiftUI

/// Home: the Chats list with what needs doing above it, and the pages pushed over it (docs/screens/chats.md).
struct HomeScreen: View {
    @EnvironmentObject var model: TalkModel
    @EnvironmentObject var connection: ConnectionStore
    @EnvironmentObject var chats: ChatStore
    @EnvironmentObject var messages: Messages
    @State var path: [Route] = []
    @State var linking = false

    var body: some View {
        NavigationStack(path: $path) {
            list
                .navigationTitle(L("chats_title"))
                .navigationBarTitleDisplayMode(.large)
                .toolbarBackground(Palette.background, for: .navigationBar)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button { path.append(.settings) } label: {
                            Avatar(user: "", name: model.ptt.myUser, size: 32)
                        }
                        .accessibilityLabel(L("chats_profile"))
                    }
                    ToolbarItem(placement: .principal) {
                        if connection.state.health == ServerHealth.offline { OfflinePill() }
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button { path.append(.newChat) } label: {
                            Image(systemName: "square.and.pencil").font(.system(size: 18, weight: .semibold))
                        }
                        .accessibilityLabel(L("chats_new"))
                    }
                }
                .navigationDestination(for: Route.self) { route in
                    destination(route)
                }
        }
        .onChange(of: model.opened) { _, route in
            guard let route else { return }
            path.append(route)
            model.opened = nil
        }
        .sheet(isPresented: $linking) {
            QrScanner { text in
                model.ptt.messaging.linkWithScanned(text: text) { ok in
                    if !ok.boolValue { messages.error(L("chats_link_wrong_code")) }
                }
            }
        }
    }

    @ViewBuilder
    private func destination(_ route: Route) -> some View {
        switch route {
        case .conversation(let chat): ConversationScreen(chat: chat, path: $path)
        case .contact(let user): ContactScreen(user: user, path: $path)
        case .safety(let user): SafetyNumberScreen(user: user)
        case .newChat: NewChatScreen(path: $path)
        case .addContact: AddContactScreen(path: $path)
        case .invite: InviteScreen()
        case .newGroup: NewGroupScreen(path: $path)
        case .group(let id): GroupScreen(id: id, path: $path)
        case .settings: SettingsScreen(path: $path)
        case .devices: DevicesScreen(path: $path)
        case .linkPhone: LinkPhoneScreen()
        }
    }

    private var list: some View {
        let state = chats.state
        let me = model.ptt.myUser
        let rows = IosViews.shared.chatRows(state: state, me: me)
        return ScrollView {
            LazyVStack(spacing: 0) {
                cards(state, me: me)
                if rows.isEmpty {
                    EmptyState(icon: "bubble.left.and.bubble.right", text: L("chats_empty")).padding(.top, 96)
                } else {
                    ForEach(rows, id: \.chat) { row in
                        Button { path.append(.conversation(row.chat)) } label: {
                            ChatRowView(row: row)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.bottom, 24)
        }
        .background(Palette.background.ignoresSafeArea())
        .refreshable {
            model.ptt.connection.connect()
            model.ptt.messaging.refreshContacts()
        }
    }

    /// What needs doing, above the chats: register again, link this phone, group invitations, pair requests.
    @ViewBuilder
    private func cards(_ state: MessagingState, me: String) -> some View {
        if connection.state.health == ServerHealth.rejected {
            HomeCard(title: L("home_rejected_title"), text: L("home_rejected_body"), action: L("home_rejected_action"), warning: true) {
                model.registerAgain()
            }
        }
        if state.keys is KeysStateLinking {
            HomeCard(title: L("chats_linking_title"), text: L("chats_linking_body"))
        }
        if state.keys is KeysStateOtherIdentity {
            HomeCard(title: L("chats_link_here_title"), text: L("chats_link_here_body"), action: L("chats_link_here_action")) {
                linking = true
            }
            HomeCard(title: L("chats_other_identity_title"), text: L("chats_other_identity_body"), action: L("chats_other_identity_action"), warning: true) {
                model.ptt.messaging.replaceIdentityKey { result in
                    if let failed = result as? ContactResultFailed { messages.error(L("contact_failed", failed.message)) }
                }
            }
        }
        if let failed = state.keys as? KeysStateFailed {
            PageNote(L("chats_keys_failed", failed.message)).padding(.horizontal, 20).padding(.vertical, 4)
        }
        ForEach(IosViews.shared.groupInvitations(state: state, me: me), id: \.id) { group in
            HomeCard(title: L("chats_group_invite_title", group.owner, Lines.groupName(group)), text: L("chats_group_invite_body"), action: L("chats_group_invite_action")) {
                path.append(.group(group.id))
            }
        }
        ForEach(state.requests, id: \.user) { request in
            HomeCard(title: L("chats_request_title", request.user), text: L("chats_request_body"), action: L("chats_request_action")) {
                model.ptt.pairing.review(user: request.user)
                path.append(.addContact)
            }
        }
    }
}

/// One chat in the list: the circle, the name, the last message's line, its time and how many are unread.
struct ChatRowView: View {
    let row: ChatRow

    var body: some View {
        HStack(spacing: 12) {
            ZStack(alignment: .bottomTrailing) {
                Avatar(user: row.chat, name: name, size: 52, group: row.group != nil)
                if row.online {
                    Circle().fill(Palette.accent).frame(width: 13, height: 13)
                        .overlay(Circle().strokeBorder(Palette.background, lineWidth: 2))
                }
            }
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(name).font(.rowTitle).foregroundStyle(Palette.onBackground).lineLimit(1)
                    // The shield marks only contacts whose safety number wasn't compared yet.
                    if let contact = row.contact, contact.paired, !contact.verified {
                        Image(systemName: "exclamationmark.shield").font(.system(size: 13)).foregroundStyle(Palette.warning)
                            .accessibilityLabel(L("contact_safety_unverified"))
                    }
                    Spacer(minLength: 4)
                    if let last = row.last {
                        Text(Dates.short(last.at)).font(.small).foregroundStyle(row.unread > 0 ? Palette.inverse : Palette.muted)
                    }
                }
                HStack(spacing: 6) {
                    Text(line.text).font(.bodyText).foregroundStyle(line.warning ? Palette.warning : Palette.muted).lineLimit(1)
                    Spacer(minLength: 4)
                    if row.unread > 0 {
                        Text(String(row.unread))
                            .font(.small.weight(.semibold))
                            .foregroundStyle(Palette.onInverse)
                            .padding(.horizontal, 7)
                            .frame(minWidth: 22, minHeight: 22)
                            .background(Palette.inverse, in: Capsule())
                    }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private var name: String {
        if let group = row.group { return Lines.groupName(group) }
        return row.contact?.name ?? row.chat
    }

    private var line: (text: String, warning: Bool) {
        if let group = row.group {
            if group.gone { return (L("group_gone_short"), false) }
            if let last = row.last {
                let preview = Lines.preview(last)
                return (last.outgoing ? L("chats_you", preview) : "\(last.author ?? ""): \(preview)", false)
            }
            return (L("group_members_count", group.readers.count), false)
        }
        guard let contact = row.contact else { return ("", false) }
        if contact.blocked { return (L("chats_blocked"), false) }
        if contact.changedKey != nil { return (L("chats_key_changed"), true) }
        if !contact.paired { return (L("chats_waiting"), false) }
        if let last = row.last {
            let preview = Lines.preview(last)
            return (last.outgoing ? L("chats_you", preview) : preview, false)
        }
        return (L("chats_paired"), false)
    }
}
