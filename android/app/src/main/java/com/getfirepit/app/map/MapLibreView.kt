package com.getfirepit.app.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/**
 * The MapLibre surface, driven by the host lifecycle.
 *
 * MapView holds a GL surface and needs every lifecycle callback forwarded or it
 * leaks the context. The view lives exactly as long as its place in the
 * composition and is resized in place, whether the window or the map's pane
 * changes size. It used to be destroyed on every window resize while it was
 * still on screen, and MapLibre crashed when that surface then resized.
 */
@Composable
fun MapLibreView(
    styleUrl: String,
    modifier: Modifier = Modifier,
    onMapReady: (MapLibreMap, MapView) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapViewHolder() }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            val view = mapView.view ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    // Once only: leaving the composition after this must not destroy it again.
                    view.onDestroy()
                    mapView.view = null
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.view?.onDestroy()
            mapView.view = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            MapLibre.getInstance(context)
            MapView(context).also { view ->
                mapView.view = view
                view.onCreate(null)
                view.getMapAsync { map ->
                    map.setStyle(styleUrl) { onMapReady(map, view) }
                }
            }
        },
    )
}

/** Holds the view across recompositions so lifecycle events reach the live instance. */
private class MapViewHolder {
    var view: MapView? = null
}
