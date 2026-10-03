import FirepitModel
import MapLibre
import SwiftUI

struct MapNotice: View {
    let text: String
    let onClose: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: FirepitSpacing.s) {
            Text(verbatim: text)
                .font(FirepitFont.bodyMedium)
                .foregroundStyle(FirepitColors.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: onClose) {
                Image(icon: .close)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(FirepitColors.textSecondary)
                    .frame(width: FirepitSpacing.minTouchTarget, height: FirepitSpacing.minTouchTarget)
            }
            .accessibilityLabel(Text("Dismiss"))
        }
        .padding(.leading, FirepitSpacing.m)
        .padding(.vertical, FirepitSpacing.xs)
        .padding(.trailing, FirepitSpacing.xs)
        .floatingControlBackground(in: .rect(cornerRadius: FirepitRadius.large))
    }
}

struct MapTallyBar: View {
    let text: String

    var body: some View {
        Text(verbatim: text)
            .font(FirepitFont.bodyMedium)
            .foregroundStyle(FirepitColors.textSecondary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.vertical, FirepitSpacing.m)
            .background(FirepitColors.surface2)
    }
}

struct MapOptionsSheet: View {
    let state: MapUiState
    let onFilter: (MapFilter) -> Void
    let onShare: () -> Void
    let onCentre: () -> Void
    let onAskEveryone: () -> Void
    let onDropPin: () -> Void
    let onOfflineAreas: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: FirepitSpacing.s) {
            SectionLabel("Show")
            ChipRow {
                ForEach(MapFilter.allCases) { choice in
                    FirepitChip(LocalizedStringKey(choice.label), selected: state.filter == choice) {
                        onFilter(choice)
                    }
                }
            }
            Text(filterCaption)
                .font(FirepitFont.bodySmall)
                .foregroundStyle(FirepitColors.textSecondary)
                .lineLimit(1)
            Divider()
                .padding(.vertical, FirepitSpacing.s)
            SheetAction(
                icon: .locate,
                label: state.isSharing ? "Change location sharing" : "Share my location",
                action: perform(onShare)
            )
            SheetAction(icon: .map, label: "Centre on everyone", action: perform(onCentre))
            SheetAction(icon: .clock, label: "Ask for a location update", action: perform(onAskEveryone))
            SheetAction(icon: .pin, label: "Drop a pin here", action: perform(onDropPin))
            SheetAction(icon: .download, label: "Offline areas", action: perform(onOfflineAreas))
        }
        .padding(FirepitSpacing.screenMargin)
        .background(FirepitColors.surface2)
    }

    private var filterCaption: String {
        switch state.filter {
        case .all:
            String(localized: "Everyone this radio has heard.")
        case .ours:
            state.hiddenByFilter > 0
                ? String(localized: "Your rooms and your own hardware. \(state.hiddenByFilter) hidden.")
                : String(localized: "Your rooms and your own hardware.")
        }
    }

    private func perform(_ action: @escaping () -> Void) -> () -> Void {
        {
            dismiss()
            action()
        }
    }
}

private struct SheetAction: View {
    let icon: FirepitIcon
    let label: LocalizedStringKey
    let action: () -> Void

    /// One size for symbols and shared glyphs alike, growing with the text beside it.
    @ScaledMetric(relativeTo: .body) private var iconSide: CGFloat = 22

    var body: some View {
        Button(action: action) {
            HStack(spacing: FirepitSpacing.m) {
                Image(icon: icon)
                    .resizable()
                    .scaledToFit()
                    .frame(width: iconSide, height: iconSide)
                    .foregroundStyle(FirepitColors.textSecondary)
                    .accessibilityHidden(true)
                Text(label)
                    .font(FirepitFont.bodyLarge)
                    .foregroundStyle(FirepitColors.textPrimary)
                Spacer()
            }
            .contentShape(.rect)
            .padding(.vertical, FirepitSpacing.s)
        }
        .buttonStyle(.plain)
    }
}

struct DropPinSheet: View {
    let onDismiss: () -> Void
    let onDrop: (String) -> Void
    @State private var name = ""

    var body: some View {
        VStack(alignment: .leading, spacing: FirepitSpacing.m) {
            Text("Drop a pin")
                .font(FirepitFont.titleMedium)
                .foregroundStyle(FirepitColors.textPrimary)
            TextField("Water", text: $name)
                .textFieldStyle(.roundedBorder)
                .onChange(of: name) { _, value in
                    if value.count > pinNameLimit { name = String(value.prefix(pinNameLimit)) }
                }
            Text("\(pinNameLimit - name.count) characters left")
                .font(FirepitFont.bodySmall)
                .foregroundStyle(FirepitColors.textSecondary)
            Text("Pins are public to everyone on the channel and travel over the mesh like any other message.")
                .font(FirepitFont.bodySmall)
                .foregroundStyle(FirepitColors.textSecondary)
            HStack {
                Button("Cancel", action: onDismiss)
                    .buttonStyle(.outlined)
                Button("Drop") {
                    if let valid = PinsReducer.validPinName(name) { onDrop(valid) }
                }
                .buttonStyle(.prominent)
                .disabled(PinsReducer.validPinName(name) == nil)
            }
        }
        .padding(FirepitSpacing.screenMargin)
    }
}

struct PinSheet: View {
    let pin: MapPin
    let canRemove: Bool
    let onRemove: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: FirepitSpacing.s) {
            Text(verbatim: pin.name.isEmpty ? "Pin" : pin.name)
                .font(FirepitFont.titleMedium)
                .foregroundStyle(FirepitColors.textPrimary)
            if !pin.description.isEmpty {
                Text(verbatim: pin.description)
                    .font(FirepitFont.bodyMedium)
                    .foregroundStyle(FirepitColors.textPrimary)
            }
            Text(verbatim: coordinateText(pin))
                .font(FirepitFont.bodySmall)
                .foregroundStyle(FirepitColors.textSecondary)
            if canRemove {
                Button("Remove for everyone", role: .destructive) {
                    onRemove()
                    dismiss()
                }
                .buttonStyle(.destructive)
            } else {
                Text("Only the person who placed this pin can remove it.")
                    .font(FirepitFont.bodySmall)
                    .foregroundStyle(FirepitColors.textSecondary)
            }
        }
        .padding(FirepitSpacing.screenMargin)
    }
}

struct PersonSheet: View {
    let marker: MapMarker
    let ask: LocationAsk
    let onAsk: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: FirepitSpacing.m) {
            Text(verbatim: marker.name)
                .font(FirepitFont.titleMedium)
                .foregroundStyle(FirepitColors.textPrimary)
            Text(verbatim: positionAge)
                .font(FirepitFont.bodyMedium)
                .foregroundStyle(FirepitColors.textPrimary)
            if let status {
                Text(verbatim: status)
                    .font(FirepitFont.bodySmall)
                    .foregroundStyle(FirepitColors.textSecondary)
            }
            HStack {
                Button("Close", action: onDismiss)
                    .buttonStyle(.outlined)
                Button(isAsking ? "Asking…" : "Ask where they are", action: onAsk)
                    .buttonStyle(.prominent)
                    .disabled(isAsking)
            }
        }
        .padding(FirepitSpacing.screenMargin)
    }

    private var isAsking: Bool {
        if case .asking(let nodeNum) = ask { return nodeNum == marker.node.nodeNum }
        return false
    }

    private var status: String? {
        if case .answered(let nodeNum, let said) = ask, nodeNum == marker.node.nodeNum { return said }
        if isAsking { return String(localized: "Asking their radio. This can take up to a minute.") }
        return nil
    }

    private var positionAge: String {
        if let minutes = marker.fixAgeMinutes {
            return String(localized: "Last seen here \(MapWords.agePhrase(minutes: minutes)).")
        }
        return String(localized: "Nothing says when this position was taken.")
    }
}

nonisolated enum CameraDecision {
    @MainActor
    @discardableResult
    static func frameMarkers(on mapView: MLNMapView, markers: [MapMarker]) -> Bool {
        let points = markers.compactMap { marker -> CLLocationCoordinate2D? in
            guard let coordinate = marker.coordinate else { return nil }
            return CLLocationCoordinate2D(latitude: coordinate.latitude, longitude: coordinate.longitude)
        }
        switch points.count {
        case 0:
            return false
        case 1:
            mapView.setCenter(points[0], zoomLevel: 14, animated: true)
        default:
            let lats = points.map(\.latitude)
            let lons = points.map(\.longitude)
            let sw = CLLocationCoordinate2D(latitude: lats.min() ?? 0, longitude: lons.min() ?? 0)
            let ne = CLLocationCoordinate2D(latitude: lats.max() ?? 0, longitude: lons.max() ?? 0)
            let bounds = MLNCoordinateBounds(sw: sw, ne: ne)
            let insets = UIEdgeInsets(top: 96, left: 48, bottom: 144, right: 48)
            mapView.setVisibleCoordinateBounds(bounds, edgePadding: insets, animated: true, completionHandler: nil)
        }
        return true
    }

    static func shouldFrameInitially(markerCount: Int, offlineOnly: Bool, alreadyFramed: Bool) -> Bool {
        !alreadyFramed && offlineOnly && markerCount > 0
    }
}
