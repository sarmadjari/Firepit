import FirepitData
import Foundation

/// What to tell somebody when an action failed: the error's own words when it has some, as Kotlin shows an
/// exception's `message`, and otherwise the caller's plain fallback rather than the system's "operation couldn't be
/// completed".
func failureMessage(_ error: any Error, fallback: String) -> String {
    if let error = error as? SendError { return error.message }
    if let error = error as? LocalizedError, let description = error.errorDescription { return description }
    return fallback
}
