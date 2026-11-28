import SharedLogic
import SwiftUI

/// A contact's or group's circle: their picture when they have one, else the first letter of the name on a colour
/// that stays theirs (Android's letter circle).
struct Avatar: View {
    let user: String
    let name: String
    var size: CGFloat = 44
    var group: Bool = false
    @EnvironmentObject private var chats: ChatStore
    @EnvironmentObject private var pictures: Pictures

    var body: some View {
        ZStack {
            if let image = pictures.image(user: user, id: IosViews.shared.pictureId(state: chats.state, user: user)) {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Circle().fill(Self.color(for: user))
                if group {
                    Image(systemName: "person.2.fill").font(.system(size: size * 0.38)).foregroundStyle(.white)
                } else {
                    Text(Self.letter(name)).font(.system(size: size * 0.42, weight: .semibold)).foregroundStyle(.white)
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .accessibilityHidden(true)
    }

    static func letter(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespaces).first.map { String($0).uppercased() } ?? "?"
    }

    /// One of a few calm colours, picked by the user name so it is the same on every screen and every start.
    static func color(for user: String) -> Color {
        let hues: [UInt32] = [0x1F5F5B, 0x8A4B6B, 0x4B5F8A, 0x7A6A2F, 0x5B4B8A, 0x2F6E7A, 0x8A5A3A, 0x4B7A52]
        var hash: UInt32 = 2166136261
        for byte in user.utf8 { hash = (hash ^ UInt32(byte)) &* 16777619 }
        return Color(UIColor(rgb: hues[Int(hash % UInt32(hues.count))]))
    }
}
