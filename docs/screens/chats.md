# Chats (home)

The app's home once this phone is enrolled (`ui/home/HomeApp.kt`), Signal's layout in Journal's look. It renders
`ConnectionController`'s state and `MessagingController`'s (`MessagingState`: keys, /ws, contacts, pair requests, the
last test line of each contact). The conversations themselves are phase 3; a contact opens their page.

- **Top bar:** the profile circle (the account's first letter in ink, or a person icon before the server says who
  this is), which opens [Settings](settings.md); the tab's title, "Chats" or "Calls", in ScreenTitle; and the
  **Offline** pill (orange cloud), only while the server can't be reached (`ServerHealth.OFFLINE`).
- **Cards** at the top (`HomeCard`), only when there is something to do:
  - **Register again** (warning mark), when the server refuses this phone's certificate or the phone has no identity
    (`ServerHealth.REJECTED`): **Register again** opens the [enrollment wizard](enrollment.md).
  - **"<user> wants to pair"**, one per pair request (someone scanned this phone's code): **Review** opens
    [Add contact](add-contact.md) on that request.
  - **"Another phone of yours holds your keys"** (warning mark), when the server has another identity key for this
    account (`KeysState.OtherIdentity`): **Start over** makes a new identity key (`replaceIdentityKey`); contacts then
    see a changed safety number. Until then this phone uploads no keys.
  - When the keys couldn't be uploaded (`KeysState.Failed`), a muted line says why; it is tried again at the next
    connection.
- **Chats** tab: the contacts (`ContactRow`): the letter in a Raised circle, the name, and one line: Blocked;
  "Safety number changed" in orange; "Waiting for them to confirm"; else the last test line ("You: …" for one sent);
  else Paired. A shield follows a verified contact. The latest line first, then the newest contacts. A tap opens the
  [contact's page](contacts.md). With no contacts: a chat icon over "No chats yet. Add a contact by scanning their
  QR code."
- **Calls** tab: a call icon over "No calls yet. Calls you make and get show here."
- **The pencil** (new chat), bottom right on Chats only, in ink: opens [New chat](contacts.md).
- **Tabs** at the bottom: Chats and Calls in one segmented pill (`SegmentedBar`), the selected one in ink.

The pages over home (Settings, New chat, Add contact, a contact, a safety number) are a stack kept across rotations;
Back closes the top one. Messages (`MessageBar`) sit above the tabs on home and at the bottom of a page.

The connection is checked when the app comes to the front and again with backoff while offline
(`ConnectionController.connect`/`stop`). `MessagingController.start` (keys checked and uploaded, then /ws) and `stop`
are called beside them from `MainActivity`'s onStart and onStop, and `start` again right after enrolling.
