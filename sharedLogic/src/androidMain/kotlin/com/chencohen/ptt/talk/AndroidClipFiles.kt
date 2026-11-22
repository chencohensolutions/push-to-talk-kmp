package com.chencohen.ptt.talk

import android.content.Context
import java.io.File

/** Clips in the app's own storage (`files/clips/<id>.clip`), ciphertext only; not backed up. */
class AndroidClipFiles(context: Context) : ClipFiles {
    private val dir = File(context.noBackupFilesDir, "clips").apply { mkdirs() }

    private fun file(id: String): File? = id.takeIf { Talk.validId(it) }?.let { File(dir, "$it.clip") }

    override fun write(id: String, bytes: ByteArray) {
        val target = file(id) ?: return
        val partial = File(dir, "$id.part")
        partial.writeBytes(bytes)
        partial.renameTo(target)
    }

    override fun read(id: String): ByteArray? = file(id)?.takeIf { it.isFile }?.readBytes()

    override fun delete(id: String) {
        file(id)?.delete()
    }
}
