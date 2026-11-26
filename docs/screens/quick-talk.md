# Quick talk (the Talk tab)

The app opens on **Talk**, the first home tab, before Chats and Calls (2026-11-22, hen: the main screen is push to
talk). It is `ui/talk/QuickTalkHome.kt`; the list and its order are `talk/QuickTalk` in sharedLogic.

- **The live list** (2026-11-26, hen: "the idea is to contact very quickly coworkers, also with messages"; concept 1
  of `docs/screens/mocks/talk-home-mock.png`, in place of the faces row + stage tried the same day): every paired
  contact and every group I can read, one row each (`talk/TalkList` in sharedLogic): its face with its presence ring
  (solid: on that chat's screen; dashed: in its talk room; none: away) and can't-hear badge, its name, its last
  message or push (while someone talks there, what the floor is doing, in the accent colour), and at the end its time
  and unread count. Who is talking comes first, then the newest message, then the newest contact or group.
- **Search and filters** on top, clear of my profile circle: Search first (a tap turns the row into a line for a
  name, with Close; rows whose name holds it), then All, Hearing now (someone there can hear a push now), Unread (with
  how many) and Groups. The chosen one is in ink. Neither moves the open row, so Push keeps going where it went.
- **Hold a face** (a moment, so a scroll is never a push): a push to that row's chat until the finger lifts, the face a
  little bigger with a pink ring meanwhile; then that row opens. Not while my push runs.
- **One row is open**, in place, on a card: its last three messages (mine at the end side, a group's with who said
  them; a tap on a push or voice message plays it), one-touch replies (Got it, On my way, 5 more minutes, One moment)
  that send at once, a line to write ("Message to testbot") with Send, and **Full chat** at the row's end. A reply
  reads the chat, as answering does. A tap on another row opens it (the card moves there in one calm move, 220 ms).
- **The strip is the chat's own** (the open chat's screen is under the list): Push 64 dp with its slides (up for
  hands-free video, left for hands-free voice or the urgent bell when someone can't hear), Stop while hands-free,
  Voice message beside it, and "To testbot" beside them saying where Push goes. The live pills and video float at the
  top, as in the chat.
- **Whoever starts talking** opens (and Push answers them), unless my push runs; while it runs the open row stays.
- **The row open last** is open next time (`TalkSettings.quickTalkPage`); else the first. Quick talk no longer limits which chats show.
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
