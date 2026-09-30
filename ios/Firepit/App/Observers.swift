/// Waits on `observers` until the calling task is cancelled, then cancels them.
///
/// Gives main-actor observer tasks the lifetime of the `.task` that started them. They are created with `Task {}` in a
/// main-actor context, so they inherit its isolation and may touch view-model state directly; a task group would need
/// `@MainActor` child closures, which the compiler's region checker rejects.
func awaitObservers(_ observers: [Task<Void, Never>]) async {
    await withTaskCancellationHandler {
        for observer in observers { await observer.value }
    } onCancel: {
        for observer in observers { observer.cancel() }
    }
}
