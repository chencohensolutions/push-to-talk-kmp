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
  slides up from the bottom edge: ✕ in its corner, the sender (and the group) and "Talking now" centred, and under
  them a big round pink **Talk** button in the middle. Hold it to talk back and let go to send, or tap to start and
  tap **Send**; "Talking 0:03 · let go or tap to send" while it records (Talk is ready's service records, as the
  widget does). After the burst it says "Hold Talk to reply", "Sent" after sending. It stays until a touch outside it
  (not while talking), ✕, or opening the app, and slides down off the screen (2026-11-22, hen 19:05). Without the
  permission there is only the heads-up.
- **Video in the reply bubble** (on by default; 2026-11-22, hen: a received video plays): a video burst's picture
  plays above the sender's name, in the picture's own shape, in step with the voice; the bar's "Video on" switch turns
  it off (and on again) for later bursts.
- **Reply window:** for 30 s after a woken burst, a headset's or Bluetooth talk button (held) talks back to its sender
  instead of Buttons talk in; while Talk is ready runs, those buttons work with the screen off.
