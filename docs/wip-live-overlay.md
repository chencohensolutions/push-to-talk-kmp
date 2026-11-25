# Live row: work in progress (2026-11-25)

Where the live row's work stands after commits `5d5f095` (checked) and `80f832a` (WIP), for whoever carries on.
The row is the voice pills and video boxes of a push: under the header in the chat (`LivePanels.kt`, `LiveStage`),
at the top of the screen in the floating window and for the floating chat (`TalkBubble.kt`). Both are placed by
`LiveLayout` and moved by `LiveMotion` (`ui/live/LiveLayout.kt`).

## Checked (5d5f095)

- One geometry and one move for both places, the same in Hebrew and English. Unit tests: `LiveLayoutTest` (14).
  Frames measured on testbot with the system in Hebrew and in English, in slow motion (animation scale 10) and by
  the per-frame logs (`adb shell setprop log.tag.TalkBubble VERBOSE`, `log.tag.LiveRow VERBOSE`).
- Slim chat header (48 dp), the row resting below it.
- The floating chat draws no pills or boxes; it tells `TalkBubble.liveRow` what is live.

## In 80f832a (builds, tests and lint pass; little of it looked at on a phone)

| Item | State | Where | Next |
|---|---|---|---|
| Stop freeze-frame | Partly done | Floating window: `TalkBubble.freeze` / `freezeMine` / `thaw`, called from `endTalk`, `pickPreview`, `useRoomVideo`, `refreshTop`; nothing live now sends the items up at once (`refreshTop`), only the emptied window waits 900 ms. Chat: `LivePanel` holds the last track (`heldLocal`, `heldRoom`) in the box until it is out. | Run once on testbot: Stop reached "row: theirs voice" at once in the log, but the frames were not looked at for black. Check the clip path (no room track) in the chat: `TalkVideoView` and `PushVideoPreview` are not held there. Check that their frozen picture is dropped when their next burst comes without the room (`show()` thaws; the floating chat's path does only when their item was off). |
| Both-live chip | Done, not seen on a phone | Chat: `bothShown` in `ConversationScreen` (chip and `LiveNow.bothLive`); floating window: `TalkBubble.bothLive` needs both cells on. | See it with two phones. A stale `liveWaiting` can no longer show the chip by itself; clearing stale states by a timeout was not done (entries already carry an expiry time). |
| Waiting state UI | Not done | `ConversationScreen`: `sendingVideo` still counts `TalkPhase.WAITING`, so `myPanel` shows my box with the "waiting for…" tag, and `standby` shows my camera beside theirs. | Decide how waiting looks (no live pill or box) and take WAITING and standby out of `myPanel` / `LiveNow.mine`; `TalkBubble` has no waiting state of its own. The rule is in style.md, "Saying what is sent". |
| Bottom pill morph | Not done | `TalkBubble.addPill`: a LinearLayout with a LayoutTransition, `showLocked`, `updateStop`, `showLockHint`. | The same way as the top row: one animated width about a fixed centre, the timer in tabular digits, a least width for Stop, Chat and ✕ gliding in the same move. |
| Make it a call from any surface | Not done | Chat: `callInChat` (HomeApp) already uses `startRoomCall`. Floating window chip: `TalkBubble` still calls `call(context, chat, true)`, a ringing call. Floating chat: `QuickReplyWindow` passes no `callInChat`, so its chip never shows. | An Android-level `startRoomCall(context, chat, video)` in sharedLogic's AndroidPtt.kt (not added: sharedLogic was being edited), then use it from the chip in `TalkBubble.addTop` and as `callInChat` in `QuickReplyWindow`. |
| Remove call buttons | Done, not seen on a phone | `ChatButtons` in `ChatComposer.kt`: Voice message and Push, both at the right. | Look at the row in the chat and the floating chat. Calls are still offered from Talk mode's actions (`TalkParts.kt`) and from a call's row in the Calls tab (`CallParts.kt`); the contact's page and the group's page offer no call. |
| Floating chat hook / check | Done for theirs | `TestBubble`: `--es where window` opens the floating chat; with `--es where chat --es theirs voice` their pill showed at the top of the screen and nothing in the window, no bottom pill added. | Not checked: my own push from the window's button, closing the window while they are live (`TalkPlaying.windowClosed`), the keyboard up under a video box (`growBy`'s `topRoom`), and that a push-to-video adds no bubble while "Keep videos in chat" is off. |
| Shadows | Checked by pixels in 5d5f095 | Floating window: the row's window is as wide as the screen with room below; chat: no AnimatedVisibility around the items, only the top edge is cut (under the header). | Brightness around pills and boxes fades evenly on testbot in both places; look on the S25. |

## Also in 80f832a

- `LiveStageTest`: the chat's row laid out for real under `LocalLayoutDirection` Rtl and Ltr (Robolectric 4.18.1, a
  new test dependency in `gradle/libs.versions.toml` and `androidApp/build.gradle.kts`).
- Faces until pictures really come: theirs until the room or the clip shows one, mine until
  `LiveTalkVideo.localPicture` (chat: `pictured` in `LivePanel`; floating window: `myFace`). Not seen on a phone.

## testbot as left

Put back as it was: device locale en-US, app locale he, animation scale unset, the two log tags cleared. It runs the
build of `80f832a` (fast). For a right-to-left system: `adb -s emulator-5554 shell cmd locale set-device-locale he-IL`;
for slow motion: `settings put global animator_duration_scale 10`.
