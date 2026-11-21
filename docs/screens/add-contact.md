# Add contact

Pairing by QR code in person (`ui/contacts/AddContactScreen.kt`, `PairingController`), Signal's "scan or show a code"
in Journal's look. Opened from [New chat](contacts.md), or from a pair request's card in [Chats](chats.md) (then it
opens on Review). The code's format and what each phone checks are in [design.md](../design.md), "Pairing".

One scan pairs both: either phone may scan the other's code. Each step takes the code's place:

- **Showing** (the first step): "Meet in person. They scan your code, or you scan theirs: one scan pairs you both.";
  this phone's QR code (black on white, 240 dp), the user's name under it, and "Works once, until <time>". A new code
  is made 30 seconds before it expires while the page is open. While it is made: Loading…; when it can't be (offline,
  too many codes, keys not uploaded yet), why, and **Make a code**. **Scan their code** (ink, the Scan icon) opens
  Google's code scanner.
- **Scanned:** their letter, "Pair with <user>?", "You’ll both see each other in Chats, and messages between you are
  end-to-end encrypted.", **Pair** and **Cancel** (back to the code).
- **Review** (a pair request): "Pair with <user>?", "<user> scanned your QR code. Pair only if it was them, in front
  of you.", **Pair** and **Decline** (forgets the request and closes the page).
- **Sending:** their letter, a spinner, "Pairing with <user>…".
- **Waiting:** "Waiting for <user>": this phone confirmed and they haven't yet; it turns into Paired when they do.
  **Done** goes home.
- **Paired:** "You and <user> are paired", and a pointer to the safety number. **Done** goes home.
- **Not paired** (`PairingStep.Failed`), with what went wrong and **Back to your code**:
  - not a Talk code; a code from a newer app; this phone's own code (or another phone of the same account);
  - a code from a phone on another server;
  - used or older than 10 minutes ("Ask them to show their code again.");
  - the server gave other keys than the code's ("Don’t pair now; scan their code again in person.");
  - their phone has no keys yet; one of them blocked the other; no such user; too many codes;
  - this phone's keys aren't on the server yet; the server can't be reached.
- **Scanning failed:** a message, "The camera couldn’t read a code. Try again." Closing the scanner does nothing.

Leaving the page stops making codes and forgets a code that was read and not paired.
