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
- **Video as the camera sends it** (2026-11-22, hen): each phone sends its camera's own 4:3 picture, uncropped. The
  phone showing it has two layouts (2026-11-23, hen, from the approved mockups), one pill at the top that names the
  layout a tap switches to, kept for next time:
  - **Maximized** (the default): their video covers the whole screen, cropped, behind the camera cutout and status
    bar too; your video is a small corner window.
  - **Normal**: upright, their whole picture as wide as the screen at its own shape from the very top, and your video
    in the rest, at its own shape, as wide as that allows, centred. On its side, side by side: theirs at full height
    from the start edge, yours in the rest, as big as fits, centred.
  The name, pills and buttons float over the video on soft shades; a single tap on the video hides or shows them all
  (no double tap).
- **Follow face** (2026-11-23, hen; a pill at the top while someone's video shows, kept for next time, apart from
  Frame my face; both may be on): on this phone's view of the received video only (the big tile in a group call), the
  same face finder looks four times a second and the view pans and zooms to keep the face near the upper third, head
  and shoulders about 45% of the height, easing in half a second, small moves left alone, back to the plain picture
  1.5 s after the face is lost. Maximized: the picture is drawn whole and clipped to the screen, so the crop slides to
  the face, with at most 1.5x more zoom; Normal: at most 1.8x inside the tile. Nothing is sent.
- **Stats** (2026-11-22, hen; Call settings › Advanced › Show video stats, off by default): a **Stats** pill at the top
  right shows or hides, on each video and your preview, its frame rate, bit rate, size and codec from WebRTC's stats,
  once a second (your camera: all simulcast layers' bytes, the biggest layer's size and rate). Kept for next time.
- **Your crop, on your preview** (2026-11-22, hen): each phone tells the others how it shows their video (VIEW in the
  call's messages: Fill or Fit, and its tile's shape). Your own preview is your whole picture, in its own shape, with
  a thin outline of the part each Fill viewer sees (their name on it when there are several); nothing for Fit.
- **Orientation** (2026-11-22, hen): the pill beside it cycles **Auto rotate** (the phone's sensor, even with
  Android's auto-rotate off), **Portrait** and **Landscape**, kept for next time; back to the app's own when the call
  screen goes. In landscape the others' videos sit side by side and this phone's own picture is wide; the camera's
  picture is sent upright either way.
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
- **Audio as RED:** the phones send call audio as RED (Opus with a copy of the frame before), encrypted whole, so the
  server keeps it whole (`audio/red`) and the phone takes each packet's primary Opus frame after decrypting it.
- **Rotation and crops in a kept call** (2026-11-22, hen): the picture goes as the camera captures it, with its
  rotation alongside, so while the server keeps a call each phone logs its camera's rotation and size, and how it
  shows each video (Fill or Fit, the tile's shape), sealed with the call's key (`logCallEvent`, `CallEvent`): once
  when keeping begins, then on every change. The file starts each video turned by its sender's first rotation
  (Matroska's ProjectionPoseRoll, which ffmpeg, VLC and the app's player honour), and has the logs as a text track,
  "Call events", one JSON line per event.
- **The player**: turns each picture by its sender's rotation as it changes; **As seen** / **Whole** (when the call
  has views logged) shows each picture cropped as the others saw it, or whole. Everyone's picture in a grid (one or two people one above the other, more two by two), each with
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
