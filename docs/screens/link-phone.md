# Link a phone

`ui/settings/LinkPhoneScreen.kt`, `MessagingController.makeLinkCode`; opened from [Settings](settings.md), Linked
devices. A QR code that the user's new phone scans when it registers: it joins this account and gets the keys and
contacts from this phone, sealed end to end. How it works is in [design.md](../design.md), "Linking a phone".

- **Loading** while the code is made (a new one each time the page opens).
- **The code:** "On your new phone, install Talk, tap Get started, then Scan QR code, and scan this code."; the QR
  code (240 dp, black on white); "This code works until <time>, once."; "Keep this screen open until the new phone
  says it's linked: this phone sends it your keys and contacts, sealed so only it can open them. Show this code only
  to your own phone."; **New code** (outlined). There is no Share or Copy: the code is for one's own phone, in hand.
- **Linked:** once this phone answered the new one: "Phone N is linked" and "It has your keys and your contacts.
  Messages to you now reach both phones."; **Done** goes back to Settings.
- **Failed:** "Couldn't make a code: <why>" (offline, or 3 codes waiting already) and **New code**.

On the new phone, the wizard enrolls as with any enrollment QR code ([enrollment.md](enrollment.md)); Chats then
shows "Finishing linking" until the key arrives ([chats.md](chats.md)).
