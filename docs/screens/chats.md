# Chats (home)

The app's home once this phone is enrolled (`ui/home/HomeApp.kt`), Signal's layout in Journal's look. Phase 1 has
no contacts yet, so both tabs are empty.

- **Top bar:** the profile circle (the account's first letter in ink, or a person icon before the server says who
  this is), which opens [Settings](settings.md); the tab's title, "Chats" or "Calls", in ScreenTitle; and the
  **Offline** pill (orange cloud), only while the server can't be reached (`ServerHealth.OFFLINE`).
- **Register again** card (`HomeCard` with a warning mark), only when the server refuses this phone's certificate or
  the phone has no identity (`ServerHealth.REJECTED`): "This phone isn’t registered with the server", and
  **Register again** opens the [enrollment wizard](enrollment.md). Nothing else is shown while all is well.
- **Chats** tab: a chat icon over "No chats yet. Add a contact by scanning their QR code."
- **Calls** tab: a call icon over "No calls yet. Calls you make and get show here."
- **The pencil** (new chat), bottom right on Chats only. It looks disabled (Muted on Raised), because pairing comes in
  phase 2. A tap only says "Pairing comes next: adding a contact by QR code isn’t built yet."
- **Tabs** at the bottom: Chats and Calls in one segmented pill (`SegmentedBar`), the selected one in ink.

The connection is checked when the app comes to the front and again with backoff while offline
(`ConnectionController.connect`/`stop`, called from `MainActivity`'s onStart and onStop).
