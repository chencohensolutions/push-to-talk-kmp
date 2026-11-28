package com.chencohen.ptt.media

import android.content.Context
import java.io.File

/**
 * Shared media's sealed parts on the phone (MediaFiles): what this phone sent in its own storage (no backup; cleared
 * only from Settings), what it got in its cache, which Android or the user may clear (then fetched again).
 */
class AndroidMediaFiles(context: Context) : MediaFiles {
    private val sentDir = File(context.noBackupFilesDir, "media-sent").apply { mkdirs() }
    private val gotDir = File(context.cacheDir, "media").apply { mkdirs() }
    private val originalsDir = File(context.cacheDir, "media-originals").apply { mkdirs() }

    /** Originals that are the only copy known (2026-11-28, hen): out of the cache, so nothing clears them. */
    private val keptDir = File(context.noBackupFilesDir, "media-originals-kept").apply { mkdirs() }

    private fun original(blob: String, partial: Boolean): File? =
        blob.takeIf { MediaSharing.validBlob(it) }?.let { File(originalsDir, if (partial) "$it.part" else it) }

    override fun originalPath(blob: String): String? =
        (kept(blob)?.takeIf { it.isFile } ?: original(blob, false)?.takeIf { it.isFile })?.absolutePath

    private fun kept(blob: String): File? = blob.takeIf { MediaSharing.validBlob(it) }?.let { File(keptDir, it) }

    override fun keepOriginal(blob: String, keep: Boolean): Boolean {
        val cached = original(blob, false) ?: return false
        val kept = kept(blob) ?: return false
        fun move(from: File, to: File) {
            to.parentFile?.mkdirs()
            if (!from.renameTo(to)) runCatching { from.copyTo(to, overwrite = true); from.delete() }
        }
        if (keep) {
            if (!kept.isFile && cached.isFile) move(cached, kept)
            return kept.isFile
        }
        if (kept.isFile) move(kept, cached)
        return false
    }

    override fun keptOriginal(blob: String): Boolean = kept(blob)?.isFile == true

    override fun originalSize(blob: String): Long = originalPath(blob)?.let { File(it).length() } ?: 0

    override fun sizeOf(name: String): Long {
        val file = fileOf(name) ?: return 0
        return listOf(sentDir, gotDir).map { File(it, file) }.firstOrNull { it.isFile }?.length() ?: 0
    }

    override fun partialOriginal(blob: String): Long = original(blob, true)?.takeIf { it.isFile }?.length() ?: 0

    override fun appendOriginal(blob: String, bytes: ByteArray) {
        val file = original(blob, true) ?: return
        originalsDir.mkdirs()
        java.io.FileOutputStream(file, true).use { it.write(bytes) }
    }

    override fun finishOriginal(blob: String) {
        val partial = original(blob, true) ?: return
        val done = original(blob, false) ?: return
        if (!partial.renameTo(done)) partial.delete()
    }

    override fun deleteOriginal(blob: String) {
        original(blob, true)?.delete()
        original(blob, false)?.delete()
        kept(blob)?.delete()
    }

    private fun fileOf(id: String): String? = id.takeIf { MediaSharing.validBlob(it) }?.let { "$it.part" }

    override fun write(name: String, bytes: ByteArray, sent: Boolean) {
        val file = fileOf(name) ?: return
        val dir = if (sent) sentDir else gotDir
        dir.mkdirs()
        val partial = File(dir, "$name.tmp")
        partial.writeBytes(bytes)
        if (!partial.renameTo(File(dir, file))) partial.delete()
    }

    override fun read(name: String): ByteArray? {
        val file = fileOf(name) ?: return null
        val found = listOf(sentDir, gotDir).map { File(it, file) }.firstOrNull { it.isFile } ?: return null
        val bytes = runCatching { found.readBytes() }.getOrNull() ?: return null
        // A file's time is when it was last read: the cache limit lets the longest unread go first.
        if (found.parentFile == gotDir) found.setLastModified(System.currentTimeMillis())
        return bytes
    }

    override fun cached(): List<CachedPart> {
        val previews = (gotDir.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".part") }
            .map { CachedPart(it.name.removeSuffix(".part"), false, it.length(), it.lastModified()) }
        val originals = (originalsDir.listFiles() ?: emptyArray()).filter { it.isFile && MediaSharing.validBlob(it.name) }
            .map { CachedPart(it.name, true, it.length(), it.lastModified()) }
        return previews + originals
    }

    override fun touchOriginal(blob: String) {
        original(blob, false)?.takeIf { it.isFile }?.setLastModified(System.currentTimeMillis())
    }

    override fun freeBytes(): Long = runCatching { android.os.StatFs(gotDir.path).availableBytes }.getOrDefault(-1L)

    override fun has(name: String): Boolean {
        val file = fileOf(name) ?: return false
        return File(sentDir, file).isFile || File(gotDir, file).isFile
    }

    override fun delete(name: String) {
        val file = fileOf(name) ?: return
        File(sentDir, file).delete()
        File(gotDir, file).delete()
    }

    override fun size(sent: Boolean): Long = (if (sent) listOf(sentDir) else listOf(gotDir, originalsDir))
        .sumOf { dir -> (dir.listFiles() ?: emptyArray()).sumOf { it.length() } }

    override fun clear(sent: Boolean) {
        (if (sent) listOf(sentDir) else listOf(gotDir, originalsDir)).forEach { dir -> (dir.listFiles() ?: emptyArray()).forEach { it.delete() } }
    }
}
