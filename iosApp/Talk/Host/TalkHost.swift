import CryptoKit
import Foundation
import SharedLogic

/// What the shared logic asks of iOS (IosHost): CryptoKit's AES-GCM, the Keychain's device keys, the TLS checks'
/// pieces, the encrypted store and libsignal. One per process, made by TalkModel.
final class TalkHost: NSObject, IosHost {
    let db: IosDb
    let signal: IosSignal

    override init() {
        let store = SealedDb()
        db = store
        signal = SignalEngine(db: store)
        super.init()
    }

    // --- AES-256-GCM: ciphertext followed by the 16-byte tag, as Android's AES/GCM/NoPadding ----------------------

    func aesGcmSeal(key: Data, nonce: Data, aad: Data, plaintext: Data) -> Data? {
        guard key.count == 32, let nonce = try? AES.GCM.Nonce(data: nonce),
              let box = try? AES.GCM.seal(plaintext, using: SymmetricKey(data: key), nonce: nonce, authenticating: aad)
        else { return nil }
        return box.ciphertext + box.tag
    }

    func aesGcmOpen(key: Data, nonce: Data, aad: Data, ciphertext: Data) -> Data? {
        guard key.count == 32, ciphertext.count >= 16, let nonce = try? AES.GCM.Nonce(data: nonce) else { return nil }
        let body = ciphertext.prefix(ciphertext.count - 16)
        let tag = ciphertext.suffix(16)
        guard let box = try? AES.GCM.SealedBox(nonce: nonce, ciphertext: body, tag: tag) else { return nil }
        return try? AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad)
    }

    // --- The device's mTLS key ------------------------------------------------------------------------------------

    func generateDeviceKey(alias: String) -> IosDeviceKey { DeviceKeys.generate(alias: alias) }

    func signWithDeviceKey(alias: String, data: Data) -> Data? { DeviceKeys.sign(alias: alias, data: data) }

    func saveDeviceCertificate(alias: String, certificateDer: Data) -> String? { DeviceKeys.saveCertificate(alias: alias, der: certificateDer) }

    func deviceCertificate(alias: String) -> Data? { DeviceKeys.certificate(alias: alias) }

    func deleteDeviceKey(alias: String) { DeviceKeys.delete(alias: alias) }

    // --- TLS ------------------------------------------------------------------------------------------------------

    func clientCredential(alias: String) -> URLCredential? { DeviceKeys.credential(alias: alias) }

    func serverCertificate(challenge: URLAuthenticationChallenge) -> Data? { ServerTrust.certificate(challenge) }

    func chainsToCas(challenge: URLAuthenticationChallenge, cas: [Data]) -> Bool { ServerTrust.chains(challenge, to: cas) }

    func trustCredential(challenge: URLAuthenticationChallenge) -> URLCredential? { ServerTrust.credential(challenge) }
}
