import FirepitProtocol
import Foundation
import Observation

/// The radios this phone administers, and what each is for. Ported from android/app/…/radio/SavedRadioStore.kt.
///
/// Stored as JSON rather than one key per radio: the set changes as a whole when Personal moves, and a half-applied
/// change would leave two Personals. The JSON matches Android's field names, so the format documents itself.
@Observable
final class SavedRadioStore {
    private(set) var radios: [SavedRadio]

    @ObservationIgnored private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        radios = Self.read(from: defaults)
    }

    var personal: SavedRadio? { SavedRadios.personal(radios: radios) }

    func assign(identifier: String, name: String, role: NodeRole, transport: DeviceTransport = .bluetooth) {
        let existing = radios.first { $0.identifier == identifier }
        write(
            SavedRadios.assign(
                radios: radios,
                radio: SavedRadio(
                    identifier: identifier,
                    name: name,
                    role: role,
                    transport: existing?.transport ?? transport,
                    nodeNum: existing?.nodeNum,
                    onMap: existing?.onMap != false,
                    publicKey: existing?.publicKey
                )))
    }

    /// Learned once the radio says who it is, and kept for when it is away.
    ///
    /// Only ever filled in, never overwritten: false when the radio answering at this address is not the one saved
    /// here — a different node, or the same node number under a different key. Anything can answer at a Bluetooth
    /// address; replacing the saved identity would make whatever answered the radio this phone trusts.
    func rememberNode(identifier: String, nodeNum: Int32, publicKey: String?) -> Bool {
        guard let existing = radios.first(where: { $0.identifier == identifier }) else { return true }
        let sameNode = existing.nodeNum == nil || existing.nodeNum == nodeNum
        // A radio that once showed a key and now shows none is not the same radio until the person says so: saying
        // nothing is how an impostor would get past a comparison.
        let sameKey = existing.publicKey == nil || existing.publicKey == publicKey
        if !sameNode || !sameKey { return false }
        if existing.nodeNum != nodeNum || (existing.publicKey == nil && publicKey != nil) {
            var updated = existing
            updated.nodeNum = nodeNum
            updated.publicKey = publicKey ?? existing.publicKey
            write(SavedRadios.assign(radios: radios, radio: updated))
        }
        return true
    }

    /// The person says the radio answering now is theirs after all, reset or reflashed.
    func trust(identifier: String, nodeNum: Int32, publicKey: String?) {
        guard var existing = radios.first(where: { $0.identifier == identifier }) else { return }
        existing.nodeNum = nodeNum
        existing.publicKey = publicKey
        write(SavedRadios.assign(radios: radios, radio: existing))
    }

    func showOnMap(identifier: String, onMap: Bool) {
        guard var existing = radios.first(where: { $0.identifier == identifier }) else { return }
        existing.onMap = onMap
        write(SavedRadios.assign(radios: radios, radio: existing))
    }

    func forget(identifier: String) {
        write(SavedRadios.forget(radios: radios, identifier: identifier))
    }

    private func write(_ radios: [SavedRadio]) {
        let array: [[String: Any]] = radios.map { radio in
            [
                Self.keyId: radio.identifier,
                Self.keyName: radio.name,
                Self.keyRole: radio.role.name,
                Self.keyTransport: radio.transport.name,
                Self.keyNode: radio.nodeNum.map { NSNumber(value: $0) } ?? NSNull(),
                Self.keyOnMap: radio.onMap,
                Self.keyPublicKey: radio.publicKey ?? NSNull(),
            ]
        }
        if let data = try? JSONSerialization.data(withJSONObject: array),
            let json = String(data: data, encoding: .utf8)
        {
            defaults.set(json, forKey: Self.keyRadios)
        }
        self.radios = radios
    }

    private static func read(from defaults: UserDefaults) -> [SavedRadio] {
        guard let raw = defaults.string(forKey: keyRadios),
            let data = raw.data(using: .utf8),
            let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        else { return [] }
        return array.compactMap { item in
            guard let identifier = item[keyId] as? String,
                let name = item[keyName] as? String,
                let role = NodeRole.allCases.first(where: { $0.name == item[keyRole] as? String })
            else { return nil }
            // Devices saved before transports were named were all Bluetooth.
            let transport = DeviceTransport.allCases.first { $0.name == item[keyTransport] as? String } ?? .bluetooth
            return SavedRadio(
                identifier: identifier,
                name: name,
                role: role,
                transport: transport,
                nodeNum: (item[keyNode] as? NSNumber).map { $0.int32Value },
                onMap: item[keyOnMap] as? Bool ?? true,
                publicKey: item[keyPublicKey] as? String
            )
        }
    }

    private static let keyRadios = "radios"
    private static let keyId = "id"
    private static let keyName = "name"
    private static let keyRole = "role"
    private static let keyTransport = "transport"
    private static let keyNode = "node"
    private static let keyOnMap = "onMap"
    private static let keyPublicKey = "publicKey"
}
