package com.getfirepit.app.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.core.designsystem.component.BackButton
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.designsystem.theme.identityColorFor
import com.getfirepit.core.model.MapPin
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: MapViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pickingRoom by remember { mutableStateOf(false) }
    var pendingRoomId by remember { mutableStateOf<Int?>(null) }
    val dark = FirepitTheme.colors.isDark
    val context = LocalContext.current

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        // Coarse is enough to publish a position; fine simply makes it better.
        if (granted.values.any { it }) {
            viewModel.setMapVisible(true)
            pendingRoomId?.let(viewModel::shareWith)
        } else if (pendingRoomId != null) {
            viewModel.reportPermissionDenied()
        }
        pendingRoomId = null
    }

    // Asked on opening the map, not on sharing: showing yourself is local and
    // has no privacy consequence, so it should not require opting into a room.
    DisposableEffect(Unit) {
        if (hasLocationPermission(context)) {
            viewModel.setMapVisible(true)
        } else {
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
        onDispose { viewModel.setMapVisible(false) }
    }

    // Held so redraws reuse one manager: a new one per update would stack
    // annotation layers on the style until the map stopped drawing.
    val markerLayer = remember { MarkerLayer() }
    var hasFramedMarkers by remember { mutableStateOf(false) }
    var droppingAt by remember { mutableStateOf<LatLng?>(null) }
    var openPin by remember { mutableStateOf<MapPin?>(null) }

    LaunchedEffect(state.markers, state.pins, dark) {
        markerLayer.draw(state.markers, state.pins, dark)
        if (!hasFramedMarkers && state.markers.isNotEmpty()) {
            // Only latch once the camera actually moved, or a first draw that
            // beat the style load would leave the map stuck in the Atlantic.
            hasFramedMarkers = markerLayer.frameAll(state.markers)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Map") },
                navigationIcon = { BackButton(onClick = onBack) },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (state.isSharing) viewModel.shareWith(null) else pickingRoom = true },
                text = { Text(if (state.isSharing) "Stop sharing" else "Share location") },
                icon = {},
            )
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            MapLibreView(
                styleUrl = OPEN_FREE_MAP_STYLE,
                modifier = Modifier.fillMaxSize(),
            ) { map, view ->
                markerLayer.attach(map, view)
                hasFramedMarkers = markerLayer.frameAll(state.markers)
                map.addOnMapLongClickListener { point ->
                    droppingAt = point
                    true
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(FirepitSpacing.m)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(FirepitSpacing.s),
            ) {
                when {
                    !state.connected -> MapNotice("Not connected — open Settings to reach your node.")
                    state.markers.none { it.isSelf } ->
                        MapNotice("Waiting for a GPS fix for your own position.")

                    state.markers.isEmpty() ->
                        MapNotice("Nobody is sharing a position yet.")
                }
                state.error?.let { MapNotice(it) }
                if (state.markers.isNotEmpty() || state.pins.isNotEmpty()) {
                    MapNotice("Long-press the map to drop a pin.")
                }
            }
        }
    }

    if (pickingRoom) {
        ShareRoomDialog(
            state = state,
            onDismiss = { pickingRoom = false },
            onPick = { roomId ->
                pickingRoom = false
                if (hasLocationPermission(context)) {
                    viewModel.shareWith(roomId)
                } else {
                    pendingRoomId = roomId
                    locationPermission.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                }
            },
        )
    }

    droppingAt?.let { point ->
        DropPinDialog(
            onDismiss = { droppingAt = null },
            onDrop = { name ->
                viewModel.dropPin(
                    latitudeI = (point.latitude * 1e7).toInt(),
                    longitudeI = (point.longitude * 1e7).toInt(),
                    name = name,
                )
                droppingAt = null
            },
        )
    }

    openPin?.let { pin ->
        PinSheet(
            pin = pin,
            canRemove = pin.canEdit(state.myNodeNum),
            onRemove = {
                viewModel.removePin(pin)
                openPin = null
            },
            onDismiss = { openPin = null },
        )
    }
}

@Composable
private fun DropPinDialog(onDismiss: () -> Unit, onDrop: (String) -> Unit) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Drop a pin") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FirepitSpacing.s)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= PIN_NAME_LIMIT) name = it },
                    singleLine = true,
                    placeholder = { Text("Water") },
                    supportingText = { Text("${PIN_NAME_LIMIT - name.length} characters left") },
                )
                Text(
                    text = "Pins are public to everyone on the channel and travel over the mesh " +
                        "like any other message.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FirepitTheme.colors.textSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDrop(name.trim()) }, enabled = name.isNotBlank()) { Text("Drop") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinSheet(pin: MapPin, canRemove: Boolean, onRemove: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FirepitSpacing.screenMargin)
                .padding(bottom = FirepitSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(FirepitSpacing.s),
        ) {
            Text(pin.name.ifBlank { "Pin" }, style = MaterialTheme.typography.titleMedium)
            if (pin.description.isNotBlank()) {
                Text(pin.description, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = "%.5f, %.5f".format(pin.latitude, pin.longitude),
                style = MaterialTheme.typography.bodySmall,
                color = FirepitTheme.colors.textSecondary,
            )

            if (canRemove) {
                TextButton(onClick = onRemove) { Text("Remove for everyone") }
            } else {
                Text(
                    text = "Only the person who placed this pin can remove it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FirepitTheme.colors.textSecondary,
                )
            }
        }
    }
}

private const val PIN_NAME_LIMIT = 30

private fun hasLocationPermission(context: Context): Boolean = listOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
).any { permission ->
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun MapNotice(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(FirepitSpacing.m),
        )
    }
}

@Composable
private fun ShareRoomDialog(state: MapUiState, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var chosen by remember { mutableStateOf(state.rooms.firstOrNull()?.id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share your location") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FirepitSpacing.xs)) {
                when {
                    !state.connected -> Text(
                        text = "Connect to your node first — Settings, then Nodes.",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    state.rooms.isEmpty() -> Text(
                        // Slot 0 is deliberately never used for position: it
                        // would broadcast to every Meshtastic node in range.
                        text = "You need a room first. Location is only ever shared with one room, " +
                            "never on the public channel, so there is nothing to share with yet.\n\n" +
                            "Create or join a room from Chats, then come back.",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    else -> {
                        Text(
                            text = "Your position goes to one room only. Choosing a room here stops " +
                                "sharing with every other.",
                            style = MaterialTheme.typography.bodySmall,
                            color = FirepitTheme.colors.textSecondary,
                        )
                        state.rooms.forEach { room ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = chosen == room.id, onClick = { chosen = room.id })
                                Text(room.displayName, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        Text(
                            text = "Anyone holding the room's key can see it, including people " +
                                "invited later.",
                            style = MaterialTheme.typography.bodySmall,
                            color = FirepitTheme.colors.warn,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (state.rooms.isNotEmpty() && state.connected) {
                TextButton(onClick = { chosen?.let(onPick) }, enabled = chosen != null) { Text("Share") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (state.rooms.isEmpty() || !state.connected) "Close" else "Cancel")
            }
        },
    )
}

/** Owns the symbol manager so markers are replaced rather than stacked. */
private class MarkerLayer {
    private var map: MapLibreMap? = null
    private var symbols: SymbolManager? = null

    // The style loads asynchronously, so markers usually arrive before there is
    // anywhere to put them. Holding the latest set means attaching draws it
    // rather than waiting for the next change that may never come.
    private var markers: List<MapMarker> = emptyList()
    private var pins: List<MapPin> = emptyList()
    private var dark: Boolean = false

    fun attach(map: MapLibreMap, view: MapView) {
        val style: Style = map.style ?: return
        this.map = map
        symbols?.onDestroy()
        symbols = SymbolManager(view, map, style).apply {
            // People matter more than tidiness: a node hidden by collision is a
            // person missing from the map. Each symbol sets its own font,
            // because the default stack has no glyphs on this style's server.
            iconAllowOverlap = true
            iconIgnorePlacement = true
        }
        redraw()
    }

    fun draw(markers: List<MapMarker>, pins: List<MapPin>, dark: Boolean) {
        this.markers = markers
        this.pins = pins
        this.dark = dark
        redraw()
    }

    private fun redraw() {
        val manager = symbols ?: return
        val style = map?.style ?: return
        manager.deleteAll()

        markers.forEach { marker ->
            val latitude = marker.node.latitude ?: return@forEach
            val longitude = marker.node.longitude ?: return@forEach
            val imageId = "node-${marker.node.nodeNum}-${marker.isLive}-${marker.isApproximate}-${marker.isSelf}"
            style.addImage(imageId, markerBitmap(marker, dark))
            manager.create(
                SymbolOptions()
                    .withLatLng(LatLng(latitude, longitude))
                    .withIconImage(imageId)
                    .withTextField(if (marker.isSelf) "You" else marker.node.displayName)
                    .withTextOffset(arrayOf(0f, 1.6f))
                    .withTextSize(11f)
                    .withTextFont(arrayOf(STYLE_FONT)),
            )
        }

        pins.forEach { pin ->
            style.addImage(PIN_IMAGE, pinBitmap())
            manager.create(
                SymbolOptions()
                    .withLatLng(LatLng(pin.latitude, pin.longitude))
                    .withIconImage(PIN_IMAGE)
                    .withTextField(pin.name)
                    .withTextOffset(arrayOf(0f, 1.4f))
                    .withTextSize(11f)
                    .withTextFont(arrayOf(STYLE_FONT)),
            )
        }
    }

    /** Frames every marker once, so later updates do not yank the camera around. */
    /** Frames every marker once. Returns false when the map is not ready yet. */
    fun frameAll(markers: List<MapMarker>): Boolean {
        val map = map ?: return false
        val points = markers.mapNotNull { marker ->
            val latitude = marker.node.latitude ?: return@mapNotNull null
            val longitude = marker.node.longitude ?: return@mapNotNull null
            LatLng(latitude, longitude)
        }
        when {
            points.size == 1 -> map.animateCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 14.0))
            points.size > 1 -> {
                val bounds = LatLngBounds.Builder().includes(points).build()
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 96))
            }
        }
        return points.isNotEmpty()
    }
}

/**
 * A tag disc in the node's identity colour, ringed green while it is live.
 *
 * Your own position is drawn as a plain blue dot instead: it is the one marker
 * people look for first, and a tag reading your own initials among five others
 * is not findable at a glance.
 */
private fun markerBitmap(marker: MapMarker, dark: Boolean): Bitmap {
    if (marker.isSelf) return selfBitmap()

    val size = 96
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = identityColorFor(marker.node.nodeNum, dark).toArgb()
        // A truncated fix describes an area, so the disc is softened to say so.
        alpha = if (marker.isApproximate) 150 else 255
    }
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (marker.isLive) LIVE_RING else STALE_RING
        style = Paint.Style.STROKE
        strokeWidth = 7f
    }
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    val radius = size / 2f - 8f
    canvas.drawCircle(size / 2f, size / 2f, radius, fill)
    canvas.drawCircle(size / 2f, size / 2f, radius, ring)
    canvas.drawText(
        marker.tag.take(2).uppercase(),
        size / 2f,
        size / 2f - (label.descent() + label.ascent()) / 2f,
        label,
    )
    return bitmap
}

/** The conventional you-are-here dot: solid fill, white collar, soft halo. */
private fun selfBitmap(): Bitmap {
    val size = 96
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)
    val centre = size / 2f

    val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SELF_COLOR
        alpha = 60
    }
    val collar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SELF_COLOR }

    canvas.drawCircle(centre, centre, centre - 4f, halo)
    canvas.drawCircle(centre, centre, centre * 0.52f, collar)
    canvas.drawCircle(centre, centre, centre * 0.40f, core)
    return bitmap
}

/** A teardrop in the warn colour, distinct from the round node discs. */
private fun pinBitmap(): Bitmap {
    val size = 72
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PIN_COLOR }

    val radius = size / 3f
    canvas.drawCircle(size / 2f, radius + 4f, radius, paint)
    val tail = android.graphics.Path().apply {
        moveTo(size / 2f - radius * 0.6f, radius + 12f)
        lineTo(size / 2f, size.toFloat() - 4f)
        lineTo(size / 2f + radius * 0.6f, radius + 12f)
        close()
    }
    canvas.drawPath(tail, paint)

    val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    canvas.drawCircle(size / 2f, radius + 4f, radius * 0.38f, hole)
    return bitmap
}

internal const val OPEN_FREE_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val LIVE_RING = 0xFF4ADE80.toInt()
private const val STALE_RING = 0xFF8A8A8A.toInt()
private const val PIN_IMAGE = "map-pin"
private const val PIN_COLOR = 0xFFF59E0B.toInt()
private const val SELF_COLOR = 0xFF2563EB.toInt()

/** The only font family the OpenFreeMap style serves glyphs for. */
private const val STYLE_FONT = "Noto Sans Regular"
