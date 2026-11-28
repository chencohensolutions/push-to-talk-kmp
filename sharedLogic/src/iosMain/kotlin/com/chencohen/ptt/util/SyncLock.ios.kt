package com.chencohen.ptt.util

import platform.Foundation.NSRecursiveLock

internal actual class SyncLock {
    @PublishedApi
    internal val lock = NSRecursiveLock()
}

internal actual inline fun <T> SyncLock.locked(block: () -> T): T {
    lock.lock()
    try {
        return block()
    } finally {
        lock.unlock()
    }
}
