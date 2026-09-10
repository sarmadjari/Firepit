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
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.core.designsystem.component.BackButton
import com.getfirepit.core.designsystem.component.FirepitIcons
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
    onOpenOfflineAreas: () -> Unit = {},
    viewModel: MapViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pickingRoom by remember { mutableStateOf(false) }
    var pendingRoomId by remember { mutableStateOf<Int?>(null) }
    var locationDenied by remember { mutableStateOf(false) }
    val dark = FirepitTheme.colors.isDark
    val context = LocalContext.current

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        // Coarse is enough to publish a position; fine simply makes it better.
        if (granted.values.any { it }) {
            viewModel.setMapVisible(true)
            pendingRoomId?.let(viewModel::shareWith)
        } else {
            locationDenied = true
            if (pendingRoomId != null) viewModel.reportPermissionDenied()
        }
        pendingRoomId = null
    }

    // Asked on opening the map, not on sharing: showing yourself is local and
    // has no privacy consequence, so it should not require opting into a room.
    //
    // Bound to the lifecycle rather than the composition: backgrounding the app
    // does not dispose this screen, so a plain DisposableEffect left the GPS
    // running with the screen off, drawing power for a map nobody could see.
    LifecycleStartEffect(Unit) {
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
        onStopOrDispose { viewModel.setMapVisible(false) }
    }

    // Held so redraws reuse one manager: a new one per update would stack
    // annotation layers on the style until the map stopped drawing.
    val markerLayer = remember { MarkerLayer() }
    val coverageMask = remember { CoverageMask() }
    var hasFramedMarkers by remember { mutableStateOf(false) }
    var droppingAt by remember { mutableStateOf<LatLng?>(null) }
    var openPin by remember { mutableStateOf<MapPin?>(null) }
    val offlineOnly by viewModel.offlineOnly.collectAsStateWithLifecycle()
    val areas by viewModel.areas.collectAsStateWithLifecycle()

    LaunchedEffect(state.markers, state.pins, dark) {
        markerLayer.draw(state.markers, state.pins, dark)
        if (!hasFramedMarkers && state.markers.isNotEmpty()) {
            // Only latch once the camera actually moved, or a first draw that
            // beat the style load would leave the map stuck in the Atlantic.
            hasFramedMarkers = markerLayer.frameAll(state.markers)
        }
    }

    LaunchedEffect(offlineOnly, areas) {
        markerLayer.style()?.let { coverageMask.apply(it, areas, offlineOnly) }
    }

    Scaffold(
        modifier = modifier,
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
                markerLayer.setOnPinClick { pin -> openPin = pin }
                map.style?.let { coverageMask.apply(it, areas, offlineOnly) }
                hasFramedMarkers = markerLayer.frameAll(state.markers)
                map.addOnMapLongClickListener { point ->
                    droppingAt = point
                    true
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    // Stops short of the controls stacked in the top corner,
                    // which were clipping the text.
                    .padding(
                        start = FirepitSpacing.m,
                        top = FirepitSpacing.m,
                        end = FirepitSpacing.m + CONTROL_SIZE + FirepitSpacing.s,
                    ),
                verticalArrangement = Arrangement.spacedBy(FirepitSpacing.s),
            ) {
                when {
                    // Only things the user can act on. Ordered by what blocks
                    // them soonest, and at most one at a time: a map covered in
                    // banners is a worse map, and a permanent tip is nagging.
                    locationDenied -> MapNotice(
                        "Location is off, so your own position cannot be shown. " +
                            "Turn it on in Android settings.",
                    )

                    !state.connected -> MapNotice("Not connected — open Settings to reach your node.")
                    offlineOnly && areas.isEmpty() -> MapNotice(
                        "Offline maps only is on but nothing is downloaded, so the map is blank. " +
                            "Settings → Offline areas.",
                    )

                    // Silence otherwise. Offline-only is a choice the user made
                    // and the grey ground already shows it; a GPS fix arrives on
                    // its own; and an empty map is described by the tally below.
                    else -> Unit
                }
                state.error?.let { MapNotice(it) }
            }

            MapControl(
                icon = FirepitIcons.Locate,
                description = "Centre on everyone",
                onClick = { markerLayer.frameAll(state.markers, force = true) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(FirepitSpacing.m),
            )

            MapControl(
                icon = FirepitIcons.Download,
                description = "Offline areas",
                onClick = onOpenOfflineAreas,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = FirepitSpacing.m + CONTROL_SIZE + FirepitSpacing.s,
                        end = FirepitSpacing.m,
                    ),
            )

            MapControl(
                icon = FirepitIcons.Pin,
                description = "Drop a pin here",
                onClick = { markerLayer.centre()?.let { droppingAt = it } },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = FirepitSpacing.m + (CONTROL_SIZE + FirepitSpacing.s) * 2,
                        end = FirepitSpacing.m,
                    ),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(FirepitSpacing.m),
            ) {
                Button(
                    onClick = {
                        if (state.isSharing) viewModel.shareWith(null) else pickingRoom = true
                    },
                    shape = RoundedCornerShape(FirepitSpacing.cardCorner),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    contentPadding = PaddingValues(
                        horizontal = FirepitSpacing.xl,
                        vertical = FirepitSpacing.m,
                    ),
                ) {
                    Icon(
                        painter = painterResource(FirepitIcons.Locate),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(FirepitSpacing.s))
                    Text(
                        text = if (state.isSharing) "Stop sharing" else "Share my location",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                Surface(
                    color = FirepitTheme.colors.surface2,
                    shape = RoundedCornerShape(
                        topStart = FirepitSpacing.xl,
                        topEnd = FirepitSpacing.xl,
                        bottomStart = 0.dp,
                        bottomEnd = 0.dp,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = mapTally(state),
                        style = MaterialTheme.typography.bodyMedium,
                        color = FirepitTheme.colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = FirepitSpacing.m),
                    )
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

/** Proto limit on Waypoint.name. */
internal const val PIN_NAME_LIMIT = 30

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

    /** Symbol id to pin, so a tap on the map can be answered with the right one. */
    private val pinsBySymbol = mutableMapOf<Long, MapPin>()
    private var onPinClick: ((MapPin) -> Unit)? = null

    fun setOnPinClick(listener: (MapPin) -> Unit) {
        onPinClick = listener
    }

    /** Where the camera is looking, for dropping a pin without a hidden gesture. */
    fun centre(): LatLng? = map?.cameraPosition?.target

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
            addClickListener { symbol ->
                pinsBySymbol[symbol.id]?.let { pin ->
                    onPinClick?.invoke(pin)
                    true
                } ?: false
            }
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
        pinsBySymbol.clear()

        markers.forEach { marker ->
            val latitude = marker.node.latitude ?: return@forEach
            val longitude = marker.node.longitude ?: return@forEach
            val imageId = "node-${marker.node.nodeNum}-${marker.isLive}-${marker.isApproximate}-${marker.isSelf}"
            style.addImage(imageId, markerBitmap(marker, dark))
            manager.create(
                SymbolOptions()
                    .withLatLng(LatLng(latitude, longitude))
                    .withIconImage(imageId)
                    // The name is drawn into the bitmap, so no text layer here.
                    .apply {
                        if (marker.isSelf) {
                            withTextField("You")
                                .withTextOffset(arrayOf(0f, 1.6f))
                                .withTextSize(11f)
                                .withTextFont(arrayOf(STYLE_FONT))
                        }
                    },
            )
        }

        pins.forEach { pin ->
            style.addImage(PIN_IMAGE, pinBitmap())
            val symbol = manager.create(
                SymbolOptions()
                    .withLatLng(LatLng(pin.latitude, pin.longitude))
                    .withIconImage(PIN_IMAGE)
                    .withTextField(pin.name)
                    .withTextOffset(arrayOf(0f, 1.4f))
                    .withTextSize(11f)
                    .withTextFont(arrayOf(STYLE_FONT)),
            )
            pinsBySymbol[symbol.id] = pin
        }
    }

    /** Frames every marker once, so later updates do not yank the camera around. */
    fun style(): Style? = map?.style

    /** Frames every marker once. Returns false when the map is not ready yet. */
    fun frameAll(markers: List<MapMarker>, force: Boolean = false): Boolean {
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

/** Round control that floats over the map, as on the mockup. */
@Composable
private fun MapControl(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(CONTROL_SIZE),
        shape = CircleShape,
        color = FirepitTheme.colors.surface2,
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = description,
                tint = FirepitTheme.colors.textPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Counts what is actually on the map, so an empty map is not silently empty. */
private fun mapTally(state: MapUiState): String {
    val people = state.markers.count { !it.isSelf }
    val live = state.markers.count { !it.isSelf && it.isLive }
    return buildList {
        add(if (people == 1) "1 person" else "$people people")
        if (live > 0) add("$live live")
        if (state.pins.isNotEmpty()) {
            add(if (state.pins.size == 1) "1 pin" else "${state.pins.size} pins")
        }
    }.joinToString(" · ")
}

private val CONTROL_SIZE = 48.dp

/**
 * A tag disc in the node's identity colour, ringed green while it is live, with
 * the name on a pill beneath it.
 *
 * The label is drawn into the same bitmap rather than left to the style's text
 * layer so it keeps its pill on any basemap; the disc stays at the bitmap's
 * centre so the icon still lands on the coordinate.
 *
 * Your own position is drawn as a plain blue dot instead: it is the one marker
 * people look for first, and a tag reading your own initials among five others
 * is not findable at a glance.
 */
private fun markerBitmap(marker: MapMarker, dark: Boolean): Bitmap {
    if (marker.isSelf) return selfBitmap()

    val disc = 96
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (marker.isLive) {
            if (dark) LABEL_TEXT_DARK else LABEL_TEXT_LIGHT
        } else {
            if (dark) LABEL_MUTED_DARK else LABEL_MUTED_LIGHT
        }
        textAlign = Paint.Align.CENTER
        textSize = 30f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    val text = markerLabel(marker)
    val textWidth = label.measureText(text)
    val pillHeight = 46f
    val pillWidth = textWidth + 32f
    val gap = 8f
    // Padded equally above so the disc, not the whole bitmap, sits on the fix.
    val extra = (gap + pillHeight) * 2
    val width = maxOf(disc.toFloat(), pillWidth).toInt()
    val height = (disc + extra).toInt()

    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)
    val centreX = width / 2f
    val centreY = height / 2f

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
    val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    val radius = disc / 2f - 8f
    canvas.drawCircle(centreX, centreY, radius, fill)
    canvas.drawCircle(centreX, centreY, radius, ring)
    canvas.drawText(
        marker.tag.take(2).uppercase(),
        centreX,
        centreY - (tag.descent() + tag.ascent()) / 2f,
        tag,
    )

    val pillTop = centreY + radius + gap
    val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) LABEL_PILL_DARK else LABEL_PILL_LIGHT
    }
    canvas.drawRoundRect(
        centreX - pillWidth / 2f,
        pillTop,
        centreX + pillWidth / 2f,
        pillTop + pillHeight,
        12f,
        12f,
        pill,
    )
    canvas.drawText(
        text,
        centreX,
        pillTop + pillHeight / 2f - (label.descent() + label.ascent()) / 2f,
        label,
    )
    return bitmap
}

/** A stale marker names its age, because a position with no time is a guess presented as a fact. */
private fun markerLabel(marker: MapMarker): String {
    val name = marker.node.displayName
    if (marker.isLive) return name
    val heard = marker.node.lastHeard ?: return name
    val minutes = (System.currentTimeMillis() - heard) / 60_000
    return when {
        minutes < 1 -> name
        minutes < 60 -> "$name · ${minutes}m"
        minutes < 60 * 24 -> "$name · ${minutes / 60}h"
        else -> "$name · ${minutes / (60 * 24)}d"
    }
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

// Ember surface-2 and text tokens, as ARGB for the Canvas that draws the pills.
private const val LABEL_PILL_LIGHT = 0xFFFFFFFF.toInt()
private const val LABEL_PILL_DARK = 0xFF1E1B18.toInt()
private const val LABEL_TEXT_LIGHT = 0xFF1A1614.toInt()
private const val LABEL_TEXT_DARK = 0xFFF1ECE7.toInt()
private const val LABEL_MUTED_LIGHT = 0xFF6B625C.toInt()
private const val LABEL_MUTED_DARK = 0xFFA39C95.toInt()
private const val PIN_IMAGE = "map-pin"
private const val PIN_COLOR = 0xFFF59E0B.toInt()
private const val SELF_COLOR = 0xFF2563EB.toInt()

/** The only font family the OpenFreeMap style serves glyphs for. */
private const val STYLE_FONT = "Noto Sans Regular"
