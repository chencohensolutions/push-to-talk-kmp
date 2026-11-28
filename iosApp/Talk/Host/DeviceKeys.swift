import Foundation
import Security
import SharedLogic

/// The device's mTLS keys in the Keychain (the shared code's DeviceKeyStore, through IosHost): EC P-256, never
/// exportable, for this phone only (not in backups or iCloud Keychain), usable after the first unlock so a push can
/// reach the server while the phone is locked. On a phone the private key is made in the Secure Enclave; the
/// simulator has none, so there it is a software key.
enum DeviceKeys {
    private static func tag(_ alias: String) -> Data { Data(("com.chencohen.ptt.key." + alias).utf8) }

    static func generate(alias: String) -> IosDeviceKey {
        delete(alias: alias)
        var lastError = "The Keychain couldn't make a key"
        for enclave in [true, false] {
            var error: Unmanaged<CFError>?
            guard let key = make(alias: alias, enclave: enclave, error: &error) else {
                if let error { lastError = (error.takeRetainedValue() as Error).localizedDescription }
                continue
            }
            guard let publicKey = SecKeyCopyPublicKey(key),
                  let point = SecKeyCopyExternalRepresentation(publicKey, &error) as Data?
            else {
                if let error { lastError = (error.takeRetainedValue() as Error).localizedDescription }
                delete(alias: alias)
                continue
            }
            return IosDeviceKey(publicPoint: point, secureEnclave: enclave, error: nil)
        }
        return IosDeviceKey(publicPoint: nil, secureEnclave: false, error: lastError)
    }

    private static func make(alias: String, enclave: Bool, error: inout Unmanaged<CFError>?) -> SecKey? {
        var privateAttributes: [String: Any] = [
            kSecAttrIsPermanent as String: true,
            kSecAttrApplicationTag as String: tag(alias),
            kSecAttrLabel as String: alias,
        ]
        var attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
        ]
        if enclave {
            guard let access = SecAccessControlCreateWithFlags(
                nil, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, .privateKeyUsage, &error
            ) else { return nil }
            privateAttributes[kSecAttrAccessControl as String] = access
            attributes[kSecAttrTokenID as String] = kSecAttrTokenIDSecureEnclave
        } else {
            privateAttributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        }
        attributes[kSecPrivateKeyAttrs as String] = privateAttributes
        return SecKeyCreateRandomKey(attributes as CFDictionary, &error)
    }

    private static func privateKey(alias: String) -> SecKey? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrKeyClass as String: kSecAttrKeyClassPrivate,
            kSecAttrApplicationTag as String: tag(alias),
            kSecReturnRef as String: true,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let item else { return nil }
        return (item as! SecKey)
    }

    /// SHA-256/ECDSA over `data`, in DER (X9.62), as the certificate request and TLS want it.
    static func sign(alias: String, data: Data) -> Data? {
        guard let key = privateKey(alias: alias) else { return nil }
        var error: Unmanaged<CFError>?
        let signature = SecKeyCreateSignature(key, .ecdsaSignatureMessageX962SHA256, data as CFData, &error) as Data?
        error?.release()
        return signature
    }

    /// Keeps the certificate beside its key; the Keychain then gives the two as one identity. Nil, or why it couldn't.
    static func saveCertificate(alias: String, der: Data) -> String? {
        guard privateKey(alias: alias) != nil else { return "No key for \(alias)" }
        guard let certificate = SecCertificateCreateWithData(nil, der as CFData) else { return "The server's certificate can't be read" }
        deleteCertificate(alias: alias)
        let add: [String: Any] = [
            kSecClass as String: kSecClassCertificate,
            kSecValueRef as String: certificate,
            kSecAttrLabel as String: alias,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        var status = SecItemAdd(add as CFDictionary, nil)
        if status == errSecDuplicateItem {
            // The same certificate under another label (an attempt that was cut off): replaced.
            SecItemDelete([kSecClass as String: kSecClassCertificate, kSecValueRef as String: certificate] as CFDictionary)
            status = SecItemAdd(add as CFDictionary, nil)
        }
        guard status == errSecSuccess else { return "The Keychain didn't keep the certificate (\(status))" }
        return identity(alias: alias) == nil ? "The certificate isn't for this phone's key" : nil
    }

    static func certificate(alias: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassCertificate,
            kSecAttrLabel as String: alias,
            kSecReturnRef as String: true,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let item else { return nil }
        return SecCertificateCopyData(item as! SecCertificate) as Data
    }

    private static func deleteCertificate(alias: String) {
        SecItemDelete([kSecClass as String: kSecClassCertificate, kSecAttrLabel as String: alias] as CFDictionary)
    }

    static func delete(alias: String) {
        deleteCertificate(alias: alias)
        SecItemDelete([
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: tag(alias),
        ] as CFDictionary)
    }

    /// The identity (key and certificate) of `alias`: among the Keychain's identities, the one whose private key
    /// carries this alias's tag.
    static func identity(alias: String) -> SecIdentity? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassIdentity,
            kSecReturnRef as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
        ]
        var items: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &items) == errSecSuccess, let found = items as? [Any] else { return nil }
        let wanted = tag(alias)
        for item in found {
            let identity = item as! SecIdentity
            var key: SecKey?
            guard SecIdentityCopyPrivateKey(identity, &key) == errSecSuccess, let key,
                  let attributes = SecKeyCopyAttributes(key) as? [String: Any]
            else { continue }
            if let tag = attributes[kSecAttrApplicationTag as String] as? Data, tag == wanted { return identity }
        }
        return nil
    }

    /// What answers the server's request for a client certificate.
    static func credential(alias: String) -> URLCredential? {
        guard let identity = identity(alias: alias) else { return nil }
        return URLCredential(identity: identity, certificates: nil, persistence: .forSession)
    }
}
