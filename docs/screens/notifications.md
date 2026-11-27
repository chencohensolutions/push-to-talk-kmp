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

## Talk (background)

Three channels, so each can be tuned or hidden on its own in Android's settings:

- **Talk is ready** (least important, silent, no badge): "Talk is ready", "Talk from others plays out loud. Not
  connected: no battery used.", **Stop** (until the next app open). It only keeps the service that lets woken bursts
  play loud; hiding the channel keeps the service working.
- **Talk playing** (high importance, no sound of its own: the burst is the sound): while a woken burst plays, the
  sender (and the group), "Talking… 0:04", "Hold your headset button to reply", with **Talk back**, **Stop** (what plays
  and what waits) and **Mute <name> 1 h** (their bursts show the missed-push notification instead of playing, for an
  hour). A tap opens that conversation's Talk screen. When it ends it goes at once (2026-11-25, hen: "show
  notification only if i missed the stream"): a push that was heard, played or live in the room, leaves nothing in
  the shade.
- **Missed push** (the Talk channel, high importance, its sound): only for a push the user didn't hear: Notify only,
  muted for an hour, the phone couldn't play it, or it reached the phone too late to play (over 30 s old, the app
  not on screen). "Missed push from Sharon" ("Missed video push from Sharon", "… in Family" in a group), "Tap to open
  the chat"; a tap opens the chat, with **Play**, **Reply**, a typed reply and Mark as read. One per chat (the newest
  replaces it), and it goes when the chat is read, here or on another phone of the user.
- **Talk back** (2026-11-22, hen: the Talk screen, not a voice message): opens that conversation's Talk screen, the
  big hold-to-talk button with the microphone already open; over the lock screen Android asks to unlock first.
- **Floating window** (2026-11-24, hen picked "A, a small pill at the bottom"; voice "c, but remove the wave, make
  the green circle glow as the voice"). Settings › Talk › Reply bubble over other apps (off by default; needs
  Android's "Display over other apps", asked for when it's turned on). While the phone is in use and unlocked and the
  app isn't on screen, someone talking shows two small floating pieces; the app beneath stays usable, and a touch
  outside closes nothing (only Close, or a swipe up on the top box).
  - **At the bottom**, about 24 dp above the navigation bar, one pill: Chat (34 dp) · Push-to-talk (46 dp) · Close
    (34 dp). Hold Push for voice push-to-talk and let go to send (a tap starts it, the next tap sends); it glows while
    held. A camera-and-lock target shows above it while held: sliding up to it (56 dp) sends the voice so far and
    switches to hands-free video (2026-11-24, hen: "sliding the button up open hands free video"; the camera opens as
    the finger starts to slide), and the button becomes a coral "■ Stop · 0:42" (renewed at 55 s, as the server ends a
    floor at 60 s). No Voice | Video switch and no hands-free voice any more. A ring around the pill pulses with the
    voice. A call ringing turns the pill into Answer (the push button, green) and Decline (Close, red).
    **One frame, one move** (2026-11-25, hen: "the bottom pill transition is juggling when morphing"): the pill is one
    frame that grows and shrinks about its centre in 300 ms with an ease, Chat at its left end and Close at its right
    gliding with it, and Stop widening out of the Push button's own circle; the same in Hebrew and English (each
    button is put by its left edge, `shapePill`). The timer's digits are all one width and Stop is as wide as its
    widest time, so the seconds don't nudge it. The room for the lock above the button is always there, unseen, so
    the window never changes size and the pill never moves when the lock shows; only the pill itself takes touches.
    A press that waits for the floor looks like waiting, not live: the Push button dimmed inside a dashed ring,
    "Waiting for Sharon…" in Stop's place when hands-free, and no pill or box of mine in the row at the top until
    its turn comes.
  - **Voice** (2026-11-24, hen, "B, a slim pill at the top"): a slim pill at the top centre, entering like the video
    box (from the top edge, resting just below the status bar): their face in a teal ring that glows with their voice
    (brightness, glow and thickness follow the level, 60 ms up and 200 ms down; no wave), "Sharon is talking" and the
    time. My own voice push the same with my face in a coral ring and one line, "You · 0:03". Both at once: two pills
    side by side; a pill beside a video box when one side is on video (top-aligned). A tap opens the chat, a swipe up
    puts it away. "The pill becomes the video": when voice turns into video (sliding up to hands-free video, or theirs),
    the pill itself grows into the box, one frame growing about its own centre with its top edge still, straight to
    the picture's known shape (300 ms), and back if video stops while the voice goes on. The bottom pill stays
    Chat · Push · Close (Stop while hands-free).
  - **Video**: a box at the top centre slides in from the very top edge of the screen and comes to rest just below
    the status bar (2026-11-24, hen: "enter from the edge of the screen and start just below the notification bar";
    250 ms; a swipe up sends it back out the same way): 23.6% of the screen high and as wide as
    the picture's own shape (no crop, no bars), rounded 18 dp with a thin teal outline, "Name · 0:04" inside its
    bottom-start corner. While I send video my camera shows the same way (coral outline, "● You · LIVE 0:03", one line),
    side by side with theirs when both are live, and (since 2026-11-27) "⤢ Full screen" under them while a video
    shows or both are live, which opens the live talk full screen in the app (docs/screens/calls.md); before that
    "You're both live · Make it a call" under them (a tap turns
    the talk room into the call, no ringing, and opens the chat with it; without a room to turn, a short word). Drag moves it; a swipe up puts it away until their next burst; a tap opens the chat.
  - **The row's places and moves** (2026-11-25, hen: "every combined animation in one move"; his phone is in Hebrew,
    where items came in at the wrong side and moved in steps). The row's window is as wide as the screen and as tall
    as the tallest row, once: nothing that shows in it moves or resizes it, and only the items themselves take touches
    (the app beneath stays usable around them). Each side is one cell, a voice pill or a video box, put at a place
    counted in pixels from the screen's left edge (`LiveLayout`): alone it is centred on the screen; two are centred
    as a pair, 6 dp apart, theirs at the language's start side (the right in Hebrew) and mine at the other. The
    language picks only that order; nothing in the geometry is mirrored, and the row is centred on the screen wherever
    the system puts the window. Every change is one move of 300 ms with an ease for everything at once
    (`LiveMotion`; [style.md](style.md), "Motion"): a new item drops in from above the screen's top edge straight into
    its own place while the other glides aside; one that ends goes back up through the top edge while the other glides
    to the centre; a pill growing into a box keeps its centre and its top. A change in the middle of a move starts
    from wherever everything is. The system's animation speed counts (Remove animations: at once). When the last
    side ends, its item goes up at once (2026-11-25, hen: "stop transmitting, slide up immediately and then close the
    camera") with its last picture still in it, never black; only the emptied window waits a moment before it goes.
    My box keeps my face until my camera's picture really comes through the room, as theirs keeps their face until
    their first picture; mine stays a box from the lock to Stop, through a hands-free renewal. The both-live chip
    shows only while both items show.
    `adb shell setprop log.tag.TalkBubble VERBOSE` logs each frame of a move.
- **Where it rests, folding, and the videos' own place** (2026-11-27, hen: "drag the floating bar ... along the
  edges", "auto hide in a smart way", "make the videos stay at the top in original position, not attached to the bar
  unless they are at the same place; make the video also to be dragged"; mocks in the project's
  `mockups/floating-bar/`). Geometry in `FloatDock` (`ui/live/FloatDock.kt`, with `FloatDockTest`), plain screen
  pixels, nothing mirrored in Hebrew.
  - **Leaving a live chat in the app** keeps them floating: leaving a chat (Back to the list, another page) while
    someone is pushing voice or video in it shows the floating window for them, as leaving the app does
    (`TalkPlaying.leftChat`). Opening that chat again takes it away (`TalkBubble.chatOpened`); the app coming to the
    front keeps it while someone is live in a chat that isn't the one on screen (`TalkPlaying.cameToFront`).
  - **Hands-free goes on** (2026-11-27, hen: "when I'm transmitting hands-free from the chat screen and exit to the
    home screen it should keep transmitting with the floating layout"): leaving the app, or the chat, while my push
    there is hands-free (voice, video or urgent), the floating window takes that same press over as it is, locked as
    if slid to hands-free in it (Stop, the renewal when its 55 s are up), through Talk's foreground service so the
    microphone and camera go on in the background (`TalkBubble.takeOver`, `TalkReadyService.EXTRA_ADOPT`). Nothing
    is pressed anew and the camera never closes (2026-11-27, hen: "the video should stay as a floating window ...
    without remounting the camera view"): a screen letting go of the camera closes it only 1.5 s later, and not while
    my video burst goes on (`Messaging.COOL_AFTER_MS`). Between two presses (a renewal's moment) the chat's is let go
    and the floating window presses anew 0.9 s later. When the floating
    window can't show (setting off, no permission, the screen off or locked) leaving the app changes nothing (it goes
    on as before) and leaving the chat ends it.
    My box or pill is in the floating row from the moment the chat lets go, so it stays through the gap, and the new
    press waits for the chat's last one to let go of its floor first (each talker has one floor per chat: asked
    sooner, the server answered BUSY with my own floor and the bar said "Waiting for testbot…"; 2026-11-27).
  - **Stays put** (2026-11-27, hen: "when moving out to home screen the videos or voices should stay put, not exit
    and enter"): for 1.5 s after the app or the chat goes (`TalkBubble.takingOverRow`), what comes into the top row is
    simply there in its place instead of dropping in from the screen's top edge (`LiveMotion.target`'s `inPlace`); an
    item the chat's row showed glides from where it was there to its floating place, never leaving the screen
    (`LiveHandoff.chat`, kept by the chat's `LiveStage`; 2026-11-27, hen).
  - **Back in the chat** (2026-11-27, hen: "if I have the floating layout and I enter the same user's chat screen,
    the floating layout should close"; "the video should move to the in-chat position, at the moment I see two
    camera videos"): that chat coming on screen (opened, or the app back in front on it) closes the floating window
    at once (`TalkBubble.handBack`); its items are handed to the chat's row from where they were (`LiveHandoff.floating`),
    and my hands-free press it held goes on in the chat as it is (hands-free there again, video or urgent as it was).
  - **The chat's face:** Chat's button is the other person's picture (else the chat name's first letter; a group's
    letter), 2026-11-27, hen: "replace the chat icon with the recipient avatar".
  - **Through their own edge** (2026-11-27, hen: "if the video is docked to any edge it should exit and enter from
    that edge"): videos resting at the bottom come in and go out through the bottom edge, ones dropped against a side
    partway down through that side, the rest through the top (`TalkBubble.awayEdge`).
  - **Moving the bar:** hold it (anywhere but Push) for 400 ms and it lifts; drag it, and the four places light up,
    dashed teal: the top, the bottom, and anywhere along the left or right edge (filled where it would go now). Let go
    and it glides from there into the nearest place (250 ms); it is kept for next time. On a side edge it stands
    upright, Chat above Push, and Stop is "■" over "0:42"; the lock (hands-free video) is then on the screen's inside
    of Push (slide toward the middle), the side target is Chat's place (slide up). At the top the lock is below Push.
  - **Folding:** with nobody talking for 8 s (`FloatDock.QUIET_MS`), when the keyboard comes up, or when an app goes
    full screen, the bar slides into its edge until only a slim handle (14 dp; a tab of 24 dp on the sides, which takes touches 20 dp further in and is kept from Android's back gesture, `systemGestureExclusionRects`: 2026-11-27, hen, "hard to interact with because of the Android gesture") with its
    grab line shows, its buttons fading in the same move (250 ms). Never while Push is held or hands-free, video is
    live, or a call rings. It comes out by itself the moment anyone starts talking (the tab's rim glows with their
    voice as before); a tap on the handle or tab, or a swipe in from the edge, brings it out too. A slow swipe toward
    its edge (past a third of its depth) folds it by hand. Whether an overlay is told about the keyboard and full
    screen depends on the phone: the log says "keyboard up/down, full screen/status bar shows" when it is.
  - **Sending it away:** a fast fling toward its edge sends the bar off the screen (and the videos up), nothing left
    behind; it comes back when someone talks to you again.
  - **The videos** (and voice pills) rest at the top centre, just below the status bar, wherever the bar is. Drag them
    (both together) and let go: near the top they go back there, near the bottom they rest at the bottom, else where
    they were let go; leaning to the side they're nearer, or centred; kept for next time. They join the bar only at the
    same place: under it when the bar is at the top and they are too, above it at the bottom, and beside it (level
    with it) when it is on a side edge and they are dropped on that side across from it. A flick up at the top, or a
    drag up past the screen's top, still puts them away until their next burst.
- **From the floating video to the chat** (2026-11-24, hen: "open the chat with the video stay connected"): a tap on
  the top box, or on the pill's Chat, opens the chat; their video goes on in the chat's live
  panel without a break (the same decoder moves to the chat's picture), and the top box grows a little and fades into
  it (200 ms) instead of sliding away. Leaving the app while their video plays live puts it back in the floating
  window, the same way.
- **Picture-in-picture**: an answered video call goes on in Android's small floating window when the app is left
  (dragged and resized by Android), the other's video filling it and this phone's in a corner, with Hang up.
- **Video in the reply bubble** (on by default; 2026-11-22, hen: a received video plays): a video burst's picture
  plays above the sender's name, in the picture's own shape, in step with the voice; the bar's "Video on" switch turns
  it off (and on again) for later bursts.
- **Reply window:** for 30 s after a woken burst, a headset's or Bluetooth talk button (held) talks back to its sender
  instead of Buttons talk in; while Talk is ready runs, those buttons work with the screen off.

## Quick reply window

- **2026-11-23 (hen):** only the message that opens the window sounds; the ones that come while it shows are
  quiet (until it closes). A new message glides into view and slides in. "typing…" shows as three rising dots at the
  bottom while the contact types, or their draft as they write it when they use Live typing. Typing in the window's
  line tells the contact "typing" (and, with Live typing on, the draft).

`QuickReplyWindow` (Android), `QuickReply` (shared; 2026-11-23, hen; Settings › Talk › Quick reply window, on by
default; needs "Display over other apps"). A message (or a voice message) that comes in while the phone is in use, its
screen on, shows its conversation in a window at the bottom of the screen, full width, 40% of its height, over other
apps, sliding up from the bottom edge (250 ms) and down off it when it closes; a swipe down follows the finger. While it
shows a message there is no notification, only the message sound (`MessageAlert`); if it closes unanswered (×, swipe,
Back, by itself, or switching away) the notification is posted then, silently, so the message stays unread. Not for
the conversation open in the app while it is in front; skipped (off, not allowed, screen off) the notification shows as
always. Every decision is logged (QuickReply). 2026-11-23: it crashed the app as it attached (Compose found no
lifecycle on the window's root view), so it never showed; fixed.

- **2026-11-25 (hen): live pills and videos are not in this window** ("the video and pills should appear from the
  top of the screen, not in the chat floating window"). While the window is open on a chat, whoever is live in it
  (their push, or mine from the window's own button) shows in the floating window's row at the top of the screen, just
  below the status bar, entering from the top edge, exactly as when only the floating window shows; the window draws
  no pill or box and no live bubble in its list (a burst lands in the list once it is said, if the chat keeps it).
  The window tells the row what is live (`ChatLive.liveRow`, `TalkBubble.liveRow`); opening or closing it doesn't
  move or re-create a row already showing, and a push that comes while it is open adds no bottom pill over it (the
  window has its own buttons). If the window closes while they are still live, the floating window takes over with
  its pill. With the keyboard up the window stays below the row's bottom edge.
- **2026-11-24 (hen): the chat itself.** Under its own header the window shows the conversation screen
  (`ConversationScreen` with `embeddedHeader`), the same design and ways: the whole conversation, teal bubbles, the
  pinned draft, status and read circles, live drafts morphing into their messages, video and voice bursts, the live
  video bubble, the waiting press, and the button row (Video call, hold to talk or video by the chat's Voice | Video,
  Voice call, Voice message). Hidden while writing. From the window, the calls and Voice message open the app (a
  voice message needs the chat), and so does anything that would ask for a permission. The header says "typing…" or
  "online" under the name. The items below that differ from this are earlier.
- **Arrival** (2026-11-24, hen: "too sudden, people tap it by accident"; OverlayArrival, the reply bubble too): it
  slides up gently (400 ms, decelerating) and for its first second a tap passes through to the app under it
  (FLAG_NOT_TOUCHABLE); meanwhile all but an incoming video is see-through (50%), the video solid; then all comes to
  full in 200 ms and it takes touches. Not again for a message while it shows. The keyboard waits for a tap.
- **The keyboard** (2026-11-24): a trace showed the window relaid out on every frame of the keyboard's animation
  (about 6 ms each). Now it takes the keyboard's room once as it starts and gives it back once it has closed; the chat
  inside rides with the keyboard frame by frame, a clear space above filling the rest.
- **Header**: a grab bar; the letter circle and the name (a group's name for a group); a tap opens the chat and
  closes the window; **×** closes it. A swipe down closes it too, and so does Back.
- **The last messages** (up to 12), newest at the bottom, in bubbles like the chat's (a group's with who wrote each).
- **A field** ("Reply") and, while it is empty, a round **hold-to-talk** button: hold to talk to them (or the group),
  let go to send, slide away to cancel (the button shows ×), as the chat's microphone. With text typed it is **Send**:
  the text goes end to end like any other.
- **The keyboard**: while it is open the window sits right above it, moving with it (its frame keeps clear of
  it); with too little room the window gets shorter and the message list gives way.
- **Closing by itself**: untouched for 20 s with nothing typed and nobody talking. A new message from them keeps it.
- **Someone else writes**: the window switches to them, unless you are typing or talking, then they wait and show when
  the window closes (once each, in order; one opened in the app meanwhile is skipped).

## The floating chat, calmer (2026-11-26, hen's recording and notes)

- **It doesn't take the keyboard**: it comes without taking the keys, so the app in use keeps its keyboard and what is
  being typed there goes on; the window sits above that keyboard. A touch on the window gives it the keys (its field can
  be written in, Back closes it).
- **No jump with the keyboard**: the chat is placed from the window's bottom edge at its own height, and Android's own
  sliding of a window whose place changed is off, so opening and closing the keyboard is the keyboard's one move.
  Android's own fade of a window that comes or goes is off too: the window slides in by itself.
- **Taller**: 47% of the screen's height (was 40%): two or three more messages, the app behind still over half the
  screen; with a keyboard it stays clear of the status bar.
- **Arriving**: 90% solid (was 50%) until it has landed, 450 ms (was a second), then full in 150 ms; it can't be
  touched for those 450 ms, as before. The floating bar arrives the same way.

## Dismissed until quiet (2026-11-26, hen: "after dismissing, new text messages should appear as notifications until the chat goes quiet")

Sending the floating chat away (×, a swipe down, Back) or swiping the floating bar down says "not now" for that
conversation: its next text messages don't bring the floating chat back. They are normal message notifications (grouped
by chat, with Reply). It lasts while the conversation goes on: until no message has come or gone in it for **5
minutes** (a reply from the notification counts, and starts the 5 minutes again), or until the chat is opened in the
app. Then the floating chat may come again. Each conversation by itself; a window that closed by itself, untouched,
isn't a dismissal. Pushes (voice, video, urgent) play and show as always. `QuickReplyQuiet` (shared, tested),
`QuickReplyWindow.arrive`.

