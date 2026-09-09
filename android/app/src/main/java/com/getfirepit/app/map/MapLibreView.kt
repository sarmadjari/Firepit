package com.getfirepit.app.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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
 * leaks the context. Fold and unfold recreate the composition, so the view is
 * keyed on the configuration and rebuilt rather than resized in place.
 */
@Composable
fun MapLibreView(
    styleUrl: String,
    modifier: Modifier = Modifier,
    onMapReady: (MapLibreMap, MapView) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current

    val mapView = remember(configuration.orientation, configuration.screenWidthDp) {
        MapViewHolder()
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            val view = mapView.view ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                Lifecycle.Event.ON_DESTROY -> view.onDestroy()
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
