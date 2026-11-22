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

## Your server

One card, all selectable to copy:

- The address the app reaches it at now (`host:port`), or "No server yet".
- Its state: "Connected", "Can’t reach your server right now" or "The server doesn’t accept this phone" (orange), or
  "Loading…" while checking.
- "Server ID T6DNBAM-IJR6WLU-…" in monospace once `serverInfo` told it, else "Server ID not known yet".
- "Version …" once the server answered.
- "Knows N addresses to find it again" ([design.md](../design.md), "Finding the server").

Under the cards, the app's version ("Talk 0.1").
