# Talk

`ui/talk/TalkParts.kt`. Push-to-talk's screens and pieces; how talk works is in [design.md](../design.md),
"Phase 5a".

## Talk mode

`TalkModeScreen`, opened by the **Talk button** (a walkie-talkie) at the right of a [conversation](conversation.md)'s
header, or by a talk notification's **Reply**. A full-screen walkie-talkie:

- On top: a back arrow at the start (mirrored in Hebrew; the same as the system back) and the conversation's name. It
  is a screen, not a sheet (2026-11-22, hen): no swipe down.
- The line under it says what the floor is doing: "Hold the button to talk" (muted); "Connecting…" while the
  server's answer is coming; "Live" (teal) while the floor is this phone's; "Dana is talking" (teal) while someone
  else holds it; "Dana is talking. Try when they let go." when a press found the floor busy; "Not connected: it goes
  as a voice message"; "Talking on your other phone"; "The microphone isn’t free"; "Talk isn’t possible here".
  Busy and refusals show for 2.5 seconds.
- A debug build adds the catch-up readout under it while a burst plays live: "Catch-up: 230 ms behind"; and, while
  the phone is in the conversation's live room (phase 5b), "Live: in the room" or "Live: heard 230 ms after the press".
- The **hold-to-talk circle**: ink, 220 dp, with a microphone and "Hold to talk"; a teal ring while live, a faded
  teal ring and a muted circle while someone else talks. Recording starts on the press (before the floor is
  granted: the plan's catch-up) and stops on release. While a Talk screen (Talk mode, or a page of the Talk tab) is on
  screen, the microphone is already open (`TalkCapture.warm`; Android shows its microphone indicator): nothing is
  sent, only the last 300 ms are kept, and a press sends those first, so it transmits at once without cutting the
  first syllable. It closes when the screen leaves, turns off or the app goes to the background. While it is held the microphone works like a speakerphone's
  (it hears a voice at arm's length), unless a headset is connected.
- **Recent talk (N)**: a row with a chevron, folded by default (2026-11-22, hen); a tap opens it to show the last ten bursts and voice messages of the conversation, newest first: Play (or Pause while it
  plays), who ("You" for ours), length and time.

The first press asks for the microphone; if it is refused, the message bar says "Talk needs the microphone. Allow it
in the app’s settings."

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

- **Voice | Video** (push-to-video, 2026-11-22, hen; Voice by default): a small switch just above the big button
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
