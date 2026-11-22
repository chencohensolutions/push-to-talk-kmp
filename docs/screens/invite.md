# Invite a friend

`ui/contacts/InviteScreen.kt`, `PairingController.makeInvite`; opened from [Add contact](add-contact.md). One link and
a six-digit code that put the app on the server for a friend and pair you, for a friend who isn't in front of you.
This phone sends them; the server sends no mail. The link's format, the message and what each phone does are in
[design.md](../design.md), "Invites"; the friend's screen is in [enrollment.md](enrollment.md), "Joining by an
invite".

- **Form:** "Send a friend one link and a 6-digit code. Together they put the app on your server for them and add
  you to each other’s contacts. You send them yourself, by email or any chat app."; **Their email (optional)** ("With
  an email, only that address can use the link, and you can email it from here."); **Make invite** (ink, a spinner
  while it is made). Errors under the field: not an email, invites off on the server, too many invites waiting (5),
  the server can't be reached.
- **Made:** a QR code for a friend next to you (240 dp, black on white): the link with its code in it, so
  scanning it is the whole invite (a new phone goes straight to its name and Join; an enrolled one, scanning in Add
  contact, to "Pair with <user>?"), and "Show this QR code to a friend next to you: scanning it is the whole invite.
  To send it, use the buttons below."; "For <email>. Works once, until <date>." or "For whoever
  opens it first. …", then "Code (sent with the link; the QR code above has it already)" and the code in large digits ("042 917"),
  for a friend who scans the QR code here. With an email: **Email the invite** (ink) opens the user's mail app (only
  mail apps) with a message to that address, from their own address: "<user> invited you to Talk", the steps, the
  link and the code; "Opens your mail app with the steps, the link and the code, sent from your own address.". No
  mail app: "No mail app on this phone. Use Share to send it another way.". **Share** (the share sheet with the same
  subject and text; ink when there is no email), **Copy invite** (the same text; a message "Invite copied"), **Make
  another**; and "Whoever has the link and the code can use it, so send them to your friend only. They’ll show as
  not verified until you compare safety numbers."

A friend who joins shows in Chats as "<their name> · <user name>", paired, not verified.
