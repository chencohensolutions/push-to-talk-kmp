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
- **Zoom.** Pinch, pan, and a double tap zooms in to 2.5× or back.
- **The original's pill**, at the bottom while it isn't here: "Asking for the original", then "Getting the original ·
  3 of 8 MB" with a thin red bar and ✕ (stops; what came is kept and opening it again goes on). If it can't come:
  "The original is no longer on their phone", "The file on their phone was changed since it was shared", or "The
  original didn't come" with Try again.

- **Info** (the ⓘ at the top right, `ui/media/MediaInfo.kt`): a sheet over the picture, 3/4 of the screen at most,
  scrolling; back or a tap above it closes it. *The original*: its size, type, taken time and place as shared, then
  every EXIF tag the file has once the original is on this phone ("Its EXIF shows once the original is on this phone"
  until then). *On this phone*: each preview (and a video's poster) made for it, with its size, type, bytes, whether
  it is kept here and which one is drawn here; then the original: the file shared from this phone, a copy fetched here
  (its size), or not here; and until when the server keeps it (30 days from its upload). *Who has what*: the one who
  shared it has the original; each other member the preview made for their screen width, received or not yet when
  this phone has their receipt (`MediaKeeping`, from `MessagingController.mediaKeeping`; a peer's own cache isn't
  seen, so "has" means the message reached them).

Still to come: Share and Save as icons (no menu), the thumbnail strip.
