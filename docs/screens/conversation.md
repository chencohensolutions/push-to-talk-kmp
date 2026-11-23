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

**Live drafts** (2026-11-23, hen): when the contact uses Live typing (Settings › Privacy), what they are writing shows
at the bottom as an outlined "Writing…" bubble (in a group, "Dana is writing…"), updated as they write, deletions
too; the message replaces it, and it fades 20 s after their last change.
A growing draft stays in view when the list is at the bottom; when the message comes, the draft turns into it in place
(250 ms: the outline fills, "Writing…" folds away, the time fades in), in the chat and the quick-reply window alike.

**Writing in the chat** (2026-11-23, hen, from the approved mockup "chat-typing-in-chat"): there is no line to write in
at the bottom. The last thing in the chat is an empty dashed bubble on this phone's side, "Write a message…"; a tap
opens the keyboard and makes it the draft: an outlined bubble (coral outline, faint fill, "Writing…") edited in place,
with a round Send inside it at the bottom end. The chat keeps it in view as it grows; Live typing, when on for the
chat, sends this same text. Send fills it in place into the sent message (250 ms, the same turn as an incoming draft),
and a new empty draft comes under it with the keyboard still up. With the keyboard up nothing else shows under the
chat; Back or a tap on the chat puts the keyboard away. Under the chat, a row of round buttons, icons only (each with
its name for TalkBack): Video call, Push-to-talk (big, hold to talk), Voice call, Voice message. Voice message: a tap
starts recording; a strip at the end of the chat shows a red dot, the time, the voice's level and ✕ to throw it away,
and the button is Send meanwhile. The header keeps the name, presence and Talk mode; the call buttons moved down. The
old line had no attachments, so nothing else needed a new place.

**How far they read** (2026-11-23, hen, from the approved mockups; `ReadMarks`): no "Read" word. Beside the time of
each message of mine they haven't read, one small circle (hen picked "filling circle"): a dashed ring turning slowly
while sending, an empty ring once the server has it, a solid dot once it is on their phone, nothing once read; each
change eases (the ring closes, then fills). Their small letter circle sits right after the last message of mine they
read, gliding down as they read; a tap shows when they read it ("read 20:44"). When they read everything it sits at the
very end, and when they start Live typing that circle grows into their "Writing…" bubble (and back if they clear it).
No circle when they hide their presence (their marks stop at the dot). Groups: no circles yet (the app has no read
receipts per member in groups).

**Transitions** (2026-11-23, hen: "everything with a transition, no sharp changes"): every item in the chat fades and
slides in and out and glides when it moves (200 ms); the marks cross-fade; the button row folds away and back with the
keyboard.

**Video bursts in the chat** (2026-11-23, hen: "every video a person sends will be in the chat"): a push-to-video burst,
sent or received, is a video bubble at its time: its first picture (decoded from the clip kept on the phone, in
memory only), its length ("live" while it is said), a play mark; a tap plays it full screen over the chat (Follow face
as set), Back or a tap closes it; a long press saves it as a file. Voice bursts stay voice bubbles.
Push-to-video from the chat (2026-11-23, hen): with the chat's Voice | Video on Video the camera stays open while the
chat shows; holding Push-to-talk puts this phone's camera live in an outlined bubble at the end of the chat (red dot,
time); on release the same bubble stays and becomes the sent video (first picture, length, status circle). Their
push-to-video plays live in their bubble the same way, then stays as the video. No separate preview over the chat.
