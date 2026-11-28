# Conversation

`ui/contacts/ConversationScreen.kt`; opened from [Chats](chats.md), New chat, or a [notification](notifications.md).
Signal's conversation in the library's look. How texts, receipts, typing and presence work is in
[design.md](../design.md), "Phase 3".

- **Header:** slim (2026-11-25, hen: "shrink the header height"): 48 dp under the status bar, with a 32 dp face; back
  and the name's row keep 48 dp touch targets. Back; the contact's letter circle, their name (the display name when they gave one) and a shield (amber) only while
  their safety number is not verified, a tap on it opening the safety number (2026-11-26, hen); under it "typing…" or "online" (teal), or "last seen <time>" (muted; a date before today), or nothing when
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

**Both pushing at once:** both are live at once; nothing waits for the other (2026-11-27, hen: "remove this logic").
Two video bursts that overlap
in time, one each side, sit side by side in one row (theirs on their side, mine on mine), the later one lower by how
much later it started, both a little smaller.

**The draft is pinned** (2026-11-23, hen): this phone's draft bubble (and a voice message's recording strip) is not
in the list: it sits fixed under the chat, above the keyboard or the buttons, and grows upwards; the chat is laid out
above it and scrolls behind nothing. On Send the message fills in at the end of the list and the pinned draft empties.


**The button row, on the screen itself** (2026-11-24, hen): laid out left to right the same in Hebrew and English, so
the thumb finds it in one place: the big push-to-talk button near the right edge, under the right thumb, with a
microphone on it (the bell while urgent). There is no Voice message button (2026-11-27, hen: "remove the microphone
button, keep only the push to transmit button, use the mic icon for the main button"): its place on Push's left stays
empty for the side target while Push is held. No call buttons in the chat (2026-11-25, hen: "in chat screen, remove the video and voice call buttons"),
in the full chat and the floating chat alike: voice and video calls start from Talk mode's actions and from a call's
row in the Calls tab ([calls.md](calls.md)).

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
  picture's own shape (no crop, no bars), rounded 18 dp, centred. Both at once: two such boxes of the same height side
  by side, centred as a pair, theirs at the language's start side and mine at the other (the right and the left in
  Hebrew), shrunk together only if they'd be wider than the screen. The tags sit inside each video on one line, a red dot with the name in a group (or "You") and the time. The boxes and pills float
  over the chat (2026-11-25, hen: "floating, not inside black box"): only their own rounded shape and a soft shadow,
  nothing behind them; the chat keeps its full height under the header and scrolls on beneath.
  **Where they rest and how they move** (2026-11-25, hen: "every combined animation in one move"; "just below the
  header"): the row rests just below the slim header, 8 dp under its edge. Every change is one move of 300 ms with an
  ease, for everything at once ([style.md](style.md), "Motion"): a side that starts comes down from under the header's
  edge (cut there, so it comes out from under the header, not over it) straight into its own place, while a side
  already there glides aside; a side that ends goes back up under the header while the other glides to the centre; a
  side turning from voice to video is ONE frame that grows from the pill into the box about its own centre, its top
  edge still (the pair staying centred when there are two), straight to the picture's known shape, and back. A side
  never leaves and comes back for that. **Never a black box** (2026-11-25, hen: "the camera turns black and then
  slides up"; a black box while waiting for the floor): a box shows its picture only once pictures really come
  (theirs through the room or the clip, mine once the room's track carries my camera's frames) and the face, large,
  until then; a side that ends goes up at once and keeps its last picture while it does (its track stays in the box
  until the box is out), the camera closing after. "You're both live · Make it a call" shows only while both sides'
  items show here (not for a side that is only waiting for the floor). The places are worked out left to right from the screen's edge whatever the
  language, which only picks the order (`LiveLayout`, `LiveMotion`, shared with the floating window; `LiveStage` in
  the chat). `adb shell setprop log.tag.LiveRow VERBOSE` logs each frame of a move. While my camera
  shows there, the small preview on the Talk button's corner is hidden (one preview at a time). A voice-only press
  shows the voice bar instead.
- **Voice pills** (2026-11-24, hen: a voice push "isn't really showing"; then "B, a slim pill at the top" and "if we
  both voice pushing then it's side by side pills"). While someone pushes voice, a slim pill comes down from under
  the header, floating over the chat as the boxes do: their face in a teal ring that glows with their voice (brightness, glow
  and thickness; no wave), "Sharon is talking" (gendered) and the time. My own voice push: my face in a coral ring,
  one line "You · 0:03". Both at once: two pills side by side, centred, theirs at the start side. In a group it is the
  floor holder's face and name. When one side is on video and the other on voice, the pill sits beside the video box,
  top-aligned. Voice turning into video: the pill itself grows into the box (above), here and in the floating window.
  **Still connecting** (2026-11-27, hen: "livekit only, show connection indication without words, only visual", in
  the pill, "not spinning, something with less movement"): a push pressed before the chat's room is ready (right
  after the chat opens, the floating window, the Talk tab) isn't live yet, and nothing is sent. My pill comes down at
  once all the same, but in still grey dashes: its outline (6 dp of every 11) and the ring round my face (twelve
  dashes), "You" greyed, no time. The moment my voice is really going out the dashes lengthen until they meet (300 ms,
  a morph like a message's dashed border, not a fade), take the coral colour, the ring glows with my voice and the
  time starts from 0:00. Nothing turns or runs. A video push shows this pill too while it connects, and grows into
  its box once live. Let go while connecting: the pill goes back up, nothing was sent. The room can't be reached
  (10 s), or is lost during a push: the pill goes back up and the phone gives one short buzz; no words
  (`OwnTalk.ROOM`). The same in the floating window's row and, as a small "You" chip in dashes, in the Talk tab's row
  (`LocalMyPushConnecting`, `joiningBorder`; `LiveCell.connecting`, `GlowRing.connecting`).
  It goes back up under the header when the talking ends. The header's "talking" line is gone (the bar or the panel says it); the
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
- **Nothing waits** (2026-11-27, hen: "what is the waiting for? ... search all app and remove this logic"): there is
  no standby, no queued press and no "Waiting for …" anywhere. Every talker has a floor of their own, so a press is
  live at once. A Busy with my own floor (my last burst's end not yet taken) lets go of my recent bursts and asks
  again, unseen (`Talk.OWN_FLOOR_RETRIES`); a Busy from anyone else (an older server) ends the press as busy, nothing
  sent or kept.
- **Full screen** (2026-11-27, hen; docs/screens/calls.md, "Live talk full screen"): "⤢ Full screen" shows just
  under the live panel while their video or both of us are live, instead of "Make it a call"; a tap ends my push and
  opens the live talk full screen, me muted. Before that:
- **Both live.** "You're both live · Make it a call" shows just under the live panel, in its own space, only while
  both sides' items are in the row. A tap turns the talk room into the call, with no ringing (2026-11-25, hen: "it's
  calling the recipient instead of opening the call seamlessly"), the same from the chat, the floating window and the
  floating chat (`startRoomCall`); when there is no shared room to turn, a short word ("Can't make this a call right
  now"), never a ringing call. Both phones go straight to the full call screen (2026-11-26, hen: "it should
  navigate to the call screen"; docs/screens/calls.md, "A push turned into a call"); their phone takes it at once. With "Both live becomes a call" on
  for the chat (off by default) it happens after 10 s without the tap.
- **A call inside the chat** (no longer used since 2026-11-26: the call screen opens instead). In the live panel's place and the same size: 3:4 boxes 23.6% of the screen high, side
  by side and centred, theirs from the start side and mine at the end (in a group two of theirs at most, then "+N"). At the bottom only a teal bar
  "Video call · 3:20" with End, then mute, camera, flip and full screen. Messages stay visible
  between them. Full screen moves it to the usual call screen; leaving the chat shows the usual call screen too. When
  it ends, the panels go and the call's line ("Video call · 3:20") stays, with its entry in Calls.

## Header edge (2026-11-25, hen: "A · Hairline")

The header (in the floating chat too) keeps the screen's own background, with one 1-px line under it in the palette's
outline variant. The live row rests just below that line.
Since 2026-11-26 the line shows only while messages are scrolled under the header (see "Solid behind the buttons, lines
only while scrolled under" below).

## Bottom buttons (2026-11-25, hen)

Push-to-talk sits in the middle of the screen, and the voice message button just to its left, the same in Hebrew and
English (not mirrored). No call buttons in the chat.
Since 2026-11-26 they are smaller and rest on a solid strip (below): Push 64 dp, Voice message 48 dp.
When hands-free gets no floor for 2.5 s it ends, and a press still on its way is let go too, so a late floor
never goes on with no Stop (2026-11-26).
While a hands-free voice or video push runs (until Stop), the voice message button shrinks and fades away in its
place, and comes back the same way when the push stops; nothing else moves (2026-11-26, hen).

## Their face's ring in the header (2026-11-25, hen: "ring only, no new words")

A solid teal ring around their face: they're on this chat's screen. A dashed one: they're in its talk room (ready to
hear, e.g. warmed in the background) without the chat on screen. No ring: neither. No words are added for it; the
header's line stays as it was (typing only).
In a one-to-one chat the read face at the chat's end no longer shows whether they're here (the header's ring does);
in a group it still does, as a group's header has no ring (2026-11-25, hen).

## A group's header: its members' faces (2026-11-25, hen chose "B")

The other members' faces (36 dp, about 6 dp apart; 2026-11-25, hen: a little bigger, a little room) sit in a row at the
header's end, the group's name at its start. Each has its ring: solid,
on this chat's screen; dashed, in its talk room; none, away. Those here come first, then those in the room, then the
rest, each in a steady order; as many as fit, then "+N"; a tap opens the group's page with its members. When someone's
state changes, the faces move to their new places together, in one move. The small read faces at the chat's end show
no presence any more, in groups too (the header does).

## Live typing lands in place (2026-11-26, hen: "it's supposed to stay in place and transition to a message")

Their live draft and the message it becomes are one item (LiveKeys: the same key), so the draft fills into the message
where it is: no remove and insert, no scroll. When their draft's end comes before the message, the draft stays where it is
for up to 1.5 s, so the message lands in its place instead of the draft going and the message coming anew. Start and end
are the language's (the bubble at the start side, the grow origin at its bottom start corner, right in Hebrew).

## Their read face beside the bubble (2026-11-26, hen: "the little avatar is misplacing often and makes problems")

One to one, their small face is no longer an item of the list. It sits beside the last message of mine they read: on
the bubble's inner side (toward the middle of the chat), at its bottom, and takes no room of its own. When they read on,
it fades out there and in beside the newer message in the same 200 ms; nothing else in the chat moves. A tap still shows
when they read it, written over the chat beside the face (it takes no width, so the bubble never gets narrower).
Before, the face was a list item that changed places (in the middle, or at the very end sharing the draft's key), so each
change pushed the chat up or down by its height, and when it left the end the list followed it and showed older messages
under the writing bubble until their next draft.

Sent while they have the chat in view, a message is taken as read from the frame it comes in, and stays so for as long
as they keep the chat in view (at least 3 s, half a minute at most without their receipt); it shows no envelope either.
Before, it was held for 3 s only, so a receipt that came later made the face go back a message and forward again.

In a group the members' faces are still a row of their own under the message (unchanged).

## A live draft, calmer (2026-11-26, hen)

- A new line: the lines already there stay in view and ride up with the bubble's top (160 ms) while the new line shows
  under them. (They were hidden for three frames at every wrap, only the new line showing.) A line fewer: the same, back.
- Their caret is always drawn over the text, at its end when their phone doesn't say where (or the draft ended and waits
  for its message); it is never a character of the text, so it can't push a word onto a new line as the message lands.
- When I send while their draft is at the chat's end, my message's place opens over the flight's 300 ms, so the messages
  above rise with the flight instead of jumping up at its start.
- Only a draft grows in; the message it became, scrolled away and back, comes whole.

## My bubble says how far it got; their faces in the gaps (2026-11-26, hen)

hen: "Message should be dashed border until it arrives to server; when arrived to server it should be solid green
border, with smart transition not a crossfade; when message arrives to all participants it should be whole green. The
avatar bubbles look good but between the messages it should be less spacing. Only when there are avatars between
messages the space is bigger to make room for the avatars. Don't stack avatars on top of each other; they should be with
a little spacing between them." (The mock: `mocks/read-avatars-mock.png`.)

This replaces the envelope marks beside the time, the read face beside the bubble and the group's row of reader circles.

**My bubble** (green `#23774a`; its outline is the same green, brighter on the dark page so the thin line reads as the
fill does):

| Where it is | The bubble |
|---|---|
| On its way, not on the server yet | A dashed green outline, no fill |
| On the server, not on every participant's phone yet | A whole green outline, no fill |
| On every participant's phone (one to one: theirs; a group: every other member's) | Whole green |
| Not sent | The dashed outline, the time in red; a tap sends it again |

- Dashed to whole is the line itself moving, not a crossfade: every dash grows from its middle until the dashes meet
  (250 ms). Then the green fades in over the whole bubble at once (250 ms; 2026-11-26, hen: "fade into green, not
  filled into green"), and the text turns from the page's colour to the bubble's with it. The two steps never run at once, so each is seen even
  when both receipts come together. A bubble that comes on screen is simply as it is; only a change moves.
- Send's flying copy lands as the dashed outline (its coral outline turns green as it opens into dashes); what came
  while it flew shows once it has landed.
- What was read was received, and whoever has a later message has the ones before it (so an old message whose receipt
  never came fills when a newer one arrives).
- In a group each member's phone now tells the group, end to end and with no push, that it has a message (a delivered
  receipt, as one to one); the author keeps who and when (`TextMessage.deliveredTo`), and Message info lists them. A
  member on an older app sends none: for them the bubble fills when they read it.
- A screen reader hears the same as the bubble's state ("Waiting to send", "Sent", "Delivered", "Not sent · tap to try
  again").

**Their faces** (22 dp), in the gap under a message of mine, on my side (the bubbles' own edge; the left in Hebrew):

- A face in a whole green ring after the last message of mine they read (not when they hide their presence); a faint
  face in a dashed circle under the last one their phone has. In the same gap only the ringed face shows.
- The gap between two messages is the chat's usual 6 dp. Only a gap with faces is taller (26 dp more), the faces in its
  middle. The gap is part of its message's own list item, not a row of its own, so nothing is added to or taken from the
  list as faces come and go.
- When someone reads or receives further, their face glides from its gap to the new one (250 ms), over the bubbles
  between, while the gap it leaves closes and the one it comes to opens, all in one move; the bubbles follow in the same
  frame and the chat stays anchored at its end. A face whose new gap is not on screen yet (a message just sent, still
  coming in) stays where it is until it is, then glides.
- Several people in one gap stand side by side, 4 dp apart, from my side inward, never over one another: ringed faces
  first, then dashed circles, each by name (so nothing shuffles as others move on). "+N" only when the row is full (13
  faces on a 360 dp wide phone).
- A tap on the faces opens a small card under them: each person, and "read 01:53" or "received 01:54".
- Sent while they have the chat in view, a message is still taken as read at once (their ringed face goes to it from
  the start); the bubble itself shows only what really came.
- Places are counted from my side inward and positions come from the layout (start and end), so it is the same in
  Hebrew and in English, mirrored.

**Rings around faces in the header** (hen: "should emerge from no border to dashed border with a continuous animation,
not a crossfade; also when transforming from dashed to solid border"): the ring is drawn with the same line as my
bubble's outline. None to dashed: the dashes grow out of nothing; dashed to whole: they grow until they meet; and back
the same way (250 ms, one move). The contact's face in the chat header and the members' faces in a group's header.


## From floating to the chat, one move (2026-11-26, hen: "should feel seamless, an expanding transformation")

A tap on the floating chat's header (or on the floating bar's Chat button) doesn't close one thing and open another: the
floating window itself grows into the chat.

- **The floating chat**: its top edge rises to the top of the screen, its round corners flatten, and its header becomes
  the chat's in the same move (it rises under the status bar; the grab bar and × go; Back comes in before the face; the
  face and the name land where the chat has them). The draft and the buttons at the bottom don't move at all, the
  messages stay where they are and the older ones show above them. 380 ms, eased. An open keyboard goes down in the same
  move, and what was written stays in the draft.
- **The floating bar**: a frame in the bar's exact place, shape and colour grows to the whole screen, its corners
  flattening and its colour turning into the chat's, the chat (laid out for the whole screen, not moving) showing
  through as it grows.
- **Then the app**: only when the window covers the whole screen, showing the chat exactly as the app does, the app
  opens under it, unseen. When the app's chat is drawn (at its end, the keyboard away, the same draft), the window goes
  (120 ms). Nothing of Android's own way in is seen, and no empty frame.
- **Back**, in a chat opened this way over another app: the same backwards. The window covers the screen, the app
  leaves under it, and the window shrinks to the floating chat over the app that was in use. After going elsewhere in
  the app, or when the window floated over this app, Back is the app's own.
- `QuickReplyWindow` (expand, growFrom, shrinkFrom), `ChatHandover`, `MainActivity` (landSoon, leaveToWindow). Debug
  builds: `-a morph_hold --es chat x --ef at 0.4` stops the move part of the way, to look at it (`--ef at -1`: off).


## Solid behind the buttons, lines only while scrolled under (2026-11-26, hen)

hen: "in chat screen the transparent layout is disorienting when scrolling"; of the mocked ways hen picked "D · Strip for
the buttons only", and "make the banner smaller, the red button smaller".

- **The strip**: behind the button row only (Voice message and Push), a solid strip in the page's own colour, light and
  dark, as tall as the row. Messages never show behind the buttons. With the keyboard up the row folds away and the
  strip with it, in the same move. The notes that stand in the row's place (blocked, waiting to pair, a group's note)
  rest on the same strip.
- **Its line**: one 1-px line in the outline variant on the strip's top edge, only while messages are scrolled under
  the strip (the newest message has passed the writing bubble on its way down); it fades in and out over 200 ms. At the
  chat's end there is no line.
- **The writing bubble** has a solid fill in the page's colour (it was 92%), and floats above the strip with my face,
  as before; messages pass beside them, never readable through the bubble.
- **The header's line** shows only while messages are scrolled under the header (the list isn't at its oldest end),
  fading in and out over 200 ms; none at the chat's oldest end. The header itself is solid as it was, and the list
  stops exactly at its bottom edge. The line is drawn at the top of the list, so it is the same under the header, the
  selection bar and the floating chat's header.
- **Smaller buttons**: Push 64 dp (it was 84), Voice message 48 dp (it was 52), 6 dp above and below them (it was 12),
  so the strip is 76 dp tall above the system's navigation bar (the row was 108). Touch targets stay 48 dp or more. The
  hands-free and video targets keep their places beside and above Push.
- Still floating, as they were: the live pills and video boxes, the day pill, the faces in the gaps, the ⌄ button, the
  Undo pill.
- Geometry is the same in Hebrew and in English (full-width strip and lines; the buttons were already unmirrored).
- `ConversationScreen` (HeaderHairline, `strip`, `underStrip`), `ChatComposer` (ChatButtons, the draft's fill).

## Chat colours (2026-11-27, hen: "customize the two colors of a chat screen messages, from the contact settings screen")

On a contact's page and a group's page, **Chat colours**: two bubbles showing them as the chat will, then **My
messages** and **Their messages**, each Default (A) or one of six hues, in one row that scrolls sideways
(2026-11-27, hen: "spread the colors evenly", then "only 6 instead of 12"): orange, lime, green, blue, violet, pink.
Each shows by the theme (hen first: "in light theme white text on dark color, in dark theme black text on bright
color", then "reverse the colors of light and dark"): in the light theme its bright shade with black text (#FA7D00,
#6AD400, #00D86C, #48A4FF, #C284FF, #FF6BB5), in the dark theme its deep shade with white text at 4.6:1 (#B85C00,
#428500, #008744, #0073E5, #9E3DFF, #C30061; the pink darker than the others for white text, 6:1, 2026-11-27, hen: "the pink is a bit too bright for white text"); the picker's circles too. Thin lines (outlines, the writing box, rings)
are the deep shade in both themes, so they stay seen on the light page. A pick stored from the earlier twelve shows as its hue; the chosen one ringed (200 ms). Kept on this phone only. In
the chat, the floating window and the Talk tab's chat: the whole way, in its colour (2026-11-27, hen: "respect the sending
rectangle border colors and also the new message textbox border colors"): my bubble's outline while sending (dashed,
then whole), its fill once every phone has it, the read faces' rings, and the writing bubble (its dashed outline at
rest, its outline, caret and Send while writing, my draft as it flies); their bubbles and their live draft's outline
and label in theirs. The motion is the same. Default keeps green, coral and teal as before. Text on a bubble is
near-white or near-black by the colour.

For every chat (2026-11-27, hen: "add this as global settings for all contacts chat color bubbles"): the same card in
**Settings**, above Talk, with Default (A) as the current look. On a contact's or group's page A is now **Like
everyone**, the default: that chat takes Settings' colours; a colour chosen there overrides them, each of the two on
its own.

The header's face takes their colour too (2026-11-27, hen: "respect colors also in the avatar in the header"): a
letter circle (theirs, or a group's) filled with it and its letter light or dark by it, and the presence ring (dashed,
then whole) in it; so do the other faces in the chat. A photo face keeps its picture, its ring in their colour.

In a group every member has a colour (2026-11-27, hen: "in group chat there should be color for every contact"): their
bubbles, their name over them, their faces and their live drafts. It is the "their messages" colour chosen on that
contact's page; else the group's own; else one of the six picked for them, steady while the members stay, different
from the other members' where it can be, and never the one my messages have. Mine keep my colour. The same light and
dark shades as in a one-to-one chat.

The small faces between messages take their person's colour too (2026-11-27, hen: "the small avatar between messages
should respect the colors"): the dashed circle (their phone has it) and the whole ring (read) in their colour, a letter
face filled with it once read; in a group each member's own. Without chosen colours they stay green as before.

**Bubble brightness** (2026-11-27, hen: "set the brightness of the bubble", then "make it from white to black slider,
the brightness, default at center"): on Settings' chat colours card, under the two rows, "Bubble brightness" for the
theme on screen (each theme keeps its own): its track goes from white through the colour to black, the colour as it
is in the middle (the default). The hue stays; the text on a bubble (and its time and name) turns white or black,
whichever reads; thin outlines stay the deep shade so they're seen on the page. The preview above follows it.

**Synced with them** (2026-11-27, hen: "when choosing colors on contact screen sync it with other person phone so same
person will be as the same color on both phone"): colours chosen on a contact's page go to that contact end to end
(`ColorsContent` in the content the phones already exchange, PUSH_NONE; older apps skip it), "Like everyone" too (as
cleared). On their phone the pair arrives swapped, so each person shows in the same colour on both phones: what I
chose for "their messages" is their "my messages" in our chat, and my "my messages" their "their messages". The
newer choice wins (each side's time of choosing); what I chose stays kept under theirs.

Groups too (2026-11-27, hen: "it also should be working in groups"): a group's colours go to its members through the
group's Send (sender keys, PUSH_NONE). What a member chose for their own messages there is their colour in that group
on every member's phone; what they chose for "their messages" becomes the group's colour for the members without one
of their own, the newest choice (mine or a member's) winning; a member's "Like everyone" there is no choice for the
others. In a group a member's colour is, in order: what they synced in it, what was chosen (or synced) on their
contact's page, the group's, one picked for them. The page's note says so
for a contact: "Shared with them, so each of you shows in the same colour on both phones".

**Respect synced colours** (hen: "add option to respect synced colors, enabled by default"; Hebrew "כבד צבעים
מסונכרנים"; then "i thought the respect colors is per contact setting"): a switch on each contact's colour card, on by
default (`TalkSettings.ignoreSyncedFrom` keeps the ones turned off), and on each group's. Off for a contact, the
colours they synced with me don't show here and my own choices for them stay; off for a group, what its members
synced there doesn't; mine still go. On again, theirs show again where they chose last. Not on Settings' card.

**A chat's own brightness** (hen: "add the slider for brightness also in the contact settings screen"): the same
"Bubble brightness" slider on a contact's and a group's colour card, for the theme on screen, with **Like everyone**
beside its title, chosen by default (Settings' brightness). Moving it gives the chat its own; the preview follows; Like
everyone goes back. It stays on this phone (not synced: each phone and theme differs).

In a group the sender's name is inside their bubble, at its top, and only on the first of their messages in a row,
until someone else's comes; the bubble's time is in the same colour as the name (2026-11-27, hen).

## Shared pictures, videos and files (2026-11-28, hen; being built)

`ui/media/MediaInChat.kt`. Each picture or video is as wide as the screen (out through the chat's side padding), in
its own shape (kept between 0.4 and 3 wide to high), drawn from its JPEG XL preview made for this screen's width,
between two half bubbles a bubble's widest on the sender's side in their bubble's colour (2026-11-28, hen; mock
`mockups/share-media/picture-sender.png`, option A). The top one says who sent them, only in a group and only on
other members' pictures, and where and when they were taken ("Tel Aviv · Yesterday 18:40": the place named on each phone by Android's
Geocoder from the item's coordinates, the date from the EXIF or else the gallery's date taken); of the first item. Over each picture after
the first, in a strip of the bubble between the two (its sides carried on, so the message reads as one long bubble
with the pictures in it), a quiet line says its place when it differs from the one before and its time when more than five minutes
apart (the day too when it changed), 2026-11-28, hen; the bottom one holds the caption
and the time sent. Mine move as my text bubbles do: dashed while it goes, the dashes growing towards a whole line as
the previews upload, whole on the server, filled once every phone has it; failed, the time turns red and a tap sends
it again. While a picture's preview goes up to the server (mine, in the writing colour) or comes down from it (theirs, in
the accent colour), a small cloud (the server) in its end corner, with an arrow up or down, fills with colour from the bottom (mock
`transfer-icon.png`, B) (hen, 2026-11-28: "i want to see upload progress and download
progress from server"; `MediaSharing.transfers`, previews uploaded in 64 KiB pieces). Their phone fetches a message's pictures as it comes, and says it got the message (my received face) only once their
previews are on it; a video's preview comes when the chat is opened, unless the chat has Download videos at once (its
page, off by default), 2026-11-28, hen. A video shows its length in a chip. A file is a row with its name and size in the same frame. A long press
selects it, as any message; a tap opens the viewer (docs/screens/viewer.md). Until the preview is there
(downloading, or fetched again after the cache was cleared), the picture's shape is held in the Raised colour, so
nothing moves when it comes.

**Writing a caption first** (`ui/media/MediaDraftBox.kt`; hen: "the image should be 100% width and the textbox
should be its max width of a bubble", mock `mockups/share-media/caption-in-box-v2.png`). What is picked (the +
button, or Talk in Android's Share menu) isn't sent at once: it is laid out as the message it will become, the
pictures edge to edge (✕ takes one out; more than half the screen scrolls) between two half bubbles outlined in the
writing colour on my side, a bubble's widest (78% of the screen, as the sent ones). The bottom one is the text box: a
bin (don't send), the caption and Send. The top half already says where and when they were taken. Sent with a caption, the whole box (a picture of it as
drawn) flies into the message's place in the chat, as a sent text does, and the message shows the frame it lands;
sent first, nothing moves: the message takes the pictures' place above the caption box. Send sends them as one message with the caption, and nothing moves sideways.
The previews start uploading as soon as they are picked (hen: "start sending the images to server before writing");
the message that names them reaches the others only on Send, which waits for whatever is still uploading. A picture
taken out, or the whole draft dropped, is deleted from the server too.

**Pictures first, then the text** (2026-11-28, hen: "first send the pictures, then write the text", one button; mock
`mockups/share-media/send-pictures-first.png`, B). Send with the box empty sends the pictures quietly
(`MediaContent.quiet`: a silent push, no notification) and leaves the box open under them as their bottom half
(`MediaCaptionBox`); on their phones the pictures come in at once between outlined half bubbles, like a live draft, and
with Live typing on the caption forms under them with my caret ("Writing…"). Send then sends the caption (an edit with
`notify`), their bubbles turn solid as a message, and only that notifies them. Left empty (the chat closed, or two
minutes without a letter), they are notified of the pictures alone. The bin at the start of the box takes them back: deleted for everyone
(previews from the server too), and nobody is notified. Send with text written first still sends both at
once, as before.


**The + button** (left of Voice message in the bottom row, hidden while Push is held or hands-free): Gallery picks
pictures and videos in Samsung's Gallery (Android's photo picker on phones without it), File picks files (Open
document, kept readable). What is picked is shared in this chat at once. The first time, Android asks for the phone's
pictures and videos and their places: the original is read again from there when someone opens it.
