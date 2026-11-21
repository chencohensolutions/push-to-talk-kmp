# Invite a friend

`ui/contacts/InviteScreen.kt`, `PairingController.makeInvite`; opened from [Add contact](add-contact.md). One link that
puts the app on the server for a friend and pairs you, for a friend who isn't in front of you. The link's format and
what each phone does are in [design.md](../design.md), "Invites"; the friend's screens are in
[enrollment.md](enrollment.md), "Joining by an invite".

- **Form:** "Send a friend one link. It puts the app on your server for them and adds you to each other’s
  contacts. They confirm their email with a code."; **Their email (optional)** ("With an email, only that address
  can use the link."); **Make invite link** (ink, a spinner while it is made). Errors under the field: not an email,
  invites off on the server, too many invites waiting (5), the server can't be reached.
- **Made:** the link's QR code (240 dp, black on white), "For <email>. Works once, until <date>." or "For whoever
  opens it first. …"; **Share link** (ink, the Share icon: Android's share sheet with "<user> invited you to Talk.
  Install the app, then open this link: <link>"), **Copy link** (a message "Link copied"), **Make another**; and
  "They’ll show as not verified until you compare safety numbers, since a link can be forwarded."

A friend who joins shows in Chats as "<their name> · <user name>", paired, not verified.
