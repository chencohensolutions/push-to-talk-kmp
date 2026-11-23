# Notifications

`Notifications.kt` (androidApp), channel **Messages** ("Texts from your contacts", high importance). The phone is
woken by the server's push and reads the texts from its queue; nothing of a text goes through Google
([design.md](../design.md), "Phase 3").

- **One notification per conversation**, Android's messaging style: the contact's name and their unread texts (the
  last 7), the count, and a speech-bubble icon. A tap opens the [conversation](conversation.md).
- **Reply:** typed in the notification; the conversation is marked read and the reply sent (or kept in the outbox),
  over a short connection the action opens.
- **Mark as read:** a read receipt goes to them, and to this user's other phones.
- It goes away when the conversation is read here or on another of the user's phones.
- The app asks for the notifications permission at first start (after the local network one); the answer goes to
  the server with the push token, which sends high-priority pushes only to phones that show notifications.

## App updates

When the server pushes `app_update` and its release is newer than the app (release builds only): with Install updates
automatically (the default) the update installs by itself when nothing is going on, and this notification shows only
when Android wants the user to confirm (a tap opens Android's screen) or Install unknown apps is off. Otherwise, once
per version, "Talk 1.3.0 is available", "Tap to update. Android asks you to confirm.", on the App updates channel; a
tap opens the app and starts the update ([design.md](../design.md), "App updates").

## Talk (background)

Three channels, so each can be tuned or hidden on its own in Android's settings:

- **Talk is ready** (least important, silent, no badge): "Talk is ready", "Talk from others plays out loud. Not
  connected: no battery used.", **Stop** (until the next app open). It only keeps the service that lets woken bursts
  play loud; hiding the channel keeps the service working.
- **Talk playing** (high importance, no sound of its own: the burst is the sound): while a woken burst plays, the
  sender (and the group), "Talking… 0:04", "Hold your headset button to reply", with **Talk back**, **Stop** (what plays
  and what waits) and **Mute <name> 1 h** (their bursts show the talk notification instead of playing, for an hour).
  A tap opens that conversation's Talk screen. When it ends, for a minute: "Talked just now" with **Talk back** and
  **Play again**.
- **Talk back** (2026-11-22, hen: the Talk screen, not a voice message): opens that conversation's Talk screen, the
  big hold-to-talk button with the microphone already open; over the lock screen Android asks to unlock first.
- **Reply bubble** (Settings › Talk › Reply bubble over other apps, off by default; needs Android's "Display over
  other apps", asked for when it's turned on): while the phone is in use and unlocked and the app isn't on screen, a
  woken burst also shows a bar at the bottom of the screen, full width with a margin, above the navigation bar. It
  slides up from the bottom edge: the sender (and the group) centred, a round Video switch while the burst has video,
  and under them a big round button with a microphone. Hold it to talk back and let go to send, or tap to start and
  tap again (a check mark) to send. Icons only, no ✕ and no hint line (2026-11-22, hen); TalkBack reads their names.
  It stays until a touch outside it (not while talking) or opening the app, and slides down off the screen.
- **In the reply bubble** (2026-11-22, hen): a ring around the bar pulses teal with the voice while they talk;
  while this phone talks, the button turns teal, grows with the voice, and a seconds counter shows under the name.
  Under the button, small round icons: Video (the burst's pictures on or off), Chat, Voice call, Video call. A green dot
  by the name says they are "here": their Talk screen or bubble for this chat is open now (HereContent, said end to
  end every 20 s, gone 30 s after the last); then Video call glows teal, a video call they'll see at once. A call that
  rings while the bubble shows turns it into the call: the caller's name, a big green Answer, and a red Decline.
- **Picture-in-picture**: an answered video call goes on in Android's small floating window when the app is left
  (dragged and resized by Android), the other's video filling it and this phone's in a corner, with Hang up.
- **Video in the reply bubble** (on by default; 2026-11-22, hen: a received video plays): a video burst's picture
  plays above the sender's name, in the picture's own shape, in step with the voice; the bar's "Video on" switch turns
  it off (and on again) for later bursts.
- **Reply window:** for 30 s after a woken burst, a headset's or Bluetooth talk button (held) talks back to its sender
  instead of Buttons talk in; while Talk is ready runs, those buttons work with the screen off.

## Quick reply window

`QuickReplyWindow` (Android), `QuickReply` (shared; 2026-11-23, hen; Settings › Talk › Quick reply window, on by
default; needs "Display over other apps"). A message (or a voice message) that comes in while the phone is in use, its
screen on, shows its conversation in a window at the bottom of the screen, full width, 40% of its height, over other
apps, sliding up from the bottom edge (250 ms) and down off it when it closes; a swipe down follows the finger. While it
shows a message there is no notification, only the message sound (`MessageAlert`); if it closes unanswered (×, swipe,
Back, by itself, or switching away) the notification is posted then, silently, so the message stays unread. Not for
the conversation open in the app while it is in front; skipped (off, not allowed, screen off) the notification shows as
always. Every decision is logged (QuickReply). 2026-11-23: it crashed the app as it attached (Compose found no
lifecycle on the window's root view), so it never showed; fixed.

- **Header**: a grab bar; the letter circle and the name (a group's name for a group); a tap opens the chat and
  closes the window; **×** closes it. A swipe down closes it too, and so does Back.
- **The last messages** (up to 12), newest at the bottom, in bubbles like the chat's (a group's with who wrote each).
- **A field** ("Reply") and, while it is empty, a round **hold-to-talk** button: hold to talk to them (or the group),
  let go to send, slide away to cancel (the button shows ×), as the chat's microphone. With text typed it is **Send**:
  the text goes end to end like any other.
- **Closing by itself**: untouched for 20 s with nothing typed and nobody talking. A new message from them keeps it.
- **Someone else writes**: the window switches to them, unless you are typing or talking, then they wait and show when
  the window closes (once each, in order; one opened in the app meanwhile is skipped).
