package com.chencohen.ptt.tls

import kotlin.io.encoding.Base64

private val PEM_CERTIFICATE = Regex("-----BEGIN CERTIFICATE-----([^-]+)-----END CERTIFICATE-----")

/** Decodes every certificate in a PEM bundle to DER bytes. */
internal fun decodePemCertificates(pem: String): List<ByteArray> =
    PEM_CERTIFICATE.findAll(pem)
        .map { match -> Base64.decode(match.groupValues[1].filterNot(Char::isWhitespace)) }
        .toList()
