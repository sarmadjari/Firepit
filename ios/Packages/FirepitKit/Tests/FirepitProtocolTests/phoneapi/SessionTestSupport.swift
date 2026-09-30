import FirepitProtos
import Foundation

@testable import FirepitProtocol

/// Runs the session on a task and lets each test cancel it.
internal func launchSession(_ session: PhoneApiSession) async -> Task<Void, Error> {
    let job = Task {
        try await session.run()
    }
    await Task.yield()
    return job
}

/// Suspends until the config download finishes.
internal func awaitReady(_ session: PhoneApiSession) async -> SessionState {
    for await state in session.state {
        if case .ready = state {
            return state
        }
    }
    return .idle
}

internal func awaitInbound(_ session: PhoneApiSession) async -> FromRadio? {
    for await message in session.inbound {
        if message.id != 0 {
            return message
        }
    }
    return nil
}

internal func awaitInboundPair(_ session: PhoneApiSession) async -> (FromRadio?, FromRadio?) {
    var first: FromRadio?
    for await message in session.inbound {
        if message.id == 0 {
            continue
        }
        if first == nil {
            first = message
        } else {
            return (first, message)
        }
    }
    return (first, nil)
}

internal func cancelSession(_ job: Task<Void, Error>) async {
    job.cancel()
    _ = await job.result
}
