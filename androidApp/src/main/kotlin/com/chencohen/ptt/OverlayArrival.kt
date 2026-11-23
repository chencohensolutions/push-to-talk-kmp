package com.chencohen.ptt

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator

/**
 * How a window over other apps arrives (2026-11-24, hen: "too sudden, people tap it by accident"): it slides up,
 * gently (400 ms, decelerating), and for its first second it can't be touched: a tap already on its way passes
 * through to the app under it (FLAG_NOT_TOUCHABLE). Meanwhile everything but an incoming video is see-through (50%:
 * the panel, header, messages, draft and buttons); the video stays solid, never drawn with alpha. Then all of it
 * comes to full in 200 ms and the window takes touches. Only on arrival: a window already showing isn't locked again.
 */
internal object OverlayArrival {
    const val ENTER_MS = 400L
    const val LOCK_MS = 1_000L
    const val LOCKED_ALPHA = 0.5f
    private val main = Handler(Looper.getMainLooper())

    /**
     * [content] slides up from [from] pixels below; [window] (attached with [params]) is untouchable for [LOCK_MS];
     * [see] is told how solid everything but the video is (0.5 while locked, then up to 1).
     */
    fun arrive(wm: WindowManager, window: View, params: WindowManager.LayoutParams, content: View, from: Float, see: (Float) -> Unit) {
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(window, params) }
        see(LOCKED_ALPHA)
        content.translationY = from
        content.animate().translationY(0f).setDuration(ENTER_MS).setInterpolator(DecelerateInterpolator(1.5f)).start()
        main.postDelayed({
            if (!window.isAttachedToWindow) return@postDelayed
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            runCatching { wm.updateViewLayout(window, params) }
            ValueAnimator.ofFloat(LOCKED_ALPHA, 1f).apply {
                duration = 200
                addUpdateListener { see(it.animatedValue as Float) }
            }.start()
        }, LOCK_MS)
    }
}
