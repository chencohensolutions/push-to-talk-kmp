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

## Enrolling by id

`ptt://enroll?server=https://host:56400&code=ABCD2345&fingerprint=<64 hex>&key=<64 hex>&address=…` (`address=` once
per address). With a `fingerprint`, every connection of the enrollment trusts that key only
(`MtlsHttpClientFactory.createPinned`), so a bare IP that the server certificate doesn't list works. When the address
doesn't answer, the link's other addresses are tried, then the DHT under `key` ([design.md](../design.md), "Finding
the server"). The wizard goes on at the address that answered. Nothing is written to the settings until the phone is
enrolled. An address typed by hand, with no link, must have a certificate the system trusts.
