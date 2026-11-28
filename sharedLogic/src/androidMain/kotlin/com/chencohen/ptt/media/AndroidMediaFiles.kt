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

    private fun original(blob: String, partial: Boolean): File? =
        blob.takeIf { MediaSharing.validBlob(it) }?.let { File(originalsDir, if (partial) "$it.part" else it) }

    override fun originalPath(blob: String): String? = original(blob, false)?.takeIf { it.isFile }?.absolutePath

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
        return listOf(sentDir, gotDir).map { File(it, file) }.firstOrNull { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }
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
