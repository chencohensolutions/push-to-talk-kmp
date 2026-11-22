package com.chencohen.ptt

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The app's process: notifications for texts that come in (Notifications), and FCM's pushes (docs/design.md,
 * "Push"). From phase 3 the phone holds no socket in the background: the server's push wakes it ([TalkMessagingService]),
 * it connects, takes its queue, and closes again.
 */
class TalkApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
        setMessageNotifier(this, Notifications(this))
    }
}

/** This phone's FCM registration with the server. */
object Push {
    private const val TAG = "Push"

    /**
     * Gives the server this phone's FCM token and whether notifications are on, when either changed ([token]: a new
     * one from FCM). Called when the app comes to the front and when FCM makes a new token. A phone without Google
     * Play services gets no token: it receives only while the app is open.
     */
    suspend fun register(context: Context, token: String? = null) {
        val current = token ?: runCatching { FirebaseMessaging.getInstance().token.await() }
            .onFailure { Log.w(TAG, "no FCM token", it) }.getOrNull()
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        runCatching { registerPush(context, current, enabled) }
            .onSuccess { Log.i(TAG, "registered: token ${if (current != null) "yes" else "no"}, notifications $enabled") }
            .onFailure { Log.w(TAG, "registerPush", it) }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> cont.resumeWithException(error)
            task.isCanceled -> cont.cancel()
            else -> cont.resume(task.result)
        }
    }
}

/**
 * The server's pushes: a data message with only {"kind": ...} ("message", "sync"; "talk" and "call" come in phases
 * 5a and 6). Each one means "connect and take your queue": what came in is shown by Notifications as it is read.
 */
class TalkMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val kind = message.data["kind"] ?: return
        Log.i("Push", "woken: $kind (priority ${message.priority})")
        // onMessageReceived runs on its own thread, with about 20 seconds before Android may stop the process.
        runBlocking { withTimeoutOrNull(18_000) { wakeForPush(applicationContext) } }
    }

    override fun onNewToken(token: String) {
        val app = applicationContext as TalkApp
        app.scope.launch { Push.register(app, token) }
    }
}
