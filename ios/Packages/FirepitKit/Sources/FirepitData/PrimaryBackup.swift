import FirepitProtos
import Foundation
import SwiftProtobuf

/// What slot 0 looked like before Firepit took it over.
///
/// Taking a radio's primary channel is presented as a choice, so it has to be one: without the settings it replaced,
/// "keep it public" could only ever mean "leave it as Firepit left it". Kept per node in its own Keychain service,
/// because a primary channel carries the pre-shared key of whatever mesh the radio was on.
public final class PrimaryBackup: Sendable {
    private let store: any SecretStore

    public init(store: any SecretStore = KeychainStore(service: "com.getfirepit.app.primary-backup")) {
        self.store = store
    }

    public func saved(nodeNum: Int32) -> Channel? {
        do {
            guard let data = try store.data(for: key(nodeNum)) else {
                return nil
            }
            return try? Channel(serializedBytes: data)
        } catch {
            return nil
        }
    }

    public func has(nodeNum: Int32) -> Bool {
        saved(nodeNum: nodeNum) != nil
    }

    /// Records the slot once and never again, so a second takeover cannot overwrite the original.
    public func remember(nodeNum: Int32, channel: Channel) throws {
        if has(nodeNum: nodeNum) {
            return
        }
        try store.set(try channel.serializedData(), for: key(nodeNum))
    }

    public func forget(nodeNum: Int32) throws {
        try store.remove(key(nodeNum))
    }

    private func key(_ nodeNum: Int32) -> String {
        "\(nodeNum)"
    }
}

/// A channel with no key and no name is the factory one, which is not worth putting back.
extension Channel {
    public func carriesAKey() -> Bool {
        settings.psk.count > 1 || !settings.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}
