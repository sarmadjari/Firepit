import FirepitModel
import Foundation
import Security

public protocol SecretStore: Sendable {
    func data(for key: String) throws -> Data?
    func set(_ data: Data, for key: String) throws
    func remove(_ key: String) throws
    func removeAll(prefix: String) throws
}

public enum SecretStoreError: Error, Sendable, Equatable { case keychain(OSStatus) }

public struct KeychainStore: SecretStore {
    public let service: String
    public init(service: String = "com.getfirepit.app") { self.service = service }
    public func data(for key: String) throws -> Data? {
        var query = baseQuery(key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw SecretStoreError.keychain(status) }
        return result as? Data
    }
    public func set(_ data: Data, for key: String) throws {
        var query = baseQuery(key)
        let update: [String: Any] = [kSecValueData as String: data]
        let status = SecItemUpdate(query as CFDictionary, update as CFDictionary)
        if status == errSecSuccess { return }
        if status != errSecItemNotFound { throw SecretStoreError.keychain(status) }
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let add = SecItemAdd(query as CFDictionary, nil)
        guard add == errSecSuccess else { throw SecretStoreError.keychain(add) }
    }
    public func remove(_ key: String) throws {
        let status = SecItemDelete(baseQuery(key) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw SecretStoreError.keychain(status) }
    }
    public func removeAll(prefix: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecReturnAttributes as String: true, kSecMatchLimit as String: kSecMatchLimitAll,
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return }
        guard status == errSecSuccess else { throw SecretStoreError.keychain(status) }
        for item in (result as? [[String: Any]]) ?? [] {
            guard let account = item[kSecAttrAccount as String] as? String, account.hasPrefix(prefix) else { continue }
            try remove(account)
        }
    }
    private func baseQuery(_ key: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecAttrSynchronizable as String: false,
        ]
    }
}

public final class InMemorySecretStore: SecretStore {
    private let values = Mutex<[String: Data]>([:])
    public init() {}
    public func data(for key: String) -> Data? { values.withLock { $0[key] } }
    public func set(_ data: Data, for key: String) { values.withLock { state in state[key] = data } }
    public func remove(_ key: String) { values.withLock { state in _ = state.removeValue(forKey: key) } }
    public func removeAll(prefix: String) {
        values.withLock { state in state.keys.filter { $0.hasPrefix(prefix) }.forEach { state.removeValue(forKey: $0) }
        }
    }
}
