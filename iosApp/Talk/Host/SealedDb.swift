import CryptoKit
import Foundation
import Security
import SQLite3
import SharedLogic

/// The phone's encrypted store on iOS (the shared code's SecureStore; docs/design.md, "The encrypted store"): one
/// SQLite table of (space, key) → value in `Application Support/Talk/ptt.db`. Each value is sealed with AES-256-GCM
/// under a random key the Keychain keeps for this phone only, with its space and key as the associated data, so a
/// value can't be moved to another slot. The file is out of backups and under iOS's data protection (readable after
/// the first unlock, so a push can be handled while the phone is locked).
///
/// A database whose key is lost can't be read and is replaced by an empty one: this phone then starts over with a new
/// identity key, as a lost phone does. Opened at first use; used on Messaging's thread only.
final class SealedDb: NSObject, IosDb {
    private var handle: OpaquePointer?
    private var key: SymmetricKey?
    private var statements: [String: OpaquePointer] = [:]
    private let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    private func open() -> OpaquePointer? {
        if let handle { return handle }
        let folder = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Talk", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var file = folder.appendingPathComponent("ptt.db")
        let existed = FileManager.default.fileExists(atPath: file.path)
        var kept = DatabaseKey.load()
        if kept == nil {
            // No key: what is in the file (if anything) can't be opened any more.
            if existed { Self.deleteFiles(file) }
            kept = DatabaseKey.renew()
        }
        guard let kept else { return nil }
        key = SymmetricKey(data: kept)
        var db: OpaquePointer?
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX | SQLITE_OPEN_FILEPROTECTION_COMPLETEUNTILFIRSTUSERAUTHENTICATION
        guard sqlite3_open_v2(file.path, &db, flags, nil) == SQLITE_OK, let db else {
            sqlite3_close(db)
            return nil
        }
        sqlite3_exec(db, "PRAGMA journal_mode=WAL", nil, nil, nil)
        sqlite3_exec(db, "PRAGMA synchronous=NORMAL", nil, nil, nil)
        sqlite3_exec(db, "CREATE TABLE IF NOT EXISTS kv (space TEXT NOT NULL, k TEXT NOT NULL, v BLOB NOT NULL, PRIMARY KEY (space, k)) WITHOUT ROWID", nil, nil, nil)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? file.setResourceValues(values)
        handle = db
        return db
    }

    private static func deleteFiles(_ file: URL) {
        for suffix in ["", "-wal", "-shm"] {
            try? FileManager.default.removeItem(atPath: file.path + suffix)
        }
    }

    private func statement(_ sql: String) -> OpaquePointer? {
        if let made = statements[sql] {
            sqlite3_reset(made)
            sqlite3_clear_bindings(made)
            return made
        }
        guard let db = open() else { return nil }
        var made: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &made, nil) == SQLITE_OK, let made else { return nil }
        statements[sql] = made
        return made
    }

    private func slot(_ space: String, _ key: String) -> Data { Data((space + "\u{0}" + key).utf8) }

    func get(space: String, key: String) -> Data? {
        guard let s = statement("SELECT v FROM kv WHERE space = ? AND k = ?"), let sealing = self.key else { return nil }
        sqlite3_bind_text(s, 1, space, -1, transient)
        sqlite3_bind_text(s, 2, key, -1, transient)
        defer { sqlite3_reset(s) }
        guard sqlite3_step(s) == SQLITE_ROW else { return nil }
        let count = Int(sqlite3_column_bytes(s, 0))
        guard count > 0, let bytes = sqlite3_column_blob(s, 0) else { return nil }
        let sealed = Data(bytes: bytes, count: count)
        guard let box = try? AES.GCM.SealedBox(combined: sealed) else { return nil }
        return try? AES.GCM.open(box, using: sealing, authenticating: slot(space, key))
    }

    func put(space: String, key: String, value: Data) {
        guard let s = statement("INSERT OR REPLACE INTO kv (space, k, v) VALUES (?, ?, ?)"), let sealing = self.key,
              let sealed = try? AES.GCM.seal(value, using: sealing, authenticating: slot(space, key)).combined
        else { return }
        sqlite3_bind_text(s, 1, space, -1, transient)
        sqlite3_bind_text(s, 2, key, -1, transient)
        sealed.withUnsafeBytes { raw in
            _ = sqlite3_bind_blob(s, 3, raw.baseAddress, Int32(raw.count), transient)
        }
        sqlite3_step(s)
        sqlite3_reset(s)
    }

    func remove(space: String, key: String) {
        guard let s = statement("DELETE FROM kv WHERE space = ? AND k = ?") else { return }
        sqlite3_bind_text(s, 1, space, -1, transient)
        sqlite3_bind_text(s, 2, key, -1, transient)
        sqlite3_step(s)
        sqlite3_reset(s)
    }

    /// Sorted by SQLite itself (byte order), as Android's store does.
    func keys(space: String) -> [String] {
        guard let s = statement("SELECT k FROM kv WHERE space = ? ORDER BY k") else { return [] }
        sqlite3_bind_text(s, 1, space, -1, transient)
        defer { sqlite3_reset(s) }
        var keys: [String] = []
        while sqlite3_step(s) == SQLITE_ROW {
            if let text = sqlite3_column_text(s, 0) { keys.append(String(cString: text)) }
        }
        return keys
    }

    /// A whole space in one query: the chats' history when the app starts.
    func entries(space: String) -> [IosEntry] {
        guard let s = statement("SELECT k, v FROM kv WHERE space = ? ORDER BY k"), let sealing = self.key else { return [] }
        sqlite3_bind_text(s, 1, space, -1, transient)
        defer { sqlite3_reset(s) }
        var entries: [IosEntry] = []
        while sqlite3_step(s) == SQLITE_ROW {
            let count = Int(sqlite3_column_bytes(s, 1))
            guard let text = sqlite3_column_text(s, 0), count > 0, let bytes = sqlite3_column_blob(s, 1) else { continue }
            let key = String(cString: text)
            guard let box = try? AES.GCM.SealedBox(combined: Data(bytes: bytes, count: count)),
                  let value = try? AES.GCM.open(box, using: sealing, authenticating: slot(space, key))
            else { continue }
            entries.append(IosEntry(key: key, value: value))
        }
        return entries
    }

    func clear(space: String) {
        guard let s = statement("DELETE FROM kv WHERE space = ?") else { return }
        sqlite3_bind_text(s, 1, space, -1, transient)
        sqlite3_step(s)
        sqlite3_reset(s)
    }

    func clearAll() {
        guard let db = open() else { return }
        sqlite3_exec(db, "DELETE FROM kv", nil, nil, nil)
    }
}

/// The database's key: 32 random bytes in the Keychain, for this phone only, readable after the first unlock.
private enum DatabaseKey {
    private static let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "com.chencohen.ptt.database",
        kSecAttrAccount as String: "key",
    ]

    static func load() -> Data? {
        var read = query
        read[kSecReturnData as String] = true
        var item: CFTypeRef?
        guard SecItemCopyMatching(read as CFDictionary, &item) == errSecSuccess, let data = item as? Data, data.count == 32 else { return nil }
        return data
    }

    /// A new random key, kept; nil when the Keychain refuses.
    static func renew() -> Data? {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { return nil }
        let key = Data(bytes)
        SecItemDelete(query as CFDictionary)
        var add = query
        add[kSecValueData as String] = key
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess ? key : nil
    }
}
