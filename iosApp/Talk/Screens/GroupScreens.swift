import SharedLogic
import SwiftUI

/// New group (docs/screens/groups.md): a name and the contacts to invite. The group is this user's; the members'
/// phones trust the changes its owner signs.
struct NewGroupScreen: View {
    @Binding var path: [Route]
    @EnvironmentObject var model: TalkModel
    @EnvironmentObject var chats: ChatStore
    @EnvironmentObject var messages: Messages
    @State var name = ""
    @State var chosen: Set<String> = []
    @State var creating = false

    var body: some View {
        let choices = chats.state.contacts.filter { $0.paired && !$0.blocked }
        Page(title: L("group_new_title")) {
            LabeledField(label: L("group_name"), text: $name, capitalization: .sentences)
            SectionLabel(L("group_choose_members"))
            if choices.isEmpty {
                PageNote(L("group_no_contacts"))
            } else {
                MemberChoices(contacts: choices, chosen: $chosen)
            }
            LoadingButton(title: L("group_create"), loading: creating) {
                creating = true
                model.ptt.messaging.createGroup(name: name, picture: nil, members: Array(chosen)) { chat, error in
                    creating = false
                    if let chat {
                        path = [.conversation(chat)]
                    } else {
                        messages.error(L("contact_failed", error ?? ""))
                    }
                }
            }
            .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
        }
    }
}

/// Contacts with a mark each: who to invite.
private struct MemberChoices: View {
    let contacts: [Contact]
    @Binding var chosen: Set<String>

    var body: some View {
        Card {
            ForEach(Array(contacts.enumerated()), id: \.element.user) { index, contact in
                Button {
                    if !chosen.insert(contact.user).inserted { chosen.remove(contact.user) }
                } label: {
                    HStack(spacing: 12) {
                        Avatar(user: contact.user, name: contact.name, size: 40)
                        Text(contact.name).font(.rowTitle).foregroundStyle(Palette.onBackground)
                        Spacer()
                        Image(systemName: chosen.contains(contact.user) ? "checkmark.circle.fill" : "circle")
                            .font(.system(size: 22))
                            .foregroundStyle(chosen.contains(contact.user) ? Palette.inverse : Palette.line)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(chosen.contains(contact.user) ? .isSelected : [])
                if index < contacts.count - 1 { RowDivider() }
            }
        }
    }
}

/// A group's page: its name, its members, an invitation to answer, and leaving or deleting it.
struct GroupScreen: View {
    let id: String
    @Binding var path: [Route]
    @EnvironmentObject var model: TalkModel
    @EnvironmentObject var chats: ChatStore
    @EnvironmentObject var messages: Messages
    @State var renaming = false
    @State var name = ""
    @State var adding = false
    @State var chosen: Set<String> = []
    @State var asking: Ask?

    enum Ask: Identifiable {
        case leave, delete, remove(String)
        var id: String {
            switch self {
            case .leave: return "leave"
            case .delete: return "delete"
            case .remove(let user): return "remove/" + user
            }
        }
    }

    private var controller: MessagingController { model.ptt.messaging }

    var body: some View {
        let state = chats.state
        Page(title: "") {
            if let group = IosViews.shared.group(state: state, chat: id) {
                page(group, state: state)
            } else {
                PageNote(L("group_missing"))
            }
        }
        .onAppear { controller.refreshGroups() }
    }

    @ViewBuilder
    private func page(_ group: TalkGroup, state: MessagingState) -> some View {
        let me = model.ptt.myUser
        let owner = group.owner == me
        let invitation = group.isInvitation(me: me)
        VStack(spacing: 8) {
            Avatar(user: group.chat, name: Lines.groupName(group), size: 96, group: true)
            Text(Lines.groupName(group)).font(.pageTitle).foregroundStyle(Palette.onBackground)
            Text(group.gone ? L("group_gone") : invitation ? L("group_invited_by", group.owner) : L("group_members_count", group.readers.count))
                .font(.caption13).foregroundStyle(Palette.muted).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)

        if invitation {
            PageNote(L("group_invitation_body", group.owner))
            HStack(spacing: 12) {
                Button(L("group_decline")) { act { controller.leaveGroup(id: group.id, done: $0) } then: { path = [] } }.buttonStyle(.secondary)
                Button(L("group_accept")) { act { controller.acceptGroup(id: group.id, done: $0) } then: {} }.buttonStyle(.primary)
            }
        } else if !group.gone {
            Button { path = [.conversation(group.chat)] } label: {
                Label(L("contacts_action_message", Lines.groupName(group)), systemImage: "bubble.left")
            }
            .buttonStyle(.primary)
        }

        if owner && !group.gone {
            Card {
                if renaming {
                    VStack(alignment: .leading, spacing: 10) {
                        LabeledField(label: L("group_name"), text: $name, capitalization: .sentences)
                        HStack(spacing: 16) {
                            Button(L("group_save_name")) {
                                renaming = false
                                act { controller.renameGroup(id: group.id, name: name, picture: nil, done: $0) } then: {}
                            }
                            .buttonStyle(.quiet)
                            Button(L("contact_rename_cancel")) { renaming = false }.buttonStyle(TextButtonStyle(color: Palette.muted))
                        }
                    }
                    .padding(16)
                } else {
                    CardRow(title: L("group_rename"), action: {
                        name = group.name
                        renaming = true
                    }) { Image(systemName: "pencil").foregroundStyle(Palette.muted) }
                }
            }
        }

        SectionLabel(L("group_members"))
        Card {
            let people = Self.people(group)
            ForEach(Array(people.enumerated()), id: \.element.0) { index, person in
                let contact = IosViews.shared.contact(state: state, user: person.0)
                CardRow(title: contact?.name ?? person.0, detail: memberLine(group, user: person.0, invited: person.1, contact: contact, me: me)) {
                    if owner && !group.gone && person.0 != me {
                        Button(L("group_remove")) { asking = .remove(person.0) }.buttonStyle(TextButtonStyle(color: Palette.error))
                    }
                }
                if index < people.count - 1 { RowDivider() }
            }
        }

        if owner && !group.gone {
            if adding {
                let choices = state.contacts.filter { c in c.paired && !c.blocked && group.member(user: c.user) == nil }
                if choices.isEmpty {
                    PageNote(L("group_no_more_contacts"))
                } else {
                    MemberChoices(contacts: choices, chosen: $chosen)
                    Button(L("group_invite")) {
                        let users = Array(chosen)
                        adding = false
                        chosen = []
                        act { controller.inviteToGroup(id: group.id, users: users, done: $0) } then: {}
                    }
                    .buttonStyle(.primary)
                    .disabled(chosen.isEmpty)
                }
            } else {
                Button { adding = true } label: { Label(L("group_add_members"), systemImage: "person.badge.plus") }.buttonStyle(.secondary)
            }
        }

        if !group.gone && !invitation {
            Card {
                if owner {
                    CardRow(title: L("group_delete"), detail: L("group_delete_body"), titleColor: Palette.error, action: { asking = .delete })
                } else {
                    CardRow(title: L("group_leave"), detail: L("group_leave_body"), titleColor: Palette.error, action: { asking = .leave })
                }
            }
            .confirmationDialog(askTitle, isPresented: Binding(get: { asking != nil }, set: { if !$0 { asking = nil } }), titleVisibility: .visible) {
                switch asking {
                case .leave?:
                    Button(L("group_leave"), role: .destructive) { act { controller.leaveGroup(id: group.id, done: $0) } then: { path = [] } }
                case .delete?:
                    Button(L("group_delete"), role: .destructive) { act { controller.deleteGroup(id: group.id, done: $0) } then: { path = [] } }
                case .remove(let user)?:
                    Button(L("group_remove"), role: .destructive) { act { controller.removeFromGroup(id: group.id, user: user, done: $0) } then: {} }
                case nil:
                    EmptyView()
                }
            }
        }
    }

    /// The members, then those invited who haven't joined: each user, and whether they are only invited.
    private static func people(_ group: TalkGroup) -> [(String, Bool)] {
        let readers = group.readers.map { $0.user }
        let invited = group.invited.map { $0.user }.filter { !readers.contains($0) }
        return readers.map { ($0, false) } + invited.map { ($0, true) }
    }

    private var askTitle: String {
        switch asking {
        case .leave?: return L("group_leave_confirm")
        case .delete?: return L("group_delete_confirm")
        case .remove(let user)?: return L("group_remove_confirm", IosViews.shared.nameOf(state: chats.state, user: user))
        case nil: return ""
        }
    }

    private func memberLine(_ group: TalkGroup, user: String, invited: Bool, contact: Contact?, me: String) -> String {
        var words: [String] = []
        if user == group.owner { words.append(L("group_owner")) }
        if invited { words.append(L("group_invited")) }
        if user == me {
            words.append(L("group_you"))
        } else if contact?.verified == true {
            words.append(L("contact_safety_verified"))
        } else if contact != nil {
            words.append(L("group_contact_unverified"))
        } else {
            words.append(L("group_not_verified_by_you"))
        }
        return words.joined(separator: " · ")
    }

    /// Runs a group change and says so when it failed; `then` runs when it worked.
    private func act(_ change: (@escaping (ContactResult) -> Void) -> Void, then: @escaping () -> Void) {
        change { result in
            if let failed = result as? ContactResultFailed {
                messages.error(L("contact_failed", failed.message))
            } else {
                then()
            }
        }
    }
}
