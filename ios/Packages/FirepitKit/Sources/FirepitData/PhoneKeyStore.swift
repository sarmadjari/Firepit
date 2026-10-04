import CryptoKit
import FirepitCrypto
import FirepitModel
import Foundation
import Security
import os

private let phoneKeyLog = Logger(subsystem: "com.getfirepit.app", category: "FirepitPhoneKey")

/// Creates and restores the phone key from the storage format chosen for this build.
public protocol PhoneKeySource: Sendable {
    func create() throws -> any PhoneKeyAgreementKey
    func restore(_ data: Data) throws -> (any PhoneKeyAgreementKey)?
    func dataRepresentation(of key: any PhoneKeyAgreementKey) throws -> Data
    var kind: String { get }
}

/// Software P-256 key source used by tests and by devices without Secure Enclave support.
public struct SoftwarePhoneKeySource: PhoneKeySource {
    public let kind = "software"

    public init() {}

    public func create() -> any PhoneKeyAgreementKey {
        P256.KeyAgreement.PrivateKey()
    }

    public func restore(_ data: Data) -> (any PhoneKeyAgreementKey)? {
        try? P256.KeyAgreement.PrivateKey(rawRepresentation: data)
    }

    public func dataRepresentation(of key: any PhoneKeyAgreementKey) throws -> Data {
        guard let key = key as? P256.KeyAgreement.PrivateKey else {
            throw PhoneKeyStoreError.unsupportedKeySource
        }
        return key.rawRepresentation
    }
}

#if !os(watchOS)
    /// Secure Enclave P-256 key source for production phones when the hardware is present.
    public struct SecureEnclavePhoneKeySource: PhoneKeySource {
        public let kind = "secure-enclave"

        public init() {}

        public func create() throws -> any PhoneKeyAgreementKey {
            var error: Unmanaged<CFError>?
            guard
                let access = SecAccessControlCreateWithFlags(
                    nil,
                    kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                    [.privateKeyUsage],
                    &error
                )
            else {
                throw error!.takeRetainedValue() as Error
            }
            return try SecureEnclave.P256.KeyAgreement.PrivateKey(accessControl: access)
        }

        public func restore(_ data: Data) -> (any PhoneKeyAgreementKey)? {
            try? SecureEnclave.P256.KeyAgreement.PrivateKey(dataRepresentation: data)
        }

        public func dataRepresentation(of key: any PhoneKeyAgreementKey) throws -> Data {
            guard let key = key as? SecureEnclave.P256.KeyAgreement.PrivateKey else {
                throw PhoneKeyStoreError.unsupportedKeySource
            }
            return key.dataRepresentation
        }
    }
#endif

public enum PhoneKeyStoreError: Error, Sendable, Equatable {
    case unsupportedKeySource
}

/// This phone's own key pair, which room keys and direct messages are sealed to.
///
/// The radio's key is not enough: whoever holds the radio can read its private key over Bluetooth, and with it
/// anything encrypted to that radio. This one exists only here, in the Secure Enclave when available, otherwise in
/// software, and its representation is stored in the Keychain account names Android used for its preferences.
public final class PhoneKeyStore: Sendable {
    private struct Pair: Sendable {
        var `private`: any PhoneKeyAgreementKey
        var `public`: Data
    }

    private let store: any SecretStore
    private let source: any PhoneKeySource
    private let loaded = Mutex<Pair?>(nil)

    public init(
        store: any SecretStore = KeychainStore(service: "com.getfirepit.app.phone-key"),
        source: (any PhoneKeySource)? = nil
    ) {
        self.store = store
        #if !os(watchOS)
            if let source {
                self.source = source
            } else if SecureEnclave.isAvailable {
                self.source = SecureEnclavePhoneKeySource()
            } else {
                self.source = SoftwarePhoneKeySource()
            }
        #else
            self.source = source ?? SoftwarePhoneKeySource()
        #endif
    }

    /// The 33-byte public half, to hand to anyone who may need to seal us a key.
    public func publicKey() throws -> Data {
        try pair().public
    }

    /// A room key sealed to this phone, or nil when it was not, or was tampered with.
    public func open(sealed: Data, context: Data, hedge: Data? = nil) -> Data? {
        guard let pair = try? pair() else {
            phoneKeyLog.error("could not restore this phone's key")
            return nil
        }
        return KeyEnvelope.open(
            privateKey: pair.private,
            ownPublic: pair.public,
            sealed: sealed,
            context: context,
            hedge: hedge
        )
    }

    /// Seals plaintext from this phone to the phone holding peerPublic. See DirectSeal.
    public func sealDirect(
        peerPublic: Data,
        plaintext: Data,
        context: Data,
        room: DirectSeal.RoomSecret? = nil
    ) throws -> Data {
        let pair = try pair()
        return try DirectSeal.seal(
            ownPrivate: pair.private,
            ownPublic: pair.public,
            peerPublic: peerPublic,
            plaintext: plaintext,
            context: context,
            room: room
        )
    }

    /// Nil when this was not sealed between that phone and this one, or was tampered with.
    public func openDirect(peerPublic: Data, sealed: Data, context: Data) -> Data? {
        openDirect(peerPublic: peerPublic, sealed: sealed, context: context, rooms: [])?.plain
    }

    /// Nil when this was not sealed between that phone and this one, or was tampered with.
    public func openDirect(peerPublic: Data, sealed: Data, context: Data, rooms: [DirectSeal.RoomSecret]) -> DirectSeal.Opening? {
        guard let pair = try? pair() else {
            phoneKeyLog.error("could not restore this phone's key")
            return nil
        }
        return DirectSeal.open(
            ownPrivate: pair.private,
            ownPublic: pair.public,
            peerPublic: peerPublic,
            sealed: sealed,
            context: context,
            rooms: rooms
        )
    }

    private func pair() throws -> Pair {
        if let pair = loaded.withLock({ $0 }) {
            return pair
        }
        let pair = try restore() ?? create()
        loaded.withLock { state in
            state = pair
        }
        return pair
    }

    private func restore() throws -> Pair? {
        guard let encoded = try store.data(for: Self.privateKey),
            let publicBytes = try store.data(for: Self.publicKey),
            KeyEnvelope.isValidPublicKey(publicBytes),
            let key = try source.restore(encoded)
        else {
            return nil
        }
        return Pair(private: key, public: publicBytes)
    }

    /// A fresh pair, replacing one the Keychain can no longer open.
    private func create() throws -> Pair {
        let key = try source.create()
        let publicBytes = KeyEnvelope.publicBytes(key.publicKey)
        try store.set(try source.dataRepresentation(of: key), for: Self.privateKey)
        try store.set(publicBytes, for: Self.publicKey)
        try store.set(Data(source.kind.utf8), for: Self.kind)
        phoneKeyLog.info("generated this phone's key")
        return Pair(private: key, public: publicBytes)
    }

    private static let privateKey = "private"
    private static let publicKey = "public"
    private static let kind = "kind"
}
