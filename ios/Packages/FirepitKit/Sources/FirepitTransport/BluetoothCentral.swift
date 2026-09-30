@preconcurrency import CoreBluetooth
import FirepitModel
import FirepitProtocol
import Foundation

/// The phone's Bluetooth, for radios.
///
/// Every CoreBluetooth object lives inside this actor, whose executor *is* CoreBluetooth's delegate queue: callbacks
/// enter it synchronously, and no peripheral or characteristic ever crosses an isolation boundary. One per app.
public actor BluetoothCentral {
    private let queue: DispatchSerialQueue
    public nonisolated var unownedExecutor: UnownedSerialExecutor { queue.asUnownedSerialExecutor() }

    private let delegate: BluetoothDelegate
    private let options: [String: Any]
    private var created: CBCentralManager?

    /// CoreBluetooth, created on first use. Creating it is what makes iOS ask for Bluetooth permission, so a new user
    /// is asked when they go to connect a radio — as Android asks on its devices screen — not the moment the app
    /// opens. Once permission has been given it is created at launch instead, because state restoration only
    /// relaunches an app that recreates its central straight away.
    private var manager: CBCentralManager {
        if let created {
            return created
        }
        let manager = CBCentralManager(delegate: delegate, queue: queue, options: options)
        created = manager
        return manager
    }

    /// Whether Bluetooth can be used, followable by the radio screens.
    public nonisolated let availability = CurrentValue(BluetoothAvailability.unknown)

    private var powerWaiters: [CheckedContinuation<Void, Error>] = []
    private var scanners: [UUID: AsyncStream<DiscoveredRadio>.Continuation] = [:]
    private var seen: [UUID: CBPeripheral] = [:]
    private var connections: [UUID: Connection] = [:]
    /// Peripherals the system handed back after relaunching the app in the background (state restoration).
    private var restored: [UUID: CBPeripheral] = [:]

    /// - Parameter restoreIdentifier: lets iOS relaunch the app in the background for its radio, keeping the
    ///   connection that the Android app keeps with a foreground service.
    public init(restoreIdentifier: String? = "com.getfirepit.app.central") {
        let queue = DispatchSerialQueue(label: "com.getfirepit.bluetooth")
        let delegate = BluetoothDelegate()
        var options: [String: Any] = [CBCentralManagerOptionShowPowerAlertKey: false]
        if let restoreIdentifier {
            options[CBCentralManagerOptionRestoreIdentifierKey] = restoreIdentifier
        }
        self.queue = queue
        self.delegate = delegate
        self.options = options
        if CBCentralManager.authorization == .allowedAlways {
            created = CBCentralManager(delegate: delegate, queue: queue, options: options)
        }
        delegate.attach(self)
    }

    // MARK: Availability

    /// Returns once Bluetooth is on, or throws if it is off, refused or missing — never waits for the person.
    func requirePoweredOn() async throws {
        switch BluetoothAvailability(manager.state) {
        case .poweredOn:
            return
        case .unknown, .resetting:
            try await withCheckedThrowingContinuation { powerWaiters.append($0) }
        case let other:
            throw BluetoothError.unavailable(other)
        }
    }

    fileprivate func handleStateChange() {
        let state = BluetoothAvailability(manager.state)
        availability.set(state)
        switch state {
        case .poweredOn:
            let waiters = powerWaiters
            powerWaiters.removeAll()
            waiters.forEach { $0.resume() }
            if !scanners.isEmpty { startScan() }
        case .unknown, .resetting:
            break
        default:
            let waiters = powerWaiters
            powerWaiters.removeAll()
            waiters.forEach { $0.resume(throwing: BluetoothError.unavailable(state)) }
            for (id, connection) in connections {
                connection.close(BluetoothError.unavailable(state))
                connections[id] = nil
            }
        }
    }

    // MARK: Scanning

    /// Radios advertising the Meshtastic service, re-emitted every time they advertise. Filtering by service UUID is
    /// native, so the system optimises the scan and other people's peripherals never appear. Scanning stops when the
    /// last subscriber stops iterating.
    public func scan() -> AsyncStream<DiscoveredRadio> {
        let id = UUID()
        let (stream, continuation) = AsyncStream<DiscoveredRadio>.makeStream(bufferingPolicy: .bufferingNewest(64))
        continuation.onTermination = { [weak self] _ in
            Task { await self?.stopScanning(id) }
        }
        scanners[id] = continuation
        if manager.state == .poweredOn { startScan() }
        return stream
    }

    /// Deduplicated, strongest first — what a device picker should show.
    public func scanDistinct() -> AsyncStream<[DiscoveredRadio]> {
        let radios = scan()
        return AsyncStream { continuation in
            let task = Task {
                var found: [String: DiscoveredRadio] = [:]
                for await radio in radios {
                    found[radio.identifier] = radio
                    continuation.yield(found.values.sorted { $0.rssi > $1.rssi })
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private func startScan() {
        manager.scanForPeripherals(
            withServices: [MeshtasticGatt.service],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
    }

    private func stopScanning(_ id: UUID) {
        scanners[id] = nil
        if scanners.isEmpty, manager.state == .poweredOn { manager.stopScan() }
    }

    fileprivate func handleDiscovery(_ peripheral: CBPeripheral, advertisedName: String?, rssi: Int) {
        seen[peripheral.identifier] = peripheral
        let radio = DiscoveredRadio(
            identifier: peripheral.identifier.uuidString,
            name: advertisedName ?? peripheral.name,
            rssi: rssi
        )
        scanners.values.forEach { $0.yield(radio) }
    }

    /// Radios holding a live connection to this phone, to Firepit or to any other app. A radio accepts one PhoneAPI
    /// client, so one connected elsewhere explains why Firepit cannot reach it.
    public func connectedRadios() -> Set<String> {
        guard manager.state == .poweredOn else { return [] }
        let connected = manager.retrieveConnectedPeripherals(withServices: [MeshtasticGatt.service])
        return Set(connected.map(\.identifier.uuidString))
    }

    // MARK: Connecting

    /// Connects, discovers the Meshtastic service and hands back the PhoneAPI transport.
    ///
    /// iOS connection requests never time out: a radio out of range connects the moment it comes back, which is what
    /// Android's autoConnect does, at no battery cost. Cancelling the calling task abandons the attempt.
    public func connect(to radio: String) async throws -> BleRadioTransport {
        guard let identifier = UUID(uuidString: radio) else { throw BluetoothError.unknownRadio }
        try await requirePoweredOn()
        guard
            let peripheral = seen[identifier] ?? restored[identifier]
                ?? manager.retrievePeripherals(withIdentifiers: [identifier]).first
        else { throw BluetoothError.unknownRadio }

        connections[identifier]?.close(BluetoothError.disconnected("replaced by a new connection"))
        let connection = Connection(peripheral: peripheral)
        connections[identifier] = connection
        peripheral.delegate = delegate

        do {
            try await withTaskCancellationHandler {
                if peripheral.state != .connected {
                    try await withCheckedThrowingContinuation { continuation in
                        connection.connected = continuation
                        manager.connect(peripheral, options: nil)
                    }
                }
                try await withCheckedThrowingContinuation { continuation in
                    connection.servicesDiscovered = continuation
                    peripheral.discoverServices([MeshtasticGatt.service])
                }
                guard let service = peripheral.services?.first(where: { $0.uuid == MeshtasticGatt.service }) else {
                    throw BluetoothError.notAMeshtasticRadio
                }
                try await withCheckedThrowingContinuation { continuation in
                    connection.characteristicsDiscovered = continuation
                    peripheral.discoverCharacteristics(
                        [MeshtasticGatt.toRadio, MeshtasticGatt.fromRadio, MeshtasticGatt.fromNum], for: service)
                }
                let characteristics = service.characteristics ?? []
                guard let toRadio = characteristics.first(where: { $0.uuid == MeshtasticGatt.toRadio }),
                    let fromRadio = characteristics.first(where: { $0.uuid == MeshtasticGatt.fromRadio }),
                    let fromNum = characteristics.first(where: { $0.uuid == MeshtasticGatt.fromNum })
                else { throw BluetoothError.notAMeshtasticRadio }
                connection.toRadio = toRadio
                connection.fromRadio = fromRadio
                connection.fromNum = fromNum
            } onCancel: {
                Task { await self.disconnect(identifier) }
            }
        } catch {
            disconnect(identifier)
            throw error
        }
        return BleRadioTransport(central: self, identifier: identifier, dataAvailable: connection.dataAvailable)
    }

    /// Hangs up on a radio: pending operations fail and its transport's notifications end.
    public func disconnect(_ radio: String) {
        guard let identifier = UUID(uuidString: radio) else { return }
        disconnect(identifier)
    }

    func disconnect(_ identifier: UUID) {
        guard let connection = connections.removeValue(forKey: identifier) else { return }
        connection.close(BluetoothError.disconnected("disconnected"))
        manager.cancelPeripheralConnection(connection.peripheral)
    }

    // MARK: PhoneAPI operations, used by BleRadioTransport

    func enableNotifications(_ identifier: UUID) async throws {
        let connection = try live(identifier)
        guard let fromNum = connection.fromNum else { throw BluetoothError.notAMeshtasticRadio }
        if fromNum.isNotifying { return }
        try await withCheckedThrowingContinuation { continuation in
            connection.notifying = continuation
            connection.peripheral.setNotifyValue(true, for: fromNum)
        }
    }

    func write(_ frame: Data, to identifier: UUID) async throws {
        let connection = try live(identifier)
        guard let toRadio = connection.toRadio else { throw BluetoothError.notAMeshtasticRadio }
        try await withCheckedThrowingContinuation { continuation in
            connection.writes.append(continuation)
            connection.peripheral.writeValue(frame, for: toRadio, type: .withResponse)
        }
    }

    func read(from identifier: UUID) async throws -> Data? {
        let connection = try live(identifier)
        guard let fromRadio = connection.fromRadio else { throw BluetoothError.notAMeshtasticRadio }
        return try await withCheckedThrowingContinuation { continuation in
            connection.reads.append(continuation)
            connection.peripheral.readValue(for: fromRadio)
        }
    }

    private func live(_ identifier: UUID) throws -> Connection {
        guard let connection = connections[identifier], connection.failure == nil else {
            throw BluetoothError.disconnected("not connected")
        }
        return connection
    }

    // MARK: Delegate events (always on the actor's queue)

    fileprivate func handleConnected(_ peripheral: CBPeripheral) {
        connections[peripheral.identifier]?.resumeConnected(nil)
    }

    fileprivate func handleConnectFailed(_ peripheral: CBPeripheral, error: Error?) {
        let failure = BluetoothError.connectionFailed(error?.localizedDescription ?? "the radio refused the connection")
        connections.removeValue(forKey: peripheral.identifier)?.close(failure)
    }

    fileprivate func handleDisconnected(_ peripheral: CBPeripheral, error: Error?) {
        let cause = BluetoothError.disconnected(error?.localizedDescription ?? "the radio went away")
        connections.removeValue(forKey: peripheral.identifier)?.close(cause)
    }

    fileprivate func handleServices(_ peripheral: CBPeripheral, error: Error?) {
        connections[peripheral.identifier]?.resumeServices(error)
    }

    fileprivate func handleCharacteristics(_ peripheral: CBPeripheral, error: Error?) {
        connections[peripheral.identifier]?.resumeCharacteristics(error)
    }

    fileprivate func handleValue(_ peripheral: CBPeripheral, characteristic: CBCharacteristic, error: Error?) {
        guard let connection = connections[peripheral.identifier] else { return }
        if characteristic.uuid == MeshtasticGatt.fromNum {
            if error == nil, let value = characteristic.value {
                connection.dataContinuation.yield(MeshtasticGatt.fromNumValue(value))
            }
        } else if characteristic.uuid == MeshtasticGatt.fromRadio, !connection.reads.isEmpty {
            let read = connection.reads.removeFirst()
            if let error {
                read.resume(throwing: error)
            } else {
                let value = characteristic.value ?? Data()
                read.resume(returning: value.isEmpty ? nil : value)
            }
        }
    }

    fileprivate func handleWrite(_ peripheral: CBPeripheral, characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == MeshtasticGatt.toRadio,
            let connection = connections[peripheral.identifier], !connection.writes.isEmpty
        else { return }
        let write = connection.writes.removeFirst()
        if let error { write.resume(throwing: error) } else { write.resume() }
    }

    fileprivate func handleNotificationState(
        _ peripheral: CBPeripheral, characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard characteristic.uuid == MeshtasticGatt.fromNum,
            let notifying = connections[peripheral.identifier]?.notifying
        else { return }
        connections[peripheral.identifier]?.notifying = nil
        if let error { notifying.resume(throwing: error) } else { notifying.resume() }
    }

    fileprivate func handleRestore(_ identifiers: [UUID]) {
        for peripheral in manager.retrievePeripherals(withIdentifiers: identifiers) {
            restored[peripheral.identifier] = peripheral
        }
    }
}

/// One radio's connection: its characteristics and the operations waiting on them.
private final class Connection {
    let peripheral: CBPeripheral
    var toRadio: CBCharacteristic?
    var fromRadio: CBCharacteristic?
    var fromNum: CBCharacteristic?

    var connected: CheckedContinuation<Void, Error>?
    var servicesDiscovered: CheckedContinuation<Void, Error>?
    var characteristicsDiscovered: CheckedContinuation<Void, Error>?
    var notifying: CheckedContinuation<Void, Error>?
    var reads: [CheckedContinuation<Data?, Error>] = []
    var writes: [CheckedContinuation<Void, Error>] = []
    private(set) var failure: Error?

    let dataAvailable: AsyncStream<Int>
    let dataContinuation: AsyncStream<Int>.Continuation

    init(peripheral: CBPeripheral) {
        self.peripheral = peripheral
        (dataAvailable, dataContinuation) = AsyncStream<Int>.makeStream(bufferingPolicy: .bufferingNewest(64))
    }

    func resumeConnected(_ error: Error?) {
        let continuation = connected
        connected = nil
        if let error { continuation?.resume(throwing: error) } else { continuation?.resume() }
    }

    func resumeServices(_ error: Error?) {
        let continuation = servicesDiscovered
        servicesDiscovered = nil
        if let error { continuation?.resume(throwing: error) } else { continuation?.resume() }
    }

    func resumeCharacteristics(_ error: Error?) {
        let continuation = characteristicsDiscovered
        characteristicsDiscovered = nil
        if let error { continuation?.resume(throwing: error) } else { continuation?.resume() }
    }

    /// Fails everything waiting and ends notifications, so the PhoneAPI session sees the link go and reconnects.
    func close(_ error: Error) {
        guard failure == nil else { return }
        failure = error
        resumeConnected(error)
        resumeServices(error)
        resumeCharacteristics(error)
        notifying?.resume(throwing: error)
        notifying = nil
        reads.forEach { $0.resume(throwing: error) }
        reads.removeAll()
        writes.forEach { $0.resume(throwing: error) }
        writes.removeAll()
        dataContinuation.finish()
    }
}

/// CoreBluetooth's delegate. Its callbacks arrive on the central's queue, which is the actor's executor, so each one
/// enters the actor synchronously.
private final class BluetoothDelegate: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, @unchecked Sendable {
    // Written once from the actor's init, before any callback can reach it through the actor.
    private let owner = Mutex<BluetoothCentral?>(nil)

    func attach(_ central: BluetoothCentral) {
        owner.withLock { $0 = central }
    }

    private func withCentral(_ body: (isolated BluetoothCentral) -> Void) {
        guard let central = owner.withLock({ $0 }) else { return }
        central.assumeIsolated(body)
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        withCentral { $0.handleStateChange() }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        // Only identifiers cross into the actor; it fetches its own peripheral objects for them.
        let identifiers = (dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []).map(\.identifier)
        withCentral { $0.handleRestore(identifiers) }
    }

    func centralManager(
        _ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any], rssi RSSI: NSNumber
    ) {
        let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        let rssi = RSSI.intValue
        withCentral { $0.handleDiscovery(peripheral, advertisedName: name, rssi: rssi) }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        withCentral { $0.handleConnected(peripheral) }
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        withCentral { $0.handleConnectFailed(peripheral, error: error) }
    }

    func centralManager(
        _ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        withCentral { $0.handleDisconnected(peripheral, error: error) }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        withCentral { $0.handleServices(peripheral, error: error) }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        withCentral { $0.handleCharacteristics(peripheral, error: error) }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        withCentral { $0.handleValue(peripheral, characteristic: characteristic, error: error) }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        withCentral { $0.handleWrite(peripheral, characteristic: characteristic, error: error) }
    }

    func peripheral(
        _ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        withCentral { $0.handleNotificationState(peripheral, characteristic: characteristic, error: error) }
    }
}

/// `RadioTransport` over Bluetooth LE: a handle on one connection held by `BluetoothCentral`.
public struct BleRadioTransport: RadioTransport {
    let central: BluetoothCentral
    public let identifier: UUID
    public let dataAvailable: AsyncStream<Int>

    /// Subscribes to FromNum and waits until the subscription is live. Must happen before the handshake: the firmware
    /// gates these notifications behind STATE_SEND_PACKETS, and subscribing afterwards can miss the transition.
    public func enableNotifications() async throws {
        try await central.enableNotifications(identifier)
    }

    /// Writes one encoded `ToRadio`, with response (long writes carry frames past the negotiated MTU).
    public func write(_ frame: Data) async throws {
        try await central.write(frame, to: identifier)
    }

    /// One encoded `FromRadio`, or nil once the radio's queue is drained.
    public func read() async throws -> Data? {
        try await central.read(from: identifier)
    }
}
