import FirepitModel
import Foundation
import GRDB

public struct MessageEntity: Codable, FetchableRecord, PersistableRecord, Sendable, Equatable {
    public static let databaseTableName = "messages"
    public var id: Int32
    public var channel: Int
    public var fromNodeNum: Int32
    public var toNodeNum: Int32
    public var peerNodeNum: Int32
    public var text: String
    public var sentAt: Int64
    public var rxTime: Int64?
    public var status: MessageStatus
    public var failureReason: String?
    public var isOutgoing: Bool
    public var rxSnr: Float?
    public var rxRssi: Int?
    public var hopsAway: Int?
    public var replyId: Int32?
    public var emoji: Int?
    public var signed: Bool
    public var roomId: Int32

    public init(
        id: Int32, channel: Int, fromNodeNum: Int32, toNodeNum: Int32, peerNodeNum: Int32, text: String,
        sentAt: Int64, rxTime: Int64?, status: MessageStatus, failureReason: String?, isOutgoing: Bool,
        rxSnr: Float?, rxRssi: Int?, hopsAway: Int?, replyId: Int32?, emoji: Int?, signed: Bool,
        roomId: Int32 = 0
    ) {
        self.id = id
        self.channel = channel
        self.fromNodeNum = fromNodeNum
        self.toNodeNum = toNodeNum
        self.peerNodeNum = peerNodeNum
        self.text = text
        self.sentAt = sentAt
        self.rxTime = rxTime
        self.status = status
        self.failureReason = failureReason
        self.isOutgoing = isOutgoing
        self.rxSnr = rxSnr
        self.rxRssi = rxRssi
        self.hopsAway = hopsAway
        self.replyId = replyId
        self.emoji = emoji
        self.signed = signed
        self.roomId = roomId
    }

    public enum Columns: String, ColumnExpression {
        case id, channel, fromNodeNum, toNodeNum, peerNodeNum, text, sentAt, rxTime, status, failureReason, isOutgoing,
            rxSnr, rxRssi, hopsAway, replyId, emoji, signed, roomId
    }

    enum CodingKeys: String, CodingKey {
        case id, channel, fromNodeNum, toNodeNum, peerNodeNum, text, sentAt, rxTime, status, failureReason, isOutgoing,
            rxSnr, rxRssi, hopsAway, replyId, emoji, signed, roomId
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int32.self, forKey: .id)
        channel = try c.decode(Int.self, forKey: .channel)
        fromNodeNum = try c.decode(Int32.self, forKey: .fromNodeNum)
        toNodeNum = try c.decode(Int32.self, forKey: .toNodeNum)
        peerNodeNum = try c.decode(Int32.self, forKey: .peerNodeNum)
        text = try c.decode(String.self, forKey: .text)
        sentAt = try c.decode(Int64.self, forKey: .sentAt)
        rxTime = try c.decodeIfPresent(Int64.self, forKey: .rxTime)
        status = MessageStatus(name: try c.decode(String.self, forKey: .status)) ?? .failed
        failureReason = try c.decodeIfPresent(String.self, forKey: .failureReason)
        isOutgoing = try c.decode(Bool.self, forKey: .isOutgoing)
        rxSnr = try c.decodeIfPresent(Float.self, forKey: .rxSnr)
        rxRssi = try c.decodeIfPresent(Int.self, forKey: .rxRssi)
        hopsAway = try c.decodeIfPresent(Int.self, forKey: .hopsAway)
        replyId = try c.decodeIfPresent(Int32.self, forKey: .replyId)
        emoji = try c.decodeIfPresent(Int.self, forKey: .emoji)
        signed = try c.decode(Bool.self, forKey: .signed)
        roomId = try c.decode(Int32.self, forKey: .roomId)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(channel, forKey: .channel)
        try c.encode(fromNodeNum, forKey: .fromNodeNum)
        try c.encode(toNodeNum, forKey: .toNodeNum)
        try c.encode(peerNodeNum, forKey: .peerNodeNum)
        try c.encode(text, forKey: .text)
        try c.encode(sentAt, forKey: .sentAt)
        try c.encodeIfPresent(rxTime, forKey: .rxTime)
        try c.encode(status.name, forKey: .status)
        try c.encodeIfPresent(failureReason, forKey: .failureReason)
        try c.encode(isOutgoing, forKey: .isOutgoing)
        try c.encodeIfPresent(rxSnr, forKey: .rxSnr)
        try c.encodeIfPresent(rxRssi, forKey: .rxRssi)
        try c.encodeIfPresent(hopsAway, forKey: .hopsAway)
        try c.encodeIfPresent(replyId, forKey: .replyId)
        try c.encodeIfPresent(emoji, forKey: .emoji)
        try c.encode(signed, forKey: .signed)
        try c.encode(roomId, forKey: .roomId)
    }

    public init(row: Row) throws {
        id = row[Columns.id]
        channel = row[Columns.channel]
        fromNodeNum = row[Columns.fromNodeNum]
        toNodeNum = row[Columns.toNodeNum]
        peerNodeNum = row[Columns.peerNodeNum]
        text = row[Columns.text]
        sentAt = row[Columns.sentAt]
        rxTime = row[Columns.rxTime]
        status = MessageStatus(name: row[Columns.status]) ?? .failed
        failureReason = row[Columns.failureReason]
        isOutgoing = row[Columns.isOutgoing]
        rxSnr = row[Columns.rxSnr]
        rxRssi = row[Columns.rxRssi]
        hopsAway = row[Columns.hopsAway]
        replyId = row[Columns.replyId]
        emoji = row[Columns.emoji]
        signed = row[Columns.signed]
        roomId = row[Columns.roomId]
    }

    public func encode(to container: inout PersistenceContainer) throws {
        container[Columns.id] = id
        container[Columns.channel] = channel
        container[Columns.fromNodeNum] = fromNodeNum
        container[Columns.toNodeNum] = toNodeNum
        container[Columns.peerNodeNum] = peerNodeNum
        container[Columns.text] = text
        container[Columns.sentAt] = sentAt
        container[Columns.rxTime] = rxTime
        container[Columns.status] = status.name
        container[Columns.failureReason] = failureReason
        container[Columns.isOutgoing] = isOutgoing
        container[Columns.rxSnr] = rxSnr
        container[Columns.rxRssi] = rxRssi
        container[Columns.hopsAway] = hopsAway
        container[Columns.replyId] = replyId
        container[Columns.emoji] = emoji
        container[Columns.signed] = signed
        container[Columns.roomId] = roomId
    }

    public func toDomain() -> ChatMessage {
        ChatMessage(
            id: id, channel: channel, fromNodeNum: fromNodeNum, toNodeNum: toNodeNum, text: text,
            sentAt: sentAt, rxTime: rxTime, status: status, failureReason: failureReason,
            isOutgoing: isOutgoing, rxSnr: rxSnr, rxRssi: rxRssi, hopsAway: hopsAway,
            replyId: replyId, emoji: emoji, signed: signed, roomId: roomId)
    }

    public static func fromDomain(_ message: ChatMessage, myNodeNum: Int32) -> MessageEntity {
        return MessageEntity(
            id: message.id, channel: message.channel, fromNodeNum: message.fromNodeNum,
            toNodeNum: message.toNodeNum,
            peerNodeNum: message.peerNode(myNodeNum: myNodeNum),
            text: message.text, sentAt: message.sentAt, rxTime: message.rxTime, status: message.status,
            failureReason: message.failureReason, isOutgoing: message.isOutgoing, rxSnr: message.rxSnr,
            rxRssi: message.rxRssi, hopsAway: message.hopsAway, replyId: message.replyId,
            emoji: message.emoji, signed: message.signed, roomId: message.roomId)
    }
}

public struct NodeEntity: Codable, FetchableRecord, PersistableRecord, Sendable, Equatable {
    public static let databaseTableName = "nodes"
    public var nodeNum: Int32
    public var userId: String?
    public var longName: String?
    public var shortName: String?
    public var hwModel: String?
    public var role: String?
    public var publicKey: String?
    public var isUnmessagable: Bool
    public var lastHeard: Int64?
    public var snr: Float?
    public var rssi: Int?
    public var hopsAway: Int?
    public var batteryLevel: Int?
    public var voltage: Float?
    public var channelUtilization: Float?
    public var airUtilTx: Float?
    public var isFavorite: Bool
    public var firstSeen: Int64
    public var latitudeI: Int32?
    public var longitudeI: Int32?
    public var altitude: Int?
    public var positionTime: Int64?
    public var positionPrecision: Int?
    public var groundSpeed: Int?
    public var groundTrack: Int?

    public init(
        nodeNum: Int32, userId: String?, longName: String?, shortName: String?, hwModel: String?, role: String?,
        publicKey: String?, isUnmessagable: Bool, lastHeard: Int64?, snr: Float?, rssi: Int?, hopsAway: Int?,
        batteryLevel: Int?, voltage: Float?, channelUtilization: Float?, airUtilTx: Float?, isFavorite: Bool,
        firstSeen: Int64, latitudeI: Int32? = nil, longitudeI: Int32? = nil, altitude: Int? = nil,
        positionTime: Int64? = nil, positionPrecision: Int? = nil, groundSpeed: Int? = nil,
        groundTrack: Int? = nil
    ) {
        self.nodeNum = nodeNum
        self.userId = userId
        self.longName = longName
        self.shortName = shortName
        self.hwModel = hwModel
        self.role = role
        self.publicKey = publicKey
        self.isUnmessagable = isUnmessagable
        self.lastHeard = lastHeard
        self.snr = snr
        self.rssi = rssi
        self.hopsAway = hopsAway
        self.batteryLevel = batteryLevel
        self.voltage = voltage
        self.channelUtilization = channelUtilization
        self.airUtilTx = airUtilTx
        self.isFavorite = isFavorite
        self.firstSeen = firstSeen
        self.latitudeI = latitudeI
        self.longitudeI = longitudeI
        self.altitude = altitude
        self.positionTime = positionTime
        self.positionPrecision = positionPrecision
        self.groundSpeed = groundSpeed
        self.groundTrack = groundTrack
    }

    public func toDomain() -> MeshNode {
        MeshNode(
            nodeNum: nodeNum, userId: userId, longName: longName, shortName: shortName, hwModel: hwModel,
            role: role, publicKey: publicKey, isUnmessagable: isUnmessagable, lastHeard: lastHeard, snr: snr,
            rssi: rssi, hopsAway: hopsAway, batteryLevel: batteryLevel, voltage: voltage,
            channelUtilization: channelUtilization, airUtilTx: airUtilTx, isFavorite: isFavorite,
            latitudeI: latitudeI, longitudeI: longitudeI, altitude: altitude, positionTime: positionTime,
            positionPrecision: positionPrecision, groundSpeed: groundSpeed, groundTrack: groundTrack)
    }

    public static func fromDomain(_ node: MeshNode, firstSeen: Int64) -> NodeEntity {
        NodeEntity(
            nodeNum: node.nodeNum, userId: node.userId, longName: node.longName, shortName: node.shortName,
            hwModel: node.hwModel, role: node.role, publicKey: node.publicKey, isUnmessagable: node.isUnmessagable,
            lastHeard: node.lastHeard, snr: node.snr, rssi: node.rssi, hopsAway: node.hopsAway,
            batteryLevel: node.batteryLevel, voltage: node.voltage, channelUtilization: node.channelUtilization,
            airUtilTx: node.airUtilTx, isFavorite: node.isFavorite, firstSeen: firstSeen, latitudeI: node.latitudeI,
            longitudeI: node.longitudeI, altitude: node.altitude, positionTime: node.positionTime,
            positionPrecision: node.positionPrecision, groundSpeed: node.groundSpeed, groundTrack: node.groundTrack)
    }
}

public struct RoomMemberEntity: Codable, FetchableRecord, PersistableRecord, Sendable, Equatable {
    public static let databaseTableName = "room_members"
    public var roomId: Int32
    public var nodeNum: Int32
    public var invitedBy: Int32?
    public var firstSeen: Int64
    public var lastHeard: Int64?
    public var lastOpenedGeneration: Int?
    public init(
        roomId: Int32,
        nodeNum: Int32,
        invitedBy: Int32?,
        firstSeen: Int64,
        lastHeard: Int64?,
        lastOpenedGeneration: Int? = nil
    ) {
        self.roomId = roomId
        self.nodeNum = nodeNum
        self.invitedBy = invitedBy
        self.firstSeen = firstSeen
        self.lastHeard = lastHeard
        self.lastOpenedGeneration = lastOpenedGeneration
    }
    public func toDomain() -> RoomMember {
        RoomMember(roomId: roomId, nodeNum: nodeNum, invitedBy: invitedBy, firstSeen: firstSeen, lastHeard: lastHeard)
    }
}

public struct ChannelStateEntity: Codable, FetchableRecord, PersistableRecord, Sendable, Equatable {
    public static let databaseTableName = "channel_state"
    public var channel: Int
    public var lastReadAt: Int64
    public var muted: Bool
    public var roomId: Int32
    public init(channel: Int, lastReadAt: Int64, muted: Bool, roomId: Int32 = 0) {
        self.channel = channel
        self.lastReadAt = lastReadAt
        self.muted = muted
        self.roomId = roomId
    }
}

public struct MapPinEntity: Codable, FetchableRecord, PersistableRecord, Sendable, Equatable {
    public static let databaseTableName = "map_pins"
    public var id: Int32
    public var channel: Int
    public var latitudeI: Int32
    public var longitudeI: Int32
    public var name: String
    public var description: String
    public var expire: Int64
    public var lockedTo: Int32
    public var icon: String?
    public var createdBy: Int32
    public var receivedAt: Int64
    public var roomId: Int32
    public init(
        id: Int32, channel: Int, latitudeI: Int32, longitudeI: Int32, name: String, description: String,
        expire: Int64, lockedTo: Int32, icon: String?, createdBy: Int32, receivedAt: Int64, roomId: Int32 = 0
    ) {
        self.id = id
        self.channel = channel
        self.latitudeI = latitudeI
        self.longitudeI = longitudeI
        self.name = name
        self.description = description
        self.expire = expire
        self.lockedTo = lockedTo
        self.icon = icon
        self.createdBy = createdBy
        self.receivedAt = receivedAt
        self.roomId = roomId
    }
    public func toDomain() -> MapPin {
        MapPin(
            id: id, channel: channel, latitudeI: latitudeI, longitudeI: longitudeI, name: name,
            description: description, expire: expire, lockedTo: lockedTo, icon: icon, createdBy: createdBy,
            receivedAt: receivedAt, roomId: roomId)
    }
    public static func fromDomain(_ pin: MapPin) -> MapPinEntity {
        MapPinEntity(
            id: pin.id, channel: pin.channel, latitudeI: pin.latitudeI, longitudeI: pin.longitudeI, name: pin.name,
            description: pin.description, expire: pin.expire, lockedTo: pin.lockedTo, icon: pin.icon,
            createdBy: pin.createdBy, receivedAt: pin.receivedAt, roomId: pin.roomId)
    }
}

public struct DeletedPinEntity: Codable, FetchableRecord, PersistableRecord, Sendable, Equatable {
    public static let databaseTableName = "deleted_pins"
    public var id: Int32
    public var channel: Int
    public var deletedAt: Int64
    public init(id: Int32, channel: Int, deletedAt: Int64) {
        self.id = id
        self.channel = channel
        self.deletedAt = deletedAt
    }
}

public struct ReceiptEntity: Codable, FetchableRecord, PersistableRecord, Sendable, Equatable {
    public static let databaseTableName = "receipts"
    public var messageId: Int32
    public var nodeNum: Int32
    public var state: ReceiptState
    public var at: Int64
    public init(messageId: Int32, nodeNum: Int32, state: ReceiptState, at: Int64) {
        self.messageId = messageId
        self.nodeNum = nodeNum
        self.state = state
        self.at = at
    }
    enum CodingKeys: String, CodingKey { case messageId, nodeNum, state, at }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        messageId = try c.decode(Int32.self, forKey: .messageId)
        nodeNum = try c.decode(Int32.self, forKey: .nodeNum)
        state = ReceiptState(name: try c.decode(String.self, forKey: .state)) ?? .received
        at = try c.decode(Int64.self, forKey: .at)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(messageId, forKey: .messageId)
        try c.encode(nodeNum, forKey: .nodeNum)
        try c.encode(state.name, forKey: .state)
        try c.encode(at, forKey: .at)
    }

    public enum Columns: String, ColumnExpression { case messageId, nodeNum, state, at }
    public init(row: Row) throws {
        messageId = row[Columns.messageId]
        nodeNum = row[Columns.nodeNum]
        state = ReceiptState(name: row[Columns.state]) ?? .received
        at = row[Columns.at]
    }
    public func encode(to container: inout PersistenceContainer) throws {
        container[Columns.messageId] = messageId
        container[Columns.nodeNum] = nodeNum
        container[Columns.state] = state.name
        container[Columns.at] = at
    }
}

public struct PersonCardEntity: Codable, FetchableRecord, PersistableRecord, Sendable, Equatable {
    public static let databaseTableName = "person_cards"
    public var nodeNum: Int32
    public var name: String
    public var tag: String
    public var colourSlot: Int?
    public var updatedAt: Int64
    public init(nodeNum: Int32, name: String, tag: String, colourSlot: Int?, updatedAt: Int64) {
        self.nodeNum = nodeNum
        self.name = name
        self.tag = tag
        self.colourSlot = colourSlot
        self.updatedAt = updatedAt
    }
    public func toDomain() -> PersonCard {
        PersonCard(nodeNum: nodeNum, name: name, tag: tag, colourSlot: colourSlot, updatedAt: updatedAt)
    }
}

public struct PeerKeyEntity: Codable, FetchableRecord, PersistableRecord, Sendable, Equatable {
    public static let databaseTableName = "peer_keys"
    public var nodeNum: Int32
    public var phoneKey: String
    public var learnedAt: Int64
    public var inPerson: Bool
    public init(nodeNum: Int32, phoneKey: String, learnedAt: Int64, inPerson: Bool = false) {
        self.nodeNum = nodeNum
        self.phoneKey = phoneKey
        self.learnedAt = learnedAt
        self.inPerson = inPerson
    }
}

public struct RoomActivityEntity: Codable, FetchableRecord, PersistableRecord, Sendable, Equatable {
    public static let databaseTableName = "room_activity"
    public var roomId: Int32
    public var joinedAt: Int64
    public var lastActivityAt: Int64
    public var muted: Bool
    public init(roomId: Int32, joinedAt: Int64, lastActivityAt: Int64, muted: Bool = false) {
        self.roomId = roomId
        self.joinedAt = joinedAt
        self.lastActivityAt = lastActivityAt
        self.muted = muted
    }
}

public struct PendingHandoverEntity: Codable, FetchableRecord, PersistableRecord, Sendable, Equatable {
    public static let databaseTableName = "pending_handovers"
    public var roomId: Int32
    public var nodeNum: Int32
    public var generation: Int
    public var heldGeneration: Int
    public var removed: String
    public var createdAt: Int64
    public var lastTriedAt: Int64
    public init(
        roomId: Int32, nodeNum: Int32, generation: Int, heldGeneration: Int, removed: String, createdAt: Int64,
        lastTriedAt: Int64
    ) {
        self.roomId = roomId
        self.nodeNum = nodeNum
        self.generation = generation
        self.heldGeneration = heldGeneration
        self.removed = removed
        self.createdAt = createdAt
        self.lastTriedAt = lastTriedAt
    }
}

public struct ChannelActivity: Codable, FetchableRecord, Sendable, Equatable {
    public var channel: Int
    public var lastAt: Int64
    public init(channel: Int, lastAt: Int64) {
        self.channel = channel
        self.lastAt = lastAt
    }
}
public struct RoomPlacement: Codable, FetchableRecord, Sendable, Equatable {
    public var channel: Int
    public var roomId: Int32
    public init(channel: Int, roomId: Int32) {
        self.channel = channel
        self.roomId = roomId
    }
}
public struct UnreadCount: Codable, FetchableRecord, Sendable, Equatable {
    public var channel: Int
    public var count: Int
    public init(channel: Int, count: Int) {
        self.channel = channel
        self.count = count
    }
}
