# New chat, a contact, the safety number

`ui/contacts/`: the pages for contacts, under `MessagingController` (docs/design.md, "Pairing").

## New chat

`NewChatScreen.kt`, Signal's "New message", from the pencil in [Chats](chats.md):

- **Add contact** first (an ink circle with the Scan icon, "Scan their QR code, or show them yours"): opens
  [Add contact](add-contact.md).
- **Contacts**, by name (`ContactRow`, as in Chats without the last line): a tap opens the contact's page. With none:
  "No contacts yet. You add someone by scanning their QR code in person."

## A contact

`ContactScreen.kt`, Signal's conversation settings, titled with the name:

- The letter (80 dp), the name, and how paired: "Paired · 2 phones", "Waiting for them to confirm the pair", or
  "You blocked <user>".
- **"<user>’s safety number changed"** (a warning card), when a message came with another identity key than the
  kept one (`Contact.changedKey`): nothing is sent to them until **Accept the new number**, which keeps the new key and
  clears Verified.
- **Safety number**: Verified, or "Not verified: compare it with them"; opens the safety number.
- **Block <user>** (red: "They can’t reach you and you can’t reach them until you unblock.") or **Unblock <user>**.
- **Remove <user>** (red): asks first in the message bar, "Remove <user> from your contacts?" with **Remove**; then
  unpairs, says "<user> was removed" and goes home.

When the contact is gone (removed on the other phone), the page says "This contact isn’t in your list anymore."

## The safety number

`SafetyNumberScreen.kt`, Signal's "Verify safety number": "If this number is the same on <user>’s phone, no one is
in between. Compare it in person, or scan their code." Then their shared code (a QR code, 200 dp), the 60 digits in
12 groups of 5 (monospace, four to a row; TalkBack reads them in groups), Verified in teal when it is.

- **Scan their code** (ink): scans the code on their phone's safety number page. "The numbers match. <user> is
  verified." or "That code doesn’t match this safety number. Don’t trust messages until you’ve checked with <user>."
- **Mark as verified** / **Clear verification** (outlined), after comparing the digits by eye.

Both phones show the same digits and code (libsignal's numeric fingerprint, version 2, the user names as the
identifiers); the code is `ptt://safety?v=1&n=<base64url>`.
