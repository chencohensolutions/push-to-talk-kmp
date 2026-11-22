package com.chencohen.ptt.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.chencohen.ptt.R
import com.chencohen.ptt.TalkApp
import com.chencohen.ptt.carTalk
import com.chencohen.ptt.messagingState
import com.chencohen.ptt.myUser
import com.chencohen.ptt.talk.SileroSpeechDetector
import com.chencohen.ptt.talk.TalkPhase
import com.chencohen.ptt.talk.TalkWidgetView
import com.chencohen.ptt.talk.VoiceEnd
import com.chencohen.ptt.talkWidgetChoices
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Talk on Android Auto (docs/screens/android-auto.md; 2026-11-22, hen): the Car App Library's messaging category.
 * Home is a grid of up to six conversations (Quick talk first); a tap talks there from the car's microphone and the
 * burst ends by itself when the speech does (Silero VAD), or with Send now / Cancel.
 */
class TalkCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
        }

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = CarHomeScreen(carContext)
    }
}

private fun pinkIcon(carContext: CarContext): CarIcon =
    CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_widget_mic))
        .setTint(CarColor.createCustom(0xFFE9504F.toInt(), 0xFFE9504F.toInt()))
        .build()

/** Up to six conversations to talk to, with who talks in each. */
class CarHomeScreen(carContext: CarContext) : Screen(carContext) {
    private var chats: List<Pair<String, String>> = emptyList()

    init {
        val app = carContext.applicationContext as TalkApp
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                lifecycleScope.launch {
                    chats = runCatching { talkWidgetChoices(app) }.getOrDefault(emptyList()).take(6)
                    invalidate()
                    // Who talks where: the tiles follow the floor and what plays.
                    messagingState(app).collect { invalidate() }
                }
            }
        })
    }

    override fun onGetTemplate(): Template {
        val app = carContext.applicationContext as TalkApp
        val talk = messagingState(app).value.talk
        val me = myUser(app)
        val list = ItemList.Builder().setNoItemsMessage(carContext.getString(R.string.car_no_chats))
        chats.forEach { (chat, name) ->
            val line = when (val v = TalkWidgetView.of(chat, talk, me, System.currentTimeMillis())) {
                is TalkWidgetView.Incoming -> carContext.getString(R.string.car_talking, v.user)
                is TalkWidgetView.Busy -> carContext.getString(R.string.car_wait)
                else -> " "
            }
            list.addItem(
                GridItem.Builder()
                    .setTitle(name)
                    .setText(line)
                    .setImage(pinkIcon(carContext))
                    .setOnClickListener { screenManager.push(CarTalkingScreen(carContext, chat, name)) }
                    .build(),
            )
        }
        @Suppress("DEPRECATION")
        return GridTemplate.Builder()
            .setTitle(carContext.getString(R.string.quick_talk_title))
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list.build())
            .build()
    }
}

/**
 * Talking to one conversation from the car: starts at once with a beep, "Talking to Family · 0:03", Send now and
 * Cancel; ends by itself after the speech (VoiceEnd), "Nothing heard" when nobody spoke in 4 s.
 */
class CarTalkingScreen(carContext: CarContext, private val chat: String, private val name: String) : Screen(carContext) {
    private val app = carContext.applicationContext as TalkApp
    private var startedAt = System.currentTimeMillis()
    private var ticker: Job? = null
    private var done = false
    private val tones = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()
    private val detector = SileroSpeechDetector.orFallback(carContext)

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = begin()
            override fun onStop(owner: LifecycleOwner) = finish(send = false)
            override fun onDestroy(owner: LifecycleOwner) {
                tones?.release()
                detector.close()
            }
        })
    }

    private fun begin() {
        tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        startedAt = System.currentTimeMillis()
        val capture = CarTalkCapture(carContext, detector) { result ->
            app.scope.launch {
                when (result) {
                    VoiceEnd.Result.NOTHING_HEARD -> {
                        CarToast.makeText(carContext, R.string.car_nothing_heard, CarToast.LENGTH_SHORT).show()
                        finish(send = false)
                    }
                    else -> finish(send = true)
                }
            }
        }
        app.scope.launch { carTalk(app, chat, capture, down = true) }
        ticker = lifecycleScope.launch {
            while (!done) {
                invalidate()
                // The floor was taken: nothing to send.
                if (messagingState(app).value.talk.own?.let { it.chat == chat && it.phase == TalkPhase.BUSY } == true) {
                    CarToast.makeText(carContext, carContext.getString(R.string.car_wait), CarToast.LENGTH_SHORT).show()
                    finish(send = false)
                }
                delay(1_000)
            }
        }
    }

    private fun finish(send: Boolean) {
        if (done) return
        done = true
        ticker?.cancel()
        if (send) tones?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
        app.scope.launch { carTalk(app, null, null, down = false, send = send) }
        runCatching { screenManager.pop() }
    }

    override fun onGetTemplate(): Template {
        val s = (System.currentTimeMillis() - startedAt) / 1000
        return MessageTemplate.Builder(carContext.getString(R.string.car_talking_to, name, "%d:%02d".format(s / 60, s % 60)))
            .setTitle(name)
            .setIcon(pinkIcon(carContext))
            .setHeaderAction(Action.BACK)
            .addAction(Action.Builder().setTitle(carContext.getString(R.string.talking_send)).setOnClickListener { finish(send = true) }.build())
            .addAction(Action.Builder().setTitle(carContext.getString(R.string.talking_cancel)).setOnClickListener { finish(send = false) }.build())
            .build()
    }
}
