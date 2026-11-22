package com.chencohen.ptt.relay

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The frames as push-to-talk-server-go's own Go code (proto/pttv1, google.golang.org/protobuf) encodes them, so a
 * change to ptt.proto that isn't made here too fails. The hex was printed by a Go test marshalling the same values.
 */
class FramesTest {
    @Test
    fun decodesAnEnvelopeTheServerEncoded() {
        val frame = Frames.decode(hex("0a400a203031323334353637383961626364656630313233343536373839616263646566120464616e611802220368656e280130013a04010203ff40f3818081a034"))
        val envelope = assertIs<EnvelopeFrame>(frame?.kind).envelope
        assertEquals("0123456789abcdef0123456789abcdef", envelope.id)
        assertEquals("dana", envelope.senderUser)
        assertEquals(2, envelope.senderDevice)
        assertEquals("hen", envelope.recipientUser)
        assertEquals(1, envelope.recipientDevice)
        assertEquals(EnvelopeType.PREKEY_MESSAGE, envelope.type)
        assertContentEquals(byteArrayOf(1, 2, 3, -1), envelope.ciphertext)
        assertEquals(1795298427123L, envelope.serverTimestamp)
    }

    @Test
    fun decodesAPairedEnvelopeWithNoCiphertext() {
        val envelope = assertIs<EnvelopeFrame>(Frames.decode(hex("0a130a026162120464616e61220368656e28013006"))?.kind).envelope
        assertEquals(EnvelopeType.PAIRED, envelope.type)
        assertEquals("dana", envelope.senderUser)
        assertEquals(0, envelope.ciphertext.size)
    }

    @Test
    fun decodesSendResults() {
        val stale = assertIs<SendResultFrame>(Frames.decode(hex("22330a026331100322120a0368656e1205080110e12112040803106322060a0464616e612a0f64657669636573206368616e676564"))?.kind).sendResult
        assertEquals("c1", stale.clientId)
        assertEquals(SendStatus.STALE_DEVICES, stale.status)
        assertEquals(listOf(UserDevices("hen", listOf(ActiveDevice(1, 4321), ActiveDevice(3, 99))), UserDevices("dana")), stale.devices)
        assertEquals("devices changed", stale.detail)

        val accepted = assertIs<SendResultFrame>(Frames.decode(hex("220c0a02633210011a01611a0162"))?.kind).sendResult
        assertEquals(SendStatus.ACCEPTED, accepted.status)
        assertEquals(listOf("a", "b"), accepted.envelopeIds)
    }

    @Test
    fun decodesQueueEmptyPingAndPong() {
        assertIs<QueueEmptyFrame>(Frames.decode(hex("3a00"))?.kind)
        assertEquals(1L shl 40, assertIs<PongFrame>(Frames.decode(hex("320708808080808020"))?.kind).pong.id)
        assertEquals(7L, assertIs<PingFrame>(Frames.decode(hex("2a020807"))?.kind).ping.id)
    }

    @Test
    fun encodesAsTheServerDoes() {
        assertEquals("12060a01780a0179", Frames.encode(Frame(AckFrame(Ack(listOf("x", "y"))))).toHex())
        assertEquals("2a020807", Frames.encode(Frame(PingFrame(Ping(7)))).toHex())
        val send = Send(
            clientId = "c3",
            envelopes = listOf(
                Envelope(recipientUser = "hen", recipientDevice = 1, type = EnvelopeType.PREKEY_MESSAGE, ciphertext = byteArrayOf(9, 8), routingToken = "tok", recipientRegistrationId = 4321),
                Envelope(recipientUser = "dana", recipientDevice = 2, type = EnvelopeType.MESSAGE, ciphertext = byteArrayOf(7)),
            ),
        )
        assertEquals(
            "1a2a0a0263331215220368656e280130013a0209084a03746f6b50e121120d220464616e61280230023a0107",
            Frames.encode(Frame(SendFrame(send))).toHex(),
        )
    }

    @Test
    fun skipsWhatItDoesNotKnow() {
        // An envelope with a field 15 added by a newer server: the field is skipped.
        val frame = Frames.decode(hex("0a080a0261627a026869"))
        assertEquals("ab", assertIs<EnvelopeFrame>(frame?.kind).envelope.id)
        assertNull(Frames.decode(byteArrayOf(0x0a, 0x7f)))
    }
}

private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

class FramesEncodeTest {
    @Test
    fun encodesAnEmptyQueueEmpty() {
        assertEquals("3a00", Frames.encode(Frame(QueueEmptyFrame(QueueEmpty()))).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
    }
}
