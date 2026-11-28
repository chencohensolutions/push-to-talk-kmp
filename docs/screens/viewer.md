# Viewer

A shared picture, video or file full screen (2026-11-28, hen), opened by a tap on it in a chat
(`ui/media/MediaViewer.kt`, the page `view/<message>/<item>` in `HomeApp`).

- **Black, edge to edge.** At the top, over a dark fade: back, the sender's name ("You" for this phone's) and when it
  was sent; "2 / 5" when the message has several pictures or videos, which a swipe moves between.
- **Out of the chat and back into it.** The picture grows from where it sits in the chat to fill the screen while the
  black comes up behind it, in one move (300 ms); back shrinks it into the same place (the chat stays drawn under the
  viewer, so it is there to shrink into). Opened from elsewhere, it simply fades in.
- **The preview at once, then the original.** The preview this phone shows in the chat is drawn first. The untouched
  original is asked for quietly (`MessagingController.openOriginal`): on the phone that shared it, it is read from where
  it is; elsewhere it comes from the sharing phone through the server and is kept in the cache. A picture switches to
  the original (no bigger than 4096 on its long side); a video plays the original; a file opens in an app that reads it.
- **The whole screen.** Not zoomed, the picture is as large as the screen allows (fitted, edge to edge, under the
  system bars; the bars float over it). A tap hides the top bar, the pill and the system's bars, so nothing covers
  it; another tap brings them back.
- **Zoom, as Journal's** (`ui/media/ViewerZoom.kt`, copied from Journal's): a double tap steps fitted → filling the
  screen → the original's own pixels (1:1) → fitted, around the tapped point (a step too close to the one before is
  left out). A pinch zooms around the fingers, from a little below fitted (it springs back) to twice the original's
  pixels, and let go within 15% of fitted, filling or 1:1 it snaps there. Zoomed, one finger pans and a flick glides
  on and slows down; the picture never moves past its edges, and a side smaller than the screen stays centred. While
  zoomed a swipe pans instead of changing pictures; a picture swiped away goes back to fitted. The original opens at
  4096 on its long side and, once zoomed in, is decoded again at its own pixels (at most 24 MP, 8192 a side).
- **The minimap and the sharp detail, as Journal's** (`ui/media/ViewerDetail.kt`): while zoomed, the whole picture
  small at the top right with the part on screen outlined and the rest dimmed; grey until what is on screen is at the
  original's quality, its colour then fading in. Zoomed past what the decoded picture holds, the part on screen (and
  15% around it) is decoded from the original at the screen's detail each time the picture stops for 150 ms, and
  fades in over it (JPEG, PNG, WebP, HEIF; the EXIF turn kept). A picture left zoomed opens there again (a 550 ms
  glide after it lands) while the app runs; Journal keeps that in the photo's note, Talk has none.
- **The original's pill**, at the bottom while it isn't here: "Asking for the original", then "Getting the original ·
  3 of 8 MB" with a thin red bar and ✕ (stops; what came is kept and opening it again goes on). If it can't come:
  "The original is no longer on their phone", "The file on their phone was changed since it was shared", or "The
  original didn't come" with Try again.
  On the phone that shared it, the file is checked to be readable first: when it isn't (with only selected photos
  allowed, the gallery's pass to a picked picture ends when Talk restarts), the pill says "Talk can't read your
  original on this phone now" with **Allow all photos** (Android's prompt, or Talk's app settings once Android stops
  asking); once allowed, it opens.

- **Info** (the ⓘ at the top right, `ui/media/MediaInfo.kt`): a sheet over the picture, 3/4 of the screen at most,
  scrolling; back or a tap above it closes it. *The original*: its size, type, taken time and place as shared, then
  every EXIF tag the file has once the original is on this phone ("Its EXIF shows once the original is on this phone"
  until then). *On this phone*: each preview (and a video's poster) made for it, with its size, type, bytes, whether
  it is kept here and which one is drawn here; then the original: the file shared from this phone, a copy fetched here
  (its size), or not here; and until when the server keeps it (30 days from its upload). *Who has what*: the one who
  shared it has the original; each other member the preview made for their screen width, received or not yet when
  this phone has their receipt (`MediaKeeping`, from `MessagingController.mediaKeeping`; a peer's own cache isn't
  seen, so "has" means the message reached them). Opening it asks the chat's phones what they hold, and a moment later
  each phone's own word replaces the guess: the original (the file shared), a copy of it, the only copy kept, a
  preview so wide, or nothing, with when it said so (a user's phones each on a line). *On this phone* also says when
  its copy is kept as the only one known, and when the original isn't known to be anywhere.
  *Asked for its original* (mine): every request for it, newest first: whose phone, when, and what came of it (sent,
  already on the server, gone, changed, or refused and why).

Still to come: Share and Save as icons (no menu), the thumbnail strip.

## Crop and focus

On my own pictures (when this phone can still read the file shared), the top bar has a crop icon (teal once the
picture has a composition): it opens the crop editor on the message's pictures, and Done changes them quietly for
everyone (docs/design.md, "Sharing media"). The viewer always shows the whole original; until it is here, a cropped
preview is drawn where its part lies in the picture.

The editor (`ui/media/CropEditor.kt`, 2026-11-28, hen: "interact with no text, only icons") is black, icons only: Close,
the counter ("1 / N"), and the corners icon (the whole picture) on top; the original with the trimmed parts dimmed, a
white line and a handle at the top and bottom edge, thirds inside; under it the shape icons (free, 4:5, 1:1, 16:9; the
one the crop is at is filled). Drag an edge to trim (it snaps at 4:5, 1:1 and 16:9 with a light tick), drag inside to
move the kept part, tap to set the point of interest (a ring with a dot). With several pictures, a filmstrip picks one
(a teal dot on those set) and the copy icon gives them all this one's crop. The coral check applies the changes.


Opening a cropped picture (2026-11-28, hen: "the transition should be seamless", "make expanding animation when full
image arrives", "try to start the animation instantly while waiting to load the original image"): it moves at once, its
kept part starting exactly where the chat shows it and heading for filling the screen. As soon as the whole original is
decoded (for mine, usually during that move) it heads for the whole picture fitted to the screen instead, opening out
above and below, the two blending into one move; an original that comes later opens it out then (300 ms). Closing
folds it back into the crop.
