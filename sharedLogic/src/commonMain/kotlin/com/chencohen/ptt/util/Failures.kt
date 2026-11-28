package com.chencohen.ptt.util

import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * For the scopes that run work nobody waits for (a tap's action, the hub's own jobs): an exception there is written
 * to the log and that one piece of work ends. Without it, the exception would reach the thread's uncaught-exception
 * handler and end the whole app, on Android and on iOS, for a failed "typing…" or a read that hit a bad row.
 * Work whose failure the user should see catches it itself and says so (ContactResult.Failed and the like).
 */
internal val logFailures = CoroutineExceptionHandler { _, error ->
    println("Talk: background work failed: " + error.stackTraceToString())
}
