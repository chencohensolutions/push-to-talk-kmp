package com.chencohen.ptt.tls

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * When an X.509 certificate (DER) expires, in milliseconds since 1970; null when it can't be read. iOS has no call
 * for it, so the validity is read from the DER here: Certificate { tbsCertificate { [0] version OPTIONAL,
 * serialNumber, signature, issuer, validity { notBefore, notAfter }, … } }.
 */
@OptIn(ExperimentalTime::class)
internal fun certificateNotAfter(der: ByteArray): Long? = runCatching {
    val certificate = DerElement.read(der, 0)
    val tbs = DerElement.read(der, certificate.contentStart)
    var element = DerElement.read(der, tbs.contentStart)
    if (element.tag == 0xA0) element = DerElement.read(der, element.end)
    // serialNumber → signature → issuer → validity
    repeat(3) { element = DerElement.read(der, element.end) }
    require(element.tag == 0x30)
    val notBefore = DerElement.read(der, element.contentStart)
    val notAfter = DerElement.read(der, notBefore.end)
    val text = der.decodeToString(notAfter.contentStart, notAfter.end)
    // UTCTime is YYMMDDHHMMSSZ (years 1950 to 2049), GeneralizedTime YYYYMMDDHHMMSSZ.
    val full = when (notAfter.tag) {
        0x17 -> (if (text.substring(0, 2).toInt() >= 50) "19" else "20") + text
        0x18 -> text
        else -> return@runCatching null
    }
    require(full.length >= 15 && full.endsWith("Z"))
    val iso = "${full.substring(0, 4)}-${full.substring(4, 6)}-${full.substring(6, 8)}T${full.substring(8, 10)}:${full.substring(10, 12)}:${full.substring(12, 14)}Z"
    Instant.parse(iso).toEpochMilliseconds()
}.getOrNull()

private class DerElement(val tag: Int, val contentStart: Int, val end: Int) {
    companion object {
        fun read(b: ByteArray, at: Int): DerElement {
            val tag = b[at].toInt() and 0xff
            var pos = at + 1
            val first = b[pos++].toInt() and 0xff
            val length = if (first < 0x80) {
                first
            } else {
                val n = first and 0x7f
                require(n in 1..4)
                var l = 0
                repeat(n) { l = (l shl 8) or (b[pos++].toInt() and 0xff) }
                l
            }
            require(length >= 0 && pos + length <= b.size)
            return DerElement(tag, pos, pos + length)
        }
    }
}
