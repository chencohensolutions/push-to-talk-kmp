# Notifications

`Notifications.kt` (androidApp), channel **Messages** ("Texts from your contacts", high importance). The phone is
woken by the server's push and reads the texts from its queue; nothing of a text goes through Google
([design.md](../design.md), "Phase 3").

- **One notification per conversation**, Android's messaging style: the contact's name and their unread texts (the
  last 7), the count, and a speech-bubble icon. A tap opens the [conversation](conversation.md).
- **Reply:** typed in the notification; the conversation is marked read and the reply sent (or kept in the outbox),
  over a short connection the action opens.
- **Mark as read:** a read receipt goes to them, and to this user's other phones.
- It goes away when the conversation is read here or on another of the user's phones.
- The app asks for the notifications permission at first start (after the local network one); the answer goes to
  the server with the push token, which sends high-priority pushes only to phones that show notifications.

## App updates

When the server pushes `app_update` and its release is newer than the app (release builds only): with Install updates
automatically (the default) the update installs by itself when nothing is going on, and this notification shows only
when Android wants the user to confirm (a tap opens Android's screen) or Install unknown apps is off. Otherwise, once
per version, "Talk 1.3.0 is available", "Tap to update. Android asks you to confirm.", on the App updates channel; a
tap opens the app and starts the update ([design.md](../design.md), "App updates").
