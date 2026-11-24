# Enrollment wizard

Shown while this phone has no identity, and again after **Register again**. The server side (codes, certificates,
the QR link) is ptt-server's `enroll-code` and `enrollDevice`; this page is what the app does
(`EnrollmentController`, `ui/EnrollmentWizard.kt`). Copied from journal's wizard and adapted.

## Steps

1. **Welcome:** "Connect this phone to your server", what registering does in three steps, and **Get started**.
2. **Connect** (`EnrollmentStep.EnterCode`, "Step 1 of 3"): the server address (default port 56400) and the
   8-character code, or **Scan QR code** (the Google code scanner, no camera permission) with **Paste link** under it.
   A `ptt://enroll?…` link fills both in and checks the code at once, whether it was scanned, pasted or opened from
   the phone's camera (the app's intent filter).

   **Paste link** (`EnrollmentController.openPasted`, `findEnrollmentLink`) reads the clipboard only when tapped:
   - A link anywhere in it (a chat message with words around it) opens exactly as a scan does.
   - Without a link, a code in the text fills in the code.
   - With neither, it says "Nothing to paste. Copy the enrollment link or code first." and nothing changes.

   A whole link pasted into the address or code field opens the same way.

   Someone's Add contact code (`ptt://pair?…`), scanned or pasted, is not taken as a code: it is for phones already
   set up and has no server, so the screen says "That's an Add contact code, for a phone that's already set up. To
   join, ask them for an invite: Add contact, then Invite a friend." An invite's QR code or link opens
   [Joining by an invite](#joining-by-an-invite).
3. **Confirm** (`EnrollmentStep.Confirm`): "Register this phone?" with the server, the account the code is for and
   when it expires, and the device name (the phone's model, editable). The server row says "Its key matches server ID
   T6DNBAM-IJR6WLU-…" after a link that pinned the key. Otherwise it says "Its certificate is trusted by this phone".
4. **Register** (`EnrollmentStep.Registering`):
   1. The key is created in secure hardware (StrongBox, else TEE).
   2. The certificate request is sent.
   3. The certificate is saved, with the server's CA (from `enrollDevice`, or asked with `serverInfo`).
   4. The connection is tested with `me` over the new certificate.

   Then it ends on **You’re connected**, with the device, its number, the key's kind and the certificate's end, and
   **Open Chats**. Or it ends on **Failed** with what went wrong ("That code didn’t work" keeps the screen for a new
   code); the key made for the attempt is deleted.

## Linking a phone

Scanning another phone's [Link a phone](link-phone.md) code (or opening its `ptt://link` link from the camera) is the
same as scanning an enrollment QR code: the server and code fill in and the wizard goes to Confirm, with the account
that phone is on. Once registered, this phone asks that one for the keys (Chats shows "Finishing linking").

## Enrolling by id

`ptt://enroll?server=https://host:56400&code=ABCD2345&fingerprint=<64 hex>&key=<64 hex>&address=…` (`address=` once
per address). With a `fingerprint`, every connection of the enrollment trusts that key only
(`MtlsHttpClientFactory.createPinned`), so a bare IP that the server certificate doesn't list works. When the address
doesn't answer, the link's other addresses are tried, then the DHT under `key` ([design.md](../design.md), "Finding
the server"). The wizard goes on at the address that answered. Nothing is written to the settings until the phone is
enrolled. An address typed by hand, with no link, must have a certificate the system trusts.

## Joining by an invite

An Invite a friend link ([invite.md](invite.md)) opened on a phone that isn't enrolled, scanned or pasted, skips the
enrollment code and goes straight to one screen:

1. **Invite** (step 2): "<Hen Cohen (hen)> invited you", "Type the 6-digit code <inviter> sent you with the link.",
   the server (with its id, from the link), **Code** (six digits, "It’s in the same message as the link."; filled in
   when the invite's face-to-face QR code was scanned or the whole message pasted), **Email** (the invite's, locked, "This invite is for this address."), or for
   an invite to anyone **Your email (optional)** ("Your contacts don’t see it, and nobody emails you. You can leave it
   empty."), **Your name** ("How <inviter> and your other contacts see you."), **Device name**, the key note, and
   **Join and pair with <inviter>**. Back leaves the invite (to Welcome).
2. Registering and You’re connected as with a code. Then the phone pairs with the inviter on its own, and they show
   in Chats.

Errors say what to do, under the field they are about: the link was used, canceled or expired (ask for a new one);
a wrong code ("Check the message with the link"; 10 wrong ones end the invite); too many tries; not an email; the
invite is for another address; the email has an account already (register with a code from the server's owner); no
name. A failed registration says "Ask
<inviter> for a new invite link." instead of the enroll-code command.

**How should the app refer to you?** (2026-11-23, hen): under the name in the invite step, Male, Female or Not set
(the default); saved at once and shared with contacts once the app runs; changeable in Settings.
