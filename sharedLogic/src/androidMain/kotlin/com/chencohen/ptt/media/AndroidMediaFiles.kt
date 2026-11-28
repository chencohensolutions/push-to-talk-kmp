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

    private fun name(id: String): String? = id.takeIf { MediaSharing.validBlob(it) }?.let { "$it.part" }

    override fun write(name: String, bytes: ByteArray, sent: Boolean) {
        val file = name(name) ?: return
        val dir = if (sent) sentDir else gotDir
        dir.mkdirs()
        val partial = File(dir, "$name.tmp")
        partial.writeBytes(bytes)
        if (!partial.renameTo(File(dir, file))) partial.delete()
    }

    override fun read(name: String): ByteArray? {
        val file = name(name) ?: return null
        return listOf(sentDir, gotDir).map { File(it, file) }.firstOrNull { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }
    }

    override fun delete(name: String) {
        val file = name(name) ?: return
        File(sentDir, file).delete()
        File(gotDir, file).delete()
    }

    override fun size(sent: Boolean): Long = ((if (sent) sentDir else gotDir).listFiles() ?: emptyArray()).sumOf { it.length() }

    override fun clear(sent: Boolean) {
        ((if (sent) sentDir else gotDir).listFiles() ?: emptyArray()).forEach { it.delete() }
    }
}
