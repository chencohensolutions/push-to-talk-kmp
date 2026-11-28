# Viewer

A shared picture, video or file full screen (2026-11-28, hen), opened by a tap on it in a chat
(`ui/media/MediaViewer.kt`, the page `view/<message>/<item>` in `HomeApp`).

- **Black, edge to edge.** At the top, over a dark fade: back, the sender's name ("You" for this phone's) and when it
  was sent; "2 / 5" when the message has several pictures or videos, which a swipe moves between.
- **The preview at once, then the original.** The preview this phone shows in the chat is drawn first. The untouched
  original is asked for quietly (`MessagingController.openOriginal`): on the phone that shared it, it is read from where
  it is; elsewhere it comes from the sharing phone through the server and is kept in the cache. A picture switches to
  the original (no bigger than 4096 on its long side); a video plays the original; a file opens in an app that reads it.
- **Zoom.** Pinch, pan, and a double tap zooms in to 2.5× or back.
- **The original's pill**, at the bottom while it isn't here: "Asking for the original", then "Getting the original ·
  3 of 8 MB" with a thin red bar and ✕ (stops; what came is kept and opening it again goes on). If it can't come:
  "The original is no longer on their phone", "The file on their phone was changed since it was shared", or "The
  original didn't come" with Try again.

Still to come: Share, Save and Info as icons (no menu), the details sheet on a swipe up, the thumbnail strip.
