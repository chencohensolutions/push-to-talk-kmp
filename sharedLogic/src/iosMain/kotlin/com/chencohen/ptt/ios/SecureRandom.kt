@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.chencohen.ptt.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import kotlin.random.Random

/** The system's secure generator as a [Random] (Android's SecureRandom().asKotlinRandom()): keys and secrets come from it. */
internal object SecureRandom : Random() {
    override fun nextBits(bitCount: Int): Int {
        val bytes = nextBytes(ByteArray(4))
        val value = ((bytes[0].toInt() and 0xff) shl 24) or ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff)
        return if (bitCount >= 32) value else value ushr (32 - bitCount) and ((1 shl bitCount) - 1)
    }

    override fun nextBytes(array: ByteArray, fromIndex: Int, toIndex: Int): ByteArray {
        val count = toIndex - fromIndex
        if (count <= 0) return array
        val status = array.usePinned { SecRandomCopyBytes(kSecRandomDefault, count.convert(), it.addressOf(fromIndex)) }
        check(status == errSecSuccess) { "The system's random generator failed ($status)" }
        return array
    }
}
