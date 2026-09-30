import FirepitModel
import FirepitProtocol
import FirepitProtos
import Foundation
import os

/// How far our traffic travels, and who decides what slot 0 looks like.
///
/// The primary channel cannot be removed — the firmware uses it to set the
/// radio's frequency, and `channel.proto` says every other enabled channel must
/// be SECONDARY. What Firepit can do is refuse to take it over uninvited.
///
/// A radio may already be somebody's Meshtastic node, on a mesh with their
/// friends. Rewriting slot 0 would silently drop it off that mesh, and they
/// would have no way to know why. So nothing is written until the person says
/// so: either by choosing a range here, or by joining a Firepit room from an
/// invite, which carries the mode and cannot work without it.
///
/// Until then `primaryIsPublic` reports the truth — that the node's name and
/// battery are readable by any Meshtastic radio — rather than quietly fixing it.
public final class RangeRepository: Sendable {
    public let mesh: MeshRepository
    public let admin: NodeAdminClient
    public let backup: PrimaryBackup

    private let preferences: Mutex<UserDefaults>

    // One write at a time: a mode change racing the reconnect check would
    // otherwise have them both read the old slot and write different names.
    private let mutex = AsyncMutex()
    private let tasks = Mutex<[Task<Void, Never>]>([])
    private let log = Logger(subsystem: "com.getfirepit.app", category: "FirepitRange")

    public let mode: CurrentValue<RangeMode>

    /**
     * True while the connected radio's primary still carries a key every
     * Meshtastic radio holds. Shown rather than hidden: until it is rewritten,
     * this node's identity is public.
     */
    public let primaryIsPublic = CurrentValue(false)

    /**
     * Whether the person has said what the connected radio's primary should
     * be. Asked per radio: a second radio may be somebody's node on another
     * mesh, and a yes given for the first is not a yes for it.
     */
    public let privacy = CurrentValue(RadioPrivacy.undecided)

    /**
     * True when the radio is connected, its identity is public, and nobody has
     * chosen yet — the one moment worth interrupting for.
     */
    public let needsChoice = CurrentValue(false)

    /**
     * True when the radio's own primary was recorded and can be put back.
     *
     * False once it has been, and for a radio Firepit never took over — there
     * is nothing to return it to.
     */
    public let canRestore = CurrentValue(false)

    public init(
        mesh: MeshRepository,
        admin: NodeAdminClient,
        backup: PrimaryBackup,
        defaults: UserDefaults = .standard
    ) {
        self.mesh = mesh
        self.admin = admin
        self.backup = backup
        self.preferences = Mutex(defaults)
        self.mode = CurrentValue(RangeMode.named(defaults.string(forKey: Self.keyMode)))
        adoptSingleChoice(nodeNum: mesh.myNodeNum.value)
        self.privacy.set(storedPrivacy(nodeNum: mesh.myNodeNum.value))
        refreshDerived()
    }

    deinit {
        tasks.withLock { jobs in
            for job in jobs {
                job.cancel()
            }
            jobs.removeAll()
        }
    }

    /// Starts the per-connection check. Safe to call once per process.
    public func start() {
        tasks.withLock { jobs in
            guard jobs.isEmpty else {
                return
            }
            jobs.append(
                Task { [weak self] in
                    guard let self else {
                        return
                    }
                    for await connected in self.mesh.isConnected.subscribe() {
                        if !connected {
                            self.primaryIsPublic.set(false)
                            self.refreshDerived()
                            continue
                        }
                        // Nothing can be read back until the config download lands.
                        let ready = try? await withTimeoutOrNil(Self.channelsTimeout) {
                            await self.mesh.channels.subscribe().first { !$0.isEmpty }
                        }
                        if ready == nil {
                            self.log.warning("radio never listed its channels")
                            continue
                        }
                        let nodeNum = self.mesh.myNodeNum.value
                        self.adoptSingleChoice(nodeNum: nodeNum)
                        self.privacy.set(self.storedPrivacy(nodeNum: nodeNum))
                        self.refreshDerived()
                        do {
                            try await self.inspect()
                        } catch {
                            self.log.warning("could not read the primary channel")
                        }
                    }
                })
        }
    }

    /**
     * Makes this radio's identity private, keeping whatever range is already
     * chosen. What the person is actually answering when asked.
     */
    public func makePrivate() async throws {
        try await choose(choice: mode.value)
    }

    /**
     * Moves this node to `choice`, and takes the primary over if it has not
     * been already. Remembered even when the radio is not connected, so the
     * next connection applies it.
     *
     * Everyone in a group must be on the same mode or they cannot hear each
     * other at all, which is why invites carry it.
     */
    public func choose(choice: RangeMode) async throws {
        preferences.withLock { defaults in
            defaults.set(choice.name, forKey: Self.keyMode)
        }
        mode.set(choice)
        rememberPrivacy(nodeNum: mesh.myNodeNum.value, privacy: .firepit)
        if !mesh.isConnected.value {
            return
        }
        try await reconcile(choice: choice)
    }

    /**
     * Leaves the radio's own primary exactly as it is, or puts it back if
     * Firepit already replaced it.
     *
     * For somebody who already uses this node on their own mesh: Firepit rooms
     * are secondary channels and work regardless, so their existing setup keeps
     * working. Restoring is the whole reason the old slot was recorded — an
     * offer to keep something public has to be able to undo having made it
     * private, or it is not an offer.
     */
    public func keepPublic() async throws {
        let nodeNum = mesh.myNodeNum.value
        let original = nodeNum.flatMap { mesh.isConnected.value ? backup.saved(nodeNum: $0) : nil }
        if let original, let nodeNum {
            // Thrown on rather than swallowed: recording the choice while the
            // radio still carries Firepit's channel would be a lie.
            try await mutex.withLock {
                try await self.admin.setChannel(original)
            }
            try backup.forget(nodeNum: nodeNum)
            primaryIsPublic.set(true)
            log.info("put the radio's own primary channel back")
        }
        rememberPrivacy(nodeNum: nodeNum ?? mesh.myNodeNum.value, privacy: .open)
        if original == nil {
            log.info("leaving the radio's own primary channel alone")
        }
    }

    private func storedPrivacy(nodeNum: Int32?) -> RadioPrivacy {
        guard let nodeNum else {
            return .undecided
        }
        return preferences.withLock { defaults in
            RadioPrivacy.named(defaults.string(forKey: privacyKey(nodeNum: nodeNum)))
        }
    }

    private func rememberPrivacy(nodeNum: Int32?, privacy: RadioPrivacy) {
        if let nodeNum {
            preferences.withLock { defaults in
                defaults.set(privacy.name, forKey: privacyKey(nodeNum: nodeNum))
            }
        }
        self.privacy.set(privacy)
        refreshDerived()
    }

    /**
     * One choice used to cover every radio. It was made about the radio in use
     * then, which is the one connecting now the first time round, so it is
     * kept for that radio alone.
     */
    private func adoptSingleChoice(nodeNum: Int32?) {
        guard let nodeNum else {
            return
        }
        preferences.withLock { defaults in
            guard let single = defaults.string(forKey: Self.keyPrivacy) else {
                return
            }
            let nodeKey = privacyKey(nodeNum: nodeNum)
            if !defaults.bool(forKey: nodeKey) && defaults.string(forKey: nodeKey) == nil {
                defaults.set(single, forKey: nodeKey)
            }
            defaults.removeObject(forKey: Self.keyPrivacy)
        }
    }

    private func privacyKey(nodeNum: Int32) -> String {
        "\(Self.keyPrivacy).\(nodeNum)"
    }

    /**
     * Adopts the mode an invite was issued under.
     *
     * A joiner on a different mode is on a different frequency and would never
     * hear the room at all, so this is applied rather than offered: accepting
     * the invite is the consent.
     */
    public func alignWith(choice: RangeMode) async throws {
        if privacy.value == .firepit && choice == mode.value && !primaryIsPublic.value {
            return
        }
        log.info("aligning range to invite")
        try await choose(choice: choice)
    }

    /**
     * Reads slot 0 back and reports whether it is still public.
     *
     * Writes only for a radio whose primary we were told to take over, and then
     * through the same path the settings screen uses — an earlier version had
     * two routes to this decision that disagreed about what to do when the slot
     * could not be read, so a choice could silently never apply.
     */
    private func inspect() async throws {
        if privacy.value == .firepit {
            try await reconcile(choice: mode.value)
            return
        }
        try await mutex.withLock {
            self.primaryIsPublic.set(PrimaryChannel.isPublic(await self.admin.getChannel(index: PrimaryChannel.slot)))
            self.refreshDerived()
        }
    }

    private func reconcile(choice: RangeMode) async throws {
        try await mutex.withLock {
            let current = await self.admin.getChannel(index: PrimaryChannel.slot)
            if current != nil && PrimaryChannel.matches(current, mode: choice) {
                self.primaryIsPublic.set(false)
                self.refreshDerived()
                self.log.info("primary channel already set")
                return
            }
            // An unconfirmed slot might still be the factory one, and the cost of a
            // redundant write is a flash page against a public identity.
            if current == nil {
                self.log.warning("primary slot would not read back")
            }
            try await self.write(choice: choice, replacing: current)
        }
    }

    private func write(choice: RangeMode, replacing: Channel?) async throws {
        // Recorded before the overwrite, and only if it is somebody else's:
        // one of our own channels is not worth returning a radio to.
        let nodeNum = mesh.myNodeNum.value
        if let nodeNum,
            let replacing,
            PrimaryChannel.modeOf(replacing) == nil,
            replacing.carriesAKey()
        {
            try backup.remember(nodeNum: nodeNum, channel: replacing)
        }

        try await admin.setChannel(PrimaryChannel.channelFor(mode: choice))
        primaryIsPublic.set(false)
        refreshDerived()
        log.info("primary channel set")
    }

    private func refreshDerived() {
        let connected = mesh.isConnected.value
        let nodeNum = mesh.myNodeNum.value
        needsChoice.set(connected && privacy.value == .undecided && primaryIsPublic.value)
        canRestore.set(
            connected && nodeNum != nil && privacy.value == .firepit && backup.has(nodeNum: nodeNum!)
        )
    }

    private static let keyMode = "range_mode"
    private static let keyPrivacy = "radio_privacy"

    /// A radio that has not listed its channels by now is not going to.
    private static let channelsTimeout: Duration = .seconds(30)
}
