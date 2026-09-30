import CoreLocation
import MapLibre
import SwiftUI

/// Pick an area on the map and keep it for when there is no signal. Ported from android/app/…/map/OfflineMapsScreen.kt.
///
/// The visible map is the selection: whatever is on screen when Download is tapped is what gets saved, which avoids a
/// corner-dragging interaction that is awkward on a phone.
struct OfflineMapsScreen: View {
    @State private var model: OfflineMapsViewModel
    @State private var bounds: GeoBox?
    @State private var mapView: MLNMapView?
    @State private var naming = false
    @State private var areaName = ""
    /// Only the first fix moves the camera, or panning away would be undone the next time the position updated.
    @State private var framed = false

    init(model: OfflineMapsViewModel) {
        _model = State(initialValue: model)
    }

    var body: some View {
        let state = model.uiState
        List {
            Section {
                MapLibreView(styleUrl: openFreeMapStyle) { view, _ in
                    mapView = view
                    frameOnFirstFix()
                } onCameraIdle: { view in
                    bounds = view.visibleBox
                }
                .frame(height: 260)
                .listRowInsets(EdgeInsets())
                .accessibilityLabel(Text("Map. The area on screen is the one that downloads."))
            }

            Section {
                Toggle(
                    "Offline maps only",
                    isOn: Binding(
                        get: { state.offlineOnly },
                        set: { model.setOfflineOnly($0) }
                    ))
            } footer: {
                if state.areas.isEmpty && state.offlineOnly {
                    Text("Nothing is downloaded, so the map will be blank.")
                        .foregroundStyle(FirepitColors.warn)
                } else {
                    Text("Never fetch tiles over the network. Ground you have not downloaded shows grey.")
                }
            }

            if state.suggestOfflineOnly {
                Section {
                    Text(
                        """
                        Downloaded. Turn on offline maps only, and the map stops asking the tile server for \
                        anything — including tiles that would show where you are.
                        """
                    )
                    .font(FirepitFont.bodyMedium)
                    .foregroundStyle(FirepitColors.textSecondary)
                    Button("Offline only") {
                        model.setOfflineOnly(true)
                        model.dismissOfflineSuggestion()
                    }
                    Button("Not now", role: .cancel) {
                        model.dismissOfflineSuggestion()
                    }
                }
            }

            downloadSection(state)

            Section("Saved areas") {
                if state.areas.isEmpty {
                    Text("No areas saved yet.")
                        .foregroundStyle(FirepitColors.textSecondary)
                } else {
                    ForEach(state.areas) { area in
                        areaRow(area, busy: state.progress != nil)
                    }
                }
            }
        }
        .navigationTitle("Offline areas")
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.observe() }
        .onChange(of: model.myPosition?.latitude) {
            frameOnFirstFix()
        }
        .alert("Name this area", isPresented: $naming) {
            TextField("Campsite", text: $areaName)
            Button("Download") {
                let name = areaName.trimmingCharacters(in: .whitespaces)
                if let bounds {
                    model.download(
                        name: name.isEmpty ? String(localized: "Saved area") : name, bounds: bounds,
                        styleUrl: openFreeMapStyle)
                }
                areaName = ""
            }
            Button("Cancel", role: .cancel) {
                areaName = ""
            }
        }
    }

    @ViewBuilder
    private func downloadSection(_ state: OfflineMapsUiState) -> some View {
        Section {
            if let here = model.myPosition {
                Button("Around me (\(Int(Self.aroundMeRadiusKm)) km)") {
                    mapView?.frame(around: here, radiusKm: Self.aroundMeRadiusKm)
                }
            }
            if let visible = bounds {
                let tiles = TileEstimate.tileCount(
                    north: visible.north, south: visible.south, east: visible.east,
                    west: visible.west, minZoom: Self.minZoom, maxZoom: Self.maxZoom)
                // The byte figure is an estimate, so it is worded as one.
                Text("This view is about \(tiles.formatted()) tiles, \(TileEstimate.describe(tiles)).")
                    .foregroundStyle(tiles > Self.largeAreaTiles ? FirepitColors.warn : FirepitColors.textPrimary)
            }
            if let progress = state.progress {
                ProgressView(value: progress) {
                    Text("Downloading \(Int(progress * 100))%")
                        .font(FirepitFont.bodySmall)
                }
            }
            if let error = state.error {
                Text(verbatim: error)
                    .font(FirepitFont.bodySmall)
                    .foregroundStyle(FirepitColors.danger)
            }
            Button("Download this area") {
                naming = true
            }
            .disabled(bounds == nil || state.progress != nil)
        } footer: {
            Text("Move the map to the area you want, then download it. Street level only, and large areas are refused.")
        }
    }

    private func areaRow(_ area: OfflineArea, busy: Bool) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: area.name)
                .font(FirepitFont.bodyLarge)
            Text(verbatim: "\(area.sizeLabel) · \(Self.downloadedLabel(area.downloadedAt))")
                .font(FirepitFont.bodySmall)
                .foregroundStyle(FirepitColors.textSecondary)
        }
        .swipeActions {
            Button("Delete", role: .destructive) {
                model.delete(area)
            }
            Button("Update") {
                model.update(area)
            }
            .tint(FirepitColors.primary)
            .disabled(busy)
        }
        .contextMenu {
            Button("Update", systemImage: "arrow.clockwise") {
                model.update(area)
            }
            .disabled(busy)
            Button("Delete", systemImage: "trash", role: .destructive) {
                model.delete(area)
            }
        }
    }

    /// Points the camera at where you are the first time that is known. The whole-world view this screen used to open
    /// on estimated tens of terabytes, which is not a choice anyone was going to make. Where you are standing is.
    private func frameOnFirstFix() {
        guard !framed, let mapView, let here = model.myPosition else { return }
        framed = true
        mapView.frame(around: here, radiusKm: Self.aroundMeRadiusKm)
    }

    private static func downloadedLabel(_ epochMillis: Int64, now: Date = .now) -> String {
        if epochMillis <= 0 {
            return String(localized: "downloaded before this was recorded")
        }
        let downloaded = Date(timeIntervalSince1970: TimeInterval(epochMillis) / 1000)
        let hours = Int(now.timeIntervalSince(downloaded) / 3600)
        let days = hours / 24
        if hours < 1 {
            return String(localized: "downloaded just now")
        }
        if hours < 24 {
            return String(localized: "downloaded \(hours)h ago")
        }
        if days < 30 {
            return String(localized: "downloaded \(days)d ago")
        }
        return String(localized: "downloaded \(downloaded.formatted(date: .abbreviated, time: .omitted))")
    }

    /// Matches the repository's download range so the estimate is not a different question.
    private static let minZoom = 8
    private static let maxZoom = 15
    /// Past this the download is worth a second look before starting.
    private static let largeAreaTiles: Int64 = 20_000
    /// Roughly an hour's walk in every direction, and a few thousand tiles rather than millions.
    private static let aroundMeRadiusKm = 20.0
}
