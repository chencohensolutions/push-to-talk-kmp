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
- **Saving** (Save calls on, or a server that keeps calls (`serverInfo.keepsCalls`), in a running call): a **Save**
  pill above the buttons starts saving, **Stop saving** (red) stops it. With Ask each time, a card "Save this call?"
  (**Not now** and **Save**) shows once the call is on; with Always saving starts by itself. Nothing is shown to the
  others that the call is being saved.
- **Kept on the server only while someone records** (2026-11-22, hen): Save also asks the server to keep the call
  (`recordCall`), encrypted end to end so only the people in the call can open it. **Stop saving** on the phone that
  started it ends it (`stopRecordingCall`). The server also stops by itself when the recording phone leaves the call,
  when the room empties, or after 4 hours, so hanging up sends nothing. A server that doesn't keep calls (or an older
  one) is ignored: the phone's own saving goes on.
- **Video as the camera sends it** (2026-11-22, hen): each phone sends its camera's own 4:3 picture, uncropped. The
  phone showing it has two layouts (2026-11-23, hen, from the approved mockups), one pill at the top that names the
  layout a tap switches to, kept for next time:
  - **Maximized** (the default): their video covers the whole screen, cropped, behind the camera cutout and status
    bar too; your video is a small window. One-on-one (2026-11-23, hen):
    - **Face height**: one finger up or down on their video moves where their face sits (default just below the
      cutout, about the upper third); with Follow face off the same drag pans the picture up and down. Kept.
    - **Your window** rests in one of six places (top or bottom; left, centre or right), clear of the bars and the
      cutout, of the top lines and buttons while they show, and on its side of the buttons' column. Drag it and it
      follows the finger; let go and it slides to the nearest place, kept for next time.
    - **Not over their face**: when your window would cover their face at the chosen height, the face goes just below
      it (or above, whichever is nearer and still on screen), with Follow face on or off.
    - A tap still shows or hides the controls; a pinch still zooms.
  - **Normal**: upright, their whole picture as wide as the screen at its own shape from the very top, and your video
    in the rest, at its own shape, as big as fits there (`CallGrid.normal`; both shrink together when that would
    leave yours under a quarter of the screen), centred, the buttons floating over it. On its side, side by side: theirs at full height
    on the camera's side, yours in the rest, as big as fits, centred.
  On its side (2026-11-23, hen), whichever way it is turned and in Hebrew too: the call's buttons are a column along
  the edge away from the camera, and in Maximized your window sits beside that column, not under it. A half turn
  swaps the sides at once (the display's rotation is followed, not the configuration).
  The name, pills and buttons float over the video on soft shades; a single tap on the video hides or shows them all
  (no double tap).
- **Group video calls** (2026-11-23, hen: "with 4 or 6 people, all faces are centred and the boxes are evenly
  sized"): with two or more others, everyone (this phone too) gets an equal tile, the columns and rows chosen for the
  screen's shape and turn (`CallGrid.shape`: the biggest equal tiles for a 3:4 picture; 4 in 2×2, 6 in 2×3 upright and
  3×2 on its side, 3 stacked on a tall phone). Every tile is cropped to fill; with Follow face each tile's crop slides
  and zooms (`CallGrid.place`, up to 1.5x) to put that person's face in its middle at the same head-and-shoulders
  size, so the grid looks even; the face finder takes turns, about three looks a second in all. No layout pill.
- **Face hints** (2026-11-23, hen: "the sender sends the face position", then "don't crop the face before
  sending"): while its camera runs, the sending phone sends its whole picture untouched and looks for the biggest face
  up to 15 times a second, as fast as it keeps up (`FaceHinter`, a LiveKit VideoProcessor, ML Kit's on-phone detector, bundled, offline). Each
  face found goes to the others as a hint (`FaceHint`: centre and height as shares of the picture, the frame's time),
  sealed with the call's key, over the call's data channel (topic `face`, lossy). Receivers keep each sender's last
  few (`FaceHints`); a hint stands for 1.5 s.
- **Follow face** (2026-11-23, hen; a pill at the top while someone's video shows, kept for next time; the one face
  option): on this phone's view of the received video only, framed by the sender's hints, or, from a sender that sends
  none (an older app), by the same face finder here (2 looks a second, shared across the tiles in the group grid); calm (2026-11-23,
  hen: "more mellow, 2 key frames a second"): the sender sends a hint twice a second; turned on, at a call's start or
  when a tile appears it goes straight to the face, then glides to each new hint over about half a second, ignoring
  small head moves (5% of the view, 0.08 of zoom), and eases back to the plain picture when the face is lost. **Pinch
  to zoom** (2026-11-23, hen) on the one big remote video: from the whole picture fitted (letterboxed, even in
  Maximized) up to Follow face's most (Maximized: the fill zoom × 1.5; Normal: 1.8); Follow face keeps the face centred
  at the zoom pinched; the zoom is kept per layout for next time; a single tap still only shows or hides the controls; the view
  pans and zooms to keep the face near the upper third, head
  and shoulders about 45% of the height, back to the plain picture 1.5 s after the face is lost. Maximized: the picture is drawn whole and clipped to the screen, so the crop slides to
  the face, with at most 1.5x more zoom; Normal: at most 1.8x inside the tile. Only this phone's view changes.
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
room's metadata said the server keeps it; the server never has it. Once the call has been downloaded to this phone —
here, or by Download saved calls — its **Save as file** button is gone, on the Calls-tab row and in the player's top
bar (2026-11-24, hen; the downloaded ids are kept in `TalkSettings.downloadedCalls`).

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

## Live talk full screen (2026-11-27, hen)

"Make it a call" is gone from the chat and the floating window (hen: "it's not 'make it a call', because two people
speaking is already a call, just in a floating layout; I want to navigate to the call screen and out to the floating
layout at ease"; "'Full screen' is enough"). In its place "⤢ Full screen" shows while their video or both of us are
live: under the live panel in the chat (`FullScreenChip`), under the boxes in the floating window. A tap opens the
same live talk full screen in the app (`LiveFull`, `LiveFullScreen`): nothing is dialled, nothing rings and the other
phone isn't told anything; their video fills it (their face while they show none), the chat's name and the time on
top. Already live when I tap it (my push held, hands-free, or the floating window's), the same press goes on there,
my microphone and camera as they were (2026-11-27, hen: "when transmitting my camera or only voice and entering full
screen my camera or microphone should stay on"; `LiveFull.open`'s `mic` and `camera`). Otherwise I start muted, shown as in a call (hen: "don't encourage to open the mic; show that I am muted, with an icon"):
the microphone with its line through it and "You're muted". Tapping it holds a press for me (renewed every 55 s, so
the other side sees me live as for any push) until tapped again; the camera button does the same with my video, mine
in a small window at the top end. The chevron at the top start, or Back, shrinks it: back to the floating layout when
it came from there (the app goes to the back and the floating window shows them again), else back to the chat; my
press ends first. Both live becomes a call after 10 s (the setting) still turns the room into a call as below.

## A push turned into a call (2026-11-26, hen)

"You're both live · Make it a call" (or Both live becomes a call after 10 s) turns the talk room into the call, with
no ringing, and both phones go straight to a full call screen (`RoomCallScreen`, "turn push to transmit into call
... should navigate to the call screen"): their camera in the middle, whole, or their face while they show none; mine
in a small rounded window at the top end while it's on; the chat's name and the time on top; Mute, Camera and Hang
up at the bottom, as on any call. Back does nothing during it. Hang up ends it on both phones and they're back in the
chat, where it is logged like any call. A video call taken at once because both were live also opens on the usual
call screen. The call inside the chat (below) is no longer used.

## A call inside the chat (2026-11-24, hen)

Started from "You're both live · Make it a call" (or by itself after 10 s with Both live becomes a call), the call
shows inside the chat instead of over everything: docs/screens/conversation.md, "Live in the chat". Full screen, or
leaving the chat, shows the usual call screen. It is logged like any call.
