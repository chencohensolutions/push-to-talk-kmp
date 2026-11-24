package com.chencohen.ptt

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.chencohen.ptt.ui.calls.KeptLoad
import com.chencohen.ptt.ui.calls.loadKeptCall
import com.chencohen.ptt.ui.calls.saveKeptCall
import java.util.concurrent.TimeUnit

/**
 * Auto-download of saved calls (Settings › Calls › Save calls › Download saved calls; 2026-11-23, hen). When a call
 * this phone saved ends, [schedule] enqueues a worker that, once the chosen conditions are met (Wi-Fi and/or
 * charging), fetches the kept call from the server, decrypts it, and writes the .mkv where "Save as file" would
 * (the chosen folder, or Downloads › Talk). WorkManager waits for the conditions and retries while the server is
 * still finishing the call.
 */
object CallDownloads {
    private const val TAG = "CallDownloads"
    const val KEY_CALL = "call"

    /** On a saved call's end: enqueue its download when Download saved calls is on. */
    fun schedule(app: TalkApp, callId: String) {
        val s = messagingState(app).value.talkSettings
        if (!s.autoDownloadCalls) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (s.autoDownloadWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(s.autoDownloadChargingOnly)
            .build()
        val request = OneTimeWorkRequestBuilder<KeptCallDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(KEY_CALL to callId))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 2, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork("kept-download-$callId", ExistingWorkPolicy.KEEP, request)
        Log.i(TAG, "auto-download scheduled for $callId")
    }
}

/** Downloads one saved call to this phone in the background (CallDownloads.schedule). */
class KeptCallDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TalkApp
        val callId = inputData.getString(CallDownloads.KEY_CALL) ?: return Result.failure()
        if (!messagingState(app).value.talkSettings.autoDownloadCalls) return Result.success()
        val message = callMessage(app, callId)
        // The entry, or the server's kept copy, may not be ready the instant the call ends: wait and try again.
        if (message?.call?.keptKey == null) {
            return if (runAttemptCount < MAX_TRIES) Result.retry() else Result.success()
        }
        return try {
            val contacts = messagingState(app).value.contacts
            val nameOf = { user: String -> contacts.firstOrNull { it.user == user }?.name ?: user }
            val load = loadKeptCall(app, message, { keptCallTracks(app, it) }, { id, onBytes -> readCallBlob(app, id, onBytes) }, nameOf) {}
            if (load !is KeptLoad.Ready) {
                return if (runAttemptCount < MAX_TRIES) Result.retry() else Result.failure()
            }
            saveKeptCall(app, load.file, chatName(app, message.user), message.at)
            markCallDownloaded(app, callId)
            Log.i("CallDownloads", "saved call $callId to this phone")
            Result.success()
        } catch (e: Exception) {
            Log.w("CallDownloads", "auto-download of $callId", e)
            if (runAttemptCount < MAX_TRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val MAX_TRIES = 20
    }
}
