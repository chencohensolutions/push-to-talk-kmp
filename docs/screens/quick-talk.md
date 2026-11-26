# Quick talk (the Talk tab)

The app opens on **Talk**, the first home tab, before Chats and Calls (2026-11-22, hen: the main screen is push to
talk). It is `ui/talk/QuickTalkHome.kt`; the list and its order are `talk/QuickTalk` in sharedLogic.

- **Made simple** (2026-11-26, hen: "its too complicated", "only chats marked as quick chat should appear there, its
  not many", "the video and voice do not integrate nicely", "the chat ux and animation should resemble the chat
  screen"; option A of `docs/screens/mocks/talk-live-list-review.png`, in place of the live list tried the same day).
  Three rules: touch a name to choose it, hold Push to talk, touch the writing bubble to write.
- **The header:** "Talk" beside my profile circle, and **+** at the other end: every contact and group with a switch;
  on, it's a row here (the same Quick talk switch as on its page).
- **The rows:** only the Quick talk chats, in the order they were marked, never re-sorted. Each: the face (56 dp) with
  its presence ring (solid: on that chat's screen; dashed: in its talk room; none: away) and can't-hear badge, the
  name (16 sp), one line (13 sp, its last message or push; in ink while unread) and the unread count. The chosen one
  is a raised card (200 ms); touching it again opens its chat.
- **The bottom is the chosen chat's own** (its screen is under the tab): its writing bubble, resting, reading "Message
  to testbot", which is the only "to whom" sign; a touch opens that chat with the bubble already writing. Under it
  the strip as in the chat: Push 64 dp and its slides, Voice message beside it, Stop while hands-free.
- **Live in the rows, not over them:** someone talking: their face's ring is whole and glows, their name and line turn
  teal ("testbot2 is talking"); nothing moves. Their video grows out of their face inside their row, top edge still,
  the rows under it moving down (one move, 300 ms), and the row scrolls whole into view; it shrinks back the same
  way. My push in the chosen row: my pill "You · 0:03" at its end; my hands-free video's box at its end (one time
  for the whole run, kept over a renewal's gap); who can't hear me under the name. The floating window doesn't show
  for the chosen chat while the tab is in front. "Make it a call" and the chat's other chips stay at the bottom.
- **Whoever starts talking** is chosen (Push answers them), unless my push runs.
- **None marked yet:** one line ("No one here yet. Add the people and groups you talk with most.") and **Add**, which
  opens the same list as +. No Push and no writing bubble. Nothing is said before the chats have come (2 s at most).
- **The chat chosen last** is chosen next time (`TalkSettings.quickTalkPage`); else the first.
- **None yet:** "No quick talk chats yet. Turn on Quick talk on a contact’s or group’s page." with **Open Chats**.
- **Quick talk switch:** on a [contact's page](contacts.md) and a [group's page](groups.md), above When I’m away:
  "Quick talk", "A page on the Talk tab: swipe to it and hold to talk." Turning it on adds the chat as the last page;
  off takes it away.

The top bar is home's (the profile circle, "Talk", Offline); the cards (Register again, pair requests, linking) stay
on Chats. Talk mode from a conversation is unchanged.

## Before the faces row (until 2026-11-26)

The page as it was, kept for its history; the stage above replaced it.

- **Chat, Voice call, Video call** (2026-11-22, hen): three round buttons under the name: the conversation (back to it
  when the Talk screen came from it), and a voice or video call there.

- **Video** (push-to-video, 2026-11-22, hen; off by default): the first of the round buttons under the big button,
  "Video off" / "Video on" (asks for the camera the first time), then Chat, Voice call, Video call. Centred above the
  big button: this phone's front camera while Video is on (the camera stays open while the screen shows, so a press
  sends it with the voice at once), or someone's video burst while it plays, upright, in step with their voice.
