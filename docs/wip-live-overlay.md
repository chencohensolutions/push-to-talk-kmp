# Live row: where it stands (2026-11-25)

The live row is the voice pills and video boxes of a push: under the header in the chat (`LivePanels.kt`,
`LiveStage`), at the top of the screen in the floating window and for the floating chat (`TalkBubble.kt`). Both are
placed by `LiveLayout` and moved by `LiveMotion` (`ui/live/LiveLayout.kt`). What it should do is in
conversation.md ("The live panel", "Waiting for the floor looks like waiting", "Both live") and notifications.md
("Floating window", "Quick reply window"); this page is only what was checked and what was not.

## How it was checked

testbot (the emulator), system in Hebrew and in English. The emulator draws only a few frames a second, so moves
were watched in slow motion (`settings put global animator_duration_scale 3` or `10`) from the emulator's own
recorder (`adb emu screenrecord`), and by the per-frame logs (`adb shell setprop log.tag.TalkBubble VERBOSE`,
`log.tag.LiveRow VERBOSE`). `TestBubble` (test build) shows the other side, my side, the Stop pill and the floating
chat without a second phone.

## Checked

- **One geometry, one move, either direction**: `LiveLayoutTest` (14), `LiveStageTest` (3: the chat's row really
  laid out under Rtl and Ltr); frames of the floating row in Hebrew and English; per-frame logs of the chat's row.
- **Bottom pill** (Chat · Push/Stop · ✕): in Hebrew, slow motion, the pill's centre stayed at x 539.0 in every frame
  while its width went 394 → 616 → 394, Chat and ✕ moving with its ends; nothing moved while the timer counted.
- **Stop keeps the last picture**: floating window and chat, a real voice → lock → video → Stop each: the box went
  up at once with the camera's picture in it, never black, the other item gliding to the centre meanwhile.
- **Waiting looks like waiting** (chat and floating chat, with a pretended floor): no item of mine in the row, the
  Push button dimmed in a dashed ring, "Waiting for hen…" beside it; back to normal on release.
- **Floating chat**: their pill and their box show at the top of the screen and nothing in the window; no bottom pill
  is added over it; my video from the window's own button showed in the row at the top; no video bubble was added to
  the window's list.
- **Shadows**: brightness around pills and boxes fades evenly in both places (pixels sampled).

## Not checked

- **With two phones**: "You're both live" showing only while both items show; Make it a call turning the room into
  the call from the chat, the floating window's chip and the floating chat, and the short word when there is no room
  (`talk_call_no_room`); their box keeping its last picture when their video ends; the floating chat closing while
  they are live (`TalkPlaying.windowClosed`).
- **Waiting in the floating window's own pill** (`TalkBubble.showWaiting`, `waiting` read in `pulse`): written, not
  seen (it needs their real floor).
- **A press queued in a group** (TalkPhase.WAITING, not standby): the same cue, not seen.
- **The clip path in the chat** (no room track): `TalkVideoView` and `PushVideoPreview` are not held while a box
  goes; whether they turn black then was not seen.
- **The keyboard under a video box in the floating chat** (`growBy`'s `topRoom`): the emulator showed no keyboard.
- **On the S25**: everything; in particular the row centred from its first frame, and the shadows.

## Seen and not explained

- In the chat on testbot, after Stop ended a hands-free video, an own voice burst went on for about 20 s (a voice
  pill of mine in the row the whole time) until the next press ended it; seen twice, in different runs. The row
  showed what was sent, but the burst was not meant. Likely the chat's hands-free logic when the emulator is slow
  (hands-free may have ended by itself before the video burst started, so the tap on "Stop" was a tap on Push);
  not traced.

## Since the rule "only when the UI says it's transmitting"

"You're both live" needs both sides' items in the row, and a waiting press has none: with one floor per
conversation the chip can now show only when both really send at once. `liveWaiting` is still sent and still feeds
"Both live becomes a call" (`autoCall`), untouched.
