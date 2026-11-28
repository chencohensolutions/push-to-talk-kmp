package com.chencohen.ptt.ios

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970

/**
 * Where the app keeps files on iOS: Application Support/Talk for what must stay (out of iCloud and device backups, as
 * Android's noBackupFilesDir), Caches/Talk for what the system may clear when the phone runs low.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal object IosFolders {
    private fun base(directory: ULong): Path =
        ((NSSearchPathForDirectoriesInDomains(directory, NSUserDomainMask, true).firstOrNull() as? String) ?: ".").toPath() / "Talk"

    /** A folder that stays and isn't backed up. */
    fun kept(name: String): Path {
        val dir = base(NSApplicationSupportDirectory) / name
        FileSystem.SYSTEM.createDirectories(dir)
        NSURL.fileURLWithPath(dir.toString()).setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
        return dir
    }

    /** A folder the system may empty. */
    fun cache(name: String): Path = (base(NSCachesDirectory) / name).also { FileSystem.SYSTEM.createDirectories(it) }

    /** Marks [path] as read now: its modification time is when it was last read (the cache limit goes by it). */
    fun touch(path: Path) {
        NSFileManager.defaultManager.setAttributes(mapOf(NSFileModificationDate to NSDate()), ofItemAtPath = path.toString(), error = null)
    }

    fun lastModified(path: Path): Long =
        FileSystem.SYSTEM.metadataOrNull(path)?.lastModifiedAtMillis ?: 0L

    fun freeBytes(path: Path): Long =
        (NSFileManager.defaultManager.attributesOfFileSystemForPath(path.toString(), error = null)?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue ?: -1L

    fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
}
