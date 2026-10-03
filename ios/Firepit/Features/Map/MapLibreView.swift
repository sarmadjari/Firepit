import CoreLocation
import FirepitModel
import MapLibre
import SwiftUI

/// The MapLibre surface, as a SwiftUI view. Ported from android/app/…/map/MapLibreView.kt.
///
/// Android forwards every lifecycle callback to its MapView by hand; on iOS `MLNMapView` follows its window, so this
/// only has to create the view, load the style and report back. `onReady` fires once the style has loaded, which is
/// when layers and sources can be added; `onCameraIdle` fires whenever the camera settles.
struct MapLibreView: UIViewRepresentable {
    let styleUrl: String
    var onReady: (MLNMapView, MLNStyle) -> Void = { _, _ in }
    var onCameraIdle: (MLNMapView) -> Void = { _ in }
    var markers: [MapMarker] = []
    var pins: [MapPin] = []
    var showsUserLocation = false
    var onMarkerTap: (MapMarker) -> Void = { _ in }
    var onPinTap: (MapPin) -> Void = { _ in }
    var onLongPress: (CLLocationCoordinate2D) -> Void = { _ in }

    func makeCoordinator() -> Coordinator {
        Coordinator(
            onReady: onReady,
            onCameraIdle: onCameraIdle,
            onMarkerTap: onMarkerTap,
            onPinTap: onPinTap,
            onLongPress: onLongPress
        )
    }

    func makeUIView(context: Context) -> MLNMapView {
        let view = MLNMapView(frame: .zero, styleURL: URL(string: styleUrl))
        view.delegate = context.coordinator
        context.coordinator.installLongPress(on: view)
        view.showsUserLocation = showsUserLocation
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        // The mesh has no use for the tilt or rotation gestures, and a rotated map misleads anyone reading a
        // position off it in a hurry.
        view.allowsTilting = false
        view.compassViewPosition = .topRight
        return view
    }

    func updateUIView(_ view: MLNMapView, context: Context) {
        context.coordinator.onReady = onReady
        context.coordinator.onCameraIdle = onCameraIdle
        context.coordinator.onMarkerTap = onMarkerTap
        context.coordinator.onPinTap = onPinTap
        context.coordinator.onLongPress = onLongPress
        view.showsUserLocation = showsUserLocation
        context.coordinator.updateAnnotations(on: view, markers: markers, pins: pins)
    }

    @MainActor
    final class Coordinator: NSObject, @preconcurrency MLNMapViewDelegate {
        var onReady: (MLNMapView, MLNStyle) -> Void
        var onCameraIdle: (MLNMapView) -> Void
        var onMarkerTap: (MapMarker) -> Void
        var onPinTap: (MapPin) -> Void
        var onLongPress: (CLLocationCoordinate2D) -> Void
        private var markerIds: Set<Int32> = []
        private var pinIds: Set<Int32> = []

        init(
            onReady: @escaping (MLNMapView, MLNStyle) -> Void,
            onCameraIdle: @escaping (MLNMapView) -> Void,
            onMarkerTap: @escaping (MapMarker) -> Void,
            onPinTap: @escaping (MapPin) -> Void,
            onLongPress: @escaping (CLLocationCoordinate2D) -> Void
        ) {
            self.onReady = onReady
            self.onCameraIdle = onCameraIdle
            self.onMarkerTap = onMarkerTap
            self.onPinTap = onPinTap
            self.onLongPress = onLongPress
        }

        // MapLibre calls its delegate on the main thread.
        func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
            onReady(mapView, style)
            onCameraIdle(mapView)
        }

        func mapView(_ mapView: MLNMapView, regionDidChangeAnimated animated: Bool) {
            onCameraIdle(mapView)
        }

        func installLongPress(on mapView: MLNMapView) {
            let recognizer = UILongPressGestureRecognizer(target: self, action: #selector(longPressed(_:)))
            recognizer.minimumPressDuration = 0.55
            mapView.addGestureRecognizer(recognizer)
        }

        func updateAnnotations(on mapView: MLNMapView, markers: [MapMarker], pins: [MapPin]) {
            let nextMarkerIds = Set(markers.map(\.id))
            let nextPinIds = Set(pins.map(\.id))
            guard nextMarkerIds != markerIds || nextPinIds != pinIds else { return }
            let ours = (mapView.annotations ?? []).filter { annotation in
                annotation is MapNodeAnnotation || annotation is MapPinAnnotation
            }
            mapView.removeAnnotations(ours)
            let markerAnnotations = markers.compactMap(MapNodeAnnotation.init(marker:))
            let pinAnnotations = pins.map(MapPinAnnotation.init(pin:))
            mapView.addAnnotations(markerAnnotations + pinAnnotations)
            markerIds = nextMarkerIds
            pinIds = nextPinIds
        }

        func mapView(_ mapView: MLNMapView, viewFor annotation: MLNAnnotation) -> MLNAnnotationView? {
            if let annotation = annotation as? MapNodeAnnotation {
                let identifier = "firepit-node"
                let view =
                    mapView.dequeueReusableAnnotationView(withIdentifier: identifier) as? MapNodeAnnotationView
                    ?? MapNodeAnnotationView(reuseIdentifier: identifier)
                view.configure(marker: annotation.marker)
                return view
            }
            if let annotation = annotation as? MapPinAnnotation {
                let identifier = "firepit-pin"
                let view =
                    mapView.dequeueReusableAnnotationView(withIdentifier: identifier) as? MapPinAnnotationView
                    ?? MapPinAnnotationView(reuseIdentifier: identifier)
                view.configure(pin: annotation.pin)
                return view
            }
            return nil
        }

        func mapView(_ mapView: MLNMapView, didSelect annotation: MLNAnnotation) {
            if let annotation = annotation as? MapNodeAnnotation {
                onMarkerTap(annotation.marker)
            } else if let annotation = annotation as? MapPinAnnotation {
                onPinTap(annotation.pin)
            }
            mapView.deselectAnnotation(annotation, animated: false)
        }

        @objc private func longPressed(_ recognizer: UILongPressGestureRecognizer) {
            guard recognizer.state == .began, let mapView = recognizer.view as? MLNMapView else { return }
            let point = recognizer.location(in: mapView)
            onLongPress(mapView.convert(point, toCoordinateFrom: mapView))
        }

    }
}

extension MLNMapView {
    /// The part of the world on screen, as a plain box.
    var visibleBox: GeoBox {
        let bounds = visibleCoordinateBounds
        return GeoBox(
            north: bounds.ne.latitude, south: bounds.sw.latitude,
            east: bounds.ne.longitude, west: bounds.sw.longitude)
    }

    /// Points the camera at a `radiusKm` box around a point, as Android's `frameAround` does.
    func frame(around centre: CLLocationCoordinate2D, radiusKm: Double, padding: CGFloat = 24) {
        let box = boxAround(latitude: centre.latitude, longitude: centre.longitude, radiusKm: radiusKm)
        let bounds = MLNCoordinateBounds(
            sw: CLLocationCoordinate2D(latitude: box.south, longitude: box.west),
            ne: CLLocationCoordinate2D(latitude: box.north, longitude: box.east)
        )
        let inset = UIEdgeInsets(top: padding, left: padding, bottom: padding, right: padding)
        setVisibleCoordinateBounds(bounds, edgePadding: inset, animated: false, completionHandler: nil)
    }
}

/// The OpenFreeMap style both platforms draw (android/app/…/map/MapScreen.kt `OPEN_FREE_MAP_STYLE`).
nonisolated let openFreeMapStyle = "https://tiles.openfreemap.org/styles/liberty"
