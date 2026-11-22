package com.chencohen.ptt.talk

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Talk's Opus on the phone's own codec, and clips played through AndroidTalkPlayer with catch-up. */
@RunWith(AndroidJUnit4::class)
class OpusCodecTest {
    private val rate = TalkAudioFormat.SAMPLE_RATE
    private val frameSamples = TalkAudioFormat.SAMPLES_PER_FRAME

    @Test
    fun sineEncodesToAPacketPerFrame() {
        val packets = encode(sine(rate))
        Log.i(TAG, "1 s of 440 Hz: ${packets.size} packets, ${packets.minOf { it.size }}..${packets.maxOf { it.size }} bytes")
        assertTrue("about 50 packets, got ${packets.size}", packets.size in 49..51)
        assertTrue("packets well under 200 bytes", packets.all { it.size < 200 })
        // 24 kbit/s is 60 bytes per 20 ms on average.
        val average = packets.sumOf { it.size }.toDouble() / packets.size
        assertTrue("average $average bytes", average in 30.0..100.0)
    }

    @Test
    fun sineDecodesToOneSecondAt440Hz() {
        val pcm = decode(encode(sine(rate)))
        Log.i(TAG, "decoded ${pcm.size} samples, rms ${rms(pcm)}")
        assertTrue("about 1 s, got ${pcm.size} samples", abs(pcm.size - rate) <= 2 * frameSamples)
        assertTrue("rms ${rms(pcm)}", rms(pcm) > 10 * Silence.THRESHOLD)
        val at440 = power(pcm, 440.0)
        val at1000 = power(pcm, 1000.0)
        val at200 = power(pcm, 200.0)
        Log.i(TAG, "power 440 Hz $at440, 1000 Hz $at1000, 200 Hz $at200")
        assertTrue("440 Hz dominates", at440 > 100 * at1000 && at440 > 100 * at200)
    }

    @Test
    fun silenceStaysSilent() {
        val pcm = decode(encode(ShortArray(rate)))
        assertTrue(abs(pcm.size - rate) <= 2 * frameSamples)
        assertTrue("rms ${rms(pcm)}", Silence.isSilent(pcm))
        pcm.toList().chunked(frameSamples).forEach { assertTrue(Silence.isSilent(it.toShortArray())) }
    }

    @Test
    fun decoderGivesEachPacketAtOnce() {
        val packets = encode(sine(rate))
        val decoder = OpusDecoder()
        try {
            val start = System.nanoTime()
            val perPacket = packets.map { packet -> decoder.decode(packet).sumOf { it.size } }
            val ms = (System.nanoTime() - start) / 1_000_000
            Log.i(TAG, "decode of ${packets.size} packets took $ms ms; samples per call $perPacket")
            // A backlog of 3 s must decode much faster than it plays, or catch-up can't work.
            assertTrue("decoding 1 s took $ms ms", ms < 300)
            assertEquals(frameSamples - 312, perPacket.first())
        } finally {
            decoder.release()
        }
    }

    @Test
    fun liveBacklogCatchesUp() {
        // 3 s of speech-like bursts: 300 ms of tone, 300 ms of pause.
        val audio = ShortArray(3 * rate)
        val tone = sine(audio.size)
        for (i in audio.indices) if ((i / (rate * 3 / 10)) % 2 == 0) audio[i] = tone[i]
        val packets = encode(audio)
        val result = play(packets, live = true, CatchUpMode.SHORTEN_PAUSES_AND_SPEED_UP)
        Log.i(TAG, "3 s live backlog played in ${result.elapsedMs} ms; last progress ${result.progress.lastOrNull()}")
        assertTrue("write took ${result.writeMs} ms", result.writeMs < 50)
        assertTrue("onDone fired", result.done)
        assertTrue("played in ${result.elapsedMs} ms, shorter than 3000", result.elapsedMs < 2_500)
        assertTrue("caught up to ${result.elapsedMs} ms, not cut short", result.elapsedMs > 1_000)
        val first = result.progress.first { it.second > 0 }
        assertTrue("first behind ${first.second} ms", first.second > 2_000)
        assertEquals(0L, result.progress.last().second)
    }

    @Test
    fun liveSpeechWithoutPausesSpeedsUp() {
        // No pause to shorten: only playing faster brings 3 s in under 3 s.
        val result = play(encode(sine(3 * rate)), live = true, CatchUpMode.SHORTEN_PAUSES_AND_SPEED_UP)
        Log.i(TAG, "3 s live tone played in ${result.elapsedMs} ms")
        assertTrue(result.done)
        assertTrue("played in ${result.elapsedMs} ms", result.elapsedMs < 2_850)
        assertTrue("played in ${result.elapsedMs} ms", result.elapsedMs > 2_300)
    }

    @Test
    fun liveWithCatchUpOffKeepsNormalSpeed() {
        val result = play(encode(sine(2 * rate)), live = true, CatchUpMode.OFF)
        Log.i(TAG, "2 s live, catch-up off, played in ${result.elapsedMs} ms")
        assertTrue(result.done)
        assertTrue("played in ${result.elapsedMs} ms", result.elapsedMs in 1_900..2_800)
    }

    @Test
    fun historyPlaysAtNormalSpeed() {
        val packets = encode(sine(rate))
        val result = play(packets, live = false, CatchUpMode.SHORTEN_PAUSES_AND_SPEED_UP)
        Log.i(TAG, "1 s from history played in ${result.elapsedMs} ms")
        assertTrue(result.done)
        assertTrue("played in ${result.elapsedMs} ms", result.elapsedMs in 900..1_800)
        val last = result.progress.last()
        assertTrue("played ${last.first} ms", abs(last.first - 1_000) <= 40)
    }

    @Test
    fun gapDoesNotEndALiveClip() {
        val half = encode(sine(rate / 2))
        val done = CountDownLatch(1)
        val playback = AndroidTalkPlayer().open(true, CatchUpMode.SHORTEN_PAUSES_AND_SPEED_UP, { _, _ -> }, { done.countDown() })
        playback.write(half)
        assertFalse("ended in the gap", done.await(1_500, TimeUnit.MILLISECONDS))
        val start = System.nanoTime()
        playback.write(half)
        playback.finish()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("the second half was played after the gap ($ms ms)", ms > 350)
    }

    @Test
    fun stopEndsAtOnce() {
        val packets = encode(sine(3 * rate))
        val done = CountDownLatch(1)
        var calls = 0
        val playback = AndroidTalkPlayer().open(false, CatchUpMode.OFF, { _, _ -> }, { calls++; done.countDown() })
        playback.write(packets)
        Thread.sleep(300)
        val start = System.nanoTime()
        playback.stop()
        assertTrue(done.await(1, TimeUnit.SECONDS))
        val ms = (System.nanoTime() - start) / 1_000_000
        Log.i(TAG, "stop to onDone: $ms ms")
        assertTrue("stop took $ms ms", ms < 300)
        playback.stop()
        playback.finish()
        Thread.sleep(200)
        assertEquals(1, calls)
    }

    @Test
    fun captureWithoutPermissionIsRefused() {
        // The app has no RECORD_AUDIO yet: start() must say no, not throw.
        val capture = AndroidTalkCapture()
        assertFalse(capture.start { })
        capture.stop()
    }

    private class Played(val done: Boolean, val elapsedMs: Long, val writeMs: Long, val progress: List<Pair<Long, Long>>)

    private fun play(packets: List<ByteArray>, live: Boolean, mode: CatchUpMode): Played {
        val done = CountDownLatch(1)
        val progress = java.util.Collections.synchronizedList(ArrayList<Pair<Long, Long>>())
        val start = System.nanoTime()
        val playback = AndroidTalkPlayer().open(live, mode, { played, behind -> progress += played to behind }, { done.countDown() })
        playback.write(packets)
        val writeMs = (System.nanoTime() - start) / 1_000_000
        playback.finish()
        val ok = done.await(10, TimeUnit.SECONDS)
        return Played(ok, (System.nanoTime() - start) / 1_000_000, writeMs, progress.toList())
    }

    private fun sine(samples: Int): ShortArray =
        ShortArray(samples) { (8_000 * sin(2 * PI * 440.0 * it / rate)).toInt().toShort() }

    private fun encode(pcm: ShortArray): List<ByteArray> {
        val encoder = OpusEncoder()
        try {
            val packets = ArrayList<ByteArray>()
            val perFrame = ArrayList<Int>()
            for (at in pcm.indices step frameSamples) {
                val got = encoder.encode(pcm.copyOfRange(at, minOf(at + frameSamples, pcm.size)), minOf(frameSamples, pcm.size - at))
                perFrame += got.size
                packets += got
            }
            val rest = encoder.finish()
            packets += rest
            Log.i(TAG, "encode: packets per frame $perFrame, then ${rest.size} at the end")
            return packets
        } finally {
            encoder.release()
        }
    }

    private fun decode(packets: List<ByteArray>): ShortArray {
        val decoder = OpusDecoder()
        try {
            val out = ArrayList<ShortArray>()
            packets.forEach { out += decoder.decode(it) }
            out += decoder.finish()
            val pcm = ShortArray(out.sumOf { it.size })
            var at = 0
            out.forEach { it.copyInto(pcm, at); at += it.size }
            return pcm
        } finally {
            decoder.release()
        }
    }

    private fun rms(pcm: ShortArray): Double = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)

    /** Goertzel: the signal's power at one frequency. */
    private fun power(pcm: ShortArray, hz: Double): Double {
        val coeff = 2 * cos(2 * PI * hz / rate)
        var s1 = 0.0
        var s2 = 0.0
        for (x in pcm) {
            val s = x + coeff * s1 - s2
            s2 = s1
            s1 = s
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }

    private companion object {
        const val TAG = "OpusCodecTest"
    }
}
