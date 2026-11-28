import SwiftUI
import UIKit

/// The library palette of docs/screens/style.md, as Android's LibraryStyle.kt: each colour has a light and a dark
/// value and follows the system's theme.
enum Palette {
    static let background = dynamic(light: 0xF4F5F3, dark: 0x0F1413)
    static let raised = dynamic(light: 0xFFFFFF, dark: 0x1E2725)
    static let onBackground = dynamic(light: 0x17201F, dark: 0xFFFFFF)
    static let muted = dynamic(light: 0x5E6866, dark: 0xA9B4B1)
    /// Teal: "live".
    static let accent = dynamic(light: 0x1F5F5B, dark: 0x1F5F5B)
    static let warning = dynamic(light: 0xB45309, dark: 0xF2B84B)
    /// Red: leave, remove, revoke.
    static let error = dynamic(light: 0xC2410C, dark: 0xC2410C)
    /// The one main action of a screen.
    static let inverse = dynamic(light: 0xE9504F, dark: 0xE9504F)
    static let onInverse = Color.white
    /// My own messages.
    static let mine = dynamic(light: 0x23774A, dark: 0x23774A)
    static let onMine = dynamic(light: 0xF2FBF5, dark: 0xF2FBF5)
    static let mineLine = dynamic(light: 0x23774A, dark: 0x32A066)
    static let line = dynamic(light: 0xDDE1DE, dark: 0x2C3735)

    private static func dynamic(light: UInt32, dark: UInt32) -> Color {
        Color(UIColor { traits in traits.userInterfaceStyle == .dark ? UIColor(rgb: dark) : UIColor(rgb: light) })
    }
}

extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(red: CGFloat((rgb >> 16) & 0xFF) / 255, green: CGFloat((rgb >> 8) & 0xFF) / 255, blue: CGFloat(rgb & 0xFF) / 255, alpha: 1)
    }
}

/// The type sizes of style.md. They scale with the phone's text size.
extension Font {
    static let screenTitle = Font.system(size: 30, weight: .bold)
    static let pageTitle = Font.system(size: 22, weight: .bold)
    static let sectionLabel = Font.system(size: 13, weight: .semibold)
    static let rowTitle = Font.system(size: 16, weight: .semibold)
    static let bodyText = Font.system(size: 14)
    static let caption13 = Font.system(size: 13)
    static let small = Font.system(size: 12)
    static let tiny = Font.system(size: 11)
}

enum Corner {
    static let card: CGFloat = 16
    static let tile: CGFloat = 12
}

// MARK: - Buttons

/// The screen's one main action: a coral pill.
struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.rowTitle)
            .foregroundStyle(Palette.onInverse)
            .frame(maxWidth: .infinity, minHeight: 52)
            .background(Palette.inverse.opacity(enabled ? (configuration.isPressed ? 0.8 : 1) : 0.4), in: Capsule())
    }
}

/// Another action beside the main one: an outlined pill.
struct SecondaryButtonStyle: ButtonStyle {
    var color: Color = Palette.onBackground
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.rowTitle)
            .foregroundStyle(color.opacity(enabled ? 1 : 0.4))
            .frame(maxWidth: .infinity, minHeight: 52)
            .background(configuration.isPressed ? Palette.line.opacity(0.5) : Color.clear, in: Capsule())
            .overlay(Capsule().strokeBorder(Palette.line, lineWidth: 1))
    }
}

/// A quiet action in a line of text.
struct TextButtonStyle: ButtonStyle {
    var color: Color = Palette.accent

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.rowTitle)
            .foregroundStyle(color.opacity(configuration.isPressed ? 0.6 : 1))
            .frame(minHeight: 44)
            .contentShape(Rectangle())
    }
}

extension ButtonStyle where Self == PrimaryButtonStyle {
    static var primary: PrimaryButtonStyle { PrimaryButtonStyle() }
}

extension ButtonStyle where Self == SecondaryButtonStyle {
    static var secondary: SecondaryButtonStyle { SecondaryButtonStyle() }
    static var danger: SecondaryButtonStyle { SecondaryButtonStyle(color: Palette.error) }
}

extension ButtonStyle where Self == TextButtonStyle {
    static var quiet: TextButtonStyle { TextButtonStyle() }
}

// MARK: - Pages

/// A page pushed over home: the palette's background, a title in the bar, its content scrolling.
struct Page<Content: View>: View {
    let title: String
    @ViewBuilder var content: () -> Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                content()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
        }
        .background(Palette.background.ignoresSafeArea())
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Palette.background, for: .navigationBar)
    }
}

/// A raised card (16 pt corners) holding rows.
struct Card<Content: View>: View {
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.raised, in: RoundedRectangle(cornerRadius: Corner.card))
    }
}

/// A small label over a card.
struct SectionLabel: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text.uppercased(with: .current))
            .font(.sectionLabel)
            .foregroundStyle(Palette.muted)
            .padding(.top, 8)
    }
}

/// A row of a settings card: a title, a line under it, and what stands at its end.
struct CardRow<Trailing: View>: View {
    let title: String
    var detail: String? = nil
    var titleColor: Color = Palette.onBackground
    var action: (() -> Void)? = nil
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        let row = HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.rowTitle).foregroundStyle(titleColor)
                if let detail, !detail.isEmpty {
                    Text(detail).font(.caption13).foregroundStyle(Palette.muted)
                }
            }
            Spacer(minLength: 8)
            trailing()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(minHeight: 56)
        .contentShape(Rectangle())
        if let action {
            Button(action: action) { row }.buttonStyle(.plain)
        } else {
            row
        }
    }
}

extension CardRow where Trailing == EmptyView {
    init(title: String, detail: String? = nil, titleColor: Color = Palette.onBackground, action: (() -> Void)? = nil) {
        self.init(title: title, detail: detail, titleColor: titleColor, action: action, trailing: { EmptyView() })
    }
}

struct RowDivider: View {
    var body: some View {
        Rectangle().fill(Palette.line).frame(height: 0.5).padding(.leading, 16)
    }
}

/// A card on home that asks for something: a title, a body and one action (Android's HomeCard).
struct HomeCard: View {
    let title: String
    let text: String
    var action: String = ""
    var warning: Bool = false
    var onAction: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.rowTitle).foregroundStyle(warning ? Palette.warning : Palette.onBackground)
            Text(text).font(.bodyText).foregroundStyle(Palette.muted)
            if !action.isEmpty {
                Button(action, action: onAction).buttonStyle(.quiet)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Palette.raised, in: RoundedRectangle(cornerRadius: Corner.card))
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }
}

/// A note on a page: muted body text.
struct PageNote: View {
    let text: String
    var color: Color = Palette.muted

    init(_ text: String, color: Color = Palette.muted) {
        self.text = text
        self.color = color
    }

    var body: some View {
        Text(text).font(.bodyText).foregroundStyle(color).fixedSize(horizontal: false, vertical: true)
    }
}

/// An empty list: an icon and a line, centred.
struct EmptyState: View {
    let icon: String
    let text: String

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: icon).font(.system(size: 40, weight: .light)).foregroundStyle(Palette.muted)
            Text(text).font(.bodyText).foregroundStyle(Palette.muted).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 40)
    }
}

/// A text field as the pages draw it: a label above, a raised field, a help line below.
struct LabeledField: View {
    let label: String
    @Binding var text: String
    var help: String? = nil
    var error: String? = nil
    var keyboard: UIKeyboardType = .default
    var content: UITextContentType? = nil
    /// Addresses, codes and emails are written left to right in Hebrew too.
    var leftToRight: Bool = false
    var capitalization: TextInputAutocapitalization = .never
    @Environment(\.layoutDirection) var direction

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.sectionLabel).foregroundStyle(Palette.muted)
            TextField("", text: $text)
                .font(.system(size: 16))
                .keyboardType(keyboard)
                .textContentType(content)
                .textInputAutocapitalization(capitalization)
                .autocorrectionDisabled()
                .environment(\.layoutDirection, leftToRight ? .leftToRight : direction)
                .multilineTextAlignment(.leading)
                .padding(.horizontal, 14)
                .frame(minHeight: 50)
                .background(Palette.raised, in: RoundedRectangle(cornerRadius: Corner.tile))
                .overlay(RoundedRectangle(cornerRadius: Corner.tile).strokeBorder(error == nil ? Palette.line : Palette.error, lineWidth: 1))
            if let error {
                Text(error).font(.caption13).foregroundStyle(Palette.error)
            } else if let help {
                Text(help).font(.caption13).foregroundStyle(Palette.muted)
            }
        }
    }
}

// MARK: - Messages

/// What the app tells the user after an action: one bar at the bottom (style.md: no alerts, no toasts).
@MainActor
final class Messages: ObservableObject {
    struct Message: Equatable {
        let id = UUID()
        let text: String
        let isError: Bool
    }

    @Published private(set) var current: Message?
    private var hide: Task<Void, Never>?

    func show(_ text: String, isError: Bool = false) {
        hide?.cancel()
        let message = Message(text: text, isError: isError)
        withAnimation(.easeOut(duration: 0.2)) { current = message }
        // An error stays until dismissed; a confirmation leaves by itself.
        guard !isError else { return }
        hide = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard !Task.isCancelled else { return }
            self?.dismiss()
        }
    }

    func error(_ text: String) { show(text, isError: true) }

    func dismiss() {
        hide?.cancel()
        withAnimation(.easeIn(duration: 0.2)) { current = nil }
    }
}

struct MessageBar: View {
    @EnvironmentObject private var messages: Messages

    var body: some View {
        if let message = messages.current {
            HStack(alignment: .top, spacing: 12) {
                Text(message.text)
                    .font(.bodyText)
                    .foregroundStyle(Palette.onBackground)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button(action: messages.dismiss) {
                    Image(systemName: "xmark").font(.system(size: 13, weight: .semibold)).foregroundStyle(Palette.muted)
                        .frame(width: 28, height: 28)
                }
                .accessibilityLabel(L("message_dismiss"))
            }
            .padding(.leading, 16)
            .padding(.trailing, 8)
            .padding(.vertical, 12)
            .background(Palette.raised, in: RoundedRectangle(cornerRadius: Corner.card))
            .overlay(RoundedRectangle(cornerRadius: Corner.card).strokeBorder(message.isError ? Palette.error : Palette.line, lineWidth: 1))
            .shadow(color: .black.opacity(0.12), radius: 12, y: 4)
            .padding(.horizontal, 16)
            .padding(.bottom, 12)
            .transition(.move(edge: .bottom).combined(with: .opacity))
            .id(message.id)
        }
    }
}

/// The quiet "Offline" pill of the home screens.
struct OfflinePill: View {
    var body: some View {
        Text(L("library_offline"))
            .font(.small.weight(.semibold))
            .foregroundStyle(Palette.muted)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Palette.raised, in: Capsule())
            .overlay(Capsule().strokeBorder(Palette.line, lineWidth: 1))
    }
}
