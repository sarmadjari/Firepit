import Foundation

/// A stream many listeners can follow at once, each from the moment they subscribe (Kotlin's `SharedFlow`).
///
/// `AsyncStream` has a single consumer; radio traffic has several (messages, rooms, positions, the radio screen), so
/// every subscriber gets its own stream fed from here.
public final class Broadcast<Element: Sendable>: Sendable {
    private let state = Mutex(State())

    private struct State {
        var subscribers: [UUID: AsyncStream<Element>.Continuation] = [:]
    }

    public init() {}

    /// A new stream of every element sent from now on. Ends when the subscriber stops iterating.
    public func subscribe(bufferingNewest limit: Int = 256) -> AsyncStream<Element> {
        let id = UUID()
        let (stream, continuation) = AsyncStream<Element>.makeStream(bufferingPolicy: .bufferingNewest(limit))
        continuation.onTermination = { [weak self] _ in
            self?.state.withLock { _ = $0.subscribers.removeValue(forKey: id) }
        }
        state.withLock { $0.subscribers[id] = continuation }
        return stream
    }

    public func send(_ element: Element) {
        for continuation in state.withLock({ Array($0.subscribers.values) }) {
            continuation.yield(element)
        }
    }
}

/// A value that changes over time, readable now and followable (Kotlin's `StateFlow`). New subscribers receive the
/// current value first; equal consecutive values are not repeated.
public final class CurrentValue<Element: Sendable & Equatable>: Sendable {
    private let state: Mutex<State>

    private struct State {
        var value: Element
        var subscribers: [UUID: AsyncStream<Element>.Continuation] = [:]
    }

    public init(_ value: Element) {
        state = Mutex(State(value: value))
    }

    public var value: Element { state.withLock(\.value) }

    public func set(_ newValue: Element) {
        let continuations: [AsyncStream<Element>.Continuation] = state.withLock { state in
            guard state.value != newValue else { return [] }
            state.value = newValue
            return Array(state.subscribers.values)
        }
        for continuation in continuations {
            continuation.yield(newValue)
        }
    }

    /// The current value, then every change.
    public func subscribe() -> AsyncStream<Element> {
        let id = UUID()
        let (stream, continuation) = AsyncStream<Element>.makeStream(bufferingPolicy: .bufferingNewest(64))
        continuation.onTermination = { [weak self] _ in
            self?.state.withLock { _ = $0.subscribers.removeValue(forKey: id) }
        }
        state.withLock { state in
            state.subscribers[id] = continuation
            continuation.yield(state.value)
        }
        return stream
    }
}

/// A lock around a value, for the few places that need synchronous thread-safe state (Synchronization's `Mutex` needs
/// iOS 18; this runs on iOS 17).
public final class Mutex<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Value

    public init(_ value: Value) {
        self.value = value
    }

    public func withLock<Result>(_ body: (inout Value) throws -> Result) rethrows -> Result {
        lock.lock()
        defer { lock.unlock() }
        return try body(&value)
    }
}
