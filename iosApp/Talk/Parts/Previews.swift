import SharedLogic
import SwiftUI

/// How a message reads in one line: a chat row's second line, and a call's or clip's line in the chat (Android's
/// previewOf and callLine).
enum Lines {
    /// 1:05
    static func length(_ ms: Int64) -> String {
        let s = (ms + 500) / 1000
        return String(format: "%d:%02d", Int(s / 60), Int(s % 60))
    }

    static func call(_ message: TextMessage) -> String {
        guard let call = message.call else { return message.body }
        let kind = L(call.video ? "call_kind_video" : "call_kind_voice")
        switch call.outcome {
        case .answered: return call.durationMs > 0 ? L("call_line_length", kind, length(call.durationMs)) : kind
        case .missed: return L("call_line_missed", kind)
        case .declined: return L("call_line_declined", kind)
        case .noAnswer: return L("call_line_no_answer", kind)
        case .busy: return L("call_line_busy", kind)
        case .cancelled: return L("call_line_cancelled", kind)
        case .elsewhere: return L("call_line_elsewhere", kind)
        default: return L("call_line_failed", kind)
        }
    }

    static func preview(_ message: TextMessage) -> String {
        if message.deleted { return L("chat_deleted") }
        if message.call != nil { return call(message) }
        if let clip = message.clip {
            let length = clip.durationMs > 0 ? Self.length(clip.durationMs) : nil
            if clip.kind == ClipKind.burst {
                return length.map { L("chats_burst_length", $0) } ?? L("chats_burst")
            }
            return length.map { L("chats_voice_length", $0) } ?? L("chats_voice")
        }
        if let media = message.media, message.body.isEmpty {
            if !media.caption.isEmpty { return media.caption }
            // Attachment kinds (AttachmentKind): 1 is a picture, 2 a video; anything else a file.
            let kind = media.items.first?.kind ?? 1
            return L(kind == 1 ? "media_picture" : kind == 2 ? "media_video" : "media_file")
        }
        return message.body
    }

    static func groupName(_ group: TalkGroup) -> String {
        group.name.trimmingCharacters(in: .whitespaces).isEmpty ? L("group_unnamed") : group.name
    }

    /// "online", "last seen …", or nothing when they don't show it.
    static func presence(_ presence: UserPresence?) -> String? {
        guard let presence else { return nil }
        if presence.online { return L("chat_online") }
        guard let seen = presence.lastSeen else { return nil }
        return L("chat_last_seen", Dates.full(seen.int64Value))
    }
}
