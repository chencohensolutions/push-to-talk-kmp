package com.chencohen.ptt.media

import com.chencohen.ptt.messaging.MediaPart
import com.chencohen.ptt.messaging.MediaRequestContent
import com.chencohen.ptt.messaging.Content
import com.chencohen.ptt.messaging.HeldOriginal
import com.chencohen.ptt.crypto.DeviceAddress
import com.chencohen.ptt.discovery.KeptDiscovery
import com.chencohen.ptt.messaging.FakeCryptoEngine
import com.chencohen.ptt.messaging.FakeServer
import com.chencohen.ptt.messaging.MediaContent
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

    override suspend fun crop(source: String, crop: com.chencohen.ptt.messaging.CropContent, widths: (croppedWidth: Int) -> List<Int>): PreparedCrop? {
        if ((files[source] ?: files["content://media/$source"]) == null) return null
        val height = 3000 * crop.kept / com.chencohen.ptt.messaging.CropContent.WHOLE
        return PreparedCrop(widths(4000).map { PreparedPreview(it, PreviewSpec.heightAt(4000, height, it), "crop $source ${crop.top} ${crop.bottom} $it".encodeToByteArray()) })
    }

    override suspend fun sizeOf(source: String): Long = (files[source] ?: files["content://media/$source"])?.size?.toLong() ?: -1

    override suspend fun open(source: String): OriginalReader? {
        val bytes = files[source] ?: files["content://media/$source"] ?: return null
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
    fun picturesSentFirstComeQuietlyAndTheCaptionNotifies() = runTest {
        val (_, hen, dana) = paired()
        val notified = mutableListOf<String>()
        dana.messaging.notifier = object : com.chencohen.ptt.messaging.MessageNotifier {
            override fun incoming(contact: com.chencohen.ptt.messaging.Contact, unread: List<com.chencohen.ptt.messaging.TextMessage>) {
                notified += unread.joinToString("|") { it.body }
            }

            override fun read(user: String) {}
        }
        val draft = hen.work.async { hen.messaging.draftMedia("dana", listOf("a")) }
        advanceUntilIdle()
        val first = hen.messaging.sendPicturesFirst(draft.await()).single()
        advanceUntilIdle()
        val got = dana.store.message(first.id)
        assertNotNull(got, "the pictures are there")
        assertEquals(true, got.media?.quiet)
        assertTrue(notified.isEmpty(), "but no notification yet")

        val sent = hen.work.async { hen.messaging.commitCaption(first.id, " the sea ") }
        advanceUntilIdle()
        assertTrue(sent.await())
        assertEquals("the sea", dana.store.message(first.id)?.media?.caption)
        assertEquals(false, dana.store.message(first.id)?.media?.quiet)
        assertEquals(listOf("the sea"), notified, "the caption notifies")
        assertEquals(false, hen.store.message(first.id)?.media?.quiet)
        hen.stop()
        dana.stop()
    }

    @Test
    fun wordsAboveAPictureGoWithIt() = runTest {
        val (_, hen, dana) = paired()
        val draft = hen.work.async { hen.messaging.draftMedia("dana", listOf("a")) }
        advanceUntilIdle()
        val drafts = draft.await()
        val item = drafts.single().media!!.items.single()
        val sent = hen.work.async { hen.messaging.sendDraft(drafts, "the end", texts = mapOf(item.id to " first the sea ")) }
        advanceUntilIdle()
        val message = sent.await().single()
        assertEquals("first the sea", dana.store.message(message.id)?.media?.items?.single()?.text)
        assertEquals("the end", dana.store.message(message.id)?.media?.caption)
        hen.stop()
        dana.stop()
    }

    @Test
    fun aDraftsPictureIsCroppedBeforeItGoes() = runTest {
        val (server, hen, dana) = paired()
        val draft = hen.work.async { hen.messaging.draftMedia("dana", listOf("a", "b")) }
        advanceUntilIdle()
        val drafts = draft.await()
        val item = drafts.single().media!!.items[0]
        val old = item.previews.map { it.blob }
        val crop = com.chencohen.ptt.messaging.CropContent(top = 2_000, bottom = 3_000, focusX = 6_200, focusY = 4_000, hasFocus = true)
        val cropped = hen.work.async { hen.messaging.recrop(drafts[0].id, item.id, crop) }
        advanceUntilIdle()
        val now = assertNotNull(cropped.await())
        assertEquals(crop, now.crop)
        assertEquals(1500, now.shownHeight, "the chat shows half its height")
        assertTrue(now.previews.none { it.blob in old }, "new previews")
        assertTrue(now.previews.all { it.blob in server.files }, "and they go up")
        assertTrue(old.none { it in server.files } && old.all { hen.files.read(it) == null }, "the old ones are gone")
        // Sent from the list the screen held, made before the crop.
        val sent = hen.work.async { hen.messaging.sendDraft(drafts, "") }
        advanceUntilIdle()
        val message = sent.await().single()
        val there = dana.store.message(message.id)!!.media!!.items[0]
        assertEquals(crop, there.crop)
        assertEquals(now.previews.map { it.blob }, there.previews.map { it.blob })
        assertEquals(3000, there.height, "the original's size stays")
        assertNull(dana.store.message(message.id)!!.media!!.items[1].crop, "the other one is whole")
        val bytes = dana.work.async { dana.messaging.media.preview(message.id, there.id) }
        advanceUntilIdle()
        assertTrue(bytes.await()!!.decodeToString().startsWith("crop "), "their preview is the cropped one")
        hen.stop()
        dana.stop()
    }

    @Test
    fun aSentPicturesCompositionChangesQuietlyOnTheirPhone() = runTest {
        val (server, hen, dana) = paired()
        val sent = hen.work.async { hen.messaging.shareMedia("dana", listOf("a"), "the sea") }
        advanceUntilIdle()
        val message = sent.await().single()
        val item = message.media!!.items.single()
        val shown = dana.work.async { dana.messaging.media.preview(message.id, item.id) }
        advanceUntilIdle()
        assertNotNull(shown.await())
        val old = item.previews.map { it.blob }
        val crop = com.chencohen.ptt.messaging.CropContent(top = 1_000)
        val cropped = hen.work.async { hen.messaging.recrop(message.id, item.id, crop) }
        advanceUntilIdle()
        val now = assertNotNull(cropped.await())
        val there = dana.store.message(message.id)!!.media!!.items.single()
        assertEquals(crop, there.crop)
        assertEquals(now.previews.map { it.blob }, there.previews.map { it.blob })
        assertTrue(old.none { dana.files.read(it) != null }, "the old previews are gone from their phone")
        assertTrue(old.none { it in server.files }, "and from the server")
        assertEquals("the sea", dana.store.message(message.id)?.media?.caption)
        val bytes = dana.work.async { dana.messaging.media.preview(message.id, item.id) }
        advanceUntilIdle()
        assertTrue(bytes.await()!!.decodeToString().startsWith("crop "))

        // Only the sender can (takeEdit checks the author), and an edit never changes the item's key.
        val forged = there.copy(key = ByteArray(32) { 7 }, crop = com.chencohen.ptt.messaging.CropContent(top = 5_000))
        val theirs = dana.store.message(message.id)!!
        dana.messaging.media.takeRecrop(theirs, forged)
        assertContentEquals(item.key, dana.store.message(message.id)!!.media!!.items.single().key, "the item's key stays")
        val mine = dana.work.async { dana.messaging.recrop(message.id, item.id, null) }
        advanceUntilIdle()
        assertNull(mine.await(), "not theirs to crop")

        // Back to the whole picture.
        val whole = hen.work.async { hen.messaging.recrop(message.id, item.id, null) }
        advanceUntilIdle()
        assertNull(assertNotNull(whole.await()).crop)
        assertNull(dana.store.message(message.id)!!.media!!.items.single().crop)
        hen.stop()
        dana.stop()
    }

    @Test
    fun aCropKeepsATenthAtLeast() {
        val c = com.chencohen.ptt.messaging.CropContent(top = 8_000, bottom = 5_000).clean()!!
        assertEquals(8_000, c.top)
        assertEquals(1_000, c.bottom)
        assertNull(com.chencohen.ptt.messaging.CropContent().clean(), "nothing trimmed and no point: no crop")
        assertEquals(0.5f to 0.25f, com.chencohen.ptt.messaging.CropContent(top = 2_000, bottom = 0, focusX = 5_000, focusY = 4_000, hasFocus = true).focusInCrop)
    }

    @Test
    fun aSentCaptionIsEditedQuietly() = runTest {
        val (_, hen, dana) = paired()
        val sent = hen.work.async { hen.messaging.shareMedia("dana", listOf("a"), "the sea") }
        advanceUntilIdle()
        val message = sent.await().single()
        val edited = hen.work.async { hen.messaging.editText(message.id, " the red sea ") }
        advanceUntilIdle()
        assertTrue(edited.await())
        assertEquals("the red sea", hen.store.message(message.id)?.media?.caption)
        val there = dana.store.message(message.id)
        assertEquals("the red sea", there?.media?.caption, "the caption changes on their phone")
        assertNotNull(there?.editedAt)
        assertEquals(1, there?.media?.items?.size, "the pictures stay")
        val emptied = hen.work.async { hen.messaging.editText(message.id, "") }
        advanceUntilIdle()
        assertTrue(emptied.await(), "a caption may be taken off")
        assertEquals("", dana.store.message(message.id)?.media?.caption)
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
        // Dana's phone fetched it as it came (its receipt waits for that): gone from her phone, it is fetched again.
        assertNotNull(dana.files.read(blob))
        dana.files.delete(blob)
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
    fun aPreviewTheServerRemovedIsPutBackByThePhoneThatSharedIt() = runTest {
        val (server, hen, dana) = paired()
        val (message, item) = shared(hen)
        val part = PreviewSpec.pick(hen.store.message(message)!!.media!!.items.single().previews, 1080)!!
        val sealed = server.files.getValue(part.blob)
        // Removed on the server to make room, and not on dana's phone (a new install, or cleared).
        server.files.remove(part.blob)
        dana.files.delete(part.blob)
        val got = dana.work.async { dana.messaging.media.preview(message, item) }
        advanceUntilIdle()
        assertNotNull(got.await(), "hen's phone put it back and dana opened it")
        assertContentEquals(sealed, server.files[part.blob], "the same sealed file, so the key in the message opens it")
        assertTrue("dana" in server.shares[part.blob].orEmpty())

        // Gone again at once: not asked again within the hour.
        server.files.remove(part.blob)
        dana.files.delete(part.blob)
        val again = dana.work.async { dana.messaging.media.preview(message, item) }
        advanceUntilIdle()
        assertNull(again.await(), "asked at most once an hour")
        assertNull(server.files[part.blob])
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
        // No longer readable on hen's phone (the gallery's pass ended): said, not shown as ready.
        val kept = preparer.files.remove("content://media/a")!!
        hen.messaging.media.openOriginal(message, item)
        advanceUntilIdle()
        assertEquals(OriginalState.Unreadable, hen.messaging.media.originals.value["$message/$item"])
        assertTrue(hen.messaging.media.keeping(message, item)!!.sourceUnreadable)
        // Readable again (access to all photos given): ready, and told.
        preparer.files["content://media/a"] = kept
        hen.messaging.media.openOriginal(message, item)
        advanceUntilIdle()
        assertEquals(OriginalState.Ready("content://media/a", local = true), hen.messaging.media.originals.value["$message/$item"])
        assertEquals(HeldOriginal.SOURCE, dana.messaging.media.holdersOf(message, item).single { it.user == "hen" }.original)

        // Dana's cache cleared: fetched again from the server, without asking hen's phone.
        val opened = preparer.opened
        dana.messaging.media.clear(sent = false)
        dana.messaging.media.openOriginal(message, item)
        advanceUntilIdle()
        assertTrue(dana.messaging.media.originals.value["$message/$item"] is OriginalState.Ready)
        assertEquals(opened, preparer.opened)
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

    @Test
    fun everyRequestForAnOriginalIsLoggedAndOnlyMineInThatChatAreAnswered() = runTest {
        val (_, hen, dana) = paired()
        val (message, item) = shared(hen)
        val preparer = hen.messaging.media.preparer as FakePreparer
        // Dana's own picture, to hen.
        val danas = dana.work.async { dana.messaging.shareMedia("hen", listOf("b"), "") }
        advanceUntilIdle()
        val theirs = danas.await().single()
        val opened = preparer.opened
        fun ask(chat: String, author: String, messageId: String, itemId: String) {
            hen.messaging.media.took(chat, author, 1, Content(mediaRequest = MediaRequestContent(messageId, itemId, MediaPart.ORIGINAL)))
        }
        ask("dana", "dana", "no-such-message", "x")
        ask("erin", "erin", message, item) // hen's picture to dana, asked for from another chat
        ask("dana", "dana", theirs.id, theirs.media!!.items.single().id) // dana's own, not hen's
        ask("dana", "dana", message, "no-such-item")
        advanceUntilIdle()
        assertEquals(opened, preparer.opened, "no file was read for a refused request")
        ask("dana", "dana", message, item)
        advanceUntilIdle()
        ask("dana", "dana", message, item)
        advanceUntilIdle()
        hen.messaging.deleteMessage(message)
        advanceUntilIdle()
        ask("dana", "dana", message, item)
        advanceUntilIdle()
        val log = hen.messaging.media.originalRequests()
        assertEquals(
            listOf(RequestResult.UNKNOWN, RequestResult.OTHER_CHAT, RequestResult.NOT_MINE, RequestResult.UNKNOWN, RequestResult.SENT, RequestResult.ON_SERVER, RequestResult.UNKNOWN),
            log.map { it.result },
        )
        assertEquals(listOf("dana", "erin", "dana", "dana", "dana", "dana", "dana"), log.map { it.user })
        assertEquals(6, hen.messaging.media.originalRequests("dana").size)
        hen.stop()
        dana.stop()
    }

    @Test
    fun eachPhoneTellsWhatItHoldsAndTheOnlyCopyIsKept() = runTest {
        val (_, hen, dana) = paired()
        val (message, item) = shared(hen)
        dana.messaging.media.openOriginal(message, item)
        advanceUntilIdle()
        val blob = (dana.messaging.media.originals.value["$message/$item"] as OriginalState.Ready).path.removePrefix("memory:")
        // Each phone told the chat what it holds: hen the shared file, dana a copy (in the cache: hen has the file).
        assertEquals(HeldOriginal.COPY, hen.messaging.media.holdersOf(message, item).single { it.user == "dana" }.original)
        assertEquals(HeldOriginal.SOURCE, dana.messaging.media.holdersOf(message, item).single { it.user == "hen" }.original)
        assertTrue(blob !in dana.files.keptOriginals)
        val info = assertNotNull(dana.messaging.mediaKeeping(message, item))
        assertEquals(HeldOriginal.SOURCE, info.peers.single { it.user == "hen" }.held)
        assertTrue(info.originalElsewhere)

        // Hen's file is deleted: hen's phone finds it, tells the chat, and dana's copy is now the only one: kept.
        (hen.messaging.media.preparer as FakePreparer).files.remove("content://media/a")
        hen.messaging.media.checkSources()
        advanceUntilIdle()
        assertEquals(HeldOriginal.NONE, dana.messaging.media.holdersOf(message, item).single { it.user == "hen" }.original)
        assertTrue(blob in dana.files.keptOriginals)
        assertEquals(HeldOriginal.KEPT, hen.messaging.media.holdersOf(message, item).single { it.user == "dana" }.original)

        // Clearing keeps it, the whole cache or the chat's.
        dana.messaging.media.clear(sent = false)
        assertNotNull(dana.files.originals[blob])
        val storage = dana.messaging.media.clearChat("hen")
        assertNotNull(dana.files.originals[blob])
        assertEquals(1, storage.onlyCount)
        assertEquals(0, storage.lost)
        assertEquals(1, hen.messaging.media.chatStorage("dana").let { if (it.lost == 0) 1 else 0 }, "dana's copy counts as the original")
        hen.stop()
        dana.stop()
    }

    @Test
    fun aChatsStorageClearsWhatCanComeAgain() = runTest {
        val (_, hen, dana) = paired()
        val (message, item) = shared(hen)
        dana.messaging.media.preview(message, item)
        dana.messaging.media.openOriginal(message, item)
        advanceUntilIdle()
        val before = dana.messaging.media.chatStorage("hen")
        assertEquals(1, before.copiesCount)
        assertTrue(before.gotCount >= 1)
        val after = dana.messaging.media.clearChat("hen")
        assertEquals(0, after.copiesCount, "hen's phone has the file")
        assertEquals(0, after.gotCount, "the preview is still on the server")
        // Both come again.
        assertNotNull(dana.messaging.media.preview(message, item))
        hen.stop()
        dana.stop()
    }

    @Test
    fun theCacheKeepsToItsLimitByLastReadAndClearsWhenThePhoneRunsLow() = runTest {
        val (_, hen, dana) = paired()
        val shared = listOf("a", "b", "c").map { source ->
            val sent = hen.work.async { hen.messaging.shareMedia("dana", listOf(source), "") }
            advanceUntilIdle()
            sent.await().single().let { it.id to it.media!!.items.single().id }
        }
        for ((m, i) in shared) dana.messaging.media.preview(m, i)
        for ((m, i) in shared) dana.messaging.media.openOriginal(m, i)
        advanceUntilIdle()
        // The first picture's original opened again: read last now.
        dana.messaging.media.openOriginal(shared[0].first, shared[0].second)
        advanceUntilIdle()
        // Each part counted as 400 MB: 3 previews and 3 originals, 2.4 GB, within the default 10 GB.
        dana.files.claimedBytes = 400L shl 20
        assertEquals(6, dana.files.cached().size)
        assertEquals(0, dana.messaging.media.trimCache())
        // 1 GB: the previews go first (read longest ago), then the original of b, the longest unread one.
        dana.messaging.setTalkSettings(dana.messaging.state.value.talkSettings.copy(mediaCacheGb = 0))
        advanceUntilIdle()
        assertEquals(1, dana.messaging.state.value.talkSettings.mediaCacheGb, "1 GB at least")
        val left = dana.files.cached()
        assertEquals(2, left.size, "$left")
        assertTrue(left.all { it.original })
        assertNull(dana.messaging.media.originals.value["${shared[1].first}/${shared[1].second}"], "b's copy is gone")
        assertTrue(dana.messaging.media.originals.value["${shared[0].first}/${shared[0].second}"] is OriginalState.Ready)
        // Bigger now: c goes (a was read after it).
        dana.files.claimedBytes = 600L shl 20
        dana.messaging.media.trimCache()
        assertEquals(1, dana.files.cached().size, "within 1 GB again")
        assertTrue(dana.messaging.media.originals.value["${shared[0].first}/${shared[0].second}"] is OriginalState.Ready)
        // Under 2 GB free on the phone: all of it goes, and all of it comes again.
        dana.files.free = 1L shl 30
        dana.messaging.media.trimCache()
        assertTrue(dana.files.cached().isEmpty())
        for ((m, i) in shared) assertNotNull(dana.messaging.media.preview(m, i))
        // Turned off: kept again, however little is free.
        dana.messaging.setTalkSettings(dana.messaging.state.value.talkSettings.copy(clearCacheWhenLow = false, mediaCacheGb = 10))
        advanceUntilIdle()
        assertEquals(0, dana.messaging.media.trimCache())
        assertEquals(3, dana.files.cached().size)
        hen.stop()
        dana.stop()
    }

    @Test
    fun onWiFiAnOriginalGoesUpRightAfterSharingKeptForever() = runTest {
        val (server, hen, dana) = paired()
        hen.messaging.unmetered = { true }
        val (message, item) = shared(hen)
        val preparer = hen.messaging.media.preparer as FakePreparer
        assertEquals(1, preparer.opened, "read once, to upload it")
        val sent = hen.store.message(message)!!.media!!
        assertEquals(MediaContent.FOREVER, sent.keepDays, "kept on the server forever")
        for (part in sent.items.single().previews) assertEquals(true, server.kept[part.blob])
        val original = server.kept.keys.single { k -> sent.items.single().previews.none { it.blob == k } }
        // Dana opens it straight from the server: hen's phone isn't asked.
        dana.messaging.media.openOriginal(message, item)
        advanceUntilIdle()
        assertTrue(dana.messaging.media.originals.value["$message/$item"] is OriginalState.Ready)
        assertContentEquals(originalOf("a"), dana.files.originals[original])
        assertEquals(1, preparer.opened)
        assertTrue(hen.messaging.media.originalRequests().isEmpty(), "nothing was asked")
        // Off Wi-Fi: it waits, and goes once on Wi-Fi.
        hen.messaging.unmetered = { false }
        val (second, _) = hen.work.async { hen.messaging.shareMedia("dana", listOf("b"), "") }.let { advanceUntilIdle(); it.await().single().let { m -> m.id to m.media!!.items.single().id } }
        assertEquals(1, preparer.opened)
        hen.messaging.unmetered = { true }
        hen.messaging.media.uploadPending()
        advanceUntilIdle()
        assertEquals(2, preparer.opened)
        hen.stop()
        dana.stop()
    }

    @Test
    fun anOriginalThatIsntTheOneSharedIsThrownAway() = runTest {
        val (server, hen, dana) = paired()
        val (message, item) = shared(hen)
        // Hen's file changes after the original went up, sealed with the right key (anyone in the chat holds it).
        dana.messaging.media.openOriginal(message, item)
        advanceUntilIdle()
        val ready = dana.messaging.media.originals.value["$message/$item"] as OriginalState.Ready
        val blob = ready.path.removePrefix("memory:")
        val attachment = dana.store.message(message)!!.media!!.items.single()
        val other = ByteArray(originalOf("a").size) { 7 }
        val partKey = MediaCrypto.partKey(attachment.key, blob, MediaCrypto.Part.ORIGINAL)
        val chunks = MediaCrypto.chunkCount(other.size.toLong())
        server.files[blob] = (0 until chunks).fold(ByteArray(0)) { acc, i ->
            val from = (i * MediaCrypto.CHUNK).toInt()
            acc + MediaCrypto.sealChunk(partKey, blob, i, i == chunks - 1, other.copyOfRange(from, minOf(other.size, from + MediaCrypto.CHUNK)))
        }
        dana.messaging.media.clear(sent = false)
        dana.messaging.media.openOriginal(message, item)
        advanceUntilIdle()
        assertEquals(OriginalState.Changed, dana.messaging.media.originals.value["$message/$item"])
        assertTrue(dana.files.originals.isEmpty(), "not kept")
        hen.stop()
        dana.stop()
    }

    @Test
    fun everySealTakesANewBlobId() = runTest {
        val (_, hen, _) = paired()
        val sent = hen.work.async { hen.messaging.shareMedia("dana", listOf("a", "b", "c"), "") }
        advanceUntilIdle()
        val blobs = sent.await().flatMap { m -> m.media!!.items.flatMap { it.previews.map { p -> p.blob } } }
        assertEquals(blobs.size, blobs.toSet().size)
        hen.stop()
    }

    @Test
    fun anOriginalStreamsFromTheServerFromAnyByteWithoutBeingKept() = runTest {
        val (_, hen, dana) = paired()
        val (message, item) = shared(hen)
        // Fetched once, so the server has it; then cleared from dana's phone.
        dana.messaging.media.openOriginal(message, item)
        advanceUntilIdle()
        dana.messaging.media.clear(sent = false)
        assertTrue(dana.files.originals.isEmpty())
        val from = MediaCrypto.CHUNK + 1000L
        val stream = assertNotNull(dana.messaging.media.streamOriginal(message, item, from))
        val whole = originalOf("a")
        assertEquals(whole.size - from, stream.length)
        val got = dana.work.async {
            var out = ByteArray(0)
            while (true) out += stream.read() ?: break
            out
        }
        advanceUntilIdle()
        assertContentEquals(whole.copyOfRange(from.toInt(), whole.size), got.await())
        stream.close()
        assertTrue(dana.files.originals.isEmpty(), "nothing kept")
        assertTrue(dana.files.partials.isEmpty())
        hen.stop()
        dana.stop()
    }

    @Test
    fun aPictureDeletedByItsSenderIsGoneForEveryone() = runTest {
        val (server, hen, dana) = paired()
        val (message, item) = shared(hen)
        dana.messaging.media.preview(message, item)
        dana.messaging.media.openOriginal(message, item)
        advanceUntilIdle()
        val parts = hen.store.message(message)!!.media!!.items.single().previews.map { it.blob }
        assertTrue(hen.messaging.deleteMessage(message))
        advanceUntilIdle()
        assertTrue(parts.none { it in server.files }, "its previews are gone from the server")
        assertTrue(dana.files.originals.isEmpty(), "dana's copy of the original is gone")
        assertTrue(parts.none { dana.files.has(it) }, "and the previews dana kept")
        assertNull(dana.store.message(message)?.media)
        assertNull(dana.messaging.media.originals.value["$message/$item"])
        hen.stop()
        dana.stop()
    }

    @Test
    fun aPictureSharedToAnotherChatIsStoredOnce() = runTest {
        val (server, hen, dana) = paired()
        val erin = MediaPhone(server, "erin", this, 3, screen = 1080)
        erin.controller.start()
        advanceUntilIdle()
        val (code, _) = hen.messaging.newPairCode()
        erin.messaging.pairWith(code, verified = true)
        advanceUntilIdle()
        assertTrue(hen.messaging.accept("erin"))
        advanceUntilIdle()

        val (toDana, danaItem) = shared(hen)
        dana.messaging.media.openOriginal(toDana, danaItem)
        advanceUntilIdle()
        val preparer = hen.messaging.media.preparer as FakePreparer
        val blobsBefore = server.files.keys.toSet()

        // The same picture to erin (picked again, so known by its size and SHA-256): the same sealed previews,
        // nothing made or uploaded again.
        val sent = hen.work.async { hen.messaging.shareMedia("erin", listOf("a"), "") }
        advanceUntilIdle()
        val toErin = sent.await().single()
        val erinItem = toErin.media!!.items.single()
        val first = hen.store.message(toDana)!!.media!!.items.single()
        assertEquals(first.previews.map { it.blob }, erinItem.previews.map { it.blob })
        assertTrue(erinItem.id != first.id)
        // Not dana's chat's item key: erin's message carries the key of each file it names, and only those.
        assertTrue(erinItem.key.isEmpty(), "the item key stays in dana's chat")
        assertTrue(erinItem.previews.all { it.key.size == MediaCrypto.KEY_BYTES })
        assertTrue(erin.store.message(toErin.id)!!.media!!.items.single().key.isEmpty())
        assertEquals(1, preparer.asked.size, "prepared once")
        assertEquals(blobsBefore, server.files.keys, "nothing new on the server")
        assertTrue(first.previews.all { "erin" in server.shares[it.blob].orEmpty() })
        assertNotNull(erin.messaging.media.preview(toErin.id, erinItem.id))
        val opened = preparer.opened

        // Erin's original is the one already on the server for dana: shared, not read from the file again.
        erin.messaging.media.openOriginal(toErin.id, erinItem.id)
        advanceUntilIdle()
        assertTrue(erin.messaging.media.originals.value["${toErin.id}/${erinItem.id}"] is OriginalState.Ready)
        assertEquals(opened, preparer.opened)
        assertEquals(blobsBefore, server.files.keys)
        assertContentEquals(originalOf("a"), erin.files.originals.values.single())

        // Deleted in dana's chat: gone for dana, still erin's.
        assertTrue(hen.messaging.deleteMessage(toDana))
        advanceUntilIdle()
        assertTrue(first.previews.all { it.blob in server.files }, "erin's chat still has them")
        assertTrue(first.previews.all { "dana" !in server.shares[it.blob].orEmpty() })
        assertTrue(first.previews.all { hen.files.has(it.blob) }, "hen's phone keeps what erin's message names")
        assertNull(dana.store.message(toDana)?.media)
        assertNotNull(erin.store.message(toErin.id)?.media)
        // A new crop in erin's chat: new previews with keys of their own, which dana's chat's key doesn't open.
        val crop = com.chencohen.ptt.messaging.CropContent(top = 1000, bottom = 9000)
        val recropped = assertNotNull(hen.work.async { hen.messaging.media.recrop(toErin.id, erinItem.id, crop) }.also { advanceUntilIdle() }.await())
        for (p in recropped.previews) {
            assertEquals(MediaCrypto.KEY_BYTES, p.key.size)
            val sealed = assertNotNull(server.files[p.blob])
            assertNull(MediaCrypto.openAll(MediaCrypto.partKey(first.key, p.blob, MediaCrypto.Part.PREVIEW), p.blob, sealed))
        }
        assertNotNull(erin.messaging.media.preview(toErin.id, erinItem.id))

        // And then in erin's: gone from the server.
        assertTrue(hen.messaging.deleteMessage(toErin.id))
        advanceUntilIdle()
        assertTrue(first.previews.none { it.blob in server.files })
        hen.stop()
        dana.stop()
        erin.stop()
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
