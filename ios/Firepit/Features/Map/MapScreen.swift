import CoreLocation
import FirepitModel
import FirepitProtocol
import MapLibre
import SwiftUI

/// The live Firepit map. Ported from android/app/…/map/MapScreen.kt.
struct MapScreen: View {
    let app: AppContainer

    @State private var model: MapViewModel
    @State private var sharingModel: SharingViewModel
    @State private var mapView: MLNMapView?
    @State private var hasFramedMarkers = false
    @State private var pickingRoom = false
    @State private var showingOptions = false
    @State private var droppingAt: DropTarget?
    @State private var openPin: MapPin?
    @State private var openMarker: MapMarker?
    @State private var showingOfflineAreas = false
    @State private var pendingShare: PendingShare?
    @Environment(\.openURL) private var openURL

    init(app: AppContainer) {
        self.app = app
        _model = State(initialValue: MapViewModel(app: app))
        _sharingModel = State(initialValue: SharingViewModel(location: app.location, mesh: app.mesh))
    }

    var body: some View {
        NavigationStack {
            ZStack(alignment: .top) {
                // The map runs under the status bar; the controls over it keep to the safe area.
                mapBody
                    .ignoresSafeArea(edges: .top)
                overlayBody
            }
            .navigationTitle("Map")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar(.hidden, for: .navigationBar)
            .background(FirepitColors.mapGround)
            .task { await model.observe() }
            .task { await sharingModel.observe() }
            .onAppear(perform: mapAppeared)
            .onDisappear { model.setMapVisible(false) }
            .onChange(of: model.ask) { _, ask in
                if case .swept = ask, openMarker == nil {
                    Task {
                        try? await Task.sleep(for: .seconds(6))
                        model.clearAsk()
                    }
                }
            }
            .sheet(isPresented: $showingOptions) { optionsSheet.fittedSheet() }
            .sheet(isPresented: $pickingRoom) { shareSheet.fittedSheet() }
            .sheet(item: $droppingAt) { target in
                DropPinSheet(
                    onDismiss: { droppingAt = nil },
                    onDrop: { name in
                        model.dropPin(
                            latitudeI: Int32(target.coordinate.latitude * 1e7),
                            longitudeI: Int32(target.coordinate.longitude * 1e7),
                            name: name
                        )
                        droppingAt = nil
                    }
                )
                .fittedSheet()
            }
            .sheet(item: $openPin) { pin in
                PinSheet(
                    pin: pin,
                    canRemove: pin.canEdit(myNodeNum: model.uiState.myNodeNum),
                    onRemove: {
                        model.removePin(pin)
                        openPin = nil
                    }
                )
                .fittedSheet()
            }
            .sheet(item: $openMarker) { marker in
                PersonSheet(
                    marker: marker,
                    ask: model.ask,
                    onAsk: { model.askWhereTheyAre(nodeNum: marker.node.nodeNum, name: marker.name) },
                    onDismiss: {
                        openMarker = nil
                        model.clearAsk()
                    }
                )
                .fittedSheet()
            }
            .navigationDestination(isPresented: $showingOfflineAreas) {
                OfflineMapsScreen(
                    model: OfflineMapsViewModel(
                        repository: app.offlineMaps, mapPreferences: app.mapPreferences, mesh: app.mesh)
                )
            }
            .alert("Location permission is off", isPresented: deniedBinding) {
                Button("Settings") { openURL(URL(string: UIApplication.openSettingsURLString)!) }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Firepit needs location permission to show your own dot and share where you are.")
            }
        }
    }

    private var mapBody: some View {
        MapLibreView(
            styleUrl: openFreeMapStyle,
            onReady: { view, style in
                mapView = view
                CoverageMask().apply(style: style, areas: model.areas, enabled: model.offlineOnly)
                frameMarkersIfNeeded()
            },
            onCameraIdle: { _ in },
            markers: model.uiState.markers,
            pins: model.uiState.pins,
            showsUserLocation: true,
            onMarkerTap: { marker in if !marker.isSelf { openMarker = marker } },
            onPinTap: { pin in openPin = pin },
            onLongPress: { coordinate in droppingAt = DropTarget(coordinate: coordinate) }
        )
        .onChange(of: model.uiState.markers) { _, _ in frameMarkersIfNeeded() }
        .onChange(of: model.offlineOnly) { _, _ in frameMarkersIfNeeded(force: model.offlineOnly) }
    }

    private var overlayBody: some View {
        VStack(spacing: 0) {
            HStack(alignment: .top) {
                noticeView
                Spacer(minLength: FirepitSpacing.s)
                mapOptionsButton
            }
            .padding(.top, FirepitSpacing.screenMargin)
            .padding(.horizontal, FirepitSpacing.m)
            Spacer()
            if !hasFramedMarkers && !model.offlineOnly && !model.uiState.markers.isEmpty {
                Button("Show everyone") { frameAll(force: true) }
                    .buttonStyle(.pill)
                    .padding(.bottom, FirepitSpacing.s)
            }
            SharingBanner(
                state: sharingModel.state,
                onChange: { pickingRoom = true },
                onStop: sharingModel.stop
            )
            .padding(.horizontal, FirepitSpacing.m)
            .padding(.bottom, FirepitSpacing.s)
            MapTallyBar(text: MapWords.tally(model.uiState))
        }
    }

    @ViewBuilder private var noticeView: some View {
        if let text = model.uiState.error ?? model.ask.notice {
            MapNotice(text: text, onClose: model.clearError)
        }
    }

    private var mapOptionsButton: some View {
        Button {
            showingOptions = true
        } label: {
            Image(icon: .more)
                .font(.headline)
                .foregroundStyle(FirepitColors.textPrimary)
                .frame(width: 48, height: 48)
                .floatingControlBackground(in: .rect(cornerRadius: FirepitRadius.large))
        }
        .accessibilityLabel(Text("Map options"))
    }

    private var optionsSheet: some View {
        MapOptionsSheet(
            state: model.uiState,
            onFilter: model.setFilter,
            onShare: { pickingRoom = true },
            onCentre: { frameAll(force: true) },
            onAskEveryone: model.askEveryone,
            onDropPin: { droppingAt = mapView.map { DropTarget(coordinate: $0.centerCoordinate) } },
            onOfflineAreas: { showingOfflineAreas = true }
        )
    }

    private var shareSheet: some View {
        ShareLocationSheet(
            state: sharingModel.state,
            onDismiss: { pickingRoom = false },
            onShare: { roomId, choice in
                pickingRoom = false
                if LocationPermission.isDenied {
                    pendingShare = PendingShare(roomId: roomId, choice: choice)
                    model.reportPermissionDenied()
                } else {
                    LocationPermission.requestIfNeeded()
                    sharingModel.share(roomId: roomId, choice: choice)
                }
            },
            onStop: {
                pickingRoom = false
                sharingModel.stop()
            }
        )
    }

    private var deniedBinding: Binding<Bool> {
        Binding(
            get: { LocationPermission.isDenied && model.uiState.error != nil },
            set: { if !$0 { model.clearError() } }
        )
    }

    private func mapAppeared() {
        LocationPermission.requestIfNeeded()
        if LocationPermission.isDenied {
            model.reportPermissionDenied()
        } else {
            model.setMapVisible(true)
        }
    }

    private func frameMarkersIfNeeded(force: Bool = false) {
        guard (force || !hasFramedMarkers) && model.offlineOnly && !model.uiState.markers.isEmpty else { return }
        frameAll(force: force)
    }

    private func frameAll(force: Bool) {
        guard let mapView else { return }
        if CameraDecision.frameMarkers(on: mapView, markers: model.uiState.markers) || force {
            hasFramedMarkers = true
        }
    }
}

private struct PendingShare: Identifiable {
    let roomId: Int32
    let choice: ShareDuration
    var id: Int32 { roomId }
}

/// Where a pin is about to be dropped, identified so a sheet can present it.
private struct DropTarget: Identifiable {
    let id = UUID()
    let coordinate: CLLocationCoordinate2D
}
