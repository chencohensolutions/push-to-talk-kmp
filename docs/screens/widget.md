# The talk widget (home screen)

A home-screen widget that push-to-talks to one contact or group (`TalkWidgetProvider`, `TalkWidgets`; 2026-11-22,
hen). Several can be added, one per conversation; 1x1, resizable to 2x1.

- **Adding it** opens "Talk to who?" (`TalkWidgetConfigureActivity`): the Quick talk chats first in their order, then
  the other contacts and groups by name (`TalkWidgetChoices`). Without a choice it says "Choose who" and a tap opens
  the list.
- **Looks:** the pink circle with a microphone, the conversation's name, and a line: "Tap to talk".
- **Talking:** a widget can't be held, so a **tap** starts talking there at once (the tap lets `TalkReadyService` open
  the microphone in the background; the floor as usual). The circle turns teal with "Talking 0:03 · tap to send", and
  a **✕** cancels. The **next tap** sends. At 60 s it sends by itself. The "Talking to Dana" notification shows too,
  with Send and Cancel.
- **Floor taken:** "Dana has the floor" for 3 s, nothing sent.
- **Incoming:** while a burst from that conversation plays in the background, the circle is muted with "Dana is
  talking".
- **Microphone not allowed:** the tap opens the app's Talk screen, which asks. Widgets live on the home screen, so the
  phone is unlocked.

The state comes from `TalkWidgetView.of` (sharedLogic, tested): Talking (this phone's own burst there, with seconds),
Busy, Incoming (the floor or what plays), or Idle.
