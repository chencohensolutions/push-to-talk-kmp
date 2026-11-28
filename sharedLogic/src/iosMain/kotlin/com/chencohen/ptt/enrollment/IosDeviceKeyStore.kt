package com.chencohen.ptt.enrollment

import com.chencohen.ptt.ios.IosHost
import com.chencohen.ptt.ios.toByteArray
import com.chencohen.ptt.ios.toNSData
import com.chencohen.ptt.tls.certificateNotAfter

/**
 * Device keys in the iOS Keychain through the app's host (DeviceKeys.swift): in the Secure Enclave on a phone, in
 * software on the simulator, never exportable, and for this phone only (not in backups or iCloud Keychain).
 */
internal class IosDeviceKeyStore(private val host: IosHost) : DeviceKeyStore {
    override fun generate(alias: String): DeviceKey {
        val made = host.generateDeviceKey(alias)
        val point = made.publicPoint?.toByteArray()
        if (point == null || point.size != 65 || point[0] != 0x04.toByte()) error(made.error ?: "The Keychain couldn't make a key")
        return IosKey(alias, if (made.secureEnclave) KeyProtection.SECURE_ENCLAVE else KeyProtection.SOFTWARE, P256_SPKI_HEADER + point)
    }

    override fun saveCertificate(alias: String, certificateDer: ByteArray) {
        host.saveDeviceCertificate(alias, certificateDer.toNSData())?.let { error(it) }
    }

    override fun delete(alias: String) = host.deleteDeviceKey(alias)

    override fun certificateNotAfter(alias: String): Long? = host.deviceCertificate(alias)?.toByteArray()?.let(::certificateNotAfter)

    private inner class IosKey(private val alias: String, override val protection: KeyProtection, override val publicKeyInfo: ByteArray) : DeviceKey {
        override fun sign(data: ByteArray): ByteArray =
            host.signWithDeviceKey(alias, data.toNSData())?.toByteArray() ?: error("The device key for $alias can't sign")
    }

    private companion object {
        /**
         * The DER SubjectPublicKeyInfo of an EC P-256 key up to its point: SEQUENCE { SEQUENCE { id-ecPublicKey,
         * prime256v1 }, BIT STRING (0 unused bits) … }. The Keychain gives only the point.
         */
        val P256_SPKI_HEADER = byteArrayOf(
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01,
            0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
        )
    }
}
