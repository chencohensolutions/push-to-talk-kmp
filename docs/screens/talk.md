# Talk

`ui/talk/TalkParts.kt`. Push-to-talk's screens and pieces; how talk works is in [design.md](../design.md),
"Phase 5a".

## Talk mode

`TalkModeScreen`, opened by the **Talk button** (a walkie-talkie) at the right of a [conversation](conversation.md)'s
header, or by a talk notification's **Reply**. A full-screen walkie-talkie:

- On top: a chevron down (back to the chat) and the conversation's name. Swiping down anywhere also goes back.
- The line under it says what the floor is doing: "Hold the button to talk" (muted); "Connecting…" while the
  server's answer is coming; "Live" (teal) while the floor is this phone's; "Dana is talking" (teal) while someone
  else holds it; "Dana is talking. Try when they let go." when a press found the floor busy; "Not connected: it goes
  as a voice message"; "Talking on your other phone"; "The microphone isn’t free"; "Talk isn’t possible here".
  Busy and refusals show for 2.5 seconds.
- A debug build adds the catch-up readout under it while a burst plays live: "Catch-up: 230 ms behind"; and, while
  the phone is in the conversation's live room (phase 5b), "Live: in the room" or "Live: heard 230 ms after the press".
- The **hold-to-talk circle**: ink, 220 dp, with a microphone and "Hold to talk"; a teal ring while live, a faded
  teal ring and a muted circle while someone else talks. Recording starts on the press (before the floor is
  granted: the plan's catch-up) and stops on release.
- **Last talk**: the last ten bursts and voice messages of the conversation, newest first: Play (or Pause while it
  plays), who ("You" for ours), length and time.

The first press asks for the microphone; if it is refused, the message bar says "Talk needs the microphone. Allow it
in the app’s settings."

## Clips in the chat

`ClipContent`, inside a bubble in place of the text: a round Play (Pause while playing) button, a bar that fills as
it plays, and under it "Talk" with a small walkie-talkie for a burst, its length ("0:04"; "Live" while still being
said), and a teal dot on one of theirs not heard yet. A tap plays it (from this phone's copy, or the server's) or
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
the burst) and **Reply** (opens Talk mode there), on the Talk channel. While a woken burst plays in a talk session,
Android shows its ongoing call-style notification "Talk · Family" with **Leave**. Stay connected has its own ongoing
notification, "Talk is connected", with **Turn off**.
