package com.chencohen.ptt.media

/**
 * Shared media this phone keeps (2026-11-28, hen): each part as the server has it, sealed (MediaCrypto), named by its
 * blob id, so a file is no use without its key in the encrypted store. Previews this phone sent are kept in the app's
 * own storage (Settings › Storage clears them); previews and originals it got are kept in its cache, which Android or
 * the user may clear: then they are fetched again, quietly.
 */
interface MediaFiles {
    fun write(name: String, bytes: ByteArray, sent: Boolean)

    fun read(name: String): ByteArray?

    /** Whether [name] is kept (without reading it). */
    fun has(name: String): Boolean = read(name) != null

    /** The bytes [name] takes here (0: not kept). */
    fun sizeOf(name: String): Long = read(name)?.size?.toLong() ?: 0

    fun delete(name: String)

    /** The bytes kept: the ones this phone sent ([sent]) or the ones it got. */
    fun size(sent: Boolean): Long

    /** Deletes what this phone kept of what it sent ([sent]) or got. */
    fun clear(sent: Boolean)

    /**
     * Originals this phone got (opened, so the viewer and the player read them; in the app's own cache, which only Talk
     * can read). One being fetched grows with [appendOriginal] until [finishOriginal]; [originalPath] is only a finished one.
     */
    fun originalPath(blob: String): String? = null

    /** How much of the original [blob] being fetched is here (0: none). */
    fun partialOriginal(blob: String): Long = 0

    fun appendOriginal(blob: String, bytes: ByteArray) = Unit

    /** The original [blob] is all here and checked: it becomes [originalPath]. */
    fun finishOriginal(blob: String) = Unit

    fun deleteOriginal(blob: String) = Unit

    /**
     * Moves the original [blob] out of the cache ([keep] true: the only copy known, so neither the system nor Clear may
     * delete it) or back into it. Answers whether it is kept now.
     */
    fun keepOriginal(blob: String, keep: Boolean): Boolean = false

    /** Whether the original [blob] is kept out of the cache. */
    fun keptOriginal(blob: String): Boolean = false

    /** The bytes the finished original [blob] takes here (0: not here). */
    fun originalSize(blob: String): Long = 0
}

class MemoryMediaFiles : MediaFiles {
    private val sentFiles = mutableMapOf<String, ByteArray>()
    private val gotFiles = mutableMapOf<String, ByteArray>()

    override fun write(name: String, bytes: ByteArray, sent: Boolean) {
        (if (sent) sentFiles else gotFiles)[name] = bytes.copyOf()
    }

    override fun read(name: String): ByteArray? = (sentFiles[name] ?: gotFiles[name])?.copyOf()

    override fun delete(name: String) {
        sentFiles.remove(name)
        gotFiles.remove(name)
    }

    override fun size(sent: Boolean): Long = (if (sent) sentFiles else gotFiles).values.sumOf { it.size.toLong() }

    override fun clear(sent: Boolean) {
        (if (sent) sentFiles else gotFiles).clear()
        if (!sent) {
            originals.keys.retainAll(keptOriginals)
            partials.clear()
        }
    }

    val originals = mutableMapOf<String, ByteArray>()
    val partials = mutableMapOf<String, ByteArray>()

    override fun originalPath(blob: String): String? = if (blob in originals) "memory:$blob" else null
    override fun partialOriginal(blob: String): Long = partials[blob]?.size?.toLong() ?: 0
    override fun appendOriginal(blob: String, bytes: ByteArray) {
        partials[blob] = (partials[blob] ?: ByteArray(0)) + bytes
    }
    override fun finishOriginal(blob: String) {
        originals[blob] = partials.remove(blob) ?: ByteArray(0)
    }
    override fun deleteOriginal(blob: String) {
        originals.remove(blob)
        partials.remove(blob)
        keptOriginals.remove(blob)
    }

    val keptOriginals = mutableSetOf<String>()

    override fun keepOriginal(blob: String, keep: Boolean): Boolean {
        if (blob !in originals) return false
        if (keep) keptOriginals += blob else keptOriginals -= blob
        return keep
    }

    override fun keptOriginal(blob: String): Boolean = blob in keptOriginals

    override fun originalSize(blob: String): Long = originals[blob]?.size?.toLong() ?: 0
}
