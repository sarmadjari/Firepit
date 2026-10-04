import CoreLocation
import FirepitData
import FirepitModel
import FirepitProtocol
import MapLibre
import SwiftUI

/// The live Firepit map. Ported from android/app/…/map/MapScreen.kt.
struct MapScreen: View {
    let app: AppContainer
    /// Kept for the life of the view, as in `ChatsPane`.
    @State private var workspace: Workspace

    @State private var model: MapViewModel
    @State private var sharingModel: SharingViewModel
    @State private var mapView: MLNMapView?
    /// Which screen this is, so the model counts it on screen exactly once.
    @State private var screenId = UUID()
    @State private var pendingShare: PendingShare?
    @Environment(\.openURL) private var openURL

    /// A map of its own, for a screen shown on its own.
    init(app: AppContainer) {
        self.init(app: app, workspace: Workspace(app: app))
    }

    /// The map side of the shell, whose models and camera outlive a change of layout.
    init(app: AppContainer, workspace: Workspace) {
        self.app = app
        _workspace = State(initialValue: workspace)
        _model = State(initialValue: workspace.map)
        _sharingModel = State(initialValue: workspace.sharing)
    }

    // The open sheets live in the workspace, so a change of layout puts them back (UX §6.11.5).
    private var pickingRoom: Bool {
        get { workspace.mapSheets.pickingRoom }
        nonmutating set { workspace.mapSheets.pickingRoom = newValue }
    }

    private var showingOptions: Bool {
        get { workspace.mapSheets.showingOptions }
        nonmutating set { workspace.mapSheets.showingOptions = newValue }
    }

    private var droppingAt: DropTarget? {
        get { workspace.mapSheets.droppingAt }
        nonmutating set { workspace.mapSheets.droppingAt = newValue }
    }

    private var openPin: MapPin? {
        get { workspace.mapSheets.openPin }
        nonmutating set { workspace.mapSheets.openPin = newValue }
    }

    private var openMarker: MapMarker? {
        get { workspace.mapSheets.openMarker }
        nonmutating set { workspace.mapSheets.openMarker = newValue }
    }

    private var showingOfflineAreas: Bool {
        get { workspace.mapSheets.showingOfflineAreas }
        nonmutating set { workspace.mapSheets.showingOfflineAreas = newValue }
    }

    /// Kept on the model, so a rebuilt map does not pull the camera to everyone again.
    private var hasFramedMarkers: Bool {
        get { model.framed }
        nonmutating set { model.framed = newValue }
    }

    #if DEBUG
        /// `-route map.everyone`: opens already framed on the demo world's people, as "Show everyone" would.
        private var debugFramesEveryone = false

        init(app: AppContainer, debugFramesEveryone: Bool) {
            self.init(app: app)
            self.debugFramesEveryone = debugFramesEveryone
        }
    #endif

    var body: some View {
        @Bindable var workspace = workspace
        return NavigationStack {
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
            .onDisappear { model.setMapVisible(false, screen: screenId) }
            .onChange(of: model.ask) { _, ask in
                if case .swept = ask, openMarker == nil {
                    Task {
                        try? await Task.sleep(for: .seconds(6))
                        model.clearAsk()
                    }
                }
            }
            .sheet(isPresented: $workspace.mapSheets.showingOptions) { optionsSheet.fittedSheet() }
            .sheet(isPresented: $workspace.mapSheets.pickingRoom) { shareSheet.fittedSheet() }
            .sheet(item: $workspace.mapSheets.droppingAt) { target in
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
            .sheet(item: $workspace.mapSheets.openPin) { pin in
                // Older pins predate the room id and know only their slot.
                let room =
                    model.uiState.rooms.first { $0.id == pin.roomId && pin.roomId != 0 }
                    ?? model.uiState.rooms.first { $0.index == pin.channel && $0.isRoom }
                PinSheet(
                    pin: pin,
                    roomName: room?.displayName,
                    onOpenRoom: room.map { found in
                        {
                            openPin = nil
                            app.router.open(channel: found.index)
                        }
                    },
                    canRemove: pin.canEdit(myNodeNum: model.uiState.myNodeNum),
                    onRemove: {
                        model.removePin(pin)
                        openPin = nil
                    }
                )
                .fittedSheet()
            }
            .sheet(item: $workspace.mapSheets.openMarker) { marker in
                PersonSheet(
                    marker: marker,
                    ask: model.ask,
                    onAsk: { model.askWhereTheyAre(nodeNum: marker.node.nodeNum, name: marker.name) },
                    onMessage: {
                        openMarker = nil
                        model.clearAsk()
                        app.router.openDirect(marker.node.nodeNum)
                    },
                    onDismiss: {
                        openMarker = nil
                        model.clearAsk()
                    }
                )
                .fittedSheet()
            }
            .navigationDestination(isPresented: $workspace.mapSheets.showingOfflineAreas) {
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
            onCameraIdle: { view in
                model.camera = MapCamera(
                    latitude: view.centerCoordinate.latitude,
                    longitude: view.centerCoordinate.longitude,
                    zoom: view.zoomLevel
                )
            },
            initialCamera: model.camera,
            markers: model.uiState.markers,
            pins: model.uiState.pins,
            showsUserLocation: LocationPermission.isAllowed && model.uiState.ownPosition != nil,
            ownLocation: model.uiState.ownPosition?.clLocation,
            onMarkerTap: { marker in if !marker.isSelf { openMarker = marker } },
            onPinTap: { pin in openPin = pin },
            onLongPress: { coordinate in droppingAt = DropTarget(coordinate: coordinate) }
        )
        .onChange(of: model.uiState.markers) { _, _ in frameMarkersIfNeeded() }
        .onChange(of: model.uiState.ownPosition) { _, _ in frameMarkersIfNeeded() }
        .onChange(of: model.offlineOnly) { _, _ in frameMarkersIfNeeded(force: model.offlineOnly) }
    }

    private var overlayBody: some View {
        VStack(spacing: 0) {
            HStack(alignment: .top) {
                noticeView
                Spacer(minLength: FirepitSpacing.s)
                // ◫ under ⋮ rather than beside it: a map side can be 280 wide, and the top row already holds the
                // notice and Show everyone.
                VStack(spacing: FirepitSpacing.s) {
                    mapOptionsButton
                    LayoutMenu {
                        Image(systemName: "rectangle.split.2x1")
                            .font(.headline)
                            .foregroundStyle(FirepitColors.textPrimary)
                            .frame(width: 48, height: 48)
                            .floatingControlBackground(in: .rect(cornerRadius: FirepitRadius.large))
                    }
                }
            }
            // Top centre, where Android has it: online, the map waits to be asked before going to where people are.
            .overlay(alignment: .top) {
                if !hasFramedMarkers && !model.offlineOnly && !model.uiState.markers.isEmpty {
                    Button("Show everyone") { frameAll(force: true) }
                        .buttonStyle(.pill)
                }
            }
            .padding(.top, FirepitSpacing.screenMargin)
            .padding(.horizontal, FirepitSpacing.m)
            Spacer()
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
            onFollow: model.resumeFollowing,
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
            preferredRoomId: {
                if case .room(let roomId, _)? = model.uiState.followable { return roomId }
                return nil
            }(),
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
        model.setLocationAllowed(LocationPermission.isAllowed)
        if LocationPermission.isDenied {
            model.reportPermissionDenied()
        }
        if LocationPermission.isAllowed {
            model.setMapVisible(true, screen: screenId)
        }
    }

    private func frameMarkersIfNeeded(force: Bool = false) {
        #if DEBUG
            if debugFramesEveryone && !hasFramedMarkers && !model.uiState.markers.isEmpty {
                frameAll(force: true)
                return
            }
        #endif
        guard (force || !hasFramedMarkers) && model.offlineOnly &&
            (!model.uiState.markers.isEmpty || model.uiState.ownPosition != nil) else { return }
        frameAll(force: force)
    }

    private func frameAll(force: Bool) {
        guard let mapView else { return }
        if CameraDecision.frameMarkers(
            on: mapView,
            markers: model.uiState.markers,
            ownPosition: model.uiState.ownPosition
        ) || force {
            hasFramedMarkers = true
        }
    }
}

private extension OwnPosition.Fix {
    var clLocation: CLLocation {
        CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: latitude, longitude: longitude),
            altitude: altitude.map(Double.init) ?? 0,
            horizontalAccuracy: kCLLocationAccuracyBest,
            verticalAccuracy: altitude == nil ? -1 : kCLLocationAccuracyBest,
            timestamp: Date(timeIntervalSince1970: Double(timeMillis) / 1_000)
        )
    }
}

private struct PendingShare: Identifiable {
    let roomId: Int32
    let choice: ShareDuration
    var id: Int32 { roomId }
}

/// Where a pin is about to be dropped, identified so a sheet can present it.
struct DropTarget: Identifiable {
    let id = UUID()
    let coordinate: CLLocationCoordinate2D
}
