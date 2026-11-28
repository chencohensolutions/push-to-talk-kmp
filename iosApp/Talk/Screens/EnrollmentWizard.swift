import SharedLogic
import SwiftUI
import UIKit

/// Registering this phone with the server (docs/screens/enrollment.md): Welcome, the code (or an invite's code),
/// Confirm, Registering, and done or what went wrong. The steps and their order are the shared EnrollmentController's;
/// this screen draws the step it is on.
struct EnrollmentWizard: View {
    @EnvironmentObject private var model: TalkModel
    @EnvironmentObject private var enrollment: EnrollmentStore
    @EnvironmentObject private var messages: Messages
    @State private var scanning = false

    private var controller: EnrollmentController { model.ptt.enrollment }

    var body: some View {
        let state = enrollment.state
        VStack(spacing: 0) {
            switch state.step {
            case is EnrollmentStepWelcome:
                welcome
            case let step as EnrollmentStepEnterCode:
                enterCode(state, step)
            case let step as EnrollmentStepConfirm:
                confirm(state, step)
            case let step as EnrollmentStepInviteCode:
                inviteCode(state, step)
            case let step as EnrollmentStepRegistering:
                registering(state, step)
            case let step as EnrollmentStepEnrolled:
                enrolled(step)
            case let step as EnrollmentStepFailed:
                failed(state, step)
            default:
                EmptyView()
            }
        }
        .background(Palette.background.ignoresSafeArea())
        .sheet(isPresented: $scanning) {
            QrScanner { controller.openScanned(text: $0) }
        }
    }

    // MARK: Steps

    private var welcome: some View {
        VStack(spacing: 0) {
            StepBody {
                Image(systemName: "lock.shield")
                    .font(.system(size: 34))
                    .foregroundStyle(Palette.inverse)
                    .frame(width: 72, height: 72)
                    .background(Palette.raised, in: Circle())
                    .padding(.top, 40)
                StepTitle(L("enroll_welcome_title")).padding(.top, 32)
                StepText(L("enroll_welcome_body")).padding(.top, 16)
                VStack(alignment: .leading, spacing: 20) {
                    NumberedItem(number: 1, title: L("enroll_welcome_step1_title"), text: L("enroll_welcome_step1_body"))
                    NumberedItem(number: 2, title: L("enroll_welcome_step2_title"), text: L("enroll_welcome_step2_body"))
                    NumberedItem(number: 3, title: L("enroll_welcome_step3_title"), text: L("enroll_welcome_step3_body"))
                }
                .padding(.top, 32)
            }
            BottomActions {
                Button(L("enroll_get_started")) { controller.start() }.buttonStyle(.primary)
            }
        }
    }

    private func enterCode(_ state: EnrollmentState, _ step: EnrollmentStepEnterCode) -> some View {
        VStack(spacing: 0) {
            WizardHeader(number: 1, label: L("enroll_step_connect")) { controller.back() }
            StepBody {
                StepTitle(L("enroll_code_title")).padding(.top, 24)
                StepText(L("enroll_code_body")).padding(.top, 8)
                HStack(spacing: 12) {
                    Button { scanning = true } label: { Label(L("enroll_scan"), systemImage: "qrcode.viewfinder") }
                        .buttonStyle(.secondary)
                    Button(action: paste) { Label(L("enroll_paste"), systemImage: "doc.on.clipboard") }
                        .buttonStyle(.secondary)
                }
                .disabled(step.checking)
                .padding(.top, 20)
                LabeledField(
                    label: L("enroll_server_address"),
                    text: Binding(get: { state.serverAddress }, set: { controller.updateServerAddress(address: $0) }),
                    help: L("enroll_server_address_help"),
                    error: step.error is EnrollmentErrorMissingServerAddress ? L("enroll_error_missing_address") : nil,
                    keyboard: .URL,
                    leftToRight: true
                )
                .disabled(step.checking)
                .padding(.top, 24)
                LabeledField(
                    label: L("enroll_code"),
                    text: Binding(get: { state.code }, set: { controller.updateCode(code: $0) }),
                    help: L("enroll_code_help"),
                    error: step.error.flatMap { $0 is EnrollmentErrorMissingServerAddress ? nil : Self.message($0) },
                    keyboard: .asciiCapable,
                    leftToRight: true,
                    capitalization: .characters
                )
                .disabled(step.checking)
                .onSubmit { controller.checkCode() }
                .padding(.top, 16)
            }
            BottomActions {
                LoadingButton(title: L("enroll_continue"), loading: step.checking) { controller.checkCode() }
            }
        }
    }

    private func confirm(_ state: EnrollmentState, _ step: EnrollmentStepConfirm) -> some View {
        VStack(spacing: 0) {
            WizardHeader(number: 2, label: L("enroll_step_confirm")) { controller.back() }
            StepBody {
                StepTitle(L("enroll_confirm_title")).padding(.top, 24)
                Card {
                    serverRow(state)
                    RowDivider()
                    DetailRow(
                        icon: "person",
                        label: L("enroll_account"),
                        value: step.user,
                        note: L("enroll_code_expires", state.code.uppercased(), Int(step.minutesLeft)),
                        highlight: false
                    )
                }
                .padding(.top, 20)
                LabeledField(
                    label: L("enroll_device_name"),
                    text: Binding(get: { state.deviceName }, set: { controller.updateDeviceName(name: $0) }),
                    help: L("enroll_device_name_help"),
                    capitalization: .words
                )
                .padding(.top, 20)
                keyNotice.padding(.top, 20)
            }
            BottomActions {
                Button(L("enroll_register")) { controller.register() }.buttonStyle(.primary)
            }
        }
    }

    private func inviteCode(_ state: EnrollmentState, _ step: EnrollmentStepInviteCode) -> some View {
        let inviter = state.invite?.inviterDisplayName?.nilIfBlank ?? state.invite?.inviter ?? ""
        let problem = (step.error as? EnrollmentErrorInvite)?.problem
        let field = Self.field(of: step.error)
        return VStack(spacing: 0) {
            WizardHeader(number: 2, label: L("enroll_step_invite")) { controller.back() }
            StepBody {
                StepTitle(L("invite_join_title", inviter)).padding(.top, 24)
                StepText(L("invite_code_body", inviter)).padding(.top, 8)
                Card { serverRow(state) }.padding(.top, 20)
                LabeledField(
                    label: L("invite_code"),
                    text: Binding(get: { state.passcode }, set: { controller.updatePasscode(code: $0) }),
                    help: L("invite_code_help"),
                    error: field == .code ? step.error.map(Self.message) : nil,
                    keyboard: .numberPad,
                    content: .oneTimeCode,
                    leftToRight: true
                )
                .padding(.top, 20)
                let bound = state.invite?.email
                LabeledField(
                    label: L(bound != nil ? "invite_email" : "invite_email_optional"),
                    text: Binding(get: { bound ?? state.email }, set: { controller.updateEmail(email: $0) }),
                    help: L(bound != nil ? "invite_email_bound" : "invite_email_help"),
                    error: field == .email ? step.error.map(Self.message) : nil,
                    keyboard: .emailAddress,
                    content: .emailAddress,
                    leftToRight: true
                )
                .disabled(bound != nil)
                .padding(.top, 16)
                LabeledField(
                    label: L("invite_name"),
                    text: Binding(get: { state.displayName }, set: { controller.updateDisplayName(name: $0) }),
                    help: L("invite_name_help", inviter),
                    error: field == .name ? step.error.map(Self.message) : nil,
                    content: .name,
                    capitalization: .words
                )
                .padding(.top, 16)
                LabeledField(
                    label: L("enroll_device_name"),
                    text: Binding(get: { state.deviceName }, set: { controller.updateDeviceName(name: $0) }),
                    help: L("enroll_device_name_help"),
                    capitalization: .words
                )
                .padding(.top, 16)
                if field == .other, problem == nil, let error = step.error {
                    PageNote(Self.message(error), color: Palette.error).padding(.top, 12)
                }
                keyNotice.padding(.top, 20)
            }
            .disabled(step.checking)
            BottomActions {
                LoadingButton(title: L("invite_join", state.invite?.inviter ?? ""), loading: step.checking) { controller.registerWithInvite() }
            }
        }
    }

    private func registering(_ state: EnrollmentState, _ step: EnrollmentStepRegistering) -> some View {
        let rows: [(String, String)] = [
            (L("enroll_progress_key"), L("enroll_progress_key_detail")),
            (L("enroll_progress_request"), L("enroll_progress_request_detail")),
            (L("enroll_progress_certificate"), L("enroll_progress_certificate_detail")),
            (L("enroll_progress_test"), L("enroll_progress_test_detail")),
        ]
        let at = Int(step.progress.ordinal)
        return VStack(spacing: 0) {
            WizardHeader(number: 3, label: L("enroll_step_register"), back: nil)
            StepBody {
                StepTitle(L("enroll_registering_title", state.deviceName.trimmingCharacters(in: .whitespaces))).padding(.top, 24)
                StepText(L("enroll_registering_body")).padding(.top, 8)
                Card {
                    ForEach(rows.indices, id: \.self) { index in
                        HStack(spacing: 14) {
                            ZStack {
                                if index < at {
                                    Image(systemName: "checkmark.circle.fill").foregroundStyle(Palette.accent)
                                } else if index == at {
                                    ProgressView()
                                } else {
                                    Image(systemName: "circle").foregroundStyle(Palette.line)
                                }
                            }
                            .font(.system(size: 22))
                            .frame(width: 28, height: 28)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(rows[index].0).font(.rowTitle).foregroundStyle(index <= at ? Palette.onBackground : Palette.muted)
                                Text(rows[index].1).font(.caption13).foregroundStyle(Palette.muted)
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                        if index < rows.count - 1 { RowDivider() }
                    }
                }
                .padding(.top, 24)
            }
            BottomActions {
                Button(L("enroll_cancel")) { controller.back() }.buttonStyle(.quiet)
            }
        }
    }

    private func enrolled(_ step: EnrollmentStepEnrolled) -> some View {
        VStack(spacing: 0) {
            StepBody(alignment: .center) {
                Image(systemName: "checkmark")
                    .font(.system(size: 34, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 80, height: 80)
                    .background(Palette.accent, in: Circle())
                    .padding(.top, 64)
                StepTitle(L("enroll_done_title")).multilineTextAlignment(.center).padding(.top, 28)
                StepText(L("enroll_done_body", step.user)).multilineTextAlignment(.center).padding(.top, 8)
                Card {
                    SummaryRow(label: L("enroll_server"), value: step.serverAddress.withoutScheme, leftToRight: true)
                    RowDivider()
                    SummaryRow(label: L("enroll_device"), value: step.deviceName)
                    RowDivider()
                    SummaryRow(label: L("enroll_device_number"), value: String(step.deviceNumber))
                    RowDivider()
                    SummaryRow(label: L("enroll_key"), value: Self.keyLabel(step.keyProtection))
                    RowDivider()
                    SummaryRow(label: L("enroll_valid_until"), value: step.validUntil, leftToRight: true)
                }
                .padding(.top, 28)
            }
            BottomActions {
                Button(L("enroll_open_app")) { model.openApp() }.buttonStyle(.primary)
            }
        }
    }

    private func failed(_ state: EnrollmentState, _ step: EnrollmentStepFailed) -> some View {
        let wrongCode = step.error is EnrollmentErrorInvalidCode
        return VStack(spacing: 0) {
            StepBody {
                Image(systemName: "exclamationmark")
                    .font(.system(size: 34, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 80, height: 80)
                    .background(Palette.error, in: Circle())
                    .padding(.top, 64)
                StepTitle(L(wrongCode ? "enroll_failed_code_title" : "enroll_failed_title")).padding(.top, 28)
                if let invite = state.invite {
                    StepText(Self.message(step.error)).padding(.top, 12)
                    StepText(L("invite_failed_ask", invite.inviter)).padding(.top, 12)
                } else {
                    StepText(wrongCode ? L("enroll_failed_code_body", state.code.uppercased()) : Self.message(step.error)).padding(.top, 12)
                    VStack(alignment: .leading, spacing: 10) {
                        Text(L("enroll_failed_new_code")).font(.rowTitle).foregroundStyle(Palette.onBackground)
                        Text(verbatim: "$ ptt-server enroll-code")
                            .font(.system(size: 14, design: .monospaced))
                            .foregroundStyle(Color(UIColor(rgb: 0xDCE5DE)))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 10)
                            .background(Color(UIColor(rgb: 0x121513)), in: RoundedRectangle(cornerRadius: 8))
                            .environment(\.layoutDirection, .leftToRight)
                    }
                    .padding(16)
                    .background(Palette.raised, in: RoundedRectangle(cornerRadius: Corner.card))
                    .padding(.top, 28)
                    Text(L("enroll_failed_key_deleted")).font(.small).foregroundStyle(Palette.muted).padding(.top, 16)
                }
            }
            BottomActions {
                Button(L(state.invite != nil ? "enroll_back" : "enroll_new_code")) { controller.back() }.buttonStyle(.primary)
            }
        }
    }

    // MARK: Pieces

    private func serverRow(_ state: EnrollmentState) -> some View {
        DetailRow(
            icon: "server.rack",
            label: L("enroll_server"),
            value: state.serverAddress.withoutScheme,
            note: state.serverId.map { L("enroll_server_verified_id", Self.shortServerId($0)) } ?? L("enroll_server_verified"),
            highlight: true,
            leftToRight: true
        )
    }

    private var keyNotice: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "key").foregroundStyle(Palette.accent)
            Text(L("enroll_key_notice")).font(.bodyText).foregroundStyle(Palette.onBackground)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.raised, in: RoundedRectangle(cornerRadius: Corner.card))
    }

    /// The clipboard is read only when Paste is tapped; iOS asks the user the first time.
    private func paste() {
        let text = UIPasteboard.general.string ?? ""
        if controller.openPasted(text: text) == PasteResult.nothing {
            messages.show(L("enroll_paste_nothing"))
        }
    }

    static func shortServerId(_ id: String) -> String {
        id.split(separator: "-").prefix(2).joined(separator: "-") + "-…"
    }

    private enum InviteField { case code, email, name, other }

    private static func field(of error: EnrollmentError?) -> InviteField? {
        guard let error else { return nil }
        guard let invite = error as? EnrollmentErrorInvite else { return .other }
        switch invite.problem {
        case .invalidEmail, .emailMismatch, .emailTaken: return .email
        case .missingName: return .name
        default: return .code
        }
    }

    static func message(_ error: EnrollmentError) -> String {
        switch error {
        case is EnrollmentErrorMissingServerAddress: return L("enroll_error_missing_address")
        case is EnrollmentErrorMissingCode: return L("enroll_error_missing_code")
        case is EnrollmentErrorContactCode: return L("enroll_error_contact_code")
        case is EnrollmentErrorInvalidCode: return L("enroll_error_invalid_code")
        case is EnrollmentErrorRateLimited: return L("enroll_error_rate_limited")
        case is EnrollmentErrorRequestRejected: return L("enroll_error_request_rejected")
        case let e as EnrollmentErrorUnreachable: return L("enroll_error_unreachable", e.message)
        case let e as EnrollmentErrorKeyStoreFailed: return L("enroll_error_key_store", e.message)
        case let e as EnrollmentErrorConnectionTestFailed: return L("enroll_error_connection_test", e.message)
        case let e as EnrollmentErrorInvite:
            switch e.problem {
            case .invalidInvite: return L("invite_error_invalid")
            case .invalidEmail: return L("invite_error_email")
            case .emailMismatch: return L("invite_error_mismatch")
            case .emailTaken: return L("invite_error_taken")
            case .wrongPasscode: return L("invite_error_code")
            case .missingName: return L("invite_error_name")
            case .rateLimited: return L("invite_error_rate_limited")
            default: return L("invite_error_invalid")
            }
        default: return L("enroll_failed_title")
        }
    }

    static func keyLabel(_ protection: KeyProtection) -> String {
        switch protection {
        case .strongbox: return L("enroll_key_strongbox")
        case .tee: return L("enroll_key_tee")
        case .secureEnclave: return L("enroll_key_secure_enclave")
        default: return L("enroll_key_software")
        }
    }
}

// MARK: - Building blocks

private struct WizardHeader: View {
    let number: Int
    let label: String
    let back: (() -> Void)?

    init(number: Int, label: String, back: (() -> Void)?) {
        self.number = number
        self.label = label
        self.back = back
    }

    var body: some View {
        HStack(spacing: 8) {
            if let back {
                Button(action: back) {
                    // chevron.backward points the way back in both writing directions.
                    Image(systemName: "chevron.backward").font(.system(size: 18, weight: .semibold)).foregroundStyle(Palette.onBackground)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel(L("enroll_back"))
            } else {
                Spacer().frame(width: 12, height: 44)
            }
            Text(L("enroll_step_of", number, label)).font(.caption13.weight(.semibold)).foregroundStyle(Palette.muted)
            Spacer()
        }
        .padding(.horizontal, 8)
        .padding(.top, 4)
    }
}

private struct StepBody<Content: View>: View {
    var alignment: HorizontalAlignment = .leading
    @ViewBuilder var content: () -> Content

    var body: some View {
        ScrollView {
            VStack(alignment: alignment, spacing: 0) {
                content()
            }
            .frame(maxWidth: .infinity, alignment: alignment == .center ? .center : .leading)
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
        }
        .scrollDismissesKeyboard(.interactively)
    }
}

private struct BottomActions<Content: View>: View {
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(spacing: 8) { content() }
            .padding(.horizontal, 24)
            .padding(.top, 8)
            .padding(.bottom, 16)
    }
}

private struct StepTitle: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text).font(.screenTitle).foregroundStyle(Palette.onBackground).fixedSize(horizontal: false, vertical: true)
    }
}

private struct StepText: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text).font(.system(size: 16)).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true)
    }
}

private struct NumberedItem: View {
    let number: Int
    let title: String
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Text(String(number))
                .font(.rowTitle)
                .foregroundStyle(Palette.onBackground)
                .frame(width: 32, height: 32)
                .background(Palette.raised, in: Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.rowTitle).foregroundStyle(Palette.onBackground)
                Text(text).font(.bodyText).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

private struct DetailRow: View {
    let icon: String
    let label: String
    let value: String
    let note: String
    let highlight: Bool
    var leftToRight: Bool = false

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon).font(.system(size: 18)).foregroundStyle(Palette.muted).frame(width: 28, height: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(.small).foregroundStyle(Palette.muted)
                Text(value).font(.rowTitle).foregroundStyle(Palette.onBackground)
                Text(note).font(.caption13).foregroundStyle(highlight ? Palette.accent : Palette.muted)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
    }
}

private struct SummaryRow: View {
    let label: String
    let value: String
    var leftToRight: Bool = false

    var body: some View {
        HStack {
            Text(label).font(.bodyText).foregroundStyle(Palette.muted)
            Spacer(minLength: 12)
            Text(value).font(.bodyText.weight(.semibold)).foregroundStyle(Palette.onBackground)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }
}

/// The main action, with a spinner in place of its words while `loading`.
struct LoadingButton: View {
    let title: String
    let loading: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            if loading {
                ProgressView().tint(.white)
            } else {
                Text(title)
            }
        }
        .buttonStyle(.primary)
        .disabled(loading)
    }
}

extension String {
    var nilIfBlank: String? { trimmingCharacters(in: .whitespaces).isEmpty ? nil : self }

    /// A server address as the screens show it: without "https://".
    var withoutScheme: String { hasPrefix("https://") ? String(dropFirst(8)) : self }
}
