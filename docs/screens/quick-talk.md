# Quick talk (the Talk tab)

The app opens on **Talk**, the first home tab, before Chats and Calls (2026-11-22, hen: the main screen is push to
talk). It is `ui/talk/QuickTalkHome.kt`; the list and its order are `talk/QuickTalk` in sharedLogic.

- **Which chats:** the contacts and groups with **Quick talk** on, one page each, in the order they were marked
  (`TalkSettings.quickTalk`, kept on this phone, not synced to the user's other phones). A contact that is no longer
  paired, a blocked one, a group that is gone or that this user can't read yet is skipped.
- **A page:** the chat's letter circle (or the group's picture) and name in PageTitle; a tap opens the
  [conversation](conversation.md). Under it, what the floor is doing, the same line as [Talk mode](talk.md) ("Hold
  the button to talk", "Live", "Dana is talking", "Busy"…), in teal while someone talks. In the middle the
  hold-to-talk circle in ink (pink), 220 dp, with a teal ring while live, as in Talk mode.
- **Swipe** left or right for the next chat. While the button is held (asking for the floor or live) the pages don't
  move. Dots under the pages say which one (only with two or more), the current one in ink.
- **The page shown last** is where the tab opens next time (`TalkSettings.quickTalkPage`); else the first.
- **None yet:** "No quick talk chats yet. Turn on Quick talk on a contact’s or group’s page." with **Open Chats**.
- **Quick talk switch:** on a [contact's page](contacts.md) and a [group's page](groups.md), above When I’m away:
  "Quick talk", "A page on the Talk tab: swipe to it and hold to talk." Turning it on adds the chat as the last page;
  off takes it away.

The top bar is home's (the profile circle, "Talk", Offline); the cards (Register again, pair requests, linking) stay
on Chats. Talk mode from a conversation is unchanged.

- **Chat, Voice call, Video call** (2026-11-22, hen): three round buttons under the name: the conversation (back to it
  when the Talk screen came from it), and a voice or video call there.
