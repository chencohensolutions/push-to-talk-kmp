# Quick talk (the Talk tab)

The app opens on **Talk**, the first home tab, before Chats and Calls (2026-11-22, hen: the main screen is push to
talk). It is `ui/talk/QuickTalkHome.kt`; the list and its order are `talk/QuickTalk` in sharedLogic.

- **Which chats:** the contacts and groups with **Quick talk** on, one page each, in the order they were marked
  (`TalkSettings.quickTalk`, kept on this phone, not synced to the user's other phones). A contact that is no longer
  paired, a blocked one, a group that is gone or that this user can't read yet is skipped.
- **Faces row + stage** (2026-11-26, hen: "this screen ux is outdated, suggest more relevant ux, in resemblance of
  the chat app"; option 3 of `docs/screens/mocks/talk-tab-mock.png`): the tab is the picked chat's own
  [conversation](conversation.md) screen, with two things in place of its header and its messages:
  - **The faces row** at the top: each Quick talk chat's face (48 dp) with its presence ring as in the chat's header
    (solid: on that chat's screen; dashed: in its talk room; none: away) and its can't-hear badge, its name under it
    (the picked one bold, with a short pink line under it; someone talking there, in the accent colour), and at the
    row's end **Add** (a dashed circle with +), which opens Chats. It keeps clear of my profile circle in the corner.
    A tap picks a chat; while my push runs (held, waiting, or hands-free) the others rest dimmed and can't be picked.
  - **The stage**, a card where the messages are: the picked chat's face large (120 dp, with its ring), its name (a
    tap opens the chat), what its floor is doing (the same line as before, "Dana is talking"…), and its last push
    with Play (Pause while it plays), who said it and how long, with a teal dot if not heard yet. At its foot, Chat,
    Voice call and Video call, each with its word. A sideways swipe on it goes to the next or previous chat. A new
    one slides in a little from its side and fades in (250 ms).
  - **Everything else is the chat's own:** the solid strip with Push (64 dp, hold to talk, slide up for hands-free
    video, slide left for hands-free voice or the urgent bell when someone can't hear, Stop while hands-free) and
    Voice message beside it, the live pills and video floating at the top, and who-can't-hear chips. There is no
    Voice/Video switch and no big circle any more.
- **Which is picked:** the one picked last (kept, as before); whoever starts talking in another Quick talk chat becomes
  the picked one, unless my push runs.
- **The page shown last** is where the tab opens next time (`TalkSettings.quickTalkPage`); else the first.
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
