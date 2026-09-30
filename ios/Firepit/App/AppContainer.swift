import FirepitData
import FirepitModel
import FirepitTransport
import Foundation
import Observation
import os

private nonisolated let containerLog = Logger(subsystem: "com.getfirepit.app", category: "FirepitApp")

/// Everything the app is made of, built once. The iOS counterpart of Android's Hilt graph (android/app/…/di and the
/// `@Singleton` classes it wires) and of `FirepitApplication.onCreate`, which starts the parts that must outlive every
/// screen.
@Observable
final class AppContainer {
    /// Where the app keeps its state and which radio it talks to.
    enum Configuration {
        /// The real thing: the protected database, the Keychain, and radios over Bluetooth.
        case live
        #if DEBUG
            /// Debug builds launched with `-demo`: an in-memory world around a pretend radio (Features/Demo), so every
            /// screen can be seen populated in the simulator. Nothing is read from or written to real storage.
            case demo
        #endif
    }

    let configuration: Configuration

    // MARK: Storage

    let messageDao: MessageDao
    let nodeDao: NodeDao
    let mapPinDao: MapPinDao
    let channelStateDao: ChannelStateDao
    let roomMemberDao: RoomMemberDao
    let receiptDao: ReceiptDao
    let personCardDao: PersonCardDao
    let peerKeyDao: PeerKeyDao
    let roomActivityDao: RoomActivityDao
    let pendingHandoverDao: PendingHandoverDao

    // MARK: Secrets and small state

    let sessionStore: SessionStore
    let sharingStore: SharingStore
    let roomKeys: RoomKeyStore
    let phoneKeys: PhoneKeyStore
    let primaryBackup: PrimaryBackup
    let chatPresence = ChatPresence()
    let phoneLocation: any PhoneLocationProviding

    // MARK: Radio

    let central: BluetoothCentral
    let link: RadioLink
    let scanner: RadioScanner
    let presence: BluetoothPresence
    /// What the repositories talk to: `link`, or the pretend radio in a demo.
    let radio: any RadioLinking

    // MARK: Repositories

    let mesh: MeshRepository
    let admin: NodeAdminClient
    let alerts: AlertClient
    let owners: OwnerRepository
    let nodeClock: NodeClock
    let history: RoomHistory
    let receipts: ReceiptRepository
    let range: RangeRepository
    let rooms: RoomRepository
    let location: LocationRepository
    let waypoints: WaypointRepository
    let traceroute: TracerouteClient

    // MARK: App

    let router = AppRouter()
    let savedRadios: SavedRadioStore
    let themePreferences = ThemePreferences()
    let notificationPreferences = NotificationPreferences()
    let screenPrivacy = ScreenPrivacyPreferences()
    let mapPreferences = MapPreferences()
    let secureWindow = SecureWindow()
    let offlineMaps = OfflineMapRepository()
    let people: PersonStore
    let retention: RetentionStore
    let notifier: MessageNotifier
    let session: RadioSessionController

    init(configuration: Configuration = .live) throws {
        self.configuration = configuration
        let daos: FirepitDaos
        let defaults: UserDefaults
        central = BluetoothCentral()
        link = RadioLink(central: central)
        switch configuration {
        case .live:
            daos = try FirepitDaos.open(at: Self.databaseDirectory())
            defaults = .standard
            roomKeys = RoomKeyStore()
            phoneKeys = PhoneKeyStore()
            primaryBackup = PrimaryBackup()
            radio = link
            phoneLocation = PhoneLocationSource()
        #if DEBUG
            case .demo:
                daos = try FirepitDaos.inMemory()
                defaults = DemoWorld.freshDefaults()
                roomKeys = RoomKeyStore(store: InMemorySecretStore())
                phoneKeys = PhoneKeyStore(store: InMemorySecretStore(), source: SoftwarePhoneKeySource())
                primaryBackup = PrimaryBackup(store: InMemorySecretStore())
                radio = DemoRadio()
                phoneLocation = DemoLocationSource()
        #endif
        }
        messageDao = daos.messageDao
        nodeDao = daos.nodeDao
        mapPinDao = daos.mapPinDao
        channelStateDao = daos.channelStateDao
        roomMemberDao = daos.roomMemberDao
        receiptDao = daos.receiptDao
        personCardDao = daos.personCardDao
        peerKeyDao = daos.peerKeyDao
        roomActivityDao = daos.roomActivityDao
        pendingHandoverDao = daos.pendingHandoverDao
        sessionStore = SessionStore(defaults: defaults)
        sharingStore = SharingStore(defaults: defaults)
        savedRadios = SavedRadioStore(defaults: defaults)

        scanner = RadioScanner(central: central)
        presence = BluetoothPresence(central: central)

        mesh = MeshRepository(
            link: radio,
            messageDao: messageDao,
            nodeDao: nodeDao,
            sessionStore: sessionStore,
            roomKeys: roomKeys,
            peerKeyDao: peerKeyDao,
            memberDao: roomMemberDao,
            phoneKeys: phoneKeys,
            roomActivity: roomActivityDao
        )
        admin = NodeAdminClient(link: radio, repository: mesh)
        alerts = AlertClient(link: radio, mesh: mesh)
        owners = OwnerRepository(mesh: mesh, admin: admin)
        nodeClock = NodeClock(link: radio, mesh: mesh, admin: admin)
        history = RoomHistory(
            mesh: mesh,
            messageDao: messageDao,
            pinDao: mapPinDao,
            channelState: channelStateDao,
            roomActivity: roomActivityDao,
            sessionStore: sessionStore
        )
        receipts = ReceiptRepository(
            link: radio,
            mesh: mesh,
            roomKeys: roomKeys,
            receiptDao: receiptDao,
            messageDao: messageDao,
            memberDao: roomMemberDao,
            phoneKeys: phoneKeys
        )
        range = RangeRepository(mesh: mesh, admin: admin, backup: primaryBackup, defaults: defaults)
        rooms = RoomRepository(
            link: radio,
            mesh: mesh,
            admin: admin,
            memberDao: roomMemberDao,
            messageDao: messageDao,
            receipts: receipts,
            roomKeys: roomKeys,
            range: range,
            personCardDao: personCardDao,
            phoneKeys: phoneKeys,
            peerKeyDao: peerKeyDao,
            pinDao: mapPinDao,
            roomActivity: roomActivityDao,
            handovers: pendingHandoverDao,
            history: history,
            sharingStore: sharingStore
        )
        location = LocationRepository(
            mesh: mesh,
            admin: admin,
            phoneLocation: phoneLocation,
            rooms: rooms,
            sharingStore: sharingStore
        )
        waypoints = WaypointRepository(mesh: mesh, rooms: rooms, pinDao: mapPinDao)
        traceroute = TracerouteClient(link: radio, mesh: mesh, rooms: rooms)
        people = PersonStore(rooms: rooms, defaults: defaults)
        retention = RetentionStore(
            defaults: defaults, daos: daos, mesh: mesh, rooms: rooms, offlineMaps: offlineMaps)
        session = RadioSessionController(
            link: link,
            sessionStore: sessionStore,
            savedRadios: savedRadios,
            primaryBackup: primaryBackup
        )
        notifier = MessageNotifier(
            mesh: mesh,
            channelState: channelStateDao,
            presence: chatPresence,
            notificationPreferences: notificationPreferences,
            rooms: rooms,
            router: router
        )
    }

    @ObservationIgnored private var started = false

    /// Starts what must outlive every screen, in Android's order (FirepitApplication.onCreate). Once per process.
    func start() {
        guard !started else { return }
        started = true
        #if DEBUG
            if case .demo = configuration {
                // The pretend radio's rooms and keys are in place before anything reads them.
                DemoWorld.prepare(self)
            }
        #endif
        mapPreferences.apply()
        mesh.start()
        // Before anything can transmit: a radio still on the factory primary announces its owner's name to every
        // Meshtastic device in range.
        range.start()
        rooms.start()
        history.start()
        waypoints.start()
        location.start()
        notifier.start()
        nodeClock.start()
        retention.start()
        session.start()
        if !screenPrivacy.allowCapture {
            secureWindow.hold(Self.screenSetting)
        }
        #if DEBUG
            if case .demo = configuration {
                DemoWorld.run(self)
            }
        #endif
        containerLog.info("started")
    }

    /// Stands for the screen-privacy setting among whoever holds the window secure.
    static let screenSetting = "screen-privacy-setting"

    /// Application Support/Database: never in the Documents folder the Files app shows, and excluded from backup by
    /// `FirepitDatabase.open`.
    private static func databaseDirectory() throws -> URL {
        try FileManager.default
            .url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("Database", isDirectory: true)
    }
}
