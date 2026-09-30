import FirepitModel
import Foundation
import Testing

@testable import FirepitData

@Test func asyncMutexExcludesConcurrentBodies() async throws {
    let mutex = AsyncMutex()
    let active = Mutex(0)
    let maxActive = Mutex(0)
    await withTaskGroup(of: Void.self) { group in
        for _ in 0..<20 {
            group.addTask {
                try? await mutex.withLock {
                    active.withLock { value in
                        value += 1
                        maxActive.withLock { $0 = max($0, value) }
                    }
                    try? await Task.sleep(for: .milliseconds(2))
                    active.withLock { $0 -= 1 }
                }
            }
        }
    }
    #expect(maxActive.withLock { $0 } == 1)
}

@Test func asyncMutexIsFifo() async throws {
    let mutex = AsyncMutex()
    let order = Mutex<[Int]>([])
    let holder = Task {
        try? await mutex.withLock {
            try? await Task.sleep(for: .milliseconds(50))
        }
    }
    try await Task.sleep(for: .milliseconds(10))
    var tasks: [Task<Void, Never>] = []
    for value in 1...4 {
        tasks.append(
            Task {
                try? await mutex.withLock {
                    order.withLock { $0.append(value) }
                }
            }
        )
        try await Task.sleep(for: .milliseconds(5))
    }
    _ = await holder.value
    for task in tasks {
        _ = await task.value
    }
    #expect(order.withLock { $0 } == [1, 2, 3, 4])
}

@Test func asyncMutexCancelledWaiterDoesNotBlockNextWaiter() async throws {
    let mutex = AsyncMutex()
    let order = Mutex<[Int]>([])
    try await mutex.withLock {
        let cancelled = Task {
            try? await mutex.withLock {
                order.withLock { $0.append(1) }
            }
        }
        try await Task.sleep(for: .milliseconds(20))
        cancelled.cancel()
        let next = Task {
            try? await mutex.withLock {
                order.withLock { $0.append(2) }
            }
        }
        try await Task.sleep(for: .milliseconds(20))
        #expect(order.withLock { $0 }.isEmpty)
        _ = next
    }
    try await Task.sleep(for: .milliseconds(50))
    #expect(order.withLock { $0 } == [2])
}

@Test func withTimeoutOrNilReturnsNilOnlyWhenDeadlinePasses() async throws {
    let value = try await withTimeoutOrNil(.milliseconds(20)) {
        try await Task.sleep(for: .milliseconds(100))
        return 1
    }
    #expect(value == nil)
}

@Test func withTimeoutOrNilRethrowsOperationErrors() async throws {
    struct Boom: Error, Equatable {}
    await #expect(throws: Boom.self) {
        _ =
            try await withTimeoutOrNil(.seconds(1)) {
                throw Boom()
            } as Int?
    }
}
