package com.getfirepit.app.map

import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * Greys out ground that is not downloaded.
 *
 * A polygon covering the world with a hole punched for each saved area, so what
 * remains in colour is exactly what will still be there with no signal. Without
 * this, going offline just makes tiles stop appearing, with nothing to say why.
 *
 * The holes are region bounds, not true tile coverage: a region downloaded to
 * zoom 15 has nothing beyond it, so zooming far in inside a clear area can still
 * run out of detail.
 */
class CoverageMask {

    fun apply(style: Style, areas: List<OfflineArea>, enabled: Boolean) {
        val bounds = areas.mapNotNull { it.bounds }
        if (!enabled || bounds.isEmpty()) {
            remove(style)
            return
        }

        val polygon = Polygon.fromLngLats(listOf(WORLD_RING) + bounds.map(::ringFor))
        val geoJson = Feature.fromGeometry(polygon)

        val existing = style.getSourceAs<GeoJsonSource>(SOURCE_ID)
        if (existing != null) {
            existing.setGeoJson(geoJson)
            return
        }

        style.addSource(GeoJsonSource(SOURCE_ID, geoJson))
        style.addLayer(
            FillLayer(LAYER_ID, SOURCE_ID).withProperties(
                PropertyFactory.fillColor(MASK_COLOR),
                PropertyFactory.fillOpacity(MASK_OPACITY),
            ),
        )
    }

    fun remove(style: Style) {
        runCatching { style.removeLayer(LAYER_ID) }
        runCatching { style.removeSource(SOURCE_ID) }
    }

    private fun ringFor(bounds: LatLngBounds): List<Point> = listOf(
        Point.fromLngLat(bounds.longitudeWest, bounds.latitudeSouth),
        Point.fromLngLat(bounds.longitudeEast, bounds.latitudeSouth),
        Point.fromLngLat(bounds.longitudeEast, bounds.latitudeNorth),
        Point.fromLngLat(bounds.longitudeWest, bounds.latitudeNorth),
        Point.fromLngLat(bounds.longitudeWest, bounds.latitudeSouth),
    )

    private companion object {
        const val SOURCE_ID = "coverage-mask-source"
        const val LAYER_ID = "coverage-mask-layer"
        const val MASK_COLOR = "#5B5B5B"
        const val MASK_OPACITY = 0.62f

        /** Web Mercator stops short of the poles, so the outer ring does too. */
        val WORLD_RING = listOf(
            Point.fromLngLat(-180.0, -85.0),
            Point.fromLngLat(180.0, -85.0),
            Point.fromLngLat(180.0, 85.0),
            Point.fromLngLat(-180.0, 85.0),
            Point.fromLngLat(-180.0, -85.0),
        )
    }
}
