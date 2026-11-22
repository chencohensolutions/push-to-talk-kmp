# Calls

`ui/calls/CallParts.kt`, with the platform's part in `CallServices.kt`. How calls work is in
[design.md](../design.md), "Phase 6".

## Starting a call

A [conversation](conversation.md)'s header has **Video call** and **Voice call** buttons before the Talk button,
for a paired contact and for a group (where one can talk). The first call asks for the microphone, and for a video
call the camera too; without the camera a video call goes on as a voice call. Without the microphone the message bar
says "Calls need the microphone. Allow it in the app's settings."

## The call screen

`CallScreen`, over everything (Back does nothing) while a call is on, on a dark background whatever the theme:

- **Ringing** (an incoming call): the caller's letter circle and name, "Incoming voice call" or "Incoming video
  call", and two round buttons: **Decline** (red, a cross) and **Answer** (teal, the call or video icon). Answering
  asks for the microphone (and camera) first if needed.
- **Calling** (this phone called): the name and "Calling…"; then **Connecting…** after an answer here.
- **In the call**: the others' cameras fill the screen, stacked (or their letter circle and the conversation's name
  for a voice call), with the length "1:24" (and who is in it, in a group). This phone's own camera sits in a
  corner, mirrored. Round buttons: **Mute** / Unmute, **Camera off** / Camera on, **Flip** (front and back camera,
  while the camera is on), and **Hang up** in red.
- The red **Recording · Dana** badge under the name while someone in the call saves it, on this phone or on the
  server. The server's part comes from the LiveKit room's metadata (`{"recording":true,"by":"user.device",…}`, read
  on joining and on every change), so every phone in the call shows it, also one that missed the chat message.
- **Saving** (Save calls on, or a server that keeps calls (`serverInfo.keepsCalls`), in a running call): a **Save**
  pill above the buttons starts saving, **Stop saving** (red) stops it. With Ask each time, a card "Save this call?"
  ("Everyone in the call will see that you're recording.", **Not now** and **Save**) shows once the call is on; with
  Always saving starts by itself.
- **Kept on the server only while someone records** (2026-11-22, hen): Save also asks the server to keep the call
  (`recordCall`), encrypted end to end so only the people in the call can open it. **Stop saving** shows for anyone in
  the call while the server keeps it, also for a recording someone else started (`stopRecordingCall`). The server
  also stops by itself when the recording phone leaves the call, when the room empties, or after 4 hours, so hanging
  up sends nothing. A server that doesn't keep calls (or an older one) is ignored: the phone's own saving goes on.
- **Ended**: for two seconds, "Call ended", "Busy", "Declined", "No answer", "Couldn't connect" or "Answered on another
  phone", then the screen goes.


## Playing a kept call

A call the server kept (someone recorded it) has **On the server · Play** under its line in the chat, and **Play** and
**Save as file** buttons on its row in the Calls tab. The call's key stays on its entry (`CallEntry.keptKey`) once the
room's metadata said the server keeps it; the server never has it.

- **Getting it**: "Getting the call from your server and opening it on this phone…" with a progress bar. The phone
  asks `callRecording` for the tracks it may read, downloads each one (`GET /blob/<id>`), decrypts the frames with the
  call's key (LiveKit's frame encryption, `LiveKitFrames`), and writes one Matroska file without re-encoding
  (`KeptCallFile`, `Matroska`): a video and an audio track per person, named after them, lined up by when each keeping
  began. It is only in the app's cache while the player is open, and goes when it closes (2026-11-22, hen: saved
  calls live in the folder chosen, not in the app); the decrypted pieces go as soon as the file is made.
- **The player**: everyone's picture in a grid (one or two people one above the other, more two by two), each with
  their name, and an avatar for someone without video; every voice plays. One set of controls: play or pause, a slider
  to move through it, and the time.
- **Save as file** (in the player's top bar, or on the call's row, which opens the player and saves once it's ready):
  writes `<chat> <date>.mkv` into the folder chosen in Settings › Calls › Save calls to (any folder Android's picker
  offers: the phone, an SD card, a USB drive), or into Downloads › Talk when none is chosen; then "Saved in <folder>"
  with **Share**. The file stays there after the app is removed. A chosen folder that isn't there any more (a card
  taken out): "The folder … isn't there", with **Choose a folder**.
- Nothing kept that this user may open: "Your server has nothing of this call you can open." A failure: "The call
  couldn't be opened. Try again later."

## Ringing outside the app

Each call is a Telecom call (Core-Telecom): an incoming one rings like a phone call, with a full-screen notification
(Android's incoming-call style) with **Decline** and **Answer**; Answer opens the app on the call screen, over the
lock screen, and answers. A running call has an ongoing call notification with **Hang up**. A call that wasn't
answered leaves a "Missed voice call" / "Missed video call" notification that opens its chat. Channels: Incoming
calls (high), Ongoing call (low), Missed calls.

## In the chat and the Calls tab

Each call is an entry in its chat, in a bubble on the caller's side: the call or video icon and "Voice call · 3:12",
"Missed video call" (red), "Voice call · Declined", "· No answer", "· Busy", "· Cancelled", "· On another phone",
"· Couldn't connect". A tap calls back the same way. A saved call adds "Saved · Share", which shares its MP4. Chats' preview line shows the same words.

The **Calls** tab (`CallsList`) lists every call, newest first: the letter circle, the name (red for a missed call),
"Outgoing" or "Incoming", the call's line and its date and time, a Share button for a saved call, and a call or video
button at the right that calls back. A tap on the row opens the chat. With no calls yet, its empty state stays: "No calls yet. Calls you make and get
show here."
