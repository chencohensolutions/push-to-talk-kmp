import Foundation
import LibSignalClient
import SharedLogic

/// libsignal for the shared code's CryptoEngine (IosSignal; docs/design.md, "Keys and sessions"): the Swift package
/// of the same pinned version Android uses (`libsignal` in gradle/libs.versions.toml and iosApp/project.yml; upgraded
/// together, on purpose). Its records are kept in the encrypted store in libsignal's own serialized form, under the
/// same spaces and keys as Android's SignalStore. Called on Messaging's thread only.
final class SignalEngine: NSObject, IosSignal {
    private let records: SignalRecords
    private let context = NoContext()

    init(db: IosDb) {
        records = SignalRecords(db: db)
    }

    // --- Replies ---------------------------------------------------------------------------------------------

    private func ok(_ data: Data? = nil, more: Data? = nil, number: Int32 = 0, text: String? = nil) -> SignalReply {
        SignalReply(data: data, more: more, number: number, text: text, error: SignalFailure.shared.NONE, message: nil)
    }

    private func failed(_ code: Int32, _ error: Error) -> SignalReply {
        SignalReply(data: nil, more: nil, number: 0, text: nil, error: code, message: "\(error)")
    }

    /// Runs `body`; an untrusted identity is said as such, anything else as `otherwise`.
    private func reply(otherwise: Int32, _ body: () throws -> SignalReply) -> SignalReply {
        do {
            return try body()
        } catch SignalError.untrustedIdentity(let message) {
            return SignalReply(data: nil, more: nil, number: 0, text: nil, error: SignalFailure.shared.UNTRUSTED, message: message)
        } catch is NoIdentity {
            return SignalReply(data: nil, more: nil, number: 0, text: nil, error: SignalFailure.shared.NO_IDENTITY, message: "No identity key yet")
        } catch {
            return failed(otherwise, error)
        }
    }

    private var invalidKeys: Int32 { SignalFailure.shared.INVALID_KEYS }
    private var undecryptable: Int32 { SignalFailure.shared.UNDECRYPTABLE }

    private func address(_ user: String, _ device: Int32) throws -> ProtocolAddress {
        try ProtocolAddress(name: user, deviceId: UInt32(device))
    }

    // --- Identity --------------------------------------------------------------------------------------------

    func identityKey() -> Data? { records.identityPair()?.publicKey.serialize() }

    func createIdentity() -> Data {
        let pair = IdentityKeyPair.generate()
        records.saveLocalIdentity(pair)
        return pair.publicKey.serialize()
    }

    func exportIdentity() -> Data? { records.identityPair()?.serialize() }

    func importIdentity(pair: Data) -> SignalReply {
        reply(otherwise: invalidKeys) {
            let identity = try IdentityKeyPair(bytes: pair)
            records.clearOwnKeys()
            records.saveLocalIdentity(identity)
            return ok(identity.publicKey.serialize())
        }
    }

    func makeAgreementKey() -> SignalReply {
        let key = PrivateKey.generate()
        return ok(key.serialize(), more: key.publicKey.serialize())
    }

    func agree(privateKey: Data, publicKey: Data) -> SignalReply {
        reply(otherwise: invalidKeys) {
            ok(try PrivateKey(privateKey).keyAgreement(with: try PublicKey(publicKey)))
        }
    }

    func registrationId() -> Int32 { Int32(records.registrationId()) }

    // --- Prekeys ---------------------------------------------------------------------------------------------

    func generateSignedPrekey(keyId: Int32, nowMillis: Int64) -> SignalReply {
        reply(otherwise: invalidKeys) {
            let identity = try records.identityKeyPair(context: context)
            let key = PrivateKey.generate()
            let publicKey = key.publicKey.serialize()
            let signature = identity.privateKey.generateSignature(message: publicKey)
            let record = try SignedPreKeyRecord(id: UInt32(keyId), timestamp: UInt64(nowMillis), privateKey: key, signature: signature)
            try records.storeSignedPreKey(record, id: UInt32(keyId), context: context)
            return ok(publicKey, more: signature)
        }
    }

    func generateKyberPrekey(keyId: Int32, nowMillis: Int64) -> SignalReply {
        reply(otherwise: invalidKeys) {
            let identity = try records.identityKeyPair(context: context)
            let pair = KEMKeyPair.generate()
            let publicKey = pair.publicKey.serialize()
            let signature = identity.privateKey.generateSignature(message: publicKey)
            let record = try KyberPreKeyRecord(id: UInt32(keyId), timestamp: UInt64(nowMillis), keyPair: pair, signature: signature)
            try records.storeKyberPreKey(record, id: UInt32(keyId), context: context)
            return ok(publicKey, more: signature)
        }
    }

    func generatePrekey(keyId: Int32) -> SignalReply {
        reply(otherwise: invalidKeys) {
            let key = PrivateKey.generate()
            try records.storePreKey(try PreKeyRecord(id: UInt32(keyId), privateKey: key), id: UInt32(keyId), context: context)
            return ok(key.publicKey.serialize())
        }
    }

    func signedPrekeyTimestamp(serialized: Data) -> Int64 {
        guard let record = try? SignedPreKeyRecord(bytes: serialized) else { return -1 }
        return Int64(record.timestamp)
    }

    func isIdentityKey(key: Data) -> Bool { (try? IdentityKey(bytes: key)) != nil }

    // --- Sessions --------------------------------------------------------------------------------------------

    func startSession(
        user: String, device: Int32, localUser: String, localDevice: Int32, identityKey: Data, registrationId: Int32,
        signedPrekeyId: Int32, signedPrekey: Data, signedPrekeySignature: Data, prekeyId: Int32, prekey: Data?,
        kyberPrekeyId: Int32, kyberPrekey: Data, kyberPrekeySignature: Data
    ) -> SignalReply {
        reply(otherwise: invalidKeys) {
            guard records.identityPair() != nil else { throw NoIdentity() }
            let identity = try IdentityKey(bytes: identityKey)
            let bundle: PreKeyBundle
            if let prekey, prekeyId >= 0 {
                bundle = try PreKeyBundle(
                    registrationId: UInt32(registrationId), deviceId: UInt32(device),
                    prekeyId: UInt32(prekeyId), prekey: try PublicKey(prekey),
                    signedPrekeyId: UInt32(signedPrekeyId), signedPrekey: try PublicKey(signedPrekey),
                    signedPrekeySignature: signedPrekeySignature, identity: identity,
                    kyberPrekeyId: UInt32(kyberPrekeyId), kyberPrekey: try KEMPublicKey(kyberPrekey),
                    kyberPrekeySignature: kyberPrekeySignature
                )
            } else {
                bundle = try PreKeyBundle(
                    registrationId: UInt32(registrationId), deviceId: UInt32(device),
                    signedPrekeyId: UInt32(signedPrekeyId), signedPrekey: try PublicKey(signedPrekey),
                    signedPrekeySignature: signedPrekeySignature, identity: identity,
                    kyberPrekeyId: UInt32(kyberPrekeyId), kyberPrekey: try KEMPublicKey(kyberPrekey),
                    kyberPrekeySignature: kyberPrekeySignature
                )
            }
            // libsignal checks both signatures against the identity key here.
            try processPreKeyBundle(
                bundle, for: try address(user, device), ourAddress: try address(localUser, localDevice),
                sessionStore: records, identityStore: records, context: context
            )
            return ok()
        }
    }

    func sessionUsable(serialized: Data) -> Bool {
        (try? SessionRecord(bytes: serialized))?.hasCurrentState ?? false
    }

    func sessionRemoteRegistrationId(serialized: Data) -> Int32 {
        guard let record = try? SessionRecord(bytes: serialized), let id = try? record.remoteRegistrationId() else { return -1 }
        return Int32(id)
    }

    func encrypt(user: String, device: Int32, localUser: String, localDevice: Int32, plaintext: Data) -> SignalReply {
        reply(otherwise: undecryptable) {
            guard records.identityPair() != nil else { throw NoIdentity() }
            let message = try signalEncrypt(
                message: plaintext, for: try address(user, device), localAddress: try address(localUser, localDevice),
                sessionStore: records, identityStore: records, context: context
            )
            return ok(message.serialize(), number: Int32(message.messageType.rawValue))
        }
    }

    func decrypt(user: String, device: Int32, localUser: String, localDevice: Int32, prekeyMessage: Bool, bytes: Data) -> SignalReply {
        reply(otherwise: undecryptable) {
            guard records.identityPair() != nil else { throw NoIdentity() }
            let from = try address(user, device)
            let local = try address(localUser, localDevice)
            if prekeyMessage {
                let message = try PreKeySignalMessage(bytes: bytes)
                let plaintext = try signalDecryptPreKey(
                    message: message, from: from, localAddress: local, sessionStore: records, identityStore: records,
                    preKeyStore: records, signedPreKeyStore: records, kyberPreKeyStore: records, context: context
                )
                return ok(plaintext, more: message.identityKey.serialize())
            }
            records.lastIdentity = nil
            let plaintext = try signalDecrypt(
                message: try SignalMessage(bytes: bytes), from: from, to: local,
                sessionStore: records, identityStore: records, context: context
            )
            // The identity key of the session it came on: the one libsignal checked or recorded while opening it.
            let sender = try records.lastIdentity ?? records.identity(for: from, context: context)
            return ok(plaintext, more: sender?.serialize())
        }
    }

    // --- Safety numbers ---------------------------------------------------------------------------------------

    private func fingerprint(_ myUser: String, _ theirUser: String, _ theirIdentityKey: Data) throws -> Fingerprint {
        guard let mine = records.identityPair() else { throw NoIdentity() }
        return try NumericFingerprintGenerator(iterations: 5200).create(
            version: 2,
            localIdentifier: Data(myUser.utf8), localKey: mine.publicKey,
            remoteIdentifier: Data(theirUser.utf8), remoteKey: try IdentityKey(bytes: theirIdentityKey).publicKey
        )
    }

    func safetyNumber(myUser: String, theirUser: String, theirIdentityKey: Data) -> SignalReply {
        reply(otherwise: invalidKeys) {
            let made = try fingerprint(myUser, theirUser, theirIdentityKey)
            return ok(made.scannable.encoding, text: made.displayable.formatted)
        }
    }

    func matchesScanned(myUser: String, theirUser: String, theirIdentityKey: Data, scanned: Data) -> Bool {
        guard let made = try? fingerprint(myUser, theirUser, theirIdentityKey) else { return false }
        return (try? made.scannable.compare(againstEncoding: scanned)) ?? false
    }

    // --- Groups: sender keys, and the owner's signature --------------------------------------------------------

    private func uuid(_ text: String) throws -> UUID {
        guard let id = UUID(uuidString: text) else { throw SignalError.invalidArgument("not a distribution id: \(text)") }
        return id
    }

    func senderKeyDistribution(localUser: String, localDevice: Int32, distributionId: String) -> SignalReply {
        reply(otherwise: undecryptable) {
            let message = try SenderKeyDistributionMessage(
                from: try address(localUser, localDevice), distributionId: try uuid(distributionId), store: records, context: context
            )
            return ok(message.serialize())
        }
    }

    func takeSenderKey(user: String, device: Int32, distribution: Data) -> SignalReply {
        reply(otherwise: invalidKeys) {
            try processSenderKeyDistributionMessage(
                try SenderKeyDistributionMessage(bytes: distribution), from: try address(user, device), store: records, context: context
            )
            return ok()
        }
    }

    func distributionIdOf(distribution: Data) -> String? {
        (try? SenderKeyDistributionMessage(bytes: distribution))?.distributionId.uuidString.lowercased()
    }

    func groupEncrypt(localUser: String, localDevice: Int32, distributionId: String, plaintext: Data) -> SignalReply {
        reply(otherwise: undecryptable) {
            let message = try LibSignalClient.groupEncrypt(
                plaintext, from: try address(localUser, localDevice), distributionId: try uuid(distributionId), store: records, context: context
            )
            return ok(message.serialize())
        }
    }

    func groupDecrypt(user: String, device: Int32, bytes: Data) -> SignalReply {
        reply(otherwise: undecryptable) {
            ok(try LibSignalClient.groupDecrypt(bytes, from: try address(user, device), store: records, context: context))
        }
    }

    func groupMessageDistributionId(bytes: Data) -> String? {
        (try? SenderKeyMessage(bytes: bytes))?.distributionId.uuidString.lowercased()
    }

    func sign(data: Data) -> SignalReply {
        reply(otherwise: invalidKeys) {
            ok(try records.identityKeyPair(context: context).privateKey.generateSignature(message: data))
        }
    }

    func verify(identityKey: Data, data: Data, signature: Data) -> Bool {
        guard let key = try? IdentityKey(bytes: identityKey) else { return false }
        return (try? key.publicKey.verifySignature(message: data, signature: signature)) ?? false
    }
}

private struct NoContext: StoreContext {}

/// This phone has no identity key yet.
private struct NoIdentity: Error {}

/// libsignal's stores over the encrypted store (Android's SignalStore): records in libsignal's serialized form, keyed
/// by id, or by "user/device" for sessions. An identity key is trusted for a user when none is kept yet (the first
/// message of a pair request, or a session started from a QR code that was trusted first) or when it is the kept one;
/// a changed key is never taken on its own (the user accepts it, MessagingController.acceptKeyChange).
private final class SignalRecords: IdentityKeyStore, PreKeyStore, SignedPreKeyStore, KyberPreKeyStore, SessionStore, SenderKeyStore {
    // The shared code's Spaces (store/SecureStore.kt).
    private enum Space {
        static let identity = "identity"
        static let prekeys = "prekeys"
        static let signedPrekeys = "signed_prekeys"
        static let kyberPrekeys = "kyber_prekeys"
        static let kyberLastResort = "kyber_last_resort"
        static let kyberBaseKeys = "kyber_base_keys"
        static let sessions = "sessions"
        static let identities = "identities"
        static let senderKeys = "sender_keys"
    }

    private let db: IosDb

    /// The identity key libsignal last checked, saved or recorded: after a decrypt, the one its session came on.
    var lastIdentity: IdentityKey?

    init(db: IosDb) {
        self.db = db
    }

    // --- Identity ---

    func identityPair() -> IdentityKeyPair? {
        db.get(space: Space.identity, key: "pair").flatMap { try? IdentityKeyPair(bytes: $0) }
    }

    func registrationId() -> UInt32 {
        db.get(space: Space.identity, key: "registration").flatMap { String(data: $0, encoding: .utf8) }.flatMap { UInt32($0) } ?? 0
    }

    /// Keeps `pair` with a new registration id of this phone's own (1 to 16380, as libsignal's KeyHelper makes them).
    func saveLocalIdentity(_ pair: IdentityKeyPair) {
        db.put(space: Space.identity, key: "pair", value: pair.serialize())
        db.put(space: Space.identity, key: "registration", value: Data(String(UInt32.random(in: 1...16380)).utf8))
    }

    /// Forgets this phone's own keys (identity, prekeys, sessions) before it takes another phone's identity.
    func clearOwnKeys() {
        for space in [Space.identity, Space.prekeys, Space.signedPrekeys, Space.kyberPrekeys, Space.kyberLastResort, Space.kyberBaseKeys, Space.sessions] {
            db.clear(space: space)
        }
    }

    func identityKeyPair(context: StoreContext) throws -> IdentityKeyPair {
        guard let pair = identityPair() else { throw NoIdentity() }
        return pair
    }

    func localRegistrationId(context: StoreContext) throws -> UInt32 { registrationId() }

    func saveIdentity(_ identity: IdentityKey, for address: ProtocolAddress, context: StoreContext) throws -> IdentityChange {
        lastIdentity = identity
        let new = identity.serialize()
        let old = db.get(space: Space.identities, key: address.name)
        if old == new { return .newOrUnchanged }
        // The kept key stays: a change needs the user (isTrustedIdentity refuses it first anyway).
        if old != nil { return .replacedExisting }
        db.put(space: Space.identities, key: address.name, value: new)
        return .newOrUnchanged
    }

    func isTrustedIdentity(_ identity: IdentityKey, for address: ProtocolAddress, direction: Direction, context: StoreContext) throws -> Bool {
        lastIdentity = identity
        guard let kept = db.get(space: Space.identities, key: address.name) else { return true }
        return kept == identity.serialize()
    }

    func identity(for address: ProtocolAddress, context: StoreContext) throws -> IdentityKey? {
        try db.get(space: Space.identities, key: address.name).map { try IdentityKey(bytes: $0) }
    }

    func recordReceivedIdentity(_ identity: IdentityKey, for address: ProtocolAddress, context: StoreContext) throws {
        lastIdentity = identity
    }

    // --- One-time EC prekeys ---

    func loadPreKey(id: UInt32, context: StoreContext) throws -> PreKeyRecord {
        guard let bytes = db.get(space: Space.prekeys, key: String(id)) else { throw SignalError.invalidKeyIdentifier("No prekey \(id)") }
        return try PreKeyRecord(bytes: bytes)
    }

    func storePreKey(_ record: PreKeyRecord, id: UInt32, context: StoreContext) throws {
        db.put(space: Space.prekeys, key: String(id), value: record.serialize())
    }

    func removePreKey(id: UInt32, context: StoreContext) throws {
        db.remove(space: Space.prekeys, key: String(id))
    }

    // --- Signed EC prekeys ---

    func loadSignedPreKey(id: UInt32, context: StoreContext) throws -> SignedPreKeyRecord {
        guard let bytes = db.get(space: Space.signedPrekeys, key: String(id)) else { throw SignalError.invalidKeyIdentifier("No signed prekey \(id)") }
        return try SignedPreKeyRecord(bytes: bytes)
    }

    func storeSignedPreKey(_ record: SignedPreKeyRecord, id: UInt32, context: StoreContext) throws {
        db.put(space: Space.signedPrekeys, key: String(id), value: record.serialize())
    }

    // --- Kyber prekeys ---

    func loadKyberPreKey(id: UInt32, context: StoreContext) throws -> KyberPreKeyRecord {
        guard let bytes = db.get(space: Space.kyberPrekeys, key: String(id)) else { throw SignalError.invalidKeyIdentifier("No Kyber prekey \(id)") }
        return try KyberPreKeyRecord(bytes: bytes)
    }

    func storeKyberPreKey(_ record: KyberPreKeyRecord, id: UInt32, context: StoreContext) throws {
        db.put(space: Space.kyberPrekeys, key: String(id), value: record.serialize())
    }

    /// A one-time Kyber prekey goes once used. The last-resort one stays, and libsignal asks that a second message
    /// with the same base key (a replay of the first message) be refused.
    func markKyberPreKeyUsed(id: UInt32, signedPreKeyId: UInt32, baseKey: PublicKey, context: StoreContext) throws {
        if db.get(space: Space.kyberLastResort, key: String(id)) == nil {
            db.remove(space: Space.kyberPrekeys, key: String(id))
            return
        }
        let seen = "\(id)/\(signedPreKeyId)/" + baseKey.serialize().map { String(format: "%02x", $0) }.joined()
        if db.get(space: Space.kyberBaseKeys, key: seen) != nil { throw SignalError.invalidMessage("A first message came twice") }
        db.put(space: Space.kyberBaseKeys, key: seen, value: Data([1]))
    }

    // --- Sessions ---

    private func key(_ address: ProtocolAddress) -> String { "\(address.name)/\(address.deviceId)" }

    func loadSession(for address: ProtocolAddress, context: StoreContext) throws -> SessionRecord? {
        try db.get(space: Space.sessions, key: key(address)).map { try SessionRecord(bytes: $0) }
    }

    func loadExistingSessions(for addresses: [ProtocolAddress], context: StoreContext) throws -> [SessionRecord] {
        try addresses.map { address in
            guard let bytes = db.get(space: Space.sessions, key: key(address)) else { throw SignalError.sessionNotFound("No session with \(address)") }
            return try SessionRecord(bytes: bytes)
        }
    }

    func storeSession(_ record: SessionRecord, for address: ProtocolAddress, context: StoreContext) throws {
        db.put(space: Space.sessions, key: key(address), value: record.serialize())
    }

    // --- Sender keys (groups) ---

    private func key(_ sender: ProtocolAddress, _ distributionId: UUID) -> String {
        "\(key(sender))/\(distributionId.uuidString.lowercased())"
    }

    func storeSenderKey(from sender: ProtocolAddress, distributionId: UUID, record: SenderKeyRecord, context: StoreContext) throws {
        db.put(space: Space.senderKeys, key: key(sender, distributionId), value: record.serialize())
    }

    func loadSenderKey(from sender: ProtocolAddress, distributionId: UUID, context: StoreContext) throws -> SenderKeyRecord? {
        try db.get(space: Space.senderKeys, key: key(sender, distributionId)).map { try SenderKeyRecord(bytes: $0) }
    }
}
