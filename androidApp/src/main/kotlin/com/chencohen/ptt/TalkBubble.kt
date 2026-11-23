package com.chencohen.ptt

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.chencohen.ptt.messaging.Group

/**
 * The reply bubble (docs/screens/notifications.md, "Reply bubble"; 2026-11-22, hen): with Settings › Talk › Reply
 * bubble over other apps on and Android's "Display over other apps" allowed, a burst that plays while the phone is in
 * use (and the app isn't on screen) shows a bar at the bottom of the screen, above the navigation bar: who talks, and a
 * big Talk button in the middle. Hold it to talk back and let go to send; a tap starts talking and the next tap sends.
 * It slides up from the bottom edge and stays until a touch outside it (not while talking), ✕, or opening the app,
 * then slides down off the screen (hen, 19:05). Without the permission the heads-up notification is all there is.
 */
object TalkBubble {
    private const val TAG = "TalkBubble"
    private const val SLIDE_MS = 250L
    private const val HOLD_MS = 350L

    private val main = Handler(Looper.getMainLooper())
    private var view: LinearLayout? = null
    /**
     * The window itself: holds [view] against its bottom edge, so whatever shows above the button (the time, this
     * phone's camera, their video) grows the window upwards while the button stays where the finger is (2026-11-23,
     * hen: "it should extend upwards while the buttons stay fixed").
     */
    private var frame: android.widget.FrameLayout? = null
    private var title: TextView? = null
    private var line: TextView? = null
    private var videoBox: android.widget.FrameLayout? = null
    private var videoSwitch: android.widget.ImageView? = null
    private var videoSurface: android.view.Surface? = null
    /** The burst now has video (push-to-video). */
    private var burstVideo = false
    private var button: android.widget.ImageView? = null
    /** Voice | Video beside the button (2026-11-23, hen), remembered per contact (TalkSettings.videoFor). */
    private var voiceTab: android.widget.ImageView? = null
    private var videoTab: android.widget.ImageView? = null
    /** This phone's camera while it sends video from here. */
    private var ownView: android.view.TextureView? = null
    private var ownSurface: android.view.Surface? = null
    /** A word after the time while talking ("camera busy"). */
    private var note: String? = null
    private var sendingVideo = false
    private var hereDot: TextView? = null
    private var videoCall: android.widget.ImageView? = null
    private var actions: LinearLayout? = null
    /** A call ringing here while the bubble shows: the bubble is its Answer (2026-11-22, hen). */
    private var ringing: com.chencohen.ptt.calls.CallUi? = null
    private var chat: String? = null
    private var talking = false
    private var talkStarted = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (!talking) return
            val s = (System.currentTimeMillis() - talkStarted) / 1000
            line?.text = "%d:%02d".format(s / 60, s % 60) + (note?.let { " · $it" } ?: "")
            main.postDelayed(this, 500)
        }
    }

    /** The bubble is on screen now. */
    val showing: Boolean get() = view != null

    /** The bubble may show now: the setting, Android's permission, the phone in use and unlocked. */
    fun allowed(context: Context): Boolean {
        if (!messagingState(context).value.talkSettings.replyBubble) return false
        if (!Settings.canDrawOverlays(context)) return false
        if (!context.getSystemService(android.os.PowerManager::class.java).isInteractive) return false
        return !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
    }

    /** [from] talks in [chat] now ([live]) or just did (the reply window). */
    fun show(context: Context, chat: String, title: String, live: Boolean, video: Boolean = false) = main.post {
        if (view == null) {
            if (!allowed(context)) return@post
            if (!add(context.applicationContext)) return@post
        }
        this.chat = chat
        this.title?.text = title
        if (this.chat != chat) setHere(context, chat)
        burstVideo = live && video
        showVideo(context)
        showMode(context)
    }

    /**
     * After something above the button shows or goes: the window takes the new height with its bottom edge where it was
     * (the content stays against it), and what came in above grows in from the bottom up.
     */
    private fun fit(animate: Boolean = false, done: () -> Unit = {}) {
        val root = view ?: return done()
        val holder = frame ?: return done()
        val lp = holder.layoutParams as? WindowManager.LayoutParams ?: return done()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val target = root.measuredHeight
        // The content at its full height against the window's bottom edge, whatever the window's height is meanwhile:
        // while it grows the content is uncovered from the bottom up, and nothing in it moves.
        root.layoutParams?.let { p ->
            if (p.height != target) {
                p.height = target
                root.layoutParams = p
            }
        }
        resizing?.cancel()
        resizing = null
        val wm = holder.context.getSystemService(WindowManager::class.java)
        if (!animate || lp.height == target) {
            if (lp.height != target) {
                lp.height = target
                runCatching { wm.updateViewLayout(holder, lp) }
            }
            return done()
        }
        // About 200 ms from the old height to the new, the bottom edge (and the button) where it is.
        resizing = android.animation.ValueAnimator.ofInt(lp.height, target).apply {
            duration = 200
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                if (frame !== holder) return@addUpdateListener
                lp.height = it.animatedValue as Int
                runCatching { wm.updateViewLayout(holder, lp) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = done()
            })
            start()
        }
    }

    private var resizing: android.animation.ValueAnimator? = null

    /** Whether a press here sends the camera too: this contact's Voice | Video. */
    private fun videoMode(context: Context): Boolean = chat?.let { messagingState(context).value.talkSettings.pushVideoFor(it) } ?: false

    /** Voice | Video shows the contact's choice; the button a camera in Video. */
    private fun showMode(context: Context) {
        val video = videoMode(context)
        fun mark(v: android.widget.ImageView?, on: Boolean) {
            (v?.background as? GradientDrawable)?.setColor(if (on) Color.WHITE else Color.TRANSPARENT)
            v?.setColorFilter(if (on) 0xFF191D1C.toInt() else Color.WHITE)
            v?.isSelected = on
        }
        mark(voiceTab, !video)
        mark(videoTab, video)
        if (!talking && ringing == null) {
            button?.setImageResource(if (video) R.drawable.ic_bubble_video else R.drawable.ic_bubble_mic)
            button?.contentDescription = context.getString(if (video) R.string.bubble_video_hint else R.string.bubble_talk_hint)
        }
    }

    /** Voice | Video chosen here: kept for this contact; Video without the camera allowed asks for it in the app. */
    private fun chooseMode(context: Context, video: Boolean) {
        val chat = chat ?: return
        if (talking || video == videoMode(context)) return
        val settings = messagingState(context).value.talkSettings
        setTalkSettings(context, settings.withVideoFor(chat, video))
        Log.i(TAG, "$chat: ${if (video) "video" else "voice"} from the bubble")
        if (video && context.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java).setAction(TalkNotifications.ACTION_TALK).putExtra(Notifications.EXTRA_USER, chat)
                        .putExtra(MainActivity.EXTRA_ASK_CAMERA, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
            dismissNow()
            return
        }
        main.postDelayed({
            showMode(context)
            showVideo(context)
        }, 100)
    }

    /** Sending video from here: the box shows this phone's camera (their picture gives way). */
    private fun showOwn(context: Context, @Suppress("UNUSED_PARAMETER") on: Boolean) = showVideo(context)

    /** The last picture's shape and turn, kept when the burst ends (no sideways frame as it goes). */
    private var lastShown: com.chencohen.ptt.talk.AndroidTalkVideoOut.Shown? = null

    /** A video burst came in this window's time: the room for the picture stays, so the window doesn't change size. */
    private var seenVideo = false

    /** This phone's camera shows here (the window's Voice | Video is Video): kept open by Talk's service. */
    private var previewOn = false

    /**
     * What the box above the button shows (2026-11-23, hen): their video burst while it plays (unless the bubble's Video
     * is off); this phone's camera while it sends, or, in Video, between bursts; a quick crossfade (180 ms) between the
     * two. The box keeps its room once the window is in Video or a video came (hidden, not gone), so the window keeps its
     * size and nothing under the finger moves.
     */
    private fun showVideo(context: Context) {
        val on = messagingState(context).value.talkSettings.bubbleVideo
        videoSwitch?.visibility = if (burstVideo) View.VISIBLE else View.GONE
        videoSwitch?.setImageResource(if (on) R.drawable.ic_bubble_video else R.drawable.ic_bubble_video_off)
        videoSwitch?.contentDescription = context.getString(if (on) R.string.talk_video_on else R.string.talk_video_off)
        if (burstVideo) seenVideo = true
        val camera = context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val callOn = messagingState(context).value.call != null
        val theirs = burstVideo && on && !sendingVideo
        val mine = sendingVideo || (!theirs && videoMode(context) && camera && !callOn && ringing == null)
        // The camera kept open for the preview (the service holds it while the app is in the background).
        if (mine && !sendingVideo && !previewOn) preview(context, true) else if (!mine && previewOn) preview(context, false)
        val box = videoBox ?: return
        // Voice: compact, the video area opens upwards (200 ms) while a video shows and closes after; Video: it stays.
        val reserve = videoMode(context)
        when {
            theirs || mine -> if (box.visibility != View.VISIBLE) {
                val grow = box.visibility == View.GONE
                box.visibility = View.VISIBLE
                fit(animate = grow)
            }
            reserve -> if (box.visibility != View.INVISIBLE) {
                val grow = box.visibility == View.GONE
                box.visibility = View.INVISIBLE
                fit(animate = grow)
            }
            box.visibility != View.GONE -> {
                // Hidden first, then the window closes over it (200 ms), then it goes.
                val root = view
                val holder = frame
                val lp = holder?.layoutParams as? WindowManager.LayoutParams
                if (root != null && lp != null) {
                    val from = lp.height
                    box.visibility = View.INVISIBLE
                    resizing?.cancel()
                    root.measure(View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    // Measured with the box: shrink to the height without it.
                    val boxH = box.measuredHeight + ((box.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { it.topMargin + it.bottomMargin } ?: 0)
                    val to = from - boxH
                    val wm = holder.context.getSystemService(WindowManager::class.java)
                    resizing = android.animation.ValueAnimator.ofInt(from, to).apply {
                        duration = 200
                        interpolator = android.view.animation.DecelerateInterpolator()
                        addUpdateListener {
                            if (frame !== holder) return@addUpdateListener
                            lp.height = it.animatedValue as Int
                            runCatching { wm.updateViewLayout(holder, lp) }
                        }
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                if (videoBox === box && box.visibility == View.INVISIBLE) {
                                    box.visibility = View.GONE
                                    fit()
                                }
                            }
                        })
                        start()
                    }
                } else {
                    fit()
                }
            }
        }
        val burstView = box.findViewWithTag<View>("video")
        fun fade(v: View?, to: Float) {
            v ?: return
            if (v.visibility != View.VISIBLE) {
                v.visibility = View.VISIBLE
                v.alpha = 0f
            }
            if (v.alpha != to) v.animate().alpha(to).setDuration(180).start()
        }
        fade(burstView, if (theirs) 1f else 0f)
        fade(ownView, if (mine) 1f else 0f)
        if (mine) ownSurface?.let { com.chencohen.ptt.talkVideoCapture?.setPreview(it) } else ownSurface?.let { s -> com.chencohen.ptt.talkVideoCapture?.clearPreview(s) {} }
        main.removeCallbacks(turn)
        if (theirs) main.post(turn)
    }

    /** Asks Talk's service to keep the camera open for the window's preview ([on]) or let it go. */
    private fun preview(context: Context, on: Boolean) {
        val chat = chat ?: return
        previewOn = on
        runCatching {
            context.startForegroundService(
                Intent(context, TalkReadyService::class.java).setAction(TalkReadyService.ACTION_PREVIEW)
                    .putExtra(TalkReadyService.EXTRA_CHAT, chat).putExtra(TalkReadyService.EXTRA_VIDEO, on),
            )
        }.onFailure { Log.w(TAG, "no camera preview", it) }
    }

    /** Keeps the picture upright and its shape as the sender's camera says, while it shows. */
    private val turn = object : Runnable {
        override fun run() {
            val box = videoBox ?: return
            if (box.visibility != View.VISIBLE) return
            val video = box.findViewWithTag<View>("video") ?: return
            val shown = com.chencohen.ptt.talk.AndroidTalkVideoOut.shown.value ?: lastShown
            lastShown = shown
            val rotation = shown?.rotation ?: 0
            val aspect = if (shown != null && shown.width > 0 && shown.height > 0) shown.width.toFloat() / shown.height else 4f / 3f
            // The box takes the picture's upright shape (tall for a phone held upright, wide on its side), at most
            // 240 dp on its long side.
            val side = (240 * box.resources.displayMetrics.density).toInt()
            val turned = rotation % 180 != 0
            val shownAspect = if (turned) 1f / aspect else aspect
            val (w, h) = if (shownAspect >= 1f) side to (side / shownAspect).toInt() else (side * shownAspect).toInt() to side
            // The box itself keeps its size (240 dp square): the window never changes size for the picture's shape.
            val (vw, vh) = if (turned) h to w else w to h
            val lp = video.layoutParams
            if (lp.width != vw || lp.height != vh) {
                lp.width = vw
                lp.height = vh
                video.layoutParams = lp
            }
            video.rotation = rotation.toFloat()
            follow(box.context, video, w.toFloat(), h.toFloat())
            main.postDelayed(this, 300)
        }
    }

    /** Follow face's place now: zoom and offset (from the picture's centre); glides there in half a second. */
    private var followAt = Triple(1f, 0f, 0f)
    private var followFaceSeen = 0L

    /**
     * Follow face in the bubble (2026-11-23, hen; the same choice as in calls and on the Talk screen): the sender's face
     * positions keep the face near the upper third, head and shoulders about 45% of the height, small moves left alone.
     */
    private fun follow(context: Context, video: View, w: Float, h: Float) {
        val on = context.getSharedPreferences("call_view", Context.MODE_PRIVATE).getBoolean(com.chencohen.ptt.ui.talk.FOLLOW_TALK, false)
        // Where the face will be when this glide ends (the clip's own clock): no lag behind the picture.
        val face = if (on) com.chencohen.ptt.talk.AndroidTalkVideoOut.faceAt(aheadMs = 300) else null
        // The bubble's box is the picture's own shape: filling it is showing it whole, so no zoom, only nothing to slide.
        val most = 1f
        val now = android.os.SystemClock.elapsedRealtime()
        if (face != null) followFaceSeen = now
        val want = if (face != null) {
            val p = com.chencohen.ptt.calls.CallGrid.place(w, h, w, h, face.x, face.y, face.h, maxZoom = most, targetY = 0.4f)
            // From the top left corner (CallGrid) to the view's own pivot, its centre.
            Triple(p.scale, p.x - (1 - p.scale) * w / 2, p.y - (1 - p.scale) * h / 2)
        } else if (on && now - followFaceSeen < 1_500) {
            followAt
        } else {
            Triple(1f, 0f, 0f)
        }
        val (s0, x0, y0) = followAt
        val first = followAt == Triple(1f, 0f, 0f) && face != null
        if (want != followAt) {
            followAt = want
            video.animate().scaleX(want.first).scaleY(want.first).translationX(want.second).translationY(want.third)
                .setDuration(if (first) 0L else 300L).setInterpolator(android.view.animation.LinearInterpolator()).start()
        }
    }

    /** The app came on screen, or the bubble isn't wanted any more. */
    fun dismiss() = main.post { if (!talking) dismissNow() }

    /** Slides down off the screen, then goes. */
    private fun dismissNow() {
        val v = view ?: return
        val params = v.layoutParams as? WindowManager.LayoutParams
        v.animate().translationY((v.height + (params?.y ?: 0)).toFloat()).setDuration(SLIDE_MS)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { if (view === v) remove() }
            .start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun add(context: Context): Boolean {
        val wm = context.getSystemService(WindowManager::class.java)
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
        val metrics = wm.currentWindowMetrics
        val bars = metrics.windowInsets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(16))
            background = GradientDrawable().apply { cornerRadius = dp(32).toFloat(); setColor(0xF2191D1C.toInt()) }
            elevation = dp(8).toFloat()
        }
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        // No ✕ (2026-11-22, hen): a touch outside closes it. Icons only: the name, no hint line.
        top.addView(View(context), LinearLayout.LayoutParams(dp(40), dp(40)))
        val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        title = TextView(context).apply { setTextColor(Color.WHITE); textSize = 19f; maxLines = 1; gravity = Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        // A green dot by the name: their Talk screen or bubble for this chat is open now ("here").
        hereDot = TextView(context).apply { text = "●"; setTextColor(HERE); textSize = 12f; visibility = View.GONE; setPadding(dp(6), 0, 0, 0) }
        val nameRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        nameRow.addView(title)
        nameRow.addView(hereDot)
        line = TextView(context).apply { setTextColor(0xFFC9D1CF.toInt()); textSize = 15f; maxLines = 1; gravity = Gravity.CENTER; visibility = View.INVISIBLE }
        words.addView(nameRow)
        words.addView(line)
        top.addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // As wide as ✕, so the words are centred over the button.
        videoSwitch = android.widget.ImageView(context).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
            visibility = View.GONE
            setOnClickListener {
                val settings = messagingState(context).value.talkSettings
                setTalkSettings(context, settings.copy(bubbleVideo = !settings.bubbleVideo))
                main.postDelayed({ showVideo(context) }, 100)
            }
        }
        top.addView(View(context), LinearLayout.LayoutParams(dp(40), dp(40)))
        // The burst's picture, drawn in step with the voice (AndroidTalkVideoOut).
        videoBox = android.widget.FrameLayout(context).apply {
            visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.BLACK) }
            clipToOutline = true
            addView(
                android.view.TextureView(context).apply {
                    surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                            videoSurface = android.view.Surface(texture).also { com.chencohen.ptt.talk.AndroidTalkVideoOut.attach(it) }
                        }

                        override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {}
                        override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture): Boolean {
                            videoSurface?.let { com.chencohen.ptt.talk.AndroidTalkVideoOut.detach(it); it.release() }
                            videoSurface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) {}
                    }
                    // Turned upright as the sender's camera says.
                    tag = "video"
                },
                android.widget.FrameLayout.LayoutParams(dp(240), dp(180), Gravity.CENTER),
            )
            ownView = android.view.TextureView(context).apply {
                visibility = View.GONE
                surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                        texture.setDefaultBufferSize(com.chencohen.ptt.talk.AndroidTalkVideoCapture.WIDTH, com.chencohen.ptt.talk.AndroidTalkVideoCapture.HEIGHT)
                        ownSurface = android.view.Surface(texture).also { if (sendingVideo) com.chencohen.ptt.talkVideoCapture?.setPreview(it) }
                    }

                    override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture): Boolean {
                        val gone = ownSurface ?: return true
                        ownSurface = null
                        val capture = com.chencohen.ptt.talkVideoCapture ?: run { gone.release(); return true }
                        capture.clearPreview(gone) {
                            gone.release()
                            texture.release()
                        }
                        return false
                    }

                    override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) {}
                }
            }
            // Upright, 3:4, as the camera draws it.
            addView(ownView, android.widget.FrameLayout.LayoutParams(dp(180), dp(240), Gravity.CENTER))
        }
        root.addView(videoBox, LinearLayout.LayoutParams(dp(240), dp(240)).apply { bottomMargin = dp(8) })
        root.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        button = android.widget.ImageView(context).apply {
            setImageResource(R.drawable.ic_bubble_mic)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(26), dp(26), dp(26), dp(26))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(PINK) }
            contentDescription = context.getString(R.string.bubble_talk_hint)
            setOnTouchListener { v, e -> touch(context, v, e) }
        }
        // Voice | Video beside the button, the other side as wide, so the button stays centred.
        val modes = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0x33FFFFFF) }
        }
        fun tab(icon: Int, label: Int, video: Boolean) = android.widget.ImageView(context).apply {
            setImageResource(icon)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) }
            contentDescription = context.getString(label)
            setOnClickListener { chooseMode(context, video) }
        }
        voiceTab = tab(R.drawable.ic_bubble_mic, R.string.talk_mode_voice, false)
        videoTab = tab(R.drawable.ic_bubble_video, R.string.talk_mode_video, true)
        modes.addView(voiceTab, LinearLayout.LayoutParams(dp(38), dp(38)))
        modes.addView(videoTab, LinearLayout.LayoutParams(dp(38), dp(38)).apply { topMargin = dp(2) })
        val buttonRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        buttonRow.addView(modes, LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT))
        buttonRow.addView(button, LinearLayout.LayoutParams(dp(96), dp(96)).apply { marginStart = dp(20); marginEnd = dp(20) })
        buttonRow.addView(View(context), LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(buttonRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        // Under the button, icons only: Video (the burst's pictures), Chat, Voice call, Video call.
        fun small(icon: Int, label: Int, onClick: () -> Unit) = android.widget.ImageView(context).apply {
            setImageResource(icon)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
            contentDescription = context.getString(label)
            setOnClickListener { onClick() }
        }
        actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val gap = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8); marginEnd = dp(8) }
        videoSwitch!!.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
        actions!!.addView(videoSwitch, LinearLayout.LayoutParams(gap))
        actions!!.addView(small(R.drawable.ic_bubble_chat, R.string.talk_action_chat) { chat?.let { openChat(context, it) }; dismissNow() }, LinearLayout.LayoutParams(gap))
        actions!!.addView(small(R.drawable.ic_bubble_call, R.string.call_voice) { chat?.let { call(context, it, false) }; dismissNow() }, LinearLayout.LayoutParams(gap))
        videoCall = small(R.drawable.ic_bubble_video, R.string.call_video) { chat?.let { call(context, it, true) }; dismissNow() }
        actions!!.addView(videoCall, LinearLayout.LayoutParams(gap))
        root.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        // A touch outside it closes it (not while talking).
        root.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE && !talking) dismissNow()
            false
        }
        val margin = dp(12)
        val params = WindowManager.LayoutParams(
            metrics.bounds.width() - bars.left - bars.right - 2 * margin,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // Above the navigation bar, in reach of a thumb.
            y = bars.bottom + margin
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
            title = "Talk reply"
        }
        val holder = android.widget.FrameLayout(context).apply {
            clipChildren = false
            addView(root, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            // A touch outside the window closes it (not while talking).
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE && !talking) dismissNow()
                false
            }
        }
        // The window as tall as what shows, set before each change shows (fit), its bottom edge fixed.
        root.measure(
            View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        params.height = root.measuredHeight
        return try {
            wm.addView(holder, params)
            frame = holder
            view = root
            // Slides up from below the bottom edge.
            root.translationY = dp(400).toFloat()
            root.post {
                root.translationY = (root.height + params.y).toFloat()
                root.animate().translationY(0f).setDuration(SLIDE_MS).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
            Log.i(TAG, "shown")
            main.post(pulse)
            true
        } catch (e: Exception) {
            Log.w(TAG, "can't show the bubble", e)
            false
        }
    }

    private fun touch(context: Context, v: View, e: MotionEvent): Boolean {
        // Ringing: the button answers.
        ringing?.let {
            if (e.actionMasked == MotionEvent.ACTION_UP) answer(context)
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (talking) {
                    // A tap started it; this one sends.
                    endTalk(context, send = true)
                } else {
                    startTalk(context)
                }
                v.isPressed = true
            }
            MotionEvent.ACTION_UP -> {
                v.isPressed = false
                // Held: letting go sends. A short tap keeps talking until the next one.
                if (talking && System.currentTimeMillis() - talkStarted >= HOLD_MS) endTalk(context, send = true) else if (talking) v.performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                if (talking && System.currentTimeMillis() - talkStarted >= HOLD_MS) endTalk(context, send = true)
            }
        }
        return true
    }

    private fun startTalk(context: Context) {
        val chat = chat ?: return
        // Video: the camera too, unless a call has it (then voice only, said after the time).
        val wantVideo = videoMode(context) && context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val callOn = messagingState(context).value.call != null
        sendingVideo = wantVideo && !callOn
        note = if (wantVideo && callOn) context.getString(R.string.bubble_camera_busy) else null
        talking = true
        talkStarted = System.currentTimeMillis()
        (button?.background as? GradientDrawable)?.setColor(TEAL)
        line?.visibility = View.VISIBLE
        line?.text = "0:00"
        button?.setImageResource(R.drawable.ic_bubble_send)
        button?.contentDescription = context.getString(R.string.bubble_send)
        main.post(tick)
        if (sendingVideo) showOwn(context, true)
        runCatching {
            context.startForegroundService(
                Intent(context, TalkReadyService::class.java).setAction(TalkReadyService.ACTION_TALK_BACK).putExtra(TalkReadyService.EXTRA_CHAT, chat)
                    .putExtra(TalkReadyService.EXTRA_VIDEO, sendingVideo),
            )
        }.onFailure {
            Log.w(TAG, "can't talk back from the bubble: the Talk screen opens", it)
            talking = false
            openTalk(context, chat)
            dismissNow()
        }
    }

    private fun endTalk(context: Context, send: Boolean) {
        if (!talking) return
        talking = false
        main.removeCallbacks(tick)
        runCatching {
            context.startService(Intent(context, TalkReadyService::class.java).setAction(if (send) TalkReadyService.ACTION_SEND else TalkReadyService.ACTION_CANCEL_TALK))
        }
        (button?.background as? GradientDrawable)?.setColor(PINK)
        line?.visibility = View.INVISIBLE
        note = null
        if (sendingVideo) {
            sendingVideo = false
            showOwn(context, false)
        }
        showMode(context)
    }

    /**
     * Shows that someone talks: a ring around the bar that pulses with the voice (teal; green while a call rings), the
     * button growing with this phone's voice while it talks, and the green dot and a glowing Video call while the
     * other user is "here".
     */
    private val pulse = object : Runnable {
        override fun run() {
            val root = view ?: return
            val context = root.context
            val state = messagingState(context).value
            val chat = chat
            val theirs = state.talk.playing?.takeIf { it.chat == chat }
            val level = when {
                talking -> com.chencohen.ptt.talk.TalkAudio.micLevel
                theirs != null -> com.chencohen.ptt.talk.TalkAudio.playLevel
                else -> 0f
            }.coerceIn(0f, 1f)
            val density = context.resources.displayMetrics.density
            val active = talking || theirs != null || ringing != null
            (root.background as? GradientDrawable)?.setStroke(
                if (active) (density * (2 + 6 * level)).toInt().coerceAtLeast((2 * density).toInt()) else 0,
                if (ringing != null) HERE else TEAL,
            )
            val scale = 1f + 0.15f * (if (talking) level else 0f)
            button?.scaleX = scale
            button?.scaleY = scale
            val here = chat != null && (state.here[chat] ?: 0L) > System.currentTimeMillis()
            hereDot?.visibility = if (here) View.VISIBLE else View.GONE
            (videoCall?.background as? GradientDrawable)?.setColor(if (here) TEAL else 0x33FFFFFF)
            main.postDelayed(this, 60)
        }
    }

    /**
     * A call rings while the bubble shows: the bubble becomes it (2026-11-22, hen): the caller's name, a big green
     * Answer, and Decline under it. Android's own ringing (its notification) goes on as well.
     */
    fun incoming(context: Context, call: com.chencohen.ptt.calls.CallUi, name: String) = main.post {
        if (view == null) return@post
        ringing = call
        title?.text = name
        (button?.background as? GradientDrawable)?.setColor(HERE)
        button?.setImageResource(if (call.video) R.drawable.ic_bubble_video else R.drawable.ic_bubble_call)
        button?.contentDescription = context.getString(R.string.call_answer)
        actions?.let { row ->
            for (i in 0 until row.childCount) row.getChildAt(i).visibility = View.GONE
            row.addView(
                android.widget.ImageView(context).apply {
                    tag = "decline"
                    setImageResource(R.drawable.ic_bubble_close)
                    val pad = (10 * context.resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, pad)
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.RED) }
                    contentDescription = context.getString(R.string.call_decline)
                    setOnClickListener {
                        context.sendBroadcast(Intent(context, CallActionReceiver::class.java).setAction(CallNotifications.ACTION_DECLINE))
                        callGone(context)
                    }
                },
                LinearLayout.LayoutParams((44 * context.resources.displayMetrics.density).toInt(), (44 * context.resources.displayMetrics.density).toInt()),
            )
        }
    }

    /** The call stopped ringing (answered, declined, missed): the bubble is the talk bar again. */
    fun callGone(context: Context) = main.post {
        if (ringing == null) return@post
        ringing = null
        (button?.background as? GradientDrawable)?.setColor(PINK)
        button?.setImageResource(R.drawable.ic_bubble_mic)
        button?.contentDescription = context.getString(R.string.bubble_talk_hint)
        actions?.let { row ->
            row.findViewWithTag<View>("decline")?.let { row.removeView(it) }
            for (i in 0 until row.childCount) row.getChildAt(i).visibility = View.VISIBLE
        }
        showVideo(context)
        showMode(context)
    }

    private fun answer(context: Context) {
        ringing ?: return
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).setAction(CallNotifications.ACTION_ANSWER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        callGone(context)
        dismissNow()
    }

    private fun openChat(context: Context, chat: String) = runCatching {
        context.startActivity(
            Intent(context, MainActivity::class.java).setAction(Notifications.ACTION_OPEN).putExtra(Notifications.EXTRA_USER, chat)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun call(context: Context, chat: String, video: Boolean) = runCatching {
        context.startActivity(
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_CALL).putExtra(Notifications.EXTRA_USER, chat)
                .putExtra(MainActivity.EXTRA_VIDEO, video)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun openTalk(context: Context, chat: String) {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).setAction(TalkNotifications.ACTION_TALK).putExtra(Notifications.EXTRA_USER, chat)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }

    private fun remove() {
        val v = view ?: return
        view = null
        main.removeCallbacks(pulse)
        setHere(v.context, null)
        ringing = null
        hereDot = null
        videoCall = null
        actions = null
        videoSurface?.let { com.chencohen.ptt.talk.AndroidTalkVideoOut.detach(it) }
        videoSurface = null
        videoBox = null
        videoSwitch = null
        burstVideo = false
        voiceTab = null
        videoTab = null
        followAt = Triple(1f, 0f, 0f)
        if (previewOn) preview(v.context, false)
        previewOn = false
        seenVideo = false
        ownView = null
        sendingVideo = false
        note = null
        title = null
        line = null
        button = null
        chat = null
        main.removeCallbacks(tick)
        val f = frame
        frame = null
        runCatching { v.context.getSystemService(WindowManager::class.java).removeView(f ?: v) }
        Log.i(TAG, "gone")
    }

    /** The title for a burst from [from] in [chat]: their name, and the group's. */
    suspend fun titleOf(context: Context, chat: String, from: String): String {
        val who = runCatching { chatName(context, from) }.getOrDefault(from)
        return if (Group.idOf(chat) != null) {
            context.getString(R.string.playing_group, who, runCatching { chatName(context, chat) }.getOrDefault(chat))
        } else {
            who
        }
    }

    private const val PINK = 0xFFE9504F.toInt()
    private const val TEAL = 0xFF2BB3A3.toInt()
    private const val HERE = 0xFF34C759.toInt()
}
