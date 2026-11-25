# Talk

`ui/talk/TalkParts.kt`. Push-to-talk's screens and pieces; how talk works is in [design.md](../design.md),
"Phase 5a".

## Talk mode (removed)

Removed 2026-11-23 (hen): there is no per-contact Talk screen any more. Push-to-talk lives in the
[conversation](conversation.md)'s bottom row (hold the big button; Video sends the camera too, live in the chat).
What opened it now opens the conversation: a talk notification's tap and Talk back, the reply bubble's name and its
"allow the camera" step, Android Contacts' Talk action, and Open Talk when someone talks (in the app). A phone woken for
a video burst opens the conversation under a full-screen layer over the lock screen: the video while it plays, black
otherwise (then the black curtain, as before). Hardware buttons, the Talk tab's pages, the reply bubble and the talk
session (Telecom) work as before.

## Clips in the chat

`ClipContent`, inside a bubble in place of the text: a round Play (Pause while playing) button, a bar that fills as
it plays, and under it "Talk" with a small walkie-talkie for a burst, its length ("0:04"; "Live" while still being
said; none for a burst that ended before this phone had it whole, until it plays), and a teal dot on one of theirs not heard yet. A tap plays it (from this phone's copy, or the server's) or
stops it. Chats shows "Talk · 0:04" or "Voice message · 0:12" as the preview line, and so do notifications.

## Voice messages

`VoiceButton`, at the right of the line to write in while it is empty (Send takes its place once there is text).
Hold to record; let go to send; slide sideways (more than 100 dp) and let go to cancel. While recording, the button
grows and turns teal and a line beside it says "0:03 · slide away to cancel", or "Let go to cancel" (red) once slid.
A voice message waits in the outbox like a text when the phone isn't connected.

## When I'm away

`WhenAwayCard`, on a contact's page and a group's page: **Play aloud** (the default: a burst plays by itself, like a
walkie-talkie, the screen off too) or **Notify only** (a notification; it plays on Play). The chosen one has a teal
check.

## Notifications

"Dana is talking" (or "Dana is talking in Family"), "Tap Play to hear it", with **Play** (opens the chat and plays
the burst) and **Reply** (opens Talk mode there), on the Talk channel. A silent "Talk is ready"
notification (its own channel, least important, **Stop**) stays while the app isn't connected, and woken bursts play
out loud through it at media volume. Without it, a woken burst plays in a talk session, and Android shows its
call-style notification "Talk · Family" with **Leave**, gone 20 s after the last burst. Stay connected has its own ongoing
notification, "Talk is connected", with **Turn off**.

## Opened when someone talks

Settings › Talk › **Open Talk when someone talks to me** (off by default; turning it on asks Android for full-screen
notifications on Android 14+). When a burst wakes the phone with the screen **off**, the "Talk playing" notification's
full-screen intent turns the screen on with that conversation's Talk screen over the lock screen; it plays there, and
a press asks to unlock first. With the screen on (another app, or just the lock screen) only the heads-up notification
shows. With Talk in front, someone's burst switches to their Talk screen. Never in Do Not Disturb or for a muted chat.
If nobody touched or unlocked the phone, 3 s after the bursts end the screen leaves the lock screen and closes; Android
then turns the screen off after its own short lock-screen timeout (a few seconds).

The talk button grows (up to 1.25 times) and glows with the voice: the one playing, or the microphone's while talking
(`VoiceLevel`: -50 to -10 dBFS, 60 ms up and 200 ms down, read once a frame).

- **Chat, Voice call, Video call** (2026-11-22, hen): three round buttons under the name: the conversation (back to it
  when the Talk screen came from it), and a voice or video call there.

- **Follow face in push-to-video** (2026-11-23, hen; its own choice, off by default, in Settings › Talk too): a
  Follow face pill at the top of someone's video burst (kept for next time); framed by the clip's own clock, frame by
  frame between the sender's two-a-second positions (no lag), starting framed; never closer than filling the video box (it
  only slides the crop; a pinch still shows the whole picture), on the Talk screen, the woken full-screen video, the reply bubble and
  replays. The sender's phone looks for its face twice a second while sending and puts where it is inside the
  encrypted clip (with the pictures, so replays have it; apps before 1.4.14 skip it and play the clip as before); the
  picture glides to keep the face near the upper third, ignoring small moves. From a sender that puts none (an older
  app, an old clip), this phone looks at the picture itself, twice a second (not in the bubble). **Pinch to zoom** on
  the Talk screen's video and the full-screen video: from the whole picture up to the closest zoom, kept for next time.
- **Voice | Video** (push-to-video, 2026-11-22, hen; Voice by default; in the chat and the floating window it gave way
  on 2026-11-24 to hold for voice, slide up to the lock for hands-free video): a small switch just above the big button
  (it was a camera among the round buttons, beside the video call, and looked like it). Video asks for the camera the
  first time; the big button then shows a camera instead of the microphone. Kept per contact or group (2026-11-23,
  hen), shared with the reply bubble's switch; one never chosen starts on Voice. Under the button: Chat, Voice call, Video
  call. Centred above the
  big button: this phone's front camera while Video is on (the camera stays open while the screen shows, so a press
  sends it with the voice at once), or someone's video burst while it plays, upright, in step with their voice.
  When their video comes while Video is on, this phone's camera slides and shrinks into the top end corner over it
  (a third of the box, 280 ms), and grows back to its place when it ends (2026-11-22, hen); it is the same view
  throughout, so the camera isn't opened again.

## Woken by a burst (2026-11-23, hen)

With Open Talk when someone talks to me on and the screen off, a **video** burst opens the Talk screen over the lock
screen, its video filling the screen (cropped to fill). A voice-only burst leaves the screen off (hen: "if it's only
voice PTT, the screen should stay off"): it plays as before, its notification quiet (no heads-up); one that turns into
video wakes it then. **Don't turn the screen on when battery is low** (Settings › Talk, on by default): under 20% and
not charging, checked at the burst, a video burst plays like a voice one. While it plays there is no notification, sound or bubble: only the
burst's own sound; messages that come meanwhile are held. When it ends:

- With **Turn the screen off right after talk** (Settings › Talk, off by default; Android's device admin, force-lock
  only; turn it off before uninstalling Talk): the phone locks at once and the screen goes off.
- Otherwise a **black curtain** (2026-11-23, hen: "the screen should be blank on video end"): the moment it ends the
  woken screen is pure black, the bars hidden, the brightness at its lowest, over the lock screen, never the app's own
  screens; Android turns the panel off after its timeout and the app goes behind. A tap or the power button shows the
  normal lock screen; a new burst brings the full-screen video back. Until the first picture comes, the woken screen is
  black too.
- In the shade, quietly (a low channel, no sound or heads-up): the burst's record and the messages held meanwhile.

Each step from the push to the first decoded frame is logged as the "wake timeline", to see where the time goes.

## Save as file (2026-11-23, hen)

A long press on a burst or voice message (the chat's Talk bubble, or a row of Recent talk) saves it as an ordinary
file: opened on the phone (`Talk.clipContents`), its Opus voice and, for push-to-video, its video as sent (VP8, H.264
or H.265, not re-encoded) in Matroska (`BurstFile`, .mkv, the picture turned upright when played), named
"<name> <date>.mkv", where saved calls go (the folder chosen in Call settings › Saving calls, or Downloads › Talk).
"Saved to …" shows with **Share**.

**Faster push-to-video start** (2026-11-23, hen: "the delay is big"): the first segment of a burst goes after 60 ms of
voice instead of 200 ms; the video decoder stays set up between bursts (drawing to a hidden surface until a view
shows, then moved onto it), and is let go after a minute without a burst.

**The floor is never stuck** (2026-11-23, hen: "something is blocking calling and sending"): a burst's segments
always go out in order, one sender at a time (a race once sent a new one ahead of those recorded before the grant;
the server refused the gap and the floor stayed held for 60 s); a refused burst lets go of the floor at once; and
someone's floor shows at most 75 s, so a lost end can't keep the button busy.

## Hands-free (2026-11-24, hen)

In a chat, the Talk button slid up to its lock keeps sending until Stop (docs/screens/conversation.md, "Live in the
chat"). Bursts aren't kept as messages unless the chat's In the chat switches say so.

## Push-to-video through LiveKit (2026-11-24, hen)

Nothing on screen changes: the same gestures, the same top video box and floating window, the same quiet line after.

**Where the live row rests** (2026-11-25, hen): the voice pills and video boxes of a push rest just below the slim
header in the chat (coming down from under its edge), and just below the status bar in the floating window and for the
floating chat (coming in from the screen's top edge). Both are the same row, placed and moved the same way: each change
one move for everything, theirs at the language's start side (conversation.md, "The live panel"; notifications.md,
"The row's places and moves").
Underneath, a video burst's picture also goes through the conversation's talk room while it is said, and the listener
shows it from there while it arrives (smaller and steadier on a weak link: simulcast), else the clip's pictures as
before. The clips still go with every burst (a sleeping phone, "Keep videos in chat", no room). The debug and fast
builds show a small readout on the received video: the path and how late the first picture came. See
[design.md](../design.md), "Push-to-video through LiveKit".

**Camera ready at finger-down** (2026-11-24, hen: "when i tap push to talk, open the camera in hidden window"; Settings ›
Talk › Get camera ready while talking, on by default): the camera opens unseen as the finger goes down on Push, and the
talk room's camera track reads it muted, so sliding up to hands-free video only unmutes it. Nothing is sent or shown
until the slide up; Android's camera dot shows while Push is held. It is let go 2 s after the finger lifts without
sliding (a quick press again reuses it). Off, the camera opens when the slide up starts.

**Voice through the room again, and the silent warm-up** (2026-11-25, hen). The voice goes through the chat's talk room
while it is said (the microphone's track waits there muted while the chat is on screen, so a press only unmutes it),
and the clip still records every burst (with the 300 ms before the press) for phones that sleep and for "Keep voice in
chat". Opening a chat quietly gets the other phone into the room (nothing on its screen), so the first words reach it
at once. See [design.md](../design.md), "Voice back in the talk room, and the silent warm-up".


## Video box labels (2026-11-25, hen)

A live video box's label (who: their name, or "● You") sits at the box's top centre, with no timer; voice pills keep
their time. Test builds' delay readout ("LiveKit · first …", off in Settings) sits at the box's bottom centre, so the two
never overlap. Both live (one to one, voice or video) shows "Make it a call" in the chat, the floating window and the
floating chat; it turns the room into the call, no ringing.

## Several talking at once in a group (2026-11-25, hen)

The server gives every talker a floor of their own, groups too, so several members may push at once. Talk keeps all
of them (`TalkUiState.talking`, by chat, in the order they started; `talkers` stays the newest one, and when that one
ends the newest still talking takes its place). The chat's overlay (`GroupLivePanel`), the floating window's row and
the floating chat's row (TalkBubble, `placeGroup`) show them all:

- **Videos** in one row under the header (or at the top of the screen), equal widths, 6 dp apart, each picture cropped
  to fill its box. The height is the usual 23.6% of the screen; three boxes get narrower (more of each picture cropped),
  and past 9:16 (four on a phone) the row gets lower too (`LiveGroupLayout.boxSize`).
- **Voices** are the slim pills, under the videos' row, or the top row when no one is on video. When their names don't
  fit side by side, every pill shows its face only (the frame covers the words; the face stays at the language's start
  side).
- **At most four in view**, the ones who spoke last (`LiveCast`). A fifth pushes out the one quiet longest; the others
  close up and the newcomer comes in after them. Mine counts as one of the four, always in view and always last.
- **"+N talking · Name"** under everything, for those live but not in view (Name: the newest of them). A tap brings
  that one into view in the place of the quietest.
- **Pictures** come through the room (each member's "user.device"); without them the box shows the face. The clip
  path's one decoder can't show several, so in groups it isn't used for the boxes.
- **One move** for every change (docs/screens/style.md, "Motion"): one joining or leaving, voice turning into video and
  back (a member's cell is kept 1 s after their push ends, so the next one grows out of it), the reorder when a fifth
  speaks, and the boxes narrowing from one to four. Placed by `LiveGroupLayout`, moved by `LiveMotion`, left to right
  whatever the language. One-to-one chats keep their row as before (`LiveLayout.place`: the pictures in their own shapes).
- Test builds: `adb shell am broadcast -a com.chencohen.ptt.TEST_BUBBLE -n com.chencohen.ptt/.TestBubble --es chat
  '#<group id>' --es members testbot2:video,testbot3:voice [--es where chat --es mine voice]` pretends members live at once.

## Who can hear you, and urgent pushes (2026-11-25, hen: "yes, make it all")

A push to someone who can't hear it now (Do Not Disturb, the phone on silent or vibrate, the media volume at 0 with Talk
volume off, or in a phone call) isn't played: it is saved, a quiet "Missed push from X" notification shows, and the
talker is told why, end to end (TalkHeardContent; HeardStatus).

- **Urgent**: while holding Push, slide sideways onto the red bell (the chat's button, and the floating window's: the
  bell shows up and to the right of Push, on the screen in either language). The button turns red with a bell, my pill
  says "You · Urgent" in red (live row and floating window). A push made urgent while held is let through after all where
  it was saved at first.
- **Contact page › Urgent pushes › Let urgent pushes through Do Not Disturb**: off by default. Turned on the first time
  without Do Not Disturb access, it asks for it (Android's Do Not Disturb access page). Without access, the note says
  urgent pushes still arrive, silently. With it, an urgent push from them opens Do Not Disturb while it plays and puts it
  back after; on silent it plays anyway.
- **Settings › Privacy › Show when I can't hear**: on by default; off, contacts see nothing of it.
- **Talker's bubble labels** (under my push): "Saved: Do Not Disturb", "Saved: silent", "Saved: in a call", "Saved:
  urgent not allowed", "URGENT ✓ Heard", "URGENT ✓ Heard through Do Not Disturb", "URGENT ✓ Heard through silent"; red
  for urgent. In a group, each with the member's name.
- **Face badges** (chat header, Chats, a group header's faces): Do Not Disturb is Android's own sign, a red filled circle
  with a white bar (never a moon); silent a crossed bell; in a call a phone. Bottom right of the face on the screen in
  either direction; it comes and goes in one move (scale with fade).
- **Header line**: "Do Not Disturb", "Silent · hears it later", "In a call" (typing… comes first). A group: "2 of 4 can
  hear now" while someone can't.
- **While pushing**: a chip under my pill: a greyed face with its badge and "Sharon can't hear now · Do Not Disturb ·
  saved", with one short buzz; or a teal glowing face and "Sharon is hearing you" only once their phone said it plays.
  Nothing shows until there is something true to say; it changes in place in one move. A group: a row of members' faces,
  lit or grey, and "2 hearing · 2 get it later" (offline ones get it later too).

## Live for exactly as long as they transmit (2026-11-25, hen)

The listener shows a talker live for as long as the talker transmits, voice or video, held, hands-free or from the
floating window. While transmitting through the talk room, the sender sends a tiny "still pushing" beat once a second
over the room's data channel (TalkBeat, sealed with its own room key). Silence while held stays live: the beat isn't
audio. The listener ends their live state at once on any end signal: the floor's end (TalkState ENDED), their room
microphone muted or unpublished, or their leaving the room; or 3 s after their last beat. A beat (or a live segment)
again puts them back, in one move. Only bursts that beat are watched: an older app is left to its floor's end. After /ws
comes back from a drop, floors with no beat in the last 3 s go, and an end of mine that couldn't be sent goes then. The
server sends ENDED (DROPPED) to the listeners 3 s after a floor holder's connection closes, unless it connects again.
