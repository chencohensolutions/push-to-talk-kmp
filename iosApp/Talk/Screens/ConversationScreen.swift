import SharedLogic
import SwiftUI
import UIKit

/// A conversation with a contact or in a group (docs/screens/contacts.md): the messages, oldest at the top, and the
/// line to write in. My own messages show how far they got by their look alone: a dashed outline while sending, a
/// solid one once the server has it, filled when it was received.
struct ConversationScreen: View {
    let chat: String
    @Binding var path: [Route]
    @EnvironmentObject var model: TalkModel
    @EnvironmentObject var chats: ChatStore
    @EnvironmentObject var messages: Messages
    /// How many of the chat's last messages are drawn; more when scrolled to their top.
    @State var shown: Int32 = 120
    @State var text = ""
    @State var lastTypingAt: Int64 = 0
    @FocusState var writing: Bool

    private var controller: MessagingController { model.ptt.messaging }
    private var isGroup: Bool { IosViews.shared.isGroup(chat: chat) }

    var body: some View {
        let state = chats.state
        let contact = IosViews.shared.contact(state: state, user: chat)
        let group = isGroup ? IosViews.shared.group(state: state, chat: chat) : nil
        let list = IosViews.shared.messages(state: state, chat: chat, limit: shown)
        let total = IosViews.shared.messageCount(state: state, chat: chat)
        VStack(spacing: 0) {
            banner(contact: contact, group: group)
            MessageList(
                chat: chat,
                messages: list,
                hasEarlier: Int(total) > list.count,
                isGroup: isGroup,
                state: state,
                loadEarlier: { shown += 120 },
                retry: { controller.retry(id: $0) },
                delete: { id, everyone in controller.deleteMessages(ids: [id], forEveryone: everyone) }
            )
            if canWrite(contact: contact, group: group) {
                composer
            }
        }
        .background(Palette.background.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Palette.background, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Button {
                    path.append(isGroup ? .group(TalkGroup.companion.idOf(chat: chat) ?? chat) : .contact(chat))
                } label: {
                    header(state: state, contact: contact, group: group)
                }
                .buttonStyle(.plain)
            }
        }
        .onAppear { controller.openChat(user: chat) }
        .onDisappear {
            if !text.isEmpty { controller.typing(user: chat, typing: false) }
            controller.openChat(user: nil)
        }
    }

    // MARK: Header

    private func header(state: MessagingState, contact: Contact?, group: TalkGroup?) -> some View {
        let name = group.map(Lines.groupName) ?? contact?.name ?? chat
        return HStack(spacing: 10) {
            Avatar(user: chat, name: name, size: 34, group: isGroup)
            VStack(alignment: .leading, spacing: 0) {
                Text(name).font(.rowTitle).foregroundStyle(Palette.onBackground).lineLimit(1)
                TimelineView(.periodic(from: .now, by: 2)) { _ in
                    if let line = subtitle(state: state, group: group) {
                        Text(line.text).font(.small).foregroundStyle(line.live ? Palette.accent : Palette.muted).lineLimit(1)
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(L("chats_contact", name))
    }

    private func subtitle(state: MessagingState, group: TalkGroup?) -> (text: String, live: Bool)? {
        let typing = IosViews.shared.typing(state: state, chat: chat, now: Dates.nowMillis)
        if !typing.isEmpty {
            if isGroup {
                let names = typing.map { IosViews.shared.nameOf(state: state, user: $0) }.joined(separator: ", ")
                return (L(typing.count > 1 ? "group_typing_many" : "group_typing", names), true)
            }
            return (L("chat_typing"), true)
        }
        if let group { return (L("group_members_count", group.readers.count), false) }
        let presence = IosViews.shared.presence(state: state, user: chat)
        return Lines.presence(presence).map { ($0, presence?.online == true) }
    }

    // MARK: Banners

    @ViewBuilder
    private func banner(contact: Contact?, group: TalkGroup?) -> some View {
        if let contact, contact.changedKey != nil {
            HomeCard(title: L("chat_key_changed", contact.name), text: L("chat_key_changed_body"), action: L("chat_key_changed_action"), warning: true) {
                path.append(.contact(contact.user))
            }
        } else if let contact, contact.blocked {
            PageNote(L("chat_blocked", contact.name)).padding(16)
        } else if let group, group.gone {
            PageNote(L("group_gone")).padding(16)
        } else if let group, group.isInvitation(me: model.ptt.myUser) {
            PageNote(L("group_invitation_note")).padding(16)
        }
    }

    private func canWrite(contact: Contact?, group: TalkGroup?) -> Bool {
        if let group { return !group.gone && !group.isInvitation(me: model.ptt.myUser) }
        guard let contact else { return false }
        return !contact.blocked
    }

    // MARK: Composer

    private var composer: some View {
        HStack(alignment: .bottom, spacing: 8) {
            TextField(L("chat_write_hint"), text: $text, axis: .vertical)
                .lineLimit(1...6)
                .font(.system(size: 16))
                .focused($writing)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Palette.raised, in: RoundedRectangle(cornerRadius: 22))
                .overlay(RoundedRectangle(cornerRadius: 22).strokeBorder(Palette.line, lineWidth: 1))
                .onChange(of: text) { _, now in typed(now) }
            Button(action: send) {
                Image(systemName: "arrow.up")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(Palette.onInverse)
                    .frame(width: 44, height: 44)
                    .background(Palette.inverse.opacity(canSend ? 1 : 0.4), in: Circle())
            }
            .disabled(!canSend)
            .accessibilityLabel(L("chat_send"))
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Palette.background)
    }

    private var canSend: Bool { !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    /// "typing…" goes out at most every three seconds while writing, and stops when the line is emptied. With Live
    /// typing on, the shared logic also sends the line itself (it decides; the screen only reports it).
    private func typed(_ now: String) {
        controller.draft(user: chat, text: now, selStart: -1, selEnd: -1)
        if now.isEmpty {
            controller.typing(user: chat, typing: false)
            lastTypingAt = 0
        } else if Dates.nowMillis - lastTypingAt > 3000 {
            lastTypingAt = Dates.nowMillis
            controller.typing(user: chat, typing: true)
        }
    }

    private func send() {
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { return }
        text = ""
        lastTypingAt = 0
        controller.draftDone(user: chat)
        controller.sendText(user: chat, body: body) { result in
            if let failed = result as? ContactResultFailed { messages.error(L("contact_failed", failed.message)) }
        }
    }
}

// MARK: - The list

/// The messages, kept at their end as new ones come. It is its own view so typing in the composer doesn't lay the
/// whole list out again.
private struct MessageList: View {
    let chat: String
    let messages: [TextMessage]
    let hasEarlier: Bool
    let isGroup: Bool
    let state: MessagingState
    let loadEarlier: () -> Void
    let retry: (String) -> Void
    let delete: (String, Bool) -> Void

    var body: some View {
        // The last of my messages that was read, and the last that was received: a small face marks each.
        let lastRead = messages.last { $0.outgoing && $0.status == MessageStatus.read }?.id
        let lastReceived = messages.last { $0.outgoing && ($0.status == MessageStatus.delivered || $0.status == MessageStatus.read) }?.id
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 0) {
                    if hasEarlier {
                        ProgressView().padding(12).onAppear(perform: loadEarlier)
                    }
                    ForEach(Array(messages.enumerated()), id: \.element.id) { index, message in
                        let previous = index > 0 ? messages[index - 1] : nil
                        if previous == nil || !Calendar.current.isDate(Dates.date(previous!.at), inSameDayAs: Dates.date(message.at)) {
                            DayLine(text: Dates.day(message.at))
                        }
                        MessageRow(
                            message: message,
                            // A group's bubble names its writer on the first of a run of theirs.
                            authorName: isGroup && !message.outgoing && previous?.author != message.author
                                ? message.author.map { IosViews.shared.nameOf(state: state, user: $0) } : nil,
                            startsRun: previous == nil || previous!.outgoing != message.outgoing || previous!.author != message.author,
                            retry: retry,
                            delete: delete
                        )
                        if message.id == lastRead {
                            ReceiptFace(chat: chat, read: true)
                        } else if message.id == lastReceived {
                            ReceiptFace(chat: chat, read: false)
                        }
                    }
                    Color.clear.frame(height: 1).id("end")
                }
                .padding(.horizontal, 12)
                .padding(.top, 8)
            }
            .scrollDismissesKeyboard(.interactively)
            .defaultScrollAnchor(.bottom)
            .onChange(of: messages.last?.id) { _, _ in
                withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo("end", anchor: .bottom) }
            }
        }
    }
}

private struct DayLine: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.small.weight(.semibold))
            .foregroundStyle(Palette.muted)
            .padding(.horizontal, 12)
            .padding(.vertical, 4)
            .background(Palette.raised, in: Capsule())
            .frame(maxWidth: .infinity)
            .padding(.vertical, 10)
    }
}

/// The small face under my last message they received (a dashed ring) or read (a solid ring).
private struct ReceiptFace: View {
    let chat: String
    let read: Bool
    @EnvironmentObject var chats: ChatStore

    var body: some View {
        let name = IosViews.shared.nameOf(state: chats.state, user: chat)
        HStack {
            Spacer()
            Avatar(user: chat, name: name, size: 16, group: IosViews.shared.isGroup(chat: chat))
                .padding(2)
                .overlay(Circle().strokeBorder(Palette.mineLine, style: StrokeStyle(lineWidth: 1, dash: read ? [] : [2, 2])))
                .accessibilityHidden(false)
                .accessibilityLabel(L(read ? "chat_status_read" : "chat_status_delivered"))
        }
        .padding(.top, 4)
        .padding(.bottom, 6)
        .transition(.opacity)
    }
}

/// One message: a bubble at my side or theirs; a call or a talk as a line of its own.
private struct MessageRow: View {
    let message: TextMessage
    let authorName: String?
    let startsRun: Bool
    let retry: (String) -> Void
    let delete: (String, Bool) -> Void

    var body: some View {
        HStack(spacing: 0) {
            if message.outgoing { Spacer(minLength: 48) }
            bubble
                .contextMenu { menu }
            if !message.outgoing { Spacer(minLength: 48) }
        }
        // Messages of one run sit close; a new writer gets a little air.
        .padding(.top, startsRun ? 8 : 2)
    }

    private var status: MessageStatus { message.status }
    private var failed: Bool { message.outgoing && status == MessageStatus.failed }
    /// Filled once it was received (or read); until then only an outline.
    private var filled: Bool { message.outgoing && (status == MessageStatus.delivered || status == MessageStatus.read) }

    private var bubble: some View {
        // As wide as its widest line: the words wrap inside the room the row leaves, the time sits at their end.
        VStack(alignment: .trailing, spacing: 3) {
            VStack(alignment: .leading, spacing: 3) {
                if let authorName {
                    Text(authorName).font(.small.weight(.semibold)).foregroundStyle(Avatar.color(for: message.author ?? ""))
                }
                content
            }
            HStack(spacing: 4) {
                if failed {
                    Text(L("chat_status_failed")).font(.tiny).foregroundStyle(Palette.error)
                } else {
                    if message.editedAt != nil && !message.deleted {
                        Text(L("chat_edited")).font(.tiny).foregroundStyle(timeColor)
                    }
                    Text(Dates.time(message.at)).font(.tiny).foregroundStyle(timeColor)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(fill, in: RoundedRectangle(cornerRadius: 18))
        .overlay(outline)
        .animation(.easeInOut(duration: 0.35), value: status)
        .contentShape(RoundedRectangle(cornerRadius: 18))
        .onTapGesture { if failed { retry(message.id) } }
        .accessibilityElement(children: .combine)
        .accessibilityValue(statusWords)
    }

    @ViewBuilder
    private var content: some View {
        if message.deleted {
            Text(L("chat_deleted")).font(.system(size: 16)).italic().foregroundStyle(textColor.opacity(0.7))
        } else if message.call != nil {
            Label(Lines.call(message), systemImage: message.call?.video == true ? "video" : "phone")
                .font(.system(size: 15))
                .foregroundStyle(message.call?.outcome == CallOutcome.missed ? Palette.error : textColor)
        } else if message.clip != nil {
            Label(Lines.preview(message), systemImage: message.clip?.video == true ? "video.circle" : "waveform")
                .font(.system(size: 15))
                .foregroundStyle(textColor)
        } else {
            let words = message.body.isEmpty ? Lines.preview(message) : message.body
            Text(words)
                .font(.system(size: 16))
                .foregroundStyle(textColor)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var fill: Color {
        if !message.outgoing { return Palette.raised }
        return filled ? Palette.mine : Palette.mine.opacity(0)
    }

    private var textColor: Color {
        if !message.outgoing { return Palette.onBackground }
        return filled ? Palette.onMine : Palette.onBackground
    }

    private var timeColor: Color {
        filled ? Palette.onMine.opacity(0.75) : Palette.muted
    }

    @ViewBuilder
    private var outline: some View {
        if message.outgoing {
            let pending = status == MessageStatus.pending
            RoundedRectangle(cornerRadius: 18)
                .strokeBorder(
                    failed ? Palette.error : Palette.mineLine,
                    style: StrokeStyle(lineWidth: 1.5, dash: pending ? [5, 4] : [])
                )
                .opacity(filled ? 0 : 1)
        }
    }

    private var statusWords: String {
        guard message.outgoing else { return "" }
        switch status {
        case .pending: return L("chat_status_pending")
        case .sent: return L("chat_status_sent")
        case .delivered: return L("chat_status_delivered")
        case .read: return L("chat_status_read")
        default: return L("chat_status_failed")
        }
    }

    @ViewBuilder
    private var menu: some View {
        if !message.deleted {
            if !message.body.isEmpty {
                Button {
                    UIPasteboard.general.string = message.body
                } label: {
                    Label(L("ios_copy"), systemImage: "doc.on.doc")
                }
            }
            if message.outgoing {
                Button(role: .destructive) { delete(message.id, true) } label: { Label(L("chat_delete"), systemImage: "trash") }
            }
        }
        Button(role: .destructive) { delete(message.id, false) } label: { Label(L("chat_delete_for_me"), systemImage: "trash.slash") }
    }
}
