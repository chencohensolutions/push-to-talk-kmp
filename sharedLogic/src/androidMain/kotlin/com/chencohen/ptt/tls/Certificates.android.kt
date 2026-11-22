package com.chencohen.ptt.tls

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

internal fun parseX509Certificate(der: ByteArray): X509Certificate =
    CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
