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
2026-11-24 (hen): the draft and its message are one list item (one key, `LiveKeys`), so nothing is removed and added:
no jump, and nothing scrolls when it turns (it is in place already); if the message differs from the last draft its
text crossfades. Their draft counts as a new message for scrolling: appearing or growing at the bottom, the list glides
to it; erased, it shrinks away and the list glides back.

**Scrolling to new messages** (2026-11-24, hen): at the bottom, a new message (or one sent) glides the list to the new
end, eased over 300 ms, after the new item is laid out, landing exactly at the bottom (`glideToEnd`). Scrolled up, the
list stays and the "New message" chip shows.

**Send's flight** (2026-11-24, hen): the bubble written in becomes the sent message. A copy is drawn over the chat from
the draft's place to the message's slot (kept unseen at the end of the list, scrolled into view, wherever the keyboard
puts it), 300 ms on one easing: the coral outline gives way to the teal fill, it moves and resizes with the text at its
size, "Your message" fades out and the time and status circle fade in. At the end the message shows and the copy goes in
the same frame. As it leaves, a new empty draft pops up in the pinned place (from 0.85 with a fade, 200 ms), the keyboard
still up and focused on it. The same in the quick-reply window.

**The keyboard** (2026-11-24, hen): the chat follows the keyboard's animation frame by frame (its insets, `imePadding`,
with the activity edge to edge and adjustResize); the list, upside down, stays anchored at its bottom and rides up and
down with it, with no scroll afterwards. The button row folds away by the same amount the keyboard is open (no
animation of its own), and the room under the list is the pinned draft and the row as measured, every frame. The
quick-reply window isn't fitted by the system any more: it grows upward by the keyboard's height as it animates.

**Right to left** (2026-11-24, hen's phone is in Hebrew): pivots are left-based, so a bubble grows from its own side
(`sideOrigin`: theirs from the start, the right in Hebrew; mine from the end); Send's flying copy covers the whole chat
and is placed at its window position with `place()` (not mirrored), so it flies to its slot rather than to the mirror
of it. Tested in RtlTransitionsTest.

**Voice | video** (2026-11-24, hen): gone (2026-11-24, later: "holding the button transmitting audio, sliding the
button up open hands free video"). The Talk button is always voice; video is hands-free, from sliding up to the lock
(see "Hold for voice, slide up for hands-free video" below). The camera opens only as the finger starts sliding up, and
while video is live.

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
each message of mine they haven't read, one small circle (hen picked "filling circle"; replaced by envelopes 2026-11-24, see Status marks below): a dashed ring turning slowly
while sending, an empty ring once the server has it, a solid dot once it is on their phone, nothing once read; each
change eases (the ring closes, then fills). Their small letter circle sits right after the last message of mine they
read, gliding down as they read; a tap shows when they read it ("read 20:44"). When they read everything it sits at the
very end, and when they start Live typing that circle grows into their "Writing…" bubble (and back if they clear it).
No circle when they hide their presence (their marks stop at the dot). In a group (2026-11-23, hen) each member's
phone sends one read receipt to the group (end to end, through the group Send, no push), and each member's circle
sits under the last message of mine they read: at most 3 in one place, then "+N"; a tap lists who read and when. A
member who hides their presence shows no circle; a message of mine counts as read once anyone read it or a later one.

**Transitions** (2026-11-23, hen: "everything with a transition, no sharp changes"): every item in the chat fades and
slides in and out and glides when it moves (200 ms); the marks cross-fade; the button row folds away and back with the
keyboard.

**Video bursts in the chat** (2026-11-23, hen: "every video a person sends will be in the chat"): a push-to-video burst,
sent or received, is a video bubble at its time: its first picture (decoded from the clip kept on the phone, in
memory only), its length ("live" while it is said), a play mark; a tap plays it full screen over the chat (Follow face
as set), Back or a tap closes it; a long press saves it as a file. Voice bursts stay voice bubbles.
Push-to-video from the chat (2026-11-23, hen; since 2026-11-24 only hands-free, from sliding up to the lock): holding Push-to-talk puts this phone's camera live in an outlined bubble at the end of the chat (red dot,
time); on release the same bubble stays and becomes the sent video (first picture, length, status circle). Their
push-to-video plays live in their bubble the same way, then stays as the video. No separate preview over the chat.

**Both pushing at once** (2026-11-23, hen): a press while the other holds the floor still records ("Waiting for
Sharon…" on the live bubble); the moment their burst ends it is asked for and sent, so both phones see the two in press
order. Three such overlaps within a minute show a "Switch to a video call?" chip for 10 s. Two video bursts that overlap
in time, one each side, sit side by side in one row (theirs on their side, mine on mine), the later one lower by how
much later it started, both a little smaller.

**The draft is pinned** (2026-11-23, hen): this phone's draft bubble (and a voice message's recording strip) is not
in the list: it sits fixed under the chat, above the keyboard or the buttons, and grows upwards; the chat is laid out
above it and scrolls behind nothing. On Send the message fills in at the end of the list and the pinned draft empties.


**The button row, on the screen itself** (2026-11-24, hen): laid out left to right the same in Hebrew and English, so
the thumb finds it in one place: Voice message, Voice call, Video call, then the big push-to-talk button near the right
edge and the upright Voice | video switch at the edge; my camera's thumbnail on the button's top-left corner.

**The writing bubble** (2026-11-24, hen: "a bit wider"): the empty "Write a message" one is 38.2% of the row (1 − 0.618, the golden ratio); writing,
it starts at that width (nothing shrinks as the first letter goes in) and grows with the text, at once, up to 85%,
then wraps (`draftWidth`); anchored on my side (the left in Hebrew, `MySide`). Sent bubbles keep their 78% at most, and
Send's flight eases the width from one to the other. The edit box takes the same widths, never narrower than the
bubble was; the text wraps inside it, and past 6 lines it scrolls inside
with the caret in view. Their live draft follows its text at once too; only its turn into the message eases. Its
place, and the row's folding, come straight from the keyboard's animated insets, frame by frame.

**Typing reads** (2026-11-24, hen): typing or a live draft to them marks what they wrote read first, the receipt before
the draft (Messaging.sendTyping, draft). In the quick-reply window, opening the draft, talking or sending reads them;
only showing the window doesn't.


**Editing a sent text** (2026-11-24, hen): a double tap on one of my texts turns it in place (200 ms) into the writing
bubble's look, the caret where the finger was (the text's own layout, right to left too), the keyboard up. The box
keeps the bubble's bounds: the same width, place and lines; only its colour and outline turn (teal to coral, no
label), and ✓ takes the place of the time and its mark at the bottom, so the height doesn't change either. It widens
only when the typed text no longer fits, toward the middle (the right in Hebrew), up to 85% of the row, then wraps
(`editBoxWidth`, RtlTransitionsTest). A tap on (or focus in) the writing bubble ends the edit unchanged, the focus moving there with the keyboard staying up; a double tap on another of my texts ends the first and starts the new one (`EditSession`, EditSessionTest). ✓ saves; Back or a tap outside turn it back unchanged (no ✕). The edit goes end to end like
a text (`EditContent` in Content field 19, kept and pushed silently, also to my other phones; in a group through the
group's Send); every phone replaces the text and shows "edited" by the time. Only the author's edit of their own text
counts; an id a phone doesn't have is ignored; older apps skip the field. While I edit, live only like live typing
(ephemeral): with Live typing on for the chat they see that message's text change letter by letter in place, else
only that it is being edited; either way their bubble gets a soft outline and "Editing…", and its size eases. A cancel
puts it back; it also goes 20 s after the last word. No server change: live-only sends exist. Messaging.editText,
editLive; MessagingTest covers the edit, the author check, an unknown id, and the live view on cancel and timeout.


**Status marks** (2026-11-24, hen's pick "E2 · Flying, sent, sealed", replacing the filling circle): on my messages,
by the time, an envelope drawn on a 16-unit square in the time's colour at 75%: Sending flies (shifted ahead, two
speed lines trailing and drifting; mirrored in Hebrew so they trail behind); Sent rests; Delivered gets a round seal
on its flap, popping in; Read: no mark (their letter circle says it). Each change eases. Failed: the envelope in the
warning colour. `StatusMark`, the chat, the quick-reply window and groups.


**Neighbours follow in the same frame** (2026-11-24, hen: "don't make the surrounding objects move with delay"): while
a bubble changes its size in place (an edit, Send's flight, a live draft or live edit), the other items have no
placement animation of their own, so they move with it frame by frame; when one is added or removed they glide.


**Selecting messages** (2026-11-24, hen, Signal's way): a long press selects a message (a soft highlight); the header
turns into its actions: ✕ and how many on the start side, then Edit (one text of mine: the same in-place edit box,
the caret at the end), Info (one: a sheet with when it was sent or received, for mine when it was delivered and read,
in a group each member's read time, and when it was edited) and Delete. A tap adds or removes others; with more than
one, only Delete. Back or a tap outside ends it. The edit box keeps only ✓ (18 dp, 48 dp touch area, 6 dp under the
text). The sheets are drawn in the screen (no dialog window, so the quick-reply window has them too).

**Delete** (2026-11-24, hen): Delete for everyone (all selected are mine) or Delete for me (anyone's, this phone only,
nothing sent). The messages leave the chat at once (they fade and the others close up), with "Message deleted · Undo"
above the writing bubble for 4 s; it is done only then (leaving the chat does it at once), and Undo brings them back.
For everyone It goes end to end (`DeleteContent`, Content field 20, kept and pushed silently, to my other phones and,
in a group, through its Send); older apps skip it. Their phones keep a faint "Message deleted" in its place (any live
edit view cleared, a burst's or voice message's file removed); my other phones drop it. Only the author's delete
counts; an unknown id is ignored (MessagingTest).


**Days** (2026-11-24, hen): a quiet divider where the day changes (Today, Yesterday, a weekday within the last 6 days,
a date this year, else the full date; calendar days in the phone's time zone), and a small pill floating under the
header with the day of the topmost message on screen when it isn't today, crossfading as the day changes and fading
away at today's messages (DaysTest).


**Scroll to the end** (2026-11-24, hen): scrolled up more than a screen (or with new messages below), a round ⌄
appears (scale and fade) above the writing bubble on their side (the right in Hebrew), clear of the buttons, with a
badge of how many came in meanwhile; a tap glides to the end and clears it; at the end it goes. It replaces the "New
message" chip.


**Their caret** (2026-11-24, hen): with Live typing on, the writer's caret (and selection) goes with the live text, a
caret move alone too, at most 10 a second (TypingContent and EditContent `selStart`/`selEnd`, optional, -1 when not
said; older apps skip them). In their live draft and in a message they are editing, a thin blinking caret in the accent
colour at that place, placed by the text's own layout (right to left and mixed text land right), gliding between
places; a selection gets a soft highlight. Gone with the draft or the edit (save, cancel, timeout). Without a caret said
(an older app), the draft shows a bar at its end as before (MessagingTest).


**The writing bubble, one element** (2026-11-24, hen; 1.4.19): no "Your message" label. Resting ("Write a message…",
dashed) and writing (coral outline, caret, Send) are one element that morphs both ways in 250 ms on the keyboard's
easing: the dash fades into the coral outline, the fill deepens, the resting words fade and slide a little as the
caret fades in, Send scales in; empty on leaving, the exact reverse; with text in it, it stays writing. The same size
in both (no jump), anchored on my side at 38.2% of the row. A tap anywhere on it (not only on its words; 1.4.21) or its focus starts writing. Send's flight
has no label to fold either.


**Video bubbles play by themselves** (2026-11-24, hen; 1.4.20): no play mark. The video most on screen (at least 60%
of it) plays in its bubble, muted and looping, one at a time (side-by-side videos count each); it stops as it leaves
the screen and while the app isn't in front. Its frames come from the clip kept on the phone, decoded on their own
thread into the bubble at their own times (`InlineVideo`, `VideoAutoplay`). A speaker-off mark in its corner (the end
side, a single tap) or a double tap on it plays it here with sound from the start (a single tap on it does nothing,
so scrolling can't start one); a tap while it plays opens it full screen; a long press selects it, as any message
(Save as file is in the selection's header now, for a burst or voice message); another video
becoming the most visible one, or scrolling it away, stops the sound. The same in the quick-reply window.


**Chat direction** (2026-11-24, hen; 1.4.22): each contact's and group's page has Chat direction: Follow the phone
(the default), Right to left or Left to right, this phone's only (TalkSettings.directionFor, never sent). The chat (and
its quick-reply window) is laid out that way: which side the bubbles, the writing bubble, the circles and the
animations take. Each message's words keep their own direction from their first strong letter (`LibraryType.Message`,
TextDirection.Content), so English is left to right and Hebrew right to left in either layout, and a live caret lands
by that (it showed at the start of an English line on a right-to-left phone).

**Their read circle as I send** (2026-11-24, hen; 1.4.22). Their phone says when it has the chat in view
(ViewingContent, live only, every 20 s, and when it leaves; only from those who show when they're online; the
quick-reply window once its user has answered there, when it starts reading at once). Sending at the end of the chat:
- they have it in view: the circle stays at the end and the new message comes in above it, the rest moving up in one
  glide; their receipt then moves nothing (no receipt in 3 s: the circle glides back above it);
- else: the new message comes in under the circle and all of it moves up in one glide; when they read it, the circle
  slides down past it (the circle is one item wherever it is, so it slides rather than fading).
In a group each member's circle follows the same rule. Send's flight goes straight to the message's own slot.

**Live edit's end** (1.4.22): saving sends an end to the live view too, and a live frame written before the save that
comes after it is ignored (each live frame carries when it was written), so "Editing…" never stays on their phone.

## Live in the chat (2026-11-24, hen)

- **Who is here.** No general "online" or "last seen" in the header (only typing is said there). Whether they are in
  this chat shows at its end: their face (or letter) where their reading reached, full size with a slow teal ring
  breathing around it while they have this chat open (the viewing signal), smaller and grey after. In a group each
  member's face has its own ring; those here come first in the at-most-3-then-+N row. Someone who hides their presence
  sends no viewing signal, so they never ring.
- **The live panel** (2026-11-24, hen: "when two sides push to video show videos at top side by side; when I push
  show the video on the top, never on bottom"; then "the video should be the same size" as the floating window).
  Push-to-video shows only under the header, never at the bottom: a box 23.6% of the screen high and as wide as the
  picture's own shape (no crop, no bars), rounded 18 dp, centred; it drops down (250 ms) when the first video starts
  and slides back up when the last ends. Both at once: two such boxes of the same height side by side, centred,
  theirs at the start side and mine at the end (following the chat's direction), shrunk together only if they'd be
  wider than the screen. The tags sit inside each video on one line, a red dot with the name in a group (or "You") and the time. The chat is laid out below
  the boxes (nothing drawn under them), and the oldest line in view fades at the list's top edge. While my camera
  shows there, the small preview on the Talk button's corner is hidden (one preview at a time). A voice-only press
  shows the voice bar instead.
- **Voice pills** (2026-11-24, hen: a voice push "isn't really showing"; then "B, a slim pill at the top" and "if we
  both voice pushing then it's side by side pills"). While someone pushes voice, a slim pill slides down under the
  header (250 ms) and pushes the chat down: their face in a teal ring that glows with their voice (brightness, glow
  and thickness; no wave), "Sharon is talking" (gendered) and the time. My own voice push: my face in a coral ring,
  one line "You · 0:03". Both at once: two pills side by side, centred, theirs at the start side. In a group it is the
  floor holder's face and name. When one side is on video and the other on voice, the pill sits beside the video box,
  top-aligned. Voice turning into video: the pill shrinks away as the box grows in (the floating window morphs the
  pill into the box).
  It slides back up when the talking ends. The header's "talking" line is gone (the bar or the panel says it); the
  line under the header is kept only for the rest (busy, refused, offline, recording a voice message, my other phone).
- **Not kept by default.** A push-to-talk burst stays in the chat as a voice or video message only when the chat's
  "Keep voice in chat" / "Keep videos in chat" is on (contact or group page, In the chat; both off by default). Off,
  nothing shows while it is live (the panel does), and once it ends a muted centred line stays: "hen sent live video ·
  0:07", "You talked · 0:03", not playable. Bursts back and forth from both sides with nothing else between (ten
  minutes apart at most) are one line per session: "Push-to-talk · hen ×5, you ×4 · 21:40–21:45". A tap lists each one
  under it (who, length, time); a tap again folds them. Voice messages from the microphone button always stay.
- **Hold for voice, slide up for hands-free video** (2026-11-24, hen: "holding the button transmitting audio, sliding
  the button up open hands free video"). Holding the Talk button is voice push-to-talk for as long as it's held (the
  voice bar shows it). While holding, a target shows above the button, a camera over a lock; the camera opens as the
  finger starts sliding up (so the video starts fast), and reaching the target (72 dp) sends the voice so far and
  switches to hands-free video: my box shows at the top and it keeps sending after the finger lifts, until **Stop**,
  which takes the button's place. The server ends a floor at 60 s, so at 55 s it is let go and taken again at once
  (each part a burst of its own). Leaving the chat stops it. There is no Voice | Video switch any more, and no
  hands-free voice (it went with this gesture). Without the camera allowed, reaching the target asks for it.
- **Both live.** In a one-to-one chat a press (or a lock) while they hold the floor doesn't wait behind it
  (2026-11-24, hen, "a"): my camera shows in the live panel beside theirs, nothing is sent yet, and my phone tells theirs I'm live
  (`liveWaiting`, live only). If their floor ends while I'm still live, my burst starts. In a group a press still waits
  its turn. A press the server still queues (it came just as their floor started, or a hands-free renewal) is turned
  into the same standby. Either side then shows "You're both live · Make it a call" just under the live panel, in its
  own space. A tap starts a video call shown inside
  the chat; their phone, both live too, takes it at once, also inside the chat. With "Both live becomes a call" on
  for the chat (off by default) it happens after 10 s without the tap.
- **A call inside the chat.** In the live panel's place and the same size: 3:4 boxes 23.6% of the screen high, side
  by side and centred, theirs from the start side and mine at the end (in a group two of theirs at most, then "+N"). At the bottom only a teal bar
  "Video call · 3:20" with End, then mute, camera, flip and full screen. Messages stay visible
  between them. Full screen moves it to the usual call screen; leaving the chat shows the usual call screen too. When
  it ends, the panels go and the call's line ("Video call · 3:20") stays, with its entry in Calls.
