package com.chencohen.ptt.media

import com.chencohen.ptt.ios.IosFolders
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.Path.Companion.toPath
import okio.blackholeSink
import okio.buffer
import okio.use

/**
 * Shared media's sealed parts on the phone (MediaFiles, as AndroidMediaFiles): what this phone sent in its own storage
 * (no backup; cleared only from Settings), what it got in Caches, which iOS may clear (then fetched again).
 */
class IosMediaFiles : MediaFiles {
    private val fs = FileSystem.SYSTEM
    private val sentDir = IosFolders.kept("media-sent")
    private val gotDir = IosFolders.cache("media")
    private val originalsDir = IosFolders.cache("media-originals")

    /** Originals that are the only copy known: out of the cache, so nothing clears them. */
    private val keptDir = IosFolders.kept("media-originals-kept")

    private fun isFile(path: Path?): Boolean = path != null && fs.metadataOrNull(path)?.isRegularFile == true

    private fun size(path: Path?): Long = path?.let { fs.metadataOrNull(it)?.size } ?: 0L

    private fun files(dir: Path): List<Path> = runCatching { fs.list(dir) }.getOrDefault(emptyList()).filter { isFile(it) }

    private fun original(blob: String, partial: Boolean): Path? =
        blob.takeIf { MediaSharing.validBlob(it) }?.let { originalsDir / (if (partial) "$it.part" else it) }

    private fun kept(blob: String): Path? = blob.takeIf { MediaSharing.validBlob(it) }?.let { keptDir / it }

    override fun originalPath(blob: String): String? =
        (kept(blob)?.takeIf { isFile(it) } ?: original(blob, false)?.takeIf { isFile(it) })?.toString()

    override fun keepOriginal(blob: String, keep: Boolean): Boolean {
        val cached = original(blob, false) ?: return false
        val kept = kept(blob) ?: return false
        fun move(from: Path, to: Path) {
            runCatching { fs.atomicMove(from, to) }.onFailure {
                runCatching {
                    fs.copy(from, to)
                    fs.delete(from)
                }
            }
        }
        if (keep) {
            if (!isFile(kept) && isFile(cached)) move(cached, kept)
            return isFile(kept)
        }
        if (isFile(kept)) move(kept, cached)
        return false
    }

    override fun keptOriginal(blob: String): Boolean = isFile(kept(blob))

    override fun originalSize(blob: String): Long = originalPath(blob)?.let { size(okioPath(it)) } ?: 0

    private fun okioPath(path: String): Path = path.toPath()

    override fun originalSha256(blob: String): ByteArray? = originalPath(blob)?.let { path ->
        runCatching {
            val hashing = HashingSink.sha256(blackholeSink())
            fs.source(okioPath(path)).use { source -> hashing.buffer().use { it.writeAll(source) } }
            hashing.hash.toByteArray()
        }.getOrNull()
    }

    private fun fileOf(id: String): String? = id.takeIf { MediaSharing.validBlob(it) }?.let { "$it.part" }

    private fun found(name: String): Path? {
        val file = fileOf(name) ?: return null
        return listOf(sentDir / file, gotDir / file).firstOrNull { isFile(it) }
    }

    override fun sizeOf(name: String): Long = size(found(name))

    override fun partialOriginal(blob: String): Long = size(original(blob, true)?.takeIf { isFile(it) })

    override fun appendOriginal(blob: String, bytes: ByteArray) {
        val file = original(blob, true) ?: return
        runCatching {
            fs.createDirectories(originalsDir)
            fs.appendingSink(file).buffer().use { it.write(bytes) }
        }
    }

    override fun finishOriginal(blob: String) {
        val partial = original(blob, true) ?: return
        val done = original(blob, false) ?: return
        runCatching { fs.atomicMove(partial, done) }.onFailure { runCatching { fs.delete(partial) } }
    }

    override fun deleteOriginal(blob: String) {
        listOfNotNull(original(blob, true), original(blob, false), kept(blob)).forEach { runCatching { fs.delete(it) } }
    }

    override fun write(name: String, bytes: ByteArray, sent: Boolean) {
        val file = fileOf(name) ?: return
        val dir = if (sent) sentDir else gotDir
        val partial = dir / "$name.tmp"
        runCatching {
            fs.createDirectories(dir)
            fs.write(partial) { write(bytes) }
            fs.atomicMove(partial, dir / file)
        }.onFailure { runCatching { fs.delete(partial) } }
    }

    override fun read(name: String): ByteArray? {
        val found = found(name) ?: return null
        val bytes = runCatching { fs.read(found) { readByteArray() } }.getOrNull() ?: return null
        // A file's time is when it was last read: the cache limit lets the longest unread go first.
        if (found.parent == gotDir) IosFolders.touch(found)
        return bytes
    }

    override fun cached(): List<CachedPart> {
        val previews = files(gotDir).filter { it.name.endsWith(".part") }
            .map { CachedPart(it.name.removeSuffix(".part"), false, size(it), IosFolders.lastModified(it)) }
        val originals = files(originalsDir).filter { MediaSharing.validBlob(it.name) }
            .map { CachedPart(it.name, true, size(it), IosFolders.lastModified(it)) }
        return previews + originals
    }

    override fun touchOriginal(blob: String) {
        original(blob, false)?.takeIf { isFile(it) }?.let { IosFolders.touch(it) }
    }

    override fun freeBytes(): Long = IosFolders.freeBytes(gotDir)

    override fun has(name: String): Boolean = found(name) != null

    override fun delete(name: String) {
        val file = fileOf(name) ?: return
        listOf(sentDir / file, gotDir / file).forEach { runCatching { fs.delete(it) } }
    }

    override fun size(sent: Boolean): Long =
        (if (sent) listOf(sentDir) else listOf(gotDir, originalsDir)).sumOf { dir -> files(dir).sumOf { size(it) } }

    override fun clear(sent: Boolean) {
        (if (sent) listOf(sentDir) else listOf(gotDir, originalsDir)).forEach { dir -> files(dir).forEach { runCatching { fs.delete(it) } } }
    }
}
