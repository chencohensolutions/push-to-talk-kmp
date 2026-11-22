# Conversation

`ui/contacts/ConversationScreen.kt`; opened from [Chats](chats.md), New chat, or a [notification](notifications.md).
Signal's conversation in the library's look. How texts, receipts, typing and presence work is in
[design.md](../design.md), "Phase 3".

- **Header:** back; the contact's letter circle, their name (the display name when they gave one) and a shield when
  verified; under it "typing…" or "online" (teal), or "last seen <time>" (muted; a date before today), or nothing when
  it isn't known or hidden. A tap on the name opens the [contact's page](contacts.md) (safety number, block, remove).
- **Safety number changed** (a warning card on top), when `Contact.changedKey` is set: "They may have a new phone, or
  someone may be pretending to be them. Your texts wait until you accept the new number on their page." **Review**
  opens their page.
- **Texts**, newest at the bottom: theirs in Raised bubbles on the left, ours in Ink on the right; under each the time,
  and for ours how far it got: "Waiting to send" (the outbox), "Sent", "Delivered", "Read", or "Not sent · tap to try
  again" (orange; a tap retries).
- **The line:** "Message", up to 5 lines, and **Send** (ink). Typing is told to them while the line has text.
  Instead of it: "You blocked <user>. Unblock them on their page to write again.", "Waiting for them to confirm the
  pair", or "This contact isn’t in your list anymore."

While it is on screen, texts that come in are read at once (no notification), and a read receipt goes back.

A group's chat uses this screen with a group header and authors on the bubbles: see [groups.md](groups.md).
