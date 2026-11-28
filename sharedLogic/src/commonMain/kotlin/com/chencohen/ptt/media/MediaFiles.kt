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

    fun delete(name: String)

    /** The bytes kept: the ones this phone sent ([sent]) or the ones it got. */
    fun size(sent: Boolean): Long

    /** Deletes what this phone kept of what it sent ([sent]) or got. */
    fun clear(sent: Boolean)
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

    override fun clear(sent: Boolean) = (if (sent) sentFiles else gotFiles).clear()
}
