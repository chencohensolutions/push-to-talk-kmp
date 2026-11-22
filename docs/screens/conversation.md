# Conversation

`ui/contacts/ConversationScreen.kt`; opened from [Chats](chats.md), New chat, or a [notification](notifications.md).
Signal's conversation in the library's look. How texts, receipts, typing and presence work is in
[design.md](../design.md), "Phase 3".

- **Header:** back; the contact's letter circle, their name (the display name when they gave one) and a shield when
  verified; under it "typing…" or "online" (teal), or "last seen <time>" (muted; a date before today), or nothing when
  it isn't known or hidden. A tap on the name opens the [contact's page](contacts.md) (safety number, block, remove).
  At the right, **Video call** and **Voice call** ([calls.md](calls.md)), then the **Talk button** (a walkie-talkie) opens [Talk mode](talk.md); it shows only where one can talk
  (paired and not blocked, or a member of the group). While someone talks there (or this phone does), a teal line
  under the header says so ("Dana is talking", "Live"); a tap opens Talk mode.
- **Safety number changed** (a warning card on top), when `Contact.changedKey` is set: "They may have a new phone, or
  someone may be pretending to be them. Your texts wait until you accept the new number on their page." **Review**
  opens their page.
- **Texts**, newest at the bottom: theirs in Raised bubbles on the left, ours in Ink on the right; under each the time,
  and for ours how far it got: "Waiting to send" (the outbox), "Sent", "Delivered", "Read", or "Not sent · tap to try
  again" (orange; a tap retries). At the bottom, a new message scrolls into view; scrolled up, the list stays put.
- **Bursts and voice messages** are bubbles with a Play button, a bar and their length ([talk.md](talk.md)).
- **The line:** "Message", up to 5 lines, and **Send** (ink) once there is text; while it is empty, the microphone
  instead: hold it to record a voice message ([talk.md](talk.md)). Typing is told to them while the line has text.
  Instead of it: "You blocked <user>. Unblock them on their page to write again.", "Waiting for them to confirm the
  pair", or "This contact isn’t in your list anymore."

While it is on screen, texts that come in are read at once (no notification), and a read receipt goes back.

A group's chat uses this screen with a group header and authors on the bubbles: see [groups.md](groups.md).

- **New messages at the bottom** (2026-11-22, hen): at the bottom of the conversation (or one message from it), a new
  message or clip scrolls into view; scrolled up, a "New message ↓" chip shows at the bottom, which scrolls down. What
  this phone sends always scrolls down.

- **The microphone by the text field is push-to-talk** (2026-11-22, hen): hold it to talk live to them, as on the Talk
  screen (the floor, the burst in the chat); slide away to cancel. It no longer records a voice message.
