package com.chencohen.ptt.talk

import com.chencohen.ptt.ios.IosFolders
import okio.FileSystem
import okio.Path

/** Clips in the app's own storage (`Application Support/Talk/clips/<id>.clip`), ciphertext only; not backed up. */
class IosClipFiles : ClipFiles {
    private val fs = FileSystem.SYSTEM
    private val dir = IosFolders.kept("clips")

    private fun file(id: String): Path? = id.takeIf { Talk.validId(it) }?.let { dir / "$it.clip" }

    override fun write(id: String, bytes: ByteArray) {
        val target = file(id) ?: return
        val partial = dir / "$id.part"
        runCatching {
            fs.write(partial) { write(bytes) }
            fs.atomicMove(partial, target)
        }.onFailure { runCatching { fs.delete(partial) } }
    }

    override fun read(id: String): ByteArray? = file(id)?.let { path -> runCatching { fs.read(path) { readByteArray() } }.getOrNull() }

    override fun delete(id: String) {
        file(id)?.let { runCatching { fs.delete(it) } }
    }
}
