package com.chencohen.ptt.media

import com.chencohen.ptt.crypto.DeviceAddress
import com.chencohen.ptt.discovery.KeptDiscovery
import com.chencohen.ptt.messaging.FakeCryptoEngine
import com.chencohen.ptt.messaging.FakeServer
import com.chencohen.ptt.messaging.MediaKind
import com.chencohen.ptt.messaging.MessageStatus
import com.chencohen.ptt.messaging.Messaging
import com.chencohen.ptt.messaging.MessagingController
import com.chencohen.ptt.messaging.MessagingStore
import com.chencohen.ptt.settings.EnrolledDevice
import com.chencohen.ptt.settings.KeyValueStore
import com.chencohen.ptt.settings.PttSettings
import com.chencohen.ptt.store.MemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MediaPrefs : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** The bytes of the original at [source]: a few chunks, the last one short. */
private fun originalOf(source: String): ByteArray = ByteArray(3 * MediaCrypto.CHUNK + 1234) { (it * 31 + source.hashCode()).toByte() }

private fun sha256(bytes: ByteArray): ByteArray = okio.ByteString.of(*bytes).sha256().toByteArray()

/** A picture 4000 wide whose previews are bytes naming their width; its original is [originalOf] until changed. */
private class FakePreparer : MediaPreparer {
    val asked = mutableListOf<List<Int>>()
    /** What is at each source now (gone: absent); starts as [originalOf]. */
    val files = mutableMapOf<String, ByteArray>()
    var opened = 0

    override suspend fun prepare(source: String, widths: (originalWidth: Int) -> List<Int>): PreparedItem? {
        if (source == "gone") return null
        val w = widths(4000).also { asked += it }
        val original = originalOf(source).also { files["content://media/$source"] = it }
        return PreparedItem(
            kind = MediaKind.IMAGE, mime = "image/jpeg", name = "$source.jpg", size = original.size.toLong(), width = 4000, height = 3000,
            sha256 = sha256(original),
            takenAt = 1_790_000_000_000, hasPlace = true, latE7 = 320_000_000, lonE7 = 340_000_000,
            previews = w.map { PreparedPreview(it, PreviewSpec.heightAt(4000, 3000, it), "preview $source $it".encodeToByteArray()) },
            source = "content://media/$source",
        )
    }

    override suspend fun open(source: String): OriginalReader? {
        val bytes = files[source] ?: return null
        opened++
        return object : OriginalReader {
            var at = 0
            override val size: Long = bytes.size.toLong()
            override suspend fun read(max: Int): ByteArray? {
                if (at >= bytes.size) return null
                // In odd pieces, as a file system may give them.
                val n = minOf(max, 7000, bytes.size - at)
                return bytes.copyOfRange(at, at + n).also { at += n }
            }
            override fun sha256(): ByteArray = sha256(bytes.copyOfRange(0, at))
            override fun close() = Unit
        }
    }
}

private class MediaPhone(val server: FakeServer, val user: String, scope: TestScope, seed: Int, screen: Int) {
    val settings = PttSettings(MediaPrefs()).apply {
        serverUrl = "https://203.0.113.7:56400/query"
        identityAlias = "ptt-device-$user-1"
        serverDiscovery = KeptDiscovery(fingerprint = "ab".repeat(32), id = "ID")
        device = EnrolledDevice(user, 1, "Pixel")
    }
    val address = DeviceAddress(user, 1)
    private val job = SupervisorJob()
    val work = CoroutineScope(scope.coroutineContext + job)
    val files = MemoryMediaFiles()
    val store = MessagingStore(MemoryStore())
    val messaging = Messaging(
        settings, FakeCryptoEngine(Random(seed)), store, server.api(address), server.connector(address), work,
        now = { 1_000_000L }, random = Random(seed), blobApi = server.blobs(address), mediaFiles = files,
    ).also {
        it.screenWidthPx = screen
        it.screenHeightPx = screen * 2
        it.appVersion = "1.4.32"
        it.media.preparer = FakePreparer()
    }
    val dispatcher = StandardTestDispatcher(scope.testScheduler)
    val controller = MessagingController(messaging, dispatcher, main = dispatcher)
    val state get() = messaging.state.value

    fun stop() {
        controller.close()
        job.cancel()
    }
}

class MediaSharingTest {
    private suspend fun TestScope.paired(): Triple<FakeServer, MediaPhone, MediaPhone> {
        val server = FakeServer()
        val hen = MediaPhone(server, "hen", this, 1, screen = 1440)
        val dana = MediaPhone(server, "dana", this, 2, screen = 1080)
        hen.controller.start()
        dana.controller.start()
        advanceUntilIdle()
        val (code, _) = hen.messaging.newPairCode()
        dana.messaging.pairWith(code, verified = true)
        advanceUntilIdle()
        assertTrue(hen.messaging.accept("dana"))
        advanceUntilIdle()
        return Triple(server, hen, dana)
    }

    @Test
    fun picturesGoWithAPreviewForEachScreenAndOpenOnTheOtherPhone() = runTest {
        val (server, hen, dana) = paired()
        // Each phone told the other its screen when it connected (About).
        hen.messaging.shareAbout()
        dana.messaging.shareAbout()
        advanceUntilIdle()
        assertEquals(1080, hen.settings.contactScreens["dana"])

        val sent = hen.work.async { hen.messaging.shareMedia("dana", listOf("a", "gone", "b"), "the sea") }
        advanceUntilIdle()
        val message = sent.await().single()
        assertEquals(MessageStatus.DELIVERED, hen.store.message(message.id)?.status, "dana's phone has it")
        assertEquals(listOf(listOf(1080, 1440), listOf(1080, 1440)), (hen.messaging.media.preparer as FakePreparer).asked, "dana's and hen's widths")
        assertEquals("the sea", message.body)
        assertEquals("content://media/a", hen.store.mediaSource(message.id, message.media!!.items[0].id), "the original stays where it is")

        // On the server: only sealed previews, never the picture's bytes or the original.
        val previews = message.media!!.items.flatMap { it.previews }
        assertEquals(4, previews.size)
        for (p in previews) {
            val kept = assertNotNull(server.files[p.blob])
            assertEquals(MediaCrypto.sealedSize(p.size), kept.size.toLong())
            assertTrue("preview".encodeToByteArray().let { needle -> kept.toList().windowed(needle.size).none { it == needle.toList() } })
        }

        // Dana's phone has the message, and shows the 1080 preview, fetched once and kept.
        val got = assertNotNull(dana.store.message(message.id))
        assertEquals(2, got.media?.items?.size)
        assertEquals("the sea", got.media?.caption)
        val item = got.media!!.items[0]
        assertEquals(true, item.hasPlace)
        assertEquals(1, item.originDevice)
        val picture = dana.work.async { dana.messaging.media.preview(message.id, item.id) }
        advanceUntilIdle()
        assertContentEquals("preview a 1080".encodeToByteArray(), picture.await())
        assertTrue(dana.messaging.media.hasPreview(message.id, item.id))

        // Dana clears the cache: it comes again, quietly. Hen's phone shows its own 1440 one from what it kept.
        dana.messaging.media.clear(sent = false)
        val again = dana.work.async { dana.messaging.media.preview(message.id, item.id) }
        advanceUntilIdle()
        assertContentEquals("preview a 1080".encodeToByteArray(), again.await())
        server.files.clear()
        val own = hen.work.async { hen.messaging.media.preview(message.id, item.id) }
        advanceUntilIdle()
        assertContentEquals("preview a 1440".encodeToByteArray(), own.await())
        hen.stop()
        dana.stop()
    }

    @Test
    fun aDraftUploadsWhileWrittenAndGoesOnSend() = runTest {
        val (server, hen, dana) = paired()
        val draft = hen.work.async { hen.messaging.draftMedia("dana", listOf("a", "b")) }
        advanceUntilIdle()
        val drafts = draft.await()
        assertEquals(1, drafts.size)
        assertNull(hen.store.message(drafts[0].id), "a draft isn't a message yet")
        val previews = drafts[0].media!!.items.flatMap { it.previews }.map { it.blob }
        assertTrue(previews.all { it in server.files }, "its previews go up while the caption is written")
        val unread = dana.store.message(drafts[0].id)
        assertNull(unread, "but nothing reaches Dana before Send")
        val sent = hen.work.async { hen.messaging.sendDraft(drafts, " the sea ") }
        advanceUntilIdle()
        val message = sent.await().single()
        assertEquals("the sea", message.media?.caption)
        assertEquals("the sea", dana.store.message(message.id)?.media?.caption)
        assertTrue(hen.messaging.media.uploads.value.isEmpty())

        // A dropped draft leaves nothing behind.
        val other = hen.work.async { hen.messaging.draftMedia("dana", listOf("c")) }
        advanceUntilIdle()
        val dropped = other.await()
        val blob = dropped[0].media!!.items[0].previews[0].blob
        assertNotNull(hen.files.read(blob))
        assertTrue(blob in server.files)
        hen.messaging.media.drop(dropped)
        advanceUntilIdle()
        assertNull(hen.files.read(blob))
        assertTrue(blob !in server.files, "a dropped draft's previews are deleted from the server")
        hen.stop()
        dana.stop()
    }

    @Test
    fun aChangedPreviewOnTheServerIsRefused() = runTest {
        val (server, hen, dana) = paired()
        val sent = hen.work.async { hen.messaging.shareMedia("dana", listOf("a"), "") }
        advanceUntilIdle()
        val message = sent.await().single()
        assertEquals("📷", message.body, "older apps see what it is")
        val item = message.media!!.items.single()
        val blob = PreviewSpec.pick(item.previews, 1080)!!.blob
        server.files[blob] = server.files[blob]!!.copyOf().also { it[3] = (it[3] + 1).toByte() }
        val picture = dana.work.async { dana.messaging.media.preview(message.id, item.id) }
        advanceUntilIdle()
        assertNull(picture.await())
        hen.stop()
        dana.stop()
    }
}

class MediaOriginalTest {
    private suspend fun TestScope.paired(): Triple<FakeServer, MediaPhone, MediaPhone> {
        val server = FakeServer()
        val hen = MediaPhone(server, "hen", this, 1, screen = 1440)
        val dana = MediaPhone(server, "dana", this, 2, screen = 1080)
        hen.controller.start()
        dana.controller.start()
        advanceUntilIdle()
        val (code, _) = hen.messaging.newPairCode()
        dana.messaging.pairWith(code, verified = true)
        advanceUntilIdle()
        assertTrue(hen.messaging.accept("dana"))
        advanceUntilIdle()
        return Triple(server, hen, dana)
    }

    private suspend fun TestScope.shared(hen: MediaPhone): Pair<String, String> {
        val sent = hen.work.async { hen.messaging.shareMedia("dana", listOf("a"), "") }
        advanceUntilIdle()
        val message = sent.await().single()
        return message.id to message.media!!.items.single().id
    }

    @Test
    fun openingAPictureFetchesItsUntouchedOriginalFromThePhoneThatSharedIt() = runTest {
        val (server, hen, dana) = paired()
        val (message, item) = shared(hen)
        val preparer = hen.messaging.media.preparer as FakePreparer
        assertEquals(0, preparer.opened, "nothing but previews went")

        dana.messaging.media.openOriginal(message, item)
        advanceUntilIdle()
        val state = dana.messaging.media.originals.value["$message/$item"]
        assertTrue(state is OriginalState.Ready, "got $state")
        val blob = state.path.removePrefix("memory:")
        assertContentEquals(originalOf("a"), dana.files.originals[blob])
        // On the server: sealed, kept media_days, and not the picture's bytes.
        val onServer = assertNotNull(server.files[blob])
        assertEquals(MediaCrypto.sealedSize(originalOf("a").size.toLong()), onServer.size.toLong())
        assertEquals(true, server.kept[blob])

        // Opened again: here already, nothing asked. On hen's own phone: where it is.
        dana.messaging.media.openOriginal(message, item)
        advanceUntilIdle()
        assertEquals(1, preparer.opened)
        hen.messaging.media.openOriginal(message, item)
        advanceUntilIdle()
        assertEquals(OriginalState.Ready("content://media/a", local = true), hen.messaging.media.originals.value["$message/$item"])

        // Dana's cache cleared: fetched again from the server, without asking hen's phone.
        dana.messaging.media.clear(sent = false)
        dana.messaging.media.openOriginal(message, item)
        advanceUntilIdle()
        assertTrue(dana.messaging.media.originals.value["$message/$item"] is OriginalState.Ready)
        assertEquals(1, preparer.opened)
        hen.stop()
        dana.stop()
    }

    @Test
    fun aFileChangedOrGoneSinceItWasSharedDoesNotCome() = runTest {
        val (server, hen, dana) = paired()
        val (message, item) = shared(hen)
        val preparer = hen.messaging.media.preparer as FakePreparer
        val before = server.files.keys.toSet()
        preparer.files["content://media/a"] = originalOf("a").copyOf().also { it[100] = (it[100] + 1).toByte() }
        dana.messaging.media.openOriginal(message, item)
        advanceUntilIdle()
        assertEquals(OriginalState.Changed, dana.messaging.media.originals.value["$message/$item"])
        assertEquals(before, server.files.keys, "what was uploaded of it is deleted")

        preparer.files.remove("content://media/a")
        dana.messaging.media.openOriginal(message, item)
        advanceUntilIdle()
        assertEquals(OriginalState.Gone, dana.messaging.media.originals.value["$message/$item"])
        hen.stop()
        dana.stop()
    }
}

class PreviewSpecTest {
    @Test
    fun oneWidthPerScreenNoWiderThanThePicture() {
        assertEquals(listOf(1080, 1440), PreviewSpec.widths(listOf(1440, 1080, 1080), 4000))
        assertEquals(listOf(800), PreviewSpec.widths(listOf(1440, 1080), 800))
        assertEquals(listOf(1080), PreviewSpec.widths(emptyList(), 4000))
        assertEquals(listOf(1080, 1440, 2160), PreviewSpec.widths(listOf(720, 1080, 1440, 2160), 4000))
        assertEquals(3000, PreviewSpec.heightAt(4000, 3000, 4000))
        assertEquals(810, PreviewSpec.heightAt(4000, 3000, 1080))
    }
}
