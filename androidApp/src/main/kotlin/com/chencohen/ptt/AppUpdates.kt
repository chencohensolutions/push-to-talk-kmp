package com.chencohen.ptt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.chencohen.ptt.update.AppRelease
import com.chencohen.ptt.update.AppUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Where an app update stands (Settings › App updates, and the home card). */
sealed interface UpdateState {
    /** Not looked yet, or looked and this is the latest; [checkedAt] 0 before the first look. */
    data class Latest(val checkedAt: Long) : UpdateState
    data object Checking : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val percent: Int) : UpdateState
    data class Installing(val release: AppRelease) : UpdateState
    /** Install unknown apps is off for Talk: Android's settings page opens, then Update again. */
    data class NeedsPermission(val release: AppRelease) : UpdateState
    data class Failed(val message: String, val release: AppRelease?) : UpdateState
    /** A debug build: it isn't signed with the release key, so releases can't install over it. */
    data object DebugBuild : UpdateState
}

/**
 * App updates (docs/design.md, "App updates"). The server watches the GitHub releases and pushes `app_update` when a
 * new one is out; the phone reads the server's `appRelease` then (a notification, "Talk 1.3.0 is available. Tap to
 * update."), each time the app comes to the screen (the card), and on Check now. It never asks GitHub for releases
 * itself and never looks on a timer. Update downloads the APK, checks its SHA-256, that it is this app, newer, and
 * signed with the same key, then installs it with PackageInstaller: Android asks the user (once for Install unknown
 * apps, and to confirm the update unless it lets the app update itself without asking, Android 12+).
 */
object AppUpdates {
    private const val TAG = "AppUpdates"
    private const val PREFS = "app_updates"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_NOTIFIED = "notified_version"
    private const val ACTION_INSTALLED = "com.chencohen.ptt.UPDATE_INSTALLED"
    const val ACTION_UPDATE = "com.chencohen.ptt.UPDATE"
    private const val CHANNEL = "app_updates"
    private const val NOTIFICATION_ID = 51

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Latest(0))
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun currentVersion(context: Context): String = packageInfo(context).versionName.orEmpty()

    private fun debuggable(context: Context) = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** When the app comes to the screen: what the server has (no request to GitHub, no timer). */
    fun checkIfDue(app: TalkApp) {
        if (debuggable(app)) {
            _state.value = UpdateState.DebugBuild
            return
        }
        val last = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0)
        if (_state.value is UpdateState.Latest) _state.value = UpdateState.Latest(last)
        check(app)
    }

    /** Check now (Settings), and when the app comes to the screen: the server's appRelease. */
    fun check(app: TalkApp, then: (AppRelease?) -> Unit = {}) {
        if (debuggable(app)) {
            _state.value = UpdateState.DebugBuild
            return
        }
        if (busy()) return
        val before = _state.value
        _state.value = UpdateState.Checking
        app.scope.launch {
            val offer = try {
                val release = appRelease(app)
                val now = System.currentTimeMillis()
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LAST_CHECK, now).apply()
                AppUpdate.offer(release, currentVersion(app)).also {
                    _state.value = if (it != null) UpdateState.Available(it) else UpdateState.Latest(now)
                    if (it != null) Log.i(TAG, "update available: ${it.version}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "asking the server for the app release", e)
                _state.value = if (before is UpdateState.Available) before else UpdateState.Failed(app.getString(R.string.update_failed_check), null)
                null
            }
            then(offer)
        }
    }

    /** An `app_update` push: read the server's release and, once per version, show the notification. */
    fun onPush(app: TalkApp) {
        if (debuggable(app)) {
            Log.i(TAG, "app_update push: a debug build doesn't update itself")
            return
        }
        check(app) { offer ->
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (AppUpdate.notify(offer, prefs.getString(KEY_NOTIFIED, null)) && offer != null) {
                notify(app, offer)
                prefs.edit().putString(KEY_NOTIFIED, offer.version).apply()
            }
        }
    }

    /** The notification's tap (MainActivity): update to what is offered, or look again first. */
    fun updateFromNotification(app: TalkApp) {
        NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
        val offered = (_state.value as? UpdateState.Available)?.release
        if (offered != null) update(app, offered) else check(app) { offer -> if (offer != null) update(app, offer) }
    }

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notify(app: TalkApp, release: AppRelease) {
        val open = PendingIntent.getActivity(
            app, NOTIFICATION_ID, Intent(app, MainActivity::class.java).setAction(ACTION_UPDATE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(app.getString(R.string.update_available, release.version))
            .setContentText(app.getString(R.string.update_notification_body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        if (app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "notifications are off: the update shows as a card")
            return
        }
        NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification)
    }

    /** Update: download, verify and install [release]. */
    fun update(app: TalkApp, release: AppRelease) {
        if (busy()) return
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(release)
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        _state.value = UpdateState.Downloading(release, 0)
        app.scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { download(app, release) }
                _state.value = UpdateState.Installing(release)
                withContext(Dispatchers.IO) { install(app, file) }
            } catch (e: Exception) {
                Log.w(TAG, "updating to ${release.version}", e)
                _state.value = UpdateState.Failed(e.message ?: app.getString(R.string.update_failed_install), release)
            }
        }
    }

    private fun busy() = _state.value.let { it is UpdateState.Checking || it is UpdateState.Downloading || it is UpdateState.Installing }

    private fun download(app: TalkApp, release: AppRelease): File {
        val expected = release.sha256
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, release.apkName)
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = open(release.apkUrl)
        try {
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.size
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                        if (percent != shown) {
                            shown = percent
                            _state.value = UpdateState.Downloading(release, percent)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            file.delete()
            error(app.getString(R.string.update_failed_checksum))
        }
        // The same app, newer, and signed with the same key: Android would refuse it otherwise, after asking.
        val archive = app.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error(app.getString(R.string.update_failed_install))
        val own = packageInfo(app, PackageManager.GET_SIGNING_CERTIFICATES)
        val sameKey = archive.signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }?.toSet() ==
            own.signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }?.toSet()
        if (archive.packageName != app.packageName || archive.longVersionCode <= own.longVersionCode || !sameKey) {
            file.delete()
            error(app.getString(R.string.update_failed_signature))
        }
        return file
    }

    private fun install(app: TalkApp, file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("talk.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(app, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALLED)
            val pending = PendingIntent.getBroadcast(app, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
        Log.i(TAG, "install session $id committed")
    }

    /** PackageInstaller's answer: Android's confirm screen to show, or the result (on success the app restarts). */
    internal fun installed(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "updated")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "install failed: $status $message")
                val release = (_state.value as? UpdateState.Installing)?.release
                _state.value = if (status == PackageInstaller.STATUS_FAILURE_ABORTED && release != null) {
                    UpdateState.Available(release)
                } else {
                    UpdateState.Failed(message ?: context.getString(R.string.update_failed_install), release)
                }
            }
        }
    }

    private fun packageInfo(context: Context, flags: Int = 0): PackageInfo =
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/octet-stream")
        setRequestProperty("User-Agent", "Talk-Android")
        if (responseCode !in 200..299) {
            disconnect()
            error("HTTP $responseCode")
        }
    }
}

/** PackageInstaller's results for an update session (AppUpdates.install). */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AppUpdates.installed(context, intent)
}
