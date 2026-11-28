# Send to (sharing to Talk)

`ui/media/ShareToScreen.kt` (2026-11-28, hen). Talk is in Android's Share menu for pictures, videos and files, one or
many (`MainActivity`'s SEND and SEND_MULTIPLE). It opens Talk on **Send to**: the title, "N to send", then the chats,
latest first: paired contacts (not blocked) and the groups this user reads, each with its last message as in
[Chats](chats.md). A tap sends what was shared to that chat and opens it; Back drops it. With no chats yet: "No chats
yet. Add a contact first."

What is sent: [conversation.md](conversation.md), "Shared pictures, videos and files"; how it travels, design.md,
"Sharing media".
