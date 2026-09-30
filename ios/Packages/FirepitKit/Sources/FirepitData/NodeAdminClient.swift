import FirepitModel
import FirepitProtocol
import FirepitProtos
import FirepitTransport
import Foundation
import os

/// Local admin over the phone link.
///
/// Admin packets are addressed to our own node, so they never reach the air and
/// cost no mesh airtime. Firepit never enables remote admin: an admin channel
/// key is full device control sitting on the mesh.
public final class NodeAdminClient: Sendable {
    public let link: any RadioLinking
    public let repository: MeshRepository
    private let log = Logger(subsystem: "com.getfirepit.app", category: "FirepitAdmin")

    public init(link: any RadioLinking, repository: MeshRepository) {
        self.link = link
        self.repository = repository
    }

    /**
     * The firmware only checks the passkey for remote senders, but it is
     * cheap, and sending it keeps the flow correct if a node is ever put in
     * managed mode.
     */
    private func sessionPasskey() async -> Data {
        var request = AdminMessage()
        request.getConfigRequest = .sessionkeyConfig
        return await self.request(message: request)?.sessionPasskey ?? Data()
    }

    /// Writes a channel slot. This does **not** reboot the radio.
    public func setChannel(_ channel: Channel) async throws {
        var message = AdminMessage()
        message.sessionPasskey = await sessionPasskey()
        message.setChannel = channel
        try await send(message: message)
        repository.applyChannelWrite(channel)
    }

    /// Reads a slot back, so a write can be confirmed rather than assumed.
    public func getChannel(index: Int) async -> Channel? {
        var message = AdminMessage()
        message.getChannelRequest = UInt32(index + 1)
        return await request(message: message)?.getChannelResponse
    }

    public func setOwner(_ user: User) async throws {
        var message = AdminMessage()
        message.sessionPasskey = await sessionPasskey()
        message.setOwner = user
        try await send(message: message)
    }

    /**
     * Sets the radio's clock from the phone.
     *
     * The firmware records this as Net quality, below GPS, so a radio with a
     * fix keeps its own better time and only one without is corrected.
     */
    public func setTime(epochSeconds: Int) async throws {
        var message = AdminMessage()
        message.sessionPasskey = await sessionPasskey()
        message.setTimeOnly = UInt32(bitPattern: Int32(epochSeconds))
        try await send(message: message)
    }

    /**
     * Writes the device config back whole.
     *
     * The firmware replaces the section rather than merging it, so this takes a
     * copy of what the radio already reported: sending a config built from one
     * changed field would reset every other one to its default. Expect a reboot.
     */
    public func setDeviceConfig(device: Config.DeviceConfig) async throws {
        var config = Config()
        config.device = device
        var message = AdminMessage()
        message.sessionPasskey = await sessionPasskey()
        message.setConfig = config
        try await send(message: message)
    }

    /// Same whole-section replacement as setDeviceConfig. Expect a reboot.
    public func setPositionConfig(position: Config.PositionConfig) async throws {
        var config = Config()
        config.position = position
        var message = AdminMessage()
        message.sessionPasskey = await sessionPasskey()
        message.setConfig = config
        try await send(message: message)
    }

    /**
     * The radio's Bluetooth pairing, whole-section like the rest. Expect a
     * reboot, after which the phone has to pair again with the new PIN.
     */
    public func setBluetoothConfig(bluetooth: Config.BluetoothConfig) async throws {
        var config = Config()
        config.bluetooth = bluetooth
        var message = AdminMessage()
        message.sessionPasskey = await sessionPasskey()
        message.setConfig = config
        try await send(message: message)
    }

    /**
     * The radio's security section, whole. It carries the radio's own key
     * pair, so this must be built from what the radio reported or the node
     * would come back as somebody else. Expect a reboot.
     */
    public func setSecurityConfig(security: Config.SecurityConfig) async throws {
        var config = Config()
        config.security = security
        var message = AdminMessage()
        message.sessionPasskey = await sessionPasskey()
        message.setConfig = config
        try await send(message: message)
    }

    /// The MQTT module, whole-section like the rest. Expect a reboot.
    public func setMqttConfig(mqtt: ModuleConfig.MQTTConfig) async throws {
        var module = ModuleConfig()
        module.mqtt = mqtt
        var message = AdminMessage()
        message.sessionPasskey = await sessionPasskey()
        message.setModuleConfig = module
        try await send(message: message)
    }

    /// Whether the radio itself announces an arriving message. Same replacement rule.
    public func setExternalNotificationConfig(config: ModuleConfig.ExternalNotificationConfig) async throws {
        var module = ModuleConfig()
        module.externalNotification = config
        var message = AdminMessage()
        message.sessionPasskey = await sessionPasskey()
        message.setModuleConfig = module
        try await send(message: message)
    }

    /// Favourited nodes are never evicted from the radio's bounded NodeDB.
    public func setFavorite(nodeNum: Int32) async throws {
        var message = AdminMessage()
        message.sessionPasskey = await sessionPasskey()
        message.setFavoriteNode = UInt32(bitPattern: nodeNum)
        try await send(message: message)
    }

    /**
     * Puts a node and its public key into the radio's own NodeDB.
     *
     * The app remembers every node it has ever seen, the radio's database is
     * bounded and evicts. Without this, encrypting to somebody the app knows
     * about can still fail on a radio that has forgotten them.
     */
    public func addContact(nodeNum: Int32, user: User) async throws {
        var contact = SharedContact()
        contact.nodeNum = UInt32(bitPattern: nodeNum)
        contact.user = user
        var message = AdminMessage()
        message.sessionPasskey = await sessionPasskey()
        message.addContact = contact
        try await send(message: message)
    }

    public func removeFavorite(nodeNum: Int32) async throws {
        var message = AdminMessage()
        message.sessionPasskey = await sessionPasskey()
        message.removeFavoriteNode = UInt32(bitPattern: nodeNum)
        try await send(message: message)
    }

    private func send(message: AdminMessage) async throws {
        guard let myNodeNum = repository.myNodeNum.value else {
            throw SendError.notConnected
        }
        let payload = try message.serializedData()
        let packet = MeshPacketBuilder.localPacket(
            myNodeNum: myNodeNum,
            portNum: .adminApp,
            payload: payload
        )
        var toRadio = ToRadio()
        toRadio.packet = packet
        try await link.send(toRadio)
    }

    /// Sends an admin request and waits for the reply carrying our packet id.
    private func request(message: AdminMessage, timeout: Duration = .seconds(10)) async -> AdminMessage? {
        guard let myNodeNum = repository.myNodeNum.value else {
            return nil
        }
        guard let payload = try? message.serializedData() else {
            return nil
        }
        let packet = MeshPacketBuilder.localPacket(
            myNodeNum: myNodeNum,
            portNum: .adminApp,
            payload: payload,
            wantResponse: true
        )
        let requestId = packet.id
        // Start listening before sending: the reply can arrive before send() returns.
        let replies = link.inbound.subscribe()
        var message = ToRadio()
        message.packet = packet
        let toRadio = message
        let link = link
        // The deadline covers the send as well as the reply, as in Kotlin; a send that fails reads as no reply.
        return try? await withTimeoutOrNil(timeout) {
            try await link.send(toRadio)
            guard
                let from = await replies.first(where: { from in
                    guard case .packet(let packet)? = from.payloadVariant,
                        case .decoded(let data)? = packet.payloadVariant
                    else {
                        return false
                    }
                    return data.portnum == .adminApp && data.requestID == requestId
                }), case .packet(let packet)? = from.payloadVariant,
                case .decoded(let data)? = packet.payloadVariant
            else {
                return nil
            }
            return try? AdminMessage(serializedBytes: data.payload)
        }
    }
}
