import FirepitProtocol
import SwiftUI

/// Settings › Quick replies (UX §5.4, §8): the ready-made messages ⚡ offers beside the message box. Ported from
/// android/app/…/settings/QuickRepliesScreen.kt; swipe to delete and Edit to reorder are iOS's own.
struct QuickRepliesScreen: View {
    let store: QuickReplyStore

    @State private var editing: Editing?

    var body: some View {
        List {
            Section {
                ForEach(Array(store.replies.enumerated()), id: \.offset) { index, reply in
                    Button {
                        editing = Editing(index: index, text: reply)
                    } label: {
                        Text(verbatim: reply).foregroundStyle(FirepitColors.textPrimary)
                    }
                    .accessibilityHint(Text("Edits this quick reply"))
                }
                .onDelete { offsets in
                    store.set(store.replies.enumerated().filter { !offsets.contains($0.offset) }.map(\.element))
                }
                .onMove { from, to in
                    var next = store.replies
                    next.move(fromOffsets: from, toOffset: to)
                    store.set(next)
                }
            } footer: {
                Text(
                    """
                    Each one goes out as a message of its own, so they are kept short: \(QuickReplies.maxBytes) bytes at \
                    most. ⚡ beside the message box sends one.
                    """
                )
            }
            .listRowBackground(FirepitColors.surface2)
            Section {
                if store.replies.count < QuickReplies.maxCount {
                    Button("Add a quick reply") { editing = Editing(index: nil, text: "") }
                }
                Button("Back to the defaults", action: store.resetToDefaults)
            }
            .listRowBackground(FirepitColors.surface2)
            .tint(FirepitColors.primary)
        }
        .scrollContentBackground(.hidden)
        .background(FirepitColors.surface)
        .navigationTitle("Quick replies")
        .toolbar { EditButton() }
        .alert(
            editing?.index == nil ? "Add a quick reply" : "Edit quick reply",
            isPresented: Binding(get: { editing != nil }, set: { if !$0 { editing = nil } })
        ) {
            TextField(
                "On my way",
                text: Binding(
                    get: { editing?.text ?? "" },
                    // Cut at the byte limit as it is typed, never splitting a character.
                    set: { editing?.text = MeshConstants.truncateToBytes($0, maxBytes: QuickReplies.maxBytes) }
                )
            )
            Button("Save", action: save)
            Button("Cancel", role: .cancel) { editing = nil }
        }
    }

    private func save() {
        guard let editing, let text = QuickReplies.clean(editing.text) else { return }
        var next = store.replies
        if let index = editing.index, next.indices.contains(index) {
            next[index] = text
        } else {
            next.append(text)
        }
        store.set(next)
        self.editing = nil
    }
}

/// The reply being edited, by its place in the list; nil index for a new one.
private struct Editing {
    let index: Int?
    var text: String
}
