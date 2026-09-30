import FirepitModel
import SwiftUI

/// Every pin currently on the map, with the actions the map itself makes fiddly.
struct PinsScreen: View {
    @State private var model: PinsViewModel
    @State private var renaming: MapPin?
    @State private var removing: MapPin?
    @Environment(\.dismiss) private var dismiss

    init(app: AppContainer) {
        _model = State(initialValue: PinsViewModel(app: app))
    }

    init(model: PinsViewModel) {
        _model = State(initialValue: model)
    }

    var body: some View {
        let state = model.uiState
        List {
            Section {
                Text(
                    "Pins are shared with everyone on the channel. You can only change the ones you placed."
                )
                .font(FirepitFont.bodySmall)
                .foregroundStyle(FirepitColors.textSecondary)
            }
            .listRowBackground(FirepitColors.surface2)

            if let error = state.error {
                Section {
                    Text(verbatim: error)
                        .font(FirepitFont.bodySmall)
                        .foregroundStyle(FirepitColors.danger)
                }
                .listRowBackground(FirepitColors.surface2)
            }

            if state.pins.isEmpty {
                Section {
                    ContentUnavailableView(
                        "No pins yet",
                        systemImage: FirepitIcon.pin.systemName,
                        description: Text("Use the pin button on the map, or long-press it.")
                    )
                }
                .listRowBackground(FirepitColors.surface2)
            } else {
                Section {
                    ForEach(state.pins) { pin in
                        PinRow(
                            pin: pin,
                            canEdit: pin.canEdit(myNodeNum: state.myNodeNum),
                            onRename: { renaming = pin },
                            onDelete: { removing = pin }
                        )
                    }
                }
                .listRowBackground(FirepitColors.surface2)
            }
        }
        .navigationTitle("Dropped pins")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button("Back") { dismiss() }
            }
        }
        .scrollContentBackground(.hidden)
        .background(FirepitColors.surface)
        .task { await model.observe() }
        .sheet(item: $renaming) { pin in
            RenamePinSheet(
                pin: pin,
                onDismiss: { renaming = nil },
                onRename: { name in
                    model.rename(pin, name: name)
                    renaming = nil
                }
            )
            .fittedSheet()
        }
        .alert(item: $removing) { pin in
            Alert(
                title: Text("Delete \(pin.name.isEmpty ? "this pin" : pin.name)?"),
                message: Text("It disappears for everyone on the channel."),
                primaryButton: .destructive(Text("Delete")) { model.remove(pin) },
                secondaryButton: .cancel()
            )
        }
    }
}

private struct PinRow: View {
    let pin: MapPin
    let canEdit: Bool
    let onRename: () -> Void
    let onDelete: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: FirepitSpacing.xs) {
            HStack(alignment: .firstTextBaseline) {
                Text(verbatim: pin.name.isEmpty ? "Pin" : pin.name)
                    .font(FirepitFont.bodyLarge)
                    .foregroundStyle(FirepitColors.textPrimary)
                Spacer()
                if canEdit {
                    Menu {
                        Button("Rename", action: onRename)
                        Button("Delete", role: .destructive, action: onDelete)
                    } label: {
                        Image(icon: .more)
                            .frame(width: FirepitSpacing.minTouchTarget, height: FirepitSpacing.minTouchTarget)
                    }
                    .accessibilityLabel(Text("Pin actions"))
                }
            }
            Text(verbatim: pinCoordinateText(pin) + (canEdit ? "" : " · placed by someone else"))
                .font(FirepitFont.bodyMedium)
                .foregroundStyle(FirepitColors.textSecondary)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct RenamePinSheet: View {
    let pin: MapPin
    let onDismiss: () -> Void
    let onRename: (String) -> Void
    @State private var name: String

    init(pin: MapPin, onDismiss: @escaping () -> Void, onRename: @escaping (String) -> Void) {
        self.pin = pin
        self.onDismiss = onDismiss
        self.onRename = onRename
        _name = State(initialValue: pin.name)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: FirepitSpacing.m) {
            Text("Rename pin")
                .font(FirepitFont.titleMedium)
                .foregroundStyle(FirepitColors.textPrimary)
            TextField("Name", text: $name)
                .textFieldStyle(.roundedBorder)
                .onChange(of: name) { _, value in
                    if value.count > pinNameLimit {
                        name = String(value.prefix(pinNameLimit))
                    }
                }
            Text("\(pinNameLimit - name.count) characters left")
                .font(FirepitFont.bodySmall)
                .foregroundStyle(FirepitColors.textSecondary)
            HStack {
                Button("Cancel", action: onDismiss)
                    .buttonStyle(.outlined)
                Button("Rename") {
                    if let valid = PinsReducer.validPinName(name) {
                        onRename(valid)
                    }
                }
                .buttonStyle(.prominent)
                .disabled(
                    PinsReducer.validPinName(name) == nil || name.trimmingCharacters(in: .whitespaces) == pin.name)
            }
        }
        .padding(FirepitSpacing.screenMargin)
    }
}

private func pinCoordinateText(_ pin: MapPin) -> String {
    "\(pin.latitude.formatted(.number.precision(.fractionLength(5)))), "
        + "\(pin.longitude.formatted(.number.precision(.fractionLength(5))))"
}
