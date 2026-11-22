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
    private var title: TextView? = null
    private var line: TextView? = null
    private var videoBox: android.widget.FrameLayout? = null
    private var videoSwitch: TextView? = null
    private var videoSurface: android.view.Surface? = null
    /** The burst now has video (push-to-video). */
    private var burstVideo = false
    private var button: TextView? = null
    private var chat: String? = null
    private var talking = false
    private var talkStarted = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (!talking) return
            val s = (System.currentTimeMillis() - talkStarted) / 1000
            line?.text = view?.context?.getString(R.string.bubble_talking, "%d:%02d".format(s / 60, s % 60))
            main.postDelayed(this, 500)
        }
    }

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
        if (!talking) line?.text = context.getString(if (live) R.string.bubble_live else R.string.bubble_reply)
        burstVideo = live && video
        showVideo(context)
    }

    /**
     * A video burst shows its picture above the bar's words, unless the bubble's Video is off (2026-11-22, hen: off by
     * default, like all of push-to-video); the Video switch shows while a burst has video.
     */
    private fun showVideo(context: Context) {
        val on = messagingState(context).value.talkSettings.bubbleVideo
        videoSwitch?.visibility = if (burstVideo) View.VISIBLE else View.GONE
        videoSwitch?.text = context.getString(if (on) R.string.talk_video_on else R.string.talk_video_off)
        videoBox?.visibility = if (burstVideo && on) View.VISIBLE else View.GONE
        main.removeCallbacks(turn)
        if (burstVideo && on) main.post(turn)
    }

    /** Keeps the picture upright and its shape as the sender's camera says, while it shows. */
    private val turn = object : Runnable {
        override fun run() {
            val box = videoBox ?: return
            if (box.visibility != View.VISIBLE) return
            val video = box.findViewWithTag<View>("video") ?: return
            val shown = com.chencohen.ptt.talk.AndroidTalkVideoOut.shown.value
            val rotation = shown?.rotation ?: 0
            val aspect = if (shown != null && shown.width > 0 && shown.height > 0) shown.width.toFloat() / shown.height else 4f / 3f
            val side = box.width.takeIf { it > 0 } ?: return main.postDelayed(this, 200).let { }
            // Fitted in the square box once turned.
            val turned = rotation % 180 != 0
            val shownAspect = if (turned) 1f / aspect else aspect
            val (w, h) = if (shownAspect >= 1f) side to (side / shownAspect).toInt() else (side * shownAspect).toInt() to side
            val (vw, vh) = if (turned) h to w else w to h
            val lp = video.layoutParams
            if (lp.width != vw || lp.height != vh) {
                lp.width = vw
                lp.height = vh
                video.layoutParams = lp
            }
            video.rotation = rotation.toFloat()
            main.postDelayed(this, 300)
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
        val close = TextView(context).apply {
            text = "✕"
            setTextColor(0xFFC9D1CF.toInt())
            textSize = 20f
            gravity = Gravity.CENTER
            contentDescription = context.getString(R.string.bubble_close)
            setOnClickListener { if (talking) endTalk(context, send = false); dismissNow() }
        }
        top.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
        val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        title = TextView(context).apply { setTextColor(Color.WHITE); textSize = 19f; maxLines = 1; gravity = Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        line = TextView(context).apply { setTextColor(0xFFC9D1CF.toInt()); textSize = 15f; maxLines = 1; gravity = Gravity.CENTER }
        words.addView(title)
        words.addView(line)
        top.addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // As wide as ✕, so the words are centred over the button.
        videoSwitch = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0x33FFFFFF) }
            visibility = View.GONE
            setOnClickListener {
                val settings = messagingState(context).value.talkSettings
                setTalkSettings(context, settings.copy(bubbleVideo = !settings.bubbleVideo))
                main.postDelayed({ showVideo(context) }, 100)
            }
        }
        top.addView(videoSwitch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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
        }
        root.addView(videoBox, LinearLayout.LayoutParams(dp(240), dp(240)).apply { bottomMargin = dp(8) })
        root.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        button = TextView(context).apply {
            text = context.getString(R.string.bubble_talk)
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(PINK) }
            contentDescription = context.getString(R.string.bubble_talk_hint)
            setOnTouchListener { v, e -> touch(context, v, e) }
        }
        root.addView(button, LinearLayout.LayoutParams(dp(96), dp(96)).apply { topMargin = dp(8) })
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
        return try {
            wm.addView(root, params)
            view = root
            // Slides up from below the bottom edge.
            root.translationY = dp(400).toFloat()
            root.post {
                root.translationY = (root.height + params.y).toFloat()
                root.animate().translationY(0f).setDuration(SLIDE_MS).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
            Log.i(TAG, "shown")
            true
        } catch (e: Exception) {
            Log.w(TAG, "can't show the bubble", e)
            false
        }
    }

    private fun touch(context: Context, v: View, e: MotionEvent): Boolean {
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
        talking = true
        talkStarted = System.currentTimeMillis()
        (button?.background as? GradientDrawable)?.setColor(Color.RED)
        button?.text = context.getString(R.string.bubble_send)
        main.post(tick)
        runCatching {
            context.startForegroundService(Intent(context, TalkReadyService::class.java).setAction(TalkReadyService.ACTION_TALK_BACK).putExtra(TalkReadyService.EXTRA_CHAT, chat))
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
        button?.text = context.getString(R.string.bubble_talk)
        line?.text = context.getString(if (send) R.string.bubble_sent else R.string.bubble_reply)
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
        videoSurface?.let { com.chencohen.ptt.talk.AndroidTalkVideoOut.detach(it) }
        videoSurface = null
        videoBox = null
        videoSwitch = null
        burstVideo = false
        title = null
        line = null
        button = null
        chat = null
        main.removeCallbacks(tick)
        runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) }
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
}
