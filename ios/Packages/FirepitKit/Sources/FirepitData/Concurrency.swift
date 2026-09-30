import FirepitModel
import Foundation

public enum TimeoutError: Error, Sendable, Equatable, LocalizedError {
    case timedOut

    public var errorDescription: String? { "The radio did not answer in time." }
}

/// Runs an async operation with a deadline, matching Kotlin's withTimeout.
public func withTimeout<T: Sendable>(
    _ duration: Duration,
    operation: @escaping @Sendable () async throws -> T
) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask {
            try await operation()
        }
        group.addTask {
            try await Task.sleep(for: duration)
            throw TimeoutError.timedOut
        }
        guard let value = try await group.next() else {
            throw TimeoutError.timedOut
        }
        group.cancelAll()
        return value
    }
}

/// Runs an async operation with a deadline, matching Kotlin's withTimeoutOrNull.
public func withTimeoutOrNil<T: Sendable>(
    _ duration: Duration,
    operation: @escaping @Sendable () async throws -> T?
) async throws -> T? {
    do {
        return try await withTimeout(duration, operation: operation)
    } catch TimeoutError.timedOut {
        return nil
    } catch {
        throw error
    }
}

/// Kotlin's coroutine `Mutex`: a lock that may be held across suspension points, such as a whole slot rearrangement.
///
/// Waiters queue in arrival order and are served first come, first served. A task cancelled while waiting leaves the
/// queue at once and throws `CancellationError` without ever holding the lock, as Kotlin's `withLock` does. Queueing
/// and
/// cancelling both happen synchronously under a plain lock, so no interleaving can reorder waiters or leave one behind.
public final class AsyncMutex: Sendable {
    private struct Waiter {
        let id: UInt64
        let continuation: CheckedContinuation<Void, any Error>
    }

    private struct State {
        var locked = false
        var waiters: [Waiter] = []
        var nextId: UInt64 = 0
    }

    private enum Admission {
        case acquired
        case cancelled
        case queued
    }

    private let state = Mutex(State())

    public init() {}

    /// Runs `body` holding the lock, releasing it however `body` ends.
    public func withLock<T: Sendable>(_ body: @Sendable () async throws -> T) async throws -> T {
        try await acquire()
        defer { release() }
        return try await body()
    }

    private func acquire() async throws {
        let id = state.withLock { state in
            state.nextId += 1
            return state.nextId
        }
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
                let admission = state.withLock { state -> Admission in
                    // Cancelled before queueing: the handler below found nothing to remove, so refuse here.
                    if Task.isCancelled {
                        return .cancelled
                    }
                    if !state.locked {
                        state.locked = true
                        return .acquired
                    }
                    state.waiters.append(Waiter(id: id, continuation: continuation))
                    return .queued
                }
                switch admission {
                case .acquired:
                    continuation.resume()
                case .cancelled:
                    continuation.resume(throwing: CancellationError())
                case .queued:
                    break
                }
            }
        } onCancel: {
            let waiter = state.withLock { state -> Waiter? in
                guard let index = state.waiters.firstIndex(where: { $0.id == id }) else {
                    return nil
                }
                return state.waiters.remove(at: index)
            }
            waiter?.continuation.resume(throwing: CancellationError())
        }
    }

    /// Hands the lock straight to the longest waiter, or unlocks when nobody waits.
    private func release() {
        let next = state.withLock { state -> Waiter? in
            if state.waiters.isEmpty {
                state.locked = false
                return nil
            }
            return state.waiters.removeFirst()
        }
        next?.continuation.resume()
    }
}

extension AsyncSequence where Element: Sendable {
    func first(where predicate: @escaping @Sendable (Element) -> Bool) async rethrows -> Element? {
        for try await element in self {
            if predicate(element) {
                return element
            }
        }
        return nil
    }
}
