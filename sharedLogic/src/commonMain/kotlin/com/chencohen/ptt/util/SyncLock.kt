package com.chencohen.ptt.util

/**
 * A lock for the few places read from more than one thread (the messages' cache, a call's face hints): `synchronized`
 * is the JVM's only, so common code that iOS compiles too takes this instead. It may be taken again by the thread that
 * holds it.
 */
internal expect class SyncLock()

/** Runs [block] holding the lock. */
internal expect inline fun <T> SyncLock.locked(block: () -> T): T
