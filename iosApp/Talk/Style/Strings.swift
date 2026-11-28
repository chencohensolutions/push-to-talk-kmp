import Foundation

/// The app's words are the Android app's, under the same keys (Localizable.strings, written by tools/ios-strings.py
/// from strings.xml). `L("chats_you", name)` is Android's `stringResource(R.string.chats_you, name)`.
/// The few words only iOS has are in Ios.strings, written by hand, under keys that start with "ios_".
func L(_ key: String, _ arguments: CVarArg...) -> String {
    let format = key.hasPrefix("ios_") ? NSLocalizedString(key, tableName: "Ios", comment: "") : NSLocalizedString(key, comment: "")
    return arguments.isEmpty ? format : String(format: format, locale: Locale.current, arguments: arguments)
}

/// A plural (Localizable.stringsdict): `LP("group_members", 3)`.
func LP(_ key: String, _ count: Int) -> String {
    String.localizedStringWithFormat(NSLocalizedString(key, comment: ""), count)
}

enum Dates {
    private static let time: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .none
        f.timeStyle = .short
        return f
    }()

    private static let day: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.timeStyle = .none
        return f
    }()

    private static let dayAndTime: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.timeStyle = .short
        f.doesRelativeDateFormatting = true
        return f
    }()

    static func date(_ millis: Int64) -> Date { Date(timeIntervalSince1970: Double(millis) / 1000) }

    /// 14:05
    static func time(_ millis: Int64) -> String { time.string(from: date(millis)) }

    /// Today, Yesterday, or the date: the line between two days of a chat.
    static func day(_ millis: Int64) -> String {
        let d = date(millis)
        if Calendar.current.isDateInToday(d) { return L("chat_today") }
        if Calendar.current.isDateInYesterday(d) { return L("chat_yesterday") }
        return day.string(from: d)
    }

    /// The time for today, else the date: a chat row's corner.
    static func short(_ millis: Int64) -> String {
        let d = date(millis)
        return Calendar.current.isDateInToday(d) ? time.string(from: d) : day.string(from: d)
    }

    /// "Today at 14:05", "3 Nov 2026 at 09:10".
    static func full(_ millis: Int64) -> String { dayAndTime.string(from: date(millis)) }

    static var nowMillis: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
