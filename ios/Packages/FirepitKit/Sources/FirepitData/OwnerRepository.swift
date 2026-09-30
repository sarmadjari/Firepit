import FirepitModel
import FirepitProtocol
import FirepitProtos
import Foundation
import os

/// What this radio calls itself to the rest of the mesh.
public struct Owner: Sendable, Equatable {
    public var longName: String
    public var shortName: String

    public init(longName: String, shortName: String) {
        self.longName = longName
        self.shortName = shortName
    }
}

/// Our own name, as everyone else sees it.
///
/// The radio owns this, not the phone: it is broadcast in NodeInfo, so changing
/// it here changes what appears in other people's chats and maps.
public final class OwnerRepository: Sendable {
    public let mesh: MeshRepository
    public let admin: NodeAdminClient
    public let owner: AsyncStream<Owner?>
    private let log = Logger(subsystem: "com.getfirepit.app", category: "FirepitOwner")

    public init(mesh: MeshRepository, admin: NodeAdminClient) {
        self.mesh = mesh
        self.admin = admin
        self.owner = Self.ownerStream(mesh: mesh)
    }

    /**
     * Renames this radio.
     *
     * The id is the node's own hex name and must be sent back unchanged, the
     * firmware treats a differing id as a request to change identity.
     */
    public func rename(longName: String, shortName: String) async throws {
        guard let myNodeNum = mesh.myNodeNum.value else {
            throw SendError.notConnected
        }
        let trimmedLong = OwnerName.longName(text: longName)
        guard !trimmedLong.isEmpty else {
            throw OwnerRepositoryError.emptyName
        }
        let fallback =
            shortName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? OwnerName.suggestShort(longName: trimmedLong) : shortName
        let trimmedShort = OwnerName.shortName(text: fallback)
        var user = User()
        user.id = MeshConstants.formatNodeId(myNodeNum)
        user.longName = trimmedLong
        user.shortName = trimmedShort
        try await admin.setOwner(user)
        try await mesh.setOwnName(nodeNum: myNodeNum, longName: trimmedLong, shortName: trimmedShort)
        log.info("renamed this node")
    }

    /// Our own node's names, recomputed when either the node list or which node is ours changes — Kotlin combines
    /// the two, so switching radios updates the owner even when the node list itself did not change.
    private static func ownerStream(mesh: MeshRepository) -> AsyncStream<Owner?> {
        AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let latest = Mutex<[MeshNode]?>(nil)
            let emit: @Sendable () -> Void = {
                guard let nodes = latest.withLock({ $0 }) else {
                    return
                }
                let myNodeNum = mesh.myNodeNum.value
                let node = nodes.first { $0.nodeNum == myNodeNum }
                continuation.yield(node.map { Owner(longName: $0.longName ?? "", shortName: $0.shortName ?? "") })
            }
            let nodesTask = Task {
                for await nodes in mesh.observeNodes() {
                    latest.withLock { $0 = nodes }
                    emit()
                }
                continuation.finish()
            }
            let ownerTask = Task {
                for await _ in mesh.myNodeNum.subscribe() {
                    emit()
                }
            }
            continuation.onTermination = { _ in
                nodesTask.cancel()
                ownerTask.cancel()
            }
        }
    }
}

public enum OwnerRepositoryError: Error, Sendable, Equatable, LocalizedError {
    case emptyName

    /// Android's `require` message.
    public var errorDescription: String? {
        switch self {
        case .emptyName: "A name cannot be empty"
        }
    }
}
