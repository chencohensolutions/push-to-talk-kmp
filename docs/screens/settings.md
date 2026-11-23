# Settings

Opened from the profile circle on [Chats](chats.md) (`ui/settings/SettingsScreen.kt`, `SettingsController`). It is
one `SettingsScaffold` page with back, grouped as Signal's Settings as far as phase 2 goes. It reads again on opening
and each time the connection's state changes.

## Account

- **This phone:** the device name, with "account · email" under it (no device number: it is an internal id) ("Not registered" before enrolling). The device
  number comes from the server (`me`, `serverInfo.deviceNumber`).
- **Your devices:** "Your phones on this account: when each was last on, remove one, or link a new one." Opens
  [Your devices](devices.md).
- **Register again** (red): "Enroll this phone again with a new code, at this server or another." Opens the
  [enrollment wizard](enrollment.md). A new enrollment replaces the identity and forgets what was kept of the old
  server.

## Privacy

- **Show when I’m online:** "On: your contacts see when you’re online and when you were last seen. Tap to turn off."
  or "Off: nobody sees when you’re online, and you don’t see theirs. …"; a tap switches it (`setPresenceVisible`);
  "Couldn’t change it: the server didn’t answer." when it can't.

## Talk

A row, "Talk · Catch up to live, Stay connected, hardware buttons", opens Settings › Talk (`TalkSettingsScreen.kt`):

- **Catch up to live**: Shorten pauses and speed up (the default), Shorten pauses only, or Off, with a line under
  each; the chosen one has a teal check.
- **Notifications › Message sound** (2026-11-22, hen): **Talk soft chime** (the default: two calm notes,
  `res/raw/message_soft.ogg`, made by `tools/ringtone/make_message.py`, on the `messages_soft` channel) or **Phone's
  sound** (the first `messages` channel, "Messages (phone's sound)"). Each is its own channel because a channel's sound
  can't change once made; Android's own settings for each still apply.
- **Call settings** ("Ringtone, video quality, saving calls"; 2026-11-22, hen: not only recording): Ringtone,
  Video, Advanced, then a **Saving calls** row (showing Off, Ask each time or Always) that opens its own screen
  (`SavingCallsScreen`): Off, Ask, Always, with the note on who keeps a saved call, then Save calls to.
- **Calls › Ringtone** (2026-11-22, hen): **Talk soft chime** (the default: a rising pentatonic chime in a 4-second
  loop, `res/raw/ring_soft.ogg`, made by `tools/ringtone/make_ring.py`) or **Phone's ringtone**. `CallRinger` loops it
  on the ringer's stream until the call is answered, declined or 45 seconds pass; silent in Do Not Disturb and silent
  mode, vibration only in vibrate mode. The ringing notification's channel (`calls_ringing_v2`) has no sound of its own.
- **Calls › Video** (2026-11-22, hen): **H.265 (smaller, sharper)**, the default where the phone has a hardware H.265
  encoder (2026-11-22, hen; a phone without one sends VP8), or **Standard (VP8)**. With H.265 the phone also sends VP8 (LiveKit's backup codec), so a phone
  that can't decode H.265 still sees it. Calls stay encrypted end to end; it applies from the next call.
- **Calls › Advanced › 60 fps video** (off by default; 2026-11-22, hen): calls capture 960×720 at 60 fps with a
  2.5 Mbit/s top layer (30 fps: LiveKit's 4:3 720p preset), and push-to-video 640×480 at 60 fps and 1 Mbit/s (30 fps:
  700 kbit/s; it was 15 fps and 350 kbit/s). Only where the front camera runs a steady 60 fps at that size; elsewhere
  the row says so and stays off. A push-to-video burst sends at most `Talk.VIDEO_BUDGET` (7.4 MB, under the server's
  8 MiB clip) of video, about 85 s at 30 fps and 60 s at 60, then goes on with the voice only.
- **Calls › Advanced › Show video stats** (off by default; 2026-11-22, hen): the Stats pill in video calls
  ([calls.md](calls.md)).
- **Calls › Save calls to** (2026-11-22, hen): **Choose a folder** opens Android's folder picker (the phone, an SD
  card or a USB drive), kept with a lasting permission; under it, the folder's name, or Downloads › Talk.
  **Use Downloads instead** goes back. Save as file on a kept call writes there.
- **In the background › Open Talk when someone talks to me** (off by default): with the screen off it turns on with
  that Talk screen over the lock screen. Android 14 and later ask for "full-screen notifications" first; while that
  isn't allowed, a row in the warning color, "Allow Talk to open over the lock screen", opens Android's page for it
  (2026-11-22: without it nothing opened). "Display over other apps" (the floating window's) does as well: with it the
  Talk screen is started straight away, which Android allows such apps from the background; else a new notification
  of its own carries the full-screen intent (one only fires when its notification is new, and the playing
  notification is updated in place, so it never fired; 2026-11-22, hen).
- **In the background › Reply bubble over other apps** (on by default since 2026-11-22, hen; a phone that had it off
  before that, most likely never chosen, starts with it on once; since then only a changed setting is stored): the bar at the bottom of the screen with a
  Talk button while the phone is in use ([notifications.md](notifications.md), "Reply bubble"). Turning it on opens
  Android's "Display over other apps" for Talk; while that isn't allowed, "Allow Talk to display over other apps"
  shows under it. While it is on without that permission, the app asks once on opening ("Show Talk over other
  apps?", Allow or Not now) and then only shows that row.
- **In the background › Quick reply window** (on by default; 2026-11-23, hen): a message shows its chat at the bottom
  of the screen over other apps, to reply or hold and talk ([notifications.md](notifications.md), "Quick reply window").
- **In the background › Unmute for incoming talk** (on by default; 2026-11-22, hen): a burst that starts while the
  media volume is 0 turns it up to 60% (`TalkUnmute`), with Android's volume bar showing, for as long as bursts play
  (the queued ones and the catch-up too), then back to 0; a volume the user changed meanwhile stays. Nothing changes in
  Do Not Disturb, on a fixed-volume phone or in a call, and the ringer is never touched.
- **In the background › Stay connected** (a switch, off by default): a foreground service keeps the connection, so
  talk plays at once and buttons work with the screen off; a note says it uses a little more battery and that, off,
  the server wakes the phone when someone talks. After a restart it resumes when the app is opened (Android starts
  no microphone service at boot).
- **Hardware buttons › Talk with a button**: a held volume key (while the app is on screen; a short press still
  changes the volume), a headset's button or a Bluetooth talk button press and release talk.
- **Buttons talk in**: No conversation, or one of the contacts and groups.

## Calls

A row, "Save calls · Save calls on this phone", opens Settings › Calls (`CallSettingsScreen.kt`): **Save calls**, Off
(the default), Ask each time, or Always, with a line under each, and a note that a saved call stays in the app's own
storage, leaves it only through Share, and that everyone in the call sees the Recording badge.

## Your server

One card, all selectable to copy:

- The address the app reaches it at now (`host:port`), or "No server yet".
- Its state: "Connected", "Can’t reach your server right now" or "The server doesn’t accept this phone" (orange), or
  "Loading…" while checking.
- "Server ID T6DNBAM-IJR6WLU-…" in monospace once `serverInfo` told it, else "Server ID not known yet".
- "Version …" once the server answered.
- "Knows N addresses to find it again" ([design.md](../design.md), "Finding the server").

Under the cards, the app's version ("Talk 0.1").

## App updates

Above Your server: where the app's update stands ("Talk is up to date", "Checked at 15:40"; "Checking for an
update…"; "Talk 1.2.0 is available", tap to update; "Downloading Talk 1.2.0… 40%"; "Installing…"; "Allow Talk to
install updates" when Android's Install unknown apps is off; "Update failed: <why>", tap to try again),
**Install updates automatically** (a switch, on: "When nothing is going on: no call or talk, the app closed. Off: a
notification, and you tap to update."), and **Check now** (asks the server, which watches the releases). A debug build says "This is a test build" and has no Check now. How updates work: [design.md](../design.md),
"App updates".


- **Contacts › Show in Android Contacts** (2026-11-22, hen; off by default; asks for Contacts): a "Talk" account in
  the phone's Contacts holds one entry per paired contact, by the name shown in Talk, with **Talk to**, **Message**,
  **Voice call** and **Video call**, which open Talk (their push-to-talk screen, the chat, or a call). The entries are
  written from the contacts this phone keeps (2026-11-22: written from the screens' state, a sync at start removed
  them all), and adding the "Talk" account in Android's Settings › Accounts turns this on (or opens Talk to allow
  Contacts first). Android joins it with a phone contact of the same name, or
  it can be linked by hand in Contacts. Only the names go to Contacts; renames, new and removed contacts follow.
  Turning it off removes the account and its entries (`AndroidContacts`).
