# Groups

`ui/groups/` (`NewGroupScreen.kt`, `GroupScreen.kt`, `GroupParts.kt`) and the group mode of
`ui/contacts/ConversationScreen.kt`. Signal's groups in the library's look. How groups work is in
[design.md](../design.md), "Phase 4: groups".

- **New chat** has **New group** first (the group icon in a Raised circle), then Add contact and the contacts.
- **New group:** the picture (a tap picks a photo; it is made small, at most 192 pixels a side), "Group name", and
  "Invite contacts": the paired contacts that aren't blocked, each with a tick box. **Create group** (ink, enabled with a
  name) makes the group with this user as owner, invites the ones ticked, and opens its chat.
- **Chats** lists groups among the contacts, newest activity first: the picture (or the name's first letter), the name,
  and the last message with who wrote it ("You: …" for ours), or "N members", or "You’re not in this group anymore".
  An invitation is a card on top: "<owner> invited you to <group>", **Open** goes to the group's page.
- **Group chat:** the header has the picture, the name, and "N members" or "<names> typing…"; a tap opens the group's
  page. Each message of someone else's has its author above it (teal). Ours show "Sent" (no receipts in groups yet).
  Instead of the line to write in: "Join the group on its page to send messages." (an invitation), "Waiting for the
  owner’s phone to add you.", or "You’re not in this group anymore. Its messages stay here."
- **The group's page:** the picture (the owner taps it to change it) and the name, "N members" or "<owner> invited
  you". An invitation shows **Decline** (outlined) and **Join** (ink). The owner has **Change the name**. **Members**:
  each with what this phone knows of them: Owner, Invited, You, Verified, "Your contact, not verified", or "Added by the
  owner, not verified by you" (never paired with this user); a tap opens their page (or Add contact for one who isn't
  a contact). The owner has **Remove** (red, asked in the message bar) on each and **Add members** (the contacts not in
  it, ticked, then **Invite**). For a member: **Quick talk** (a switch: the group is a page of the
  [Talk tab](quick-talk.md)) and **When I’m away**. Last, in red: **Leave group** (a member) or **Delete group** (the owner), both asked
  first in the message bar.

## In the chat (2026-11-24, hen)

The same three switches as a contact's page (Keep voice in chat, Keep videos in chat, Both live becomes a call), for
the group's chat; off by default. In a group the top panel shows whoever holds the floor, with their name, and both
live counts any member hands-free and waiting.
