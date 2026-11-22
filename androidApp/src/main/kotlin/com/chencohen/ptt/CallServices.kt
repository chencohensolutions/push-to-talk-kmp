package com.chencohen.ptt

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallsManager
import com.chencohen.ptt.calls.CallHost
import com.chencohen.ptt.calls.CallUi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Calls outside the screen (docs/design.md, "Phase 6"): each call is a Telecom call (Core-Telecom's addCall), so the
// system rings it like a phone call, routes its audio (Bluetooth, the car) and keeps the process while it runs. An
// incoming call shows a full-screen notification with Answer and Decline; a running one an ongoing call notification
// with Hang up, from CallService (a phone-call foreground service).

/** The platform's part in calls (CallHost). */
class AndroidCallHost(private val app: TalkApp) : CallHost {
    override fun ring(call: CallUi) {
        app.scope.launch {
            val name = runCatching { chatName(app, call.chat) }.getOrDefault(call.chat)
            CallSession.ring(app, call, name)
            // The reply bubble on screen becomes this call's Answer.
            TalkBubble.incoming(app, call, name)
        }
    }

    override fun ongoing(call: CallUi) {
        TalkBubble.callGone(app)
        app.scope.launch {
            val name = runCatching { chatName(app, call.chat) }.getOrDefault(call.chat)
            CallSession.ongoing(app, call, name)
        }
    }

    override fun ended(call: CallUi) {
        TalkBubble.callGone(app)
        CallSession.end(app, call.id)
    }

    override fun missed(chat: String, caller: String, video: Boolean) {
        app.scope.launch {
            val name = runCatching { chatName(app, chat) }.getOrDefault(chat)
            CallNotifications.missed(app, chat, name, video)
        }
    }
}

/** The one Telecom call of this phone's call. */
object CallSession {
    private const val TAG = "CallSession"

    @Volatile
    var ringing = false
        private set

    private var id: String? = null
    private var job: Job? = null
    private var answered: CompletableDeferred<Boolean>? = null
    private var done: CompletableDeferred<Unit>? = null
    private var registered = false

    /** Rings [call] (an incoming one): Telecom's incoming call and the full-screen notification. */
    fun ring(app: TalkApp, call: CallUi, name: String) {
        if (id == call.id) return
        end(app, id ?: "")
        id = call.id
        ringing = true
        val answer = CompletableDeferred<Boolean>()
        answered = answer
        val over = CompletableDeferred<Unit>()
        done = over
        CallNotifications.incoming(app, call, name)
        job = app.scope.launch {
            runCall(app, call, name, CallAttributesCompat.DIRECTION_INCOMING) {
                // Answered here (the screen, the notification) or by Telecom (a headset, the car).
                if (answer.await()) {
                    answer(if (call.video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
                    ringing = false
                    CallService.start(app, name, call.video)
                }
                over.await()
            }
        }
    }

    /** This phone's call is placed or answered: an outgoing Telecom call, or the ringing one answered. */
    fun ongoing(app: TalkApp, call: CallUi, name: String) {
        CallNotifications.cancelIncoming(app)
        if (id == call.id) {
            answered?.complete(true)
            return
        }
        end(app, id ?: "")
        id = call.id
        val over = CompletableDeferred<Unit>()
        done = over
        job = app.scope.launch {
            runCall(app, call, name, CallAttributesCompat.DIRECTION_OUTGOING) {
                setActive()
                CallService.start(app, name, call.video)
                over.await()
            }
        }
    }

    /** The call is over here: Telecom's call disconnects, the notifications go. */
    fun end(app: TalkApp, callId: String) {
        if (callId.isEmpty() || callId != id) return
        id = null
        ringing = false
        answered?.complete(false)
        done?.complete(Unit)
        CallNotifications.cancelIncoming(app)
        CallService.stop(app)
    }

    private suspend fun runCall(app: TalkApp, call: CallUi, name: String, direction: Int, block: suspend CallControlScope.() -> Unit) {
        try {
            val manager = CallsManager(app)
            if (!registered) {
                manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE or CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING)
                registered = true
            }
            val attributes = CallAttributesCompat(
                displayName = name,
                address = Uri.fromParts("ptt", call.chat, null),
                direction = direction,
                callType = if (call.video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
            )
            manager.addCall(
                attributes,
                onAnswer = { app.scope.launch { callAction(app, "accept") } },
                onDisconnect = { app.scope.launch { callAction(app, "hang_up") } },
                onSetActive = {},
                onSetInactive = {},
            ) {
                launch {
                    block()
                    disconnect(DisconnectCause(DisconnectCause.LOCAL))
                }
            }
        } catch (e: Exception) {
            // Without Telecom the call still runs; only the system's ringing and routing are missing.
            Log.w(TAG, "no Telecom call", e)
            if (direction == CallAttributesCompat.DIRECTION_OUTGOING) CallService.start(app, name, call.video)
        }
    }
}

/** The calls' notifications: ringing (full screen), missed. */
object CallNotifications {
    /** Silent: CallRinger rings (v2, 2026-11-22: a channel's sound can't change, and the first one had one). */
    const val RING_CHANNEL = "calls_ringing_v2"
    private const val OLD_RING_CHANNEL = "calls_ringing"
    const val ONGOING_CHANNEL = "calls_ongoing"
    const val MISSED_CHANNEL = "calls_missed"
    private const val RING_ID = 51
    const val ACTION_ANSWER = "com.chencohen.ptt.CALL_ANSWER"
    const val ACTION_DECLINE = "com.chencohen.ptt.CALL_DECLINE"
    const val ACTION_HANG_UP = "com.chencohen.ptt.CALL_HANG_UP"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(OLD_RING_CHANNEL)
        manager.createNotificationChannel(
            NotificationChannel(RING_CHANNEL, context.getString(R.string.call_channel_ringing), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(ONGOING_CHANNEL, context.getString(R.string.call_channel_ongoing), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(MISSED_CHANNEL, context.getString(R.string.call_channel_missed), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun allowed(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Opens the app on the call screen; [action] answers on the way. */
    fun openCall(context: Context, action: String?): PendingIntent = PendingIntent.getActivity(
        context, action.hashCode(),
        Intent(context, MainActivity::class.java).setAction(action ?: Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun broadcast(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, action.hashCode(), Intent(context, CallActionReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    fun incoming(context: Context, call: CallUi, name: String) {
        CallRinger.start(context, messagingState(context).value.talkSettings.softRing)
        if (!allowed(context)) return
        val person = Person.Builder().setName(name).setImportant(true).build()
        val notification = NotificationCompat.Builder(context, RING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, broadcast(context, ACTION_DECLINE), openCall(context, ACTION_ANSWER)).setIsVideo(call.video))
            .setContentText(context.getString(if (call.video) R.string.call_ringing_video else R.string.call_ringing_voice))
            .setFullScreenIntent(openCall(context, null), true)
            .setContentIntent(openCall(context, null))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setTimeoutAfter(45_000L)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(RING_ID, notification)
        } catch (_: SecurityException) {
            // Notifications were turned off meanwhile.
        }
    }

    fun cancelIncoming(context: Context) {
        CallRinger.stop()
        NotificationManagerCompat.from(context).cancel(RING_ID)
    }

    fun ongoing(context: Context, name: String): Notification {
        val person = Person.Builder().setName(name).build()
        return NotificationCompat.Builder(context, ONGOING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, broadcast(context, ACTION_HANG_UP)))
            .setContentIntent(openCall(context, null))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    fun missed(context: Context, chat: String, name: String, video: Boolean) {
        if (!allowed(context)) return
        val notification = NotificationCompat.Builder(context, MISSED_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(if (video) R.string.call_missed_video else R.string.call_missed_voice))
            .setContentText(name)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, chat.hashCode(),
                    Intent(context, MainActivity::class.java).setAction(Notifications.ACTION_OPEN).putExtra(Notifications.EXTRA_USER, chat)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .build()
        try {
            NotificationManagerCompat.from(context).notify("missed/$chat", 0, notification)
        } catch (_: SecurityException) {
            // Notifications were turned off meanwhile.
        }
    }
}

/** Decline and Hang up from a notification. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TalkApp
        val action = when (intent.action) {
            CallNotifications.ACTION_DECLINE -> "decline"
            CallNotifications.ACTION_HANG_UP -> "hang_up"
            else -> return
        }
        val pending = goAsync()
        app.scope.launch {
            try {
                callAction(app, action)
            } finally {
                pending.finish()
            }
        }
    }
}

/** The phone-call foreground service of a running call, with its ongoing notification (Hang up). */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra(EXTRA_NAME).orEmpty()
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true
        val notification = CallNotifications.ongoing(this, name)
        val camera = video && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val types = listOf(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or
                (if (camera) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL,
        )
        for (type in types) {
            try {
                startForeground(ID, notification, type)
                break
            } catch (e: Exception) {
                Log.w("CallService", "no foreground service of type $type", e)
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 52
        private const val EXTRA_NAME = "name"
        private const val EXTRA_VIDEO = "video"

        fun start(context: Context, name: String, video: Boolean) {
            runCatching {
                context.startForegroundService(Intent(context, CallService::class.java).putExtra(EXTRA_NAME, name).putExtra(EXTRA_VIDEO, video))
            }.onFailure { Log.w("CallService", "can't start the service", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}

/** A call rings or runs: the process stays for it after a push woke it. */
internal fun callKeepsProcess(): Boolean = CallSession.ringing
