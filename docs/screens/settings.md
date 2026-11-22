# Settings

Opened from the profile circle on [Chats](chats.md) (`ui/settings/SettingsScreen.kt`, `SettingsController`). It is
one `SettingsScaffold` page with back, grouped as Signal's Settings as far as phase 2 goes. It reads again on opening
and each time the connection's state changes.

## Account

- **This phone:** the device name, with "account · device N" under it ("Not registered" before enrolling). The device
  number comes from the server (`me`, `serverInfo.deviceNumber`).
- **Linked devices:** "Link a phone: your new phone joins this account with your contacts." Opens
  [Link a phone](link-phone.md).
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
- **In the background › Open Talk when someone talks to me** (off by default): with the screen off it turns on with
  that Talk screen over the lock screen. Android 14 and later ask for "full-screen notifications" first; while that
  isn't allowed, a row in the warning color, "Allow Talk to open over the lock screen", opens Android's page for it
  (2026-11-22: without it nothing opened).
- **In the background › Reply bubble over other apps** (off by default): the bar at the bottom of the screen with a
  Talk button while the phone is in use ([notifications.md](notifications.md), "Reply bubble"). Turning it on opens
  Android's "Display over other apps" for Talk; while that isn't allowed, "Allow Talk to display over other apps"
  shows under it.
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

