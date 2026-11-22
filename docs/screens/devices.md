# Your devices

`ui/settings/DevicesScreen.kt`, `MessagingController.loadDevices` / `revokeDevice`; opened from
[Settings](settings.md), Your devices (2026-11-22, hen). The user's phones that the server hasn't revoked (the
server's `devices` query), read each time the page opens.

- **Loading**, then "Every phone registered to your account. Remove one you lost or don't use: it is signed out of
  Talk and stops getting your messages."
- **Phones** card, this phone first. No device numbers are shown: they are libsignal's internal ids, and confused (2026-11-22, hen). Each row: the name the phone enrolled with ("· this phone" on
  this one); when it last reached the server ("Active now" for this phone and one seen in the last two minutes, "Last
  seen 25 minutes ago" within a day, else the date and time; "Last seen: not known yet" when the server has no time
  for it, as for phones not seen since the server began keeping it); then "Registered <date> · key in the security chip" (StrongBox), "key in secure hardware" (TEE or Secure Enclave) or "key in software" (emulators).
- **Remove** on every row but this phone's asks "Remove <name>?" — "It is signed out of Talk now and gets no more
  messages or calls. To use it again, it registers again with a new link." — then calls `revokeMyDevice`: the server
  revokes its certificates, closes its /ws and drops its keys and queue, so contacts stop sending to it. A toast says
  "<name> is removed" and the list reads again. A phone can't remove itself (the server refuses CURRENT_DEVICE).
- **Link a phone** (outlined) opens [Link a phone](link-phone.md).
- **Failed:** "Couldn't read your devices: <why>" and **Try again**.
