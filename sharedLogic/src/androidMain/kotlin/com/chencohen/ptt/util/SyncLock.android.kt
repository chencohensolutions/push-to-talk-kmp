package com.chencohen.ptt.util

internal actual class SyncLock

internal actual inline fun <T> SyncLock.locked(block: () -> T): T = synchronized(this, block)
