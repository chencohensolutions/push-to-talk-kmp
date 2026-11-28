import SharedLogic
import SwiftUI

/// Settings (docs/screens/settings.md): the account and this phone, privacy, and the server.
struct SettingsScreen: View {
    @Binding var path: [Route]
    @EnvironmentObject var model: TalkModel
    @EnvironmentObject var settings: SettingsStore
    @EnvironmentObject var chats: ChatStore
    @EnvironmentObject var messages: Messages
    @State var presenceVisible: Bool?
    @State var gender: Int32 = 0
    @State var editingEmail = false
    @State var email = ""
    @State var shownEmail: String?

    private var controller: MessagingController { model.ptt.messaging }

    var body: some View {
        let state = settings.state
        Page(title: L("settings_title")) {
            SectionLabel(L("settings_account_title"))
            Card {
                CardRow(
                    title: state.device?.deviceName ?? L("settings_device_name"),
                    detail: state.device.map { "\($0.user) · \(L("enroll_device_number")) \($0.deviceNumber)" } ?? L("settings_device_unknown")
                )
                RowDivider()
                CardRow(title: L("settings_linked_title"), detail: L("settings_linked_body"), action: { path.append(.devices) }) { chevron }
                RowDivider()
                CardRow(title: L("settings_register_again"), detail: L("settings_register_again_body"), action: { model.registerAgain() })
            }

            SectionLabel(L("settings_privacy_title"))
            Card {
                CardRow(
                    title: L("settings_presence_title"),
                    detail: L(presenceVisible == nil ? "library_loading" : presenceVisible == true ? "settings_presence_on" : "settings_presence_off"),
                    action: togglePresence
                ) {
                    Toggle("", isOn: Binding(get: { presenceVisible ?? false }, set: { _ in togglePresence() }))
                        .labelsHidden()
                        .tint(Palette.accent)
                        .disabled(presenceVisible == nil)
                }
                RowDivider()
                CardRow(title: L("settings_live_typing_default"), detail: L("settings_live_typing_body")) {
                    Toggle("", isOn: Binding(
                        get: { chats.state.talkSettings.liveTyping },
                        set: { controller.setTalkSettings(settings: IosViews.shared.withLiveTyping(settings: controller.talkSettings, on: $0)) }
                    ))
                    .labelsHidden()
                    .tint(Palette.accent)
                }
            }

            SectionLabel(L("settings_contacts_title"))
            Card {
                if editingEmail {
                    VStack(alignment: .leading, spacing: 10) {
                        LabeledField(label: L("settings_my_email"), text: $email, help: L("settings_my_email_body"), keyboard: .emailAddress, content: .emailAddress, leftToRight: true)
                        HStack(spacing: 16) {
                            Button(L("contact_rename_save")) {
                                controller.setMyEmail(email: email)
                                shownEmail = email.nilIfBlank
                                editingEmail = false
                            }
                            .buttonStyle(.quiet)
                            Button(L("contact_rename_cancel")) { editingEmail = false }.buttonStyle(TextButtonStyle(color: Palette.muted))
                        }
                    }
                    .padding(16)
                } else {
                    CardRow(title: L("settings_my_email"), detail: shownEmail ?? L("settings_my_email_none"), action: {
                        email = shownEmail ?? ""
                        editingEmail = true
                    }) { Image(systemName: "pencil").foregroundStyle(Palette.muted) }
                }
                RowDivider()
                VStack(alignment: .leading, spacing: 8) {
                    Text(L("profile_gender_title")).font(.rowTitle).foregroundStyle(Palette.onBackground)
                    Text(L("profile_gender_body")).font(.caption13).foregroundStyle(Palette.muted)
                    Picker("", selection: Binding(get: { gender }, set: {
                        gender = $0
                        controller.setMyGender(gender: $0)
                    })) {
                        // The shared Gender's numbers: 1 male, 2 female, 0 not set.
                        Text(L("profile_gender_male")).tag(Int32(1))
                        Text(L("profile_gender_female")).tag(Int32(2))
                        Text(L("profile_gender_not_set")).tag(Int32(0))
                    }
                    .pickerStyle(.segmented)
                }
                .padding(16)
            }

            SectionLabel(L("settings_server_title"))
            Card {
                VStack(alignment: .leading, spacing: 4) {
                    Text(state.serverAddress.isEmpty ? L("settings_server_none") : state.serverAddress.withoutScheme)
                        .font(.rowTitle).foregroundStyle(Palette.onBackground)
                    Text(serverLine(state)).font(.caption13)
                        .foregroundStyle(state.connected ? Palette.accent : state.health == ServerHealth.ok ? Palette.muted : Palette.warning)
                    Text(state.serverId.map { L("settings_server_id", $0) } ?? L("settings_server_no_id"))
                        .font(.caption13).foregroundStyle(Palette.muted)
                    if let version = state.serverVersion {
                        Text(L("settings_server_version", version)).font(.caption13).foregroundStyle(Palette.muted)
                    }
                    if state.knownAddresses > 0 {
                        Text(L("settings_server_addresses", Int(state.knownAddresses))).font(.caption13).foregroundStyle(Palette.muted)
                    }
                }
                .padding(16)
            }

            if let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String {
                PageNote(L("settings_app_version", version))
            }
        }
        .onAppear {
            model.ptt.settingsController.refresh()
            presenceVisible = controller.presenceVisible
            gender = controller.myGender
            shownEmail = controller.myEmail?.nilIfBlank
        }
    }

    private var chevron: some View {
        // chevron.forward points onward in both writing directions.
        Image(systemName: "chevron.forward").font(.system(size: 13, weight: .semibold)).foregroundStyle(Palette.muted)
    }

    private func serverLine(_ state: SettingsState) -> String {
        if state.connected { return L("settings_server_connected") }
        if state.health == ServerHealth.rejected { return L("settings_server_rejected") }
        if state.health == ServerHealth.offline { return L("settings_server_offline") }
        return L("library_loading")
    }

    private func togglePresence() {
        guard let now = presenceVisible else { return }
        presenceVisible = nil
        controller.setPresenceVisible(visible: !now) { shown in
            if let shown {
                presenceVisible = shown.boolValue
            } else {
                presenceVisible = now
                messages.error(L("settings_presence_failed"))
            }
        }
    }
}

/// Your devices (docs/screens/devices.md): every phone of this account, removing one, and linking a new one.
struct DevicesScreen: View {
    @Binding var path: [Route]
    @EnvironmentObject var model: TalkModel
    @EnvironmentObject var messages: Messages
    @State var devices: [OwnDevice]?
    @State var failure: String?
    @State var asking: OwnDevice?
    @State var removing: Int32?

    private var controller: MessagingController { model.ptt.messaging }

    var body: some View {
        Page(title: L("devices_title")) {
            if let failure {
                PageNote(L("devices_failed", failure))
                Button(L("devices_again"), action: load).buttonStyle(.primary)
            } else if let devices {
                PageNote(L("devices_intro"))
                SectionLabel(L("devices_section"))
                Card {
                    ForEach(Array(devices.enumerated()), id: \.element.deviceNumber) { index, device in
                        CardRow(
                            title: device.current ? L("devices_this_phone", device.name) : device.name,
                            detail: [seen(device), device.registeredAt.map { L("devices_registered", Dates.day($0.int64Value)) }, keyWords(device.keyProtection)]
                                .compactMap { $0 }.joined(separator: " · ")
                        ) {
                            if !device.current {
                                Button(L(removing == device.deviceNumber ? "devices_removing" : "devices_remove")) { asking = device }
                                    .buttonStyle(TextButtonStyle(color: Palette.error))
                                    .disabled(removing != nil)
                            }
                        }
                        if index < devices.count - 1 { RowDivider() }
                    }
                }
                Button { path.append(.linkPhone) } label: { Label(L("devices_link"), systemImage: "qrcode") }.buttonStyle(.secondary)
            } else {
                ProgressView().frame(maxWidth: .infinity).padding(.top, 40)
            }
        }
        .onAppear(perform: load)
        .confirmationDialog(
            asking.map { L("devices_remove_title", $0.name) } ?? "",
            isPresented: Binding(get: { asking != nil }, set: { if !$0 { asking = nil } }),
            titleVisibility: .visible
        ) {
            Button(L("devices_remove"), role: .destructive) { if let device = asking { remove(device) } }
            Button(L("devices_cancel"), role: .cancel) {}
        } message: {
            Text(L("devices_remove_body"))
        }
    }

    private func load() {
        failure = nil
        controller.loadDevices { list, error in
            devices = list
            failure = list == nil ? (error ?? "") : nil
        }
    }

    private func remove(_ device: OwnDevice) {
        removing = device.deviceNumber
        controller.revokeDevice(deviceNumber: device.deviceNumber) { error in
            removing = nil
            if let error {
                messages.error(L("devices_remove_failed", error))
            } else {
                messages.show(L("devices_removed", device.name))
                load()
            }
        }
    }

    private func seen(_ device: OwnDevice) -> String {
        if device.current { return L("devices_active_now") }
        guard let at = device.lastSeenAt?.int64Value else { return L("devices_seen_unknown") }
        if Dates.nowMillis - at < 120_000 { return L("devices_active_now") }
        let relative = RelativeDateTimeFormatter().localizedString(for: Dates.date(at), relativeTo: Date())
        return L("devices_seen", relative)
    }

    private func keyWords(_ protection: String) -> String? {
        switch protection {
        case "STRONGBOX": return L("devices_key_strongbox")
        case "TEE", "SECURE_ENCLAVE": return L("devices_key_tee")
        case "SOFTWARE": return L("devices_key_software")
        default: return nil
        }
    }
}

/// Link a phone (docs/screens/link-phone.md): a QR code the user's new phone scans when it registers. It joins this
/// account and gets its keys and contacts from this phone, sealed end to end, while this screen is open.
struct LinkPhoneScreen: View {
    @EnvironmentObject var model: TalkModel
    @EnvironmentObject var chats: ChatStore
    @Environment(\.dismiss) var dismiss
    @State var code: ShownLinkCode?
    @State var failure: String?
    /// The phone linked last when the screen opened: a different one since means the new phone is linked.
    @State var linkedBefore: Int32?
    @State var opened = false

    var body: some View {
        let linkedNow = chats.state.linkedDevice?.int32Value
        Page(title: L("link_title")) {
            if opened && linkedNow != nil && linkedNow != linkedBefore {
                VStack(spacing: 12) {
                    Image(systemName: "checkmark.circle.fill").font(.system(size: 56)).foregroundStyle(Palette.accent)
                    Text(L("link_done_title")).font(.pageTitle).foregroundStyle(Palette.onBackground).multilineTextAlignment(.center)
                    Text(L("link_done_body")).font(.bodyText).foregroundStyle(Palette.muted).multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 32)
                Button(L("link_done")) { dismiss() }.buttonStyle(.primary)
            } else if let failure {
                PageNote(L("link_failed", failure))
                Button(L("link_again"), action: make).buttonStyle(.primary)
            } else if let code {
                VStack(spacing: 12) {
                    Text(L("link_steps")).font(.bodyText).foregroundStyle(Palette.onBackground).multilineTextAlignment(.center)
                    QrCode(text: AppLinks.shared.web(link: code.code)).accessibilityLabel(L("link_code_label"))
                    Text(L("link_until", Dates.time(code.expiresAt))).font(.caption13).foregroundStyle(Palette.muted)
                    Text(L("link_note")).font(.caption13).foregroundStyle(Palette.muted).multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                Button(L("link_again"), action: make).buttonStyle(.secondary)
            } else {
                ProgressView().frame(maxWidth: .infinity).padding(.top, 40)
            }
        }
        .onAppear {
            guard !opened else { return }
            linkedBefore = linkedNow
            opened = true
            make()
        }
    }

    private func make() {
        failure = nil
        code = nil
        model.ptt.messaging.makeLinkCode { made, error in
            code = made
            failure = made == nil ? (error ?? "") : nil
        }
    }
}
