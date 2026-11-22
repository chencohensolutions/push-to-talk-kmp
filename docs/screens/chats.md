# Chats (home)

The app's home once this phone is enrolled (`ui/home/HomeApp.kt`), Signal's layout in Journal's look. It renders
`ConnectionController`'s state and `MessagingController`'s (`MessagingState`: keys, /ws, contacts, pair requests, the
conversations and how many texts of each aren't read). A contact opens their [conversation](conversation.md).

- **Top bar:** the profile circle (the account's first letter in ink, or a person icon before the server says who
  this is), which opens [Settings](settings.md); the tab's title, "Talk", "Chats" or "Calls", in ScreenTitle; and the
  **Offline** pill (orange cloud), only while the server can't be reached (`ServerHealth.OFFLINE`).
- **Cards** at the top (`HomeCard`), only when there is something to do:
  - **Register again** (warning mark), when the server refuses this phone's certificate or the phone has no identity
    (`ServerHealth.REJECTED`): **Register again** opens the [enrollment wizard](enrollment.md).
  - **"<user> wants to pair"**, one per pair request (someone scanned this phone's code): **Review** opens
    [Add contact](add-contact.md) on that request.
  - **"Finishing linking"**, no button, while this phone waits for the identity key from the phone whose Link a
    phone code it enrolled with (`KeysState.Linking`): "Open Talk on your other phone (phone N) and keep it
    connected: it sends this phone your keys and contacts."
  - When the server has another identity key for this account (`KeysState.OtherIdentity`), two cards:
    **"Link this phone to your other one"**: "On your other phone open Settings, Linked devices, and scan the code
    it shows. …"; **Scan the code** opens the scanner (`linkWithScanned`; a code of another account says "That
    isn't a Link a phone code of this account."). Then **"Another phone of yours holds your keys"** (warning mark):
    **Start over** makes a new identity key (`replaceIdentityKey`); contacts then see a changed safety number.
    Until then this phone uploads no keys.
  - When the keys couldn't be uploaded (`KeysState.Failed`), a muted line says why; it is tried again at the next
    connection.
- **Chats** tab: the contacts (`ContactRow`): the letter in a Raised circle, the name, and one line: Blocked;
  "Safety number changed" in orange; "Waiting for them to confirm"; else the last text ("You: …" for one sent);
  else Paired. The count of unread texts in an ink pill, and a shield for a verified contact. The latest text first,
  then the newest contacts. A tap opens the [conversation](conversation.md). With no contacts: a chat icon over "No chats yet. Add a contact by scanning their
  QR code."
- **Calls** tab: every call, newest first, with call-back buttons ([calls.md](calls.md)); before the first, a call
  icon over "No calls yet. Calls you make and get show here."
- **The pencil** (new chat), bottom right on Chats only, in ink: opens [New chat](contacts.md).
- **Tabs** at the bottom: Talk, Chats and Calls in one segmented pill (`SegmentedBar`), the selected one in ink. The
  app opens on [Talk](quick-talk.md), the Quick talk chats.

The pages over home (Settings, New chat, Add contact, a contact, a safety number) are a stack kept across rotations;
Back closes the top one. Messages (`MessageBar`) sit above the tabs on home and at the bottom of a page.

The connection is checked when the app comes to the front and again with backoff while offline
(`ConnectionController.connect`/`stop`). `MessagingController.start` (keys checked and uploaded, then /ws) and `stop`
are called beside them from `MainActivity`'s onStart and onStop, and `start` again right after enrolling.

Groups and group invitations show here too: see [groups.md](groups.md).
