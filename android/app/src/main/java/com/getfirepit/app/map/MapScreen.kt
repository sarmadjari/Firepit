package com.getfirepit.app.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withRotation
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.app.location.ShareLocationSheet
import com.getfirepit.app.location.SharingBanner
import com.getfirepit.app.location.SharingViewModel
import com.getfirepit.core.designsystem.component.BackButton
import com.getfirepit.core.designsystem.component.FirepitChip
import com.getfirepit.core.designsystem.component.FirepitIcons
import com.getfirepit.core.designsystem.component.SectionLabel
import com.getfirepit.core.designsystem.theme.FirepitColors
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.designsystem.theme.SheetShape
import com.getfirepit.core.designsystem.theme.identityColorFor
import com.getfirepit.core.designsystem.theme.onIdentityColorFor
import com.getfirepit.core.protocol.NodeRole
import com.getfirepit.core.protocol.ShareDuration
import com.getfirepit.core.model.MapPin
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions
import org.maplibre.android.style.layers.Property

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenOfflineAreas: () -> Unit = {},
    viewModel: MapViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sharingViewModel: SharingViewModel = hiltViewModel()
    val sharing by sharingViewModel.state.collectAsStateWithLifecycle()
    var pickingRoom by remember { mutableStateOf(false) }
    var pendingShare by remember { mutableStateOf<PendingShare?>(null) }
    val palette = MarkerPalette.from(FirepitTheme.colors)
    val context = LocalContext.current

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        // Coarse is enough to publish a position; fine simply makes it better.
        if (granted.values.any { it }) {
            viewModel.setMapVisible(true)
            pendingShare?.let { sharingViewModel.share(it.roomId, it.choice) }
        } else if (pendingShare != null) {
            // Reported only when it blocked something: a denial on opening the
            // map costs nothing but your own dot.
            viewModel.reportPermissionDenied()
        }
        pendingShare = null
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
    var openMarker by remember { mutableStateOf<MapMarker?>(null) }
    var showingOptions by remember { mutableStateOf(false) }
    val offlineOnly by viewModel.offlineOnly.collectAsStateWithLifecycle()
    val areas by viewModel.areas.collectAsStateWithLifecycle()
    val ask by viewModel.ask.collectAsStateWithLifecycle()

    // A finished sweep has said its piece; leaving it up would make it another
    // standing notice sitting over the map.
    LaunchedEffect(ask) {
        if (ask is LocationAsk.Swept && openMarker == null) {
            delay(NOTICE_LINGER)
            viewModel.clearAsk()
        }
    }

    LaunchedEffect(state.markers, state.pins, palette, offlineOnly) {
        markerLayer.draw(state.markers, state.pins, palette)
        // Framed on its own only when the tiles come from this phone. Online,
        // moving the camera to everyone fetches the tiles around them, which
        // tells the tile server where the group is; that waits for a tap.
        if (!hasFramedMarkers && state.markers.isNotEmpty() && offlineOnly) {
            // Only latch once the camera actually moved, or a first draw that
            // beat the style load would leave the map stuck in the Atlantic.
            hasFramedMarkers = markerLayer.frameAll(state.markers)
        }
    }

    LaunchedEffect(offlineOnly, areas) {
        markerLayer.style()?.let { coverageMask.apply(it, areas, offlineOnly) }
    }

    // No Scaffold: the shell already inset this screen, and a second one added
    // the status and navigation bars again, costing roughly 180dp of map.
    Box(modifier.fillMaxSize()) {
        MapLibreView(
            styleUrl = OPEN_FREE_MAP_STYLE,
            modifier = Modifier.fillMaxSize(),
        ) { map, view ->
            markerLayer.attach(map, view)
            markerLayer.setOnPinClick { pin -> openPin = pin }
            markerLayer.setOnMarkerClick { marker -> if (!marker.isSelf) openMarker = marker }
                map.style?.let { coverageMask.apply(it, areas, offlineOnly) }
                if (offlineOnly) hasFramedMarkers = markerLayer.frameAll(state.markers)
                map.addOnMapLongClickListener { point ->
                    droppingAt = point
                    true
                }
            }

            // Only the outcome of something the user just did. The standing
            // notices that used to live here — not connected, offline-only,
            // location off — described state visible elsewhere and sat over the
            // map permanently.
            val sweeping = ask as? LocationAsk.Sweeping
            val swept = ask as? LocationAsk.Swept
            (
                state.error
                    ?: sweeping?.let { "Asking ${it.done} of ${it.total}…" }
                    ?: swept?.said
                )?.let { message ->
                MapNotice(
                    text = message,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .fillMaxWidth()
                        // Stops short of the control in the top corner, which
                        // was clipping the text.
                        .padding(
                            start = FirepitSpacing.m,
                            top = FirepitSpacing.m,
                            end = FirepitSpacing.m + CONTROL_SIZE + FirepitSpacing.s,
                        ),
                )
            }

            MapControl(
                icon = FirepitIcons.More,
                description = "Map options",
                onClick = { showingOptions = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(FirepitSpacing.m),
            )

            // Online, the map waits to be asked before going to where people
            // are: the tiles it would fetch there tell the tile server the place.
            if (!hasFramedMarkers && !offlineOnly && state.markers.isNotEmpty()) {
                Button(
                    onClick = { hasFramedMarkers = markerLayer.frameAll(state.markers, force = true) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = FirepitSpacing.m),
                ) { Text("Show everyone") }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                SharingBanner(
                    state = sharing,
                    onChange = { pickingRoom = true },
                    onStop = sharingViewModel::stop,
                    modifier = Modifier.padding(
                        horizontal = FirepitSpacing.m,
                        vertical = FirepitSpacing.s,
                    ),
                )

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

    if (showingOptions) {
        MapOptionsSheet(
            state = state,
            onDismiss = { showingOptions = false },
            onFilter = viewModel::setFilter,
            onShare = {
                showingOptions = false
                pickingRoom = true
            },
            onCentre = {
                showingOptions = false
                markerLayer.frameAll(state.markers, force = true)
            },
            onAskEveryone = {
                showingOptions = false
                viewModel.askEveryone()
            },
            onDropPin = {
                showingOptions = false
                markerLayer.centre()?.let { droppingAt = it }
            },
            onOfflineAreas = {
                showingOptions = false
                onOpenOfflineAreas()
            },
        )
    }

    if (pickingRoom) {
        ShareLocationSheet(
            state = sharing,
            onDismiss = { pickingRoom = false },
            onStop = {
                pickingRoom = false
                sharingViewModel.stop()
            },
            onShare = { roomId, choice ->
                pickingRoom = false
                if (hasLocationPermission(context)) {
                    sharingViewModel.share(roomId, choice)
                } else {
                    pendingShare = PendingShare(roomId, choice)
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

    openMarker?.let { marker ->
        PersonSheet(
            marker = marker,
            ask = ask,
            onAsk = { viewModel.askWhereTheyAre(marker.node.nodeNum, marker.name) },
            onDismiss = {
                openMarker = null
                viewModel.clearAsk()
            },
        )
    }
}

/**
 * One person, and the one thing the mesh can be asked about them.
 *
 * A position request goes to their radio, not their phone, so it reaches
 * somebody who has Firepit closed. It cannot reach one that is switched off,
 * and the wait is real — up to a minute — so the asking is shown rather than
 * left to look like nothing happened.
 */
@Composable
private fun PersonSheet(
    marker: MapMarker,
    ask: LocationAsk,
    onAsk: () -> Unit,
    onDismiss: () -> Unit,
) {
    val asking = ask is LocationAsk.Asking && ask.nodeNum == marker.node.nodeNum
    val said = (ask as? LocationAsk.Answered)
        ?.takeIf { it.nodeNum == marker.node.nodeNum }
        ?.said

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(marker.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FirepitSpacing.m)) {
                Text(
                    text = marker.fixAgeMinutes
                        ?.let { "Last seen here ${agePhrase(it)}." }
                        ?: "Nothing says when this position was taken.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (said != null) {
                    Text(
                        text = said,
                        style = MaterialTheme.typography.bodySmall,
                        color = FirepitTheme.colors.textSecondary,
                    )
                } else if (asking) {
                    Text(
                        text = "Asking their radio. This can take up to a minute.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FirepitTheme.colors.textSecondary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAsk, enabled = !asking) {
                Text(if (asking) "Asking…" else "Ask where they are")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/** Plain words for an age already rounded to minutes. */
private fun agePhrase(minutes: Long): String = when {
    minutes < 1 -> "just now"
    minutes < 60 -> "$minutes minute${if (minutes == 1L) "" else "s"} ago"
    minutes < 60 * 24 -> (minutes / 60).let { "$it hour${if (it == 1L) "" else "s"} ago" }
    else -> (minutes / (60 * 24)).let { "$it day${if (it == 1L) "" else "s"} ago" }
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
    ModalBottomSheet(onDismissRequest = onDismiss, shape = SheetShape) {
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
private fun MapNotice(text: String, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(FirepitSpacing.m),
        )
    }
}

/**
 * Everything the map can do, in one place.
 *
 * Three floating buttons stacked down the corner covered the ground they were
 * meant to help read; the map is the screen, and the controls are not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapOptionsSheet(
    state: MapUiState,
    onDismiss: () -> Unit,
    onFilter: (MapFilter) -> Unit,
    onShare: () -> Unit,
    onCentre: () -> Unit,
    onAskEveryone: () -> Unit,
    onDropPin: () -> Unit,
    onOfflineAreas: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = SheetShape,
        // Left unconsumed so the column below can clear the gesture bar itself.
        // Taking the default put the last action inside it, where a tap opened
        // Recents instead.
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Scrollable and inset past the navigation bar: the caption
                // under the chips changes length with the filter, and without
                // these the last action was pushed off the bottom.
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = FirepitSpacing.screenMargin)
                // Clears the gesture bar: the sheet is anchored to the bottom
                // of the screen, so this is what lifts the last action out of
                // it. A tap there opened Recents instead.
                .padding(bottom = FirepitSpacing.minTouchTarget),
            verticalArrangement = Arrangement.spacedBy(FirepitSpacing.s),
        ) {
            SectionLabel("Show")
            Row(horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.xs)) {
                MapFilter.entries.forEach { choice ->
                    FirepitChip(
                        label = choice.label,
                        selected = state.filter == choice,
                        onClick = { onFilter(choice) },
                    )
                }
            }
            Text(
                text = when (state.filter) {
                    MapFilter.ALL -> "Everyone this radio has heard."
                    MapFilter.OURS -> "Your rooms and your own hardware." +
                        state.hiddenByFilter.takeIf { it > 0 }?.let { " $it hidden." }.orEmpty()
                },
                style = MaterialTheme.typography.bodySmall,
                color = FirepitTheme.colors.textSecondary,
                // One line in both states, so switching filter does not shift
                // the actions under the reader's finger.
                minLines = 1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            HorizontalDivider(Modifier.padding(vertical = FirepitSpacing.s))

            SheetAction(
                icon = FirepitIcons.Locate,
                label = if (state.isSharing) "Change location sharing" else "Share my location",
                onClick = onShare,
            )
            SheetAction(FirepitIcons.Map, "Centre on everyone", onCentre)
            SheetAction(FirepitIcons.Clock, "Ask for a location update", onAskEveryone)
            SheetAction(FirepitIcons.Pin, "Drop a pin here", onDropPin)
            SheetAction(FirepitIcons.Download, "Offline areas", onOfflineAreas)
        }
    }
}

@Composable
private fun SheetAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FirepitSpacing.chipCorner))
            .clickable(onClick = onClick)
            .padding(vertical = FirepitSpacing.s, horizontal = FirepitSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.m),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = FirepitTheme.colors.textSecondary,
        )
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** A share waiting on the location permission dialog. */
private data class PendingShare(val roomId: Int, val choice: ShareDuration)

/** Owns the symbol manager so markers are replaced rather than stacked. */
private class MarkerLayer {
    private var map: MapLibreMap? = null
    private var symbols: SymbolManager? = null
    // Held for loading the role drawables painted into marker discs.
    private var context: Context? = null

    // The style loads asynchronously, so markers usually arrive before there is
    // anywhere to put them. Holding the latest set means attaching draws it
    // rather than waiting for the next change that may never come.
    private var markers: List<MapMarker> = emptyList()
    private var pins: List<MapPin> = emptyList()
    private var palette: MarkerPalette? = null

    /** Symbol id to pin, so a tap on the map can be answered with the right one. */
    private val pinsBySymbol = mutableMapOf<Long, MapPin>()
    private val markersBySymbol = mutableMapOf<Long, MapMarker>()
    private var onPinClick: ((MapPin) -> Unit)? = null
    private var onMarkerClick: ((MapMarker) -> Unit)? = null

    fun setOnPinClick(listener: (MapPin) -> Unit) {
        onPinClick = listener
    }

    fun setOnMarkerClick(listener: (MapMarker) -> Unit) {
        onMarkerClick = listener
    }

    /** Where the camera is looking, for dropping a pin without a hidden gesture. */
    fun centre(): LatLng? = map?.cameraPosition?.target

    fun attach(map: MapLibreMap, view: MapView) {
        val style: Style = map.style ?: return
        this.map = map
        this.context = view.context
        symbols?.onDestroy()
        symbols = SymbolManager(view, map, style).apply {
            // People matter more than tidiness: a node hidden by collision is a
            // person missing from the map. Each symbol sets its own font,
            // because the default stack has no glyphs on this style's server.
            iconAllowOverlap = true
            iconIgnorePlacement = true
            addClickListener { symbol ->
                val pin = pinsBySymbol[symbol.id]
                val marker = markersBySymbol[symbol.id]
                when {
                    pin != null -> onPinClick?.invoke(pin)
                    marker != null -> onMarkerClick?.invoke(marker)
                    else -> return@addClickListener false
                }
                true
            }
        }
        redraw()
    }

    fun draw(markers: List<MapMarker>, pins: List<MapPin>, palette: MarkerPalette) {
        this.markers = markers
        this.pins = pins
        this.palette = palette
        redraw()
    }

    private fun redraw() {
        val manager = symbols ?: return
        val style = map?.style ?: return
        val context = context ?: return
        val palette = palette ?: return
        manager.deleteAll()
        pinsBySymbol.clear()
        markersBySymbol.clear()

        markers.forEach { marker ->
            val latitude = marker.node.latitude ?: return@forEach
            val longitude = marker.node.longitude ?: return@forEach
            // The age and the theme are drawn into the bitmap, so they belong in the key.
            val imageId = "node-${marker.node.nodeNum}-${marker.isLive}-" +
                "${marker.isApproximate}-${marker.isSelf}-${marker.fixAgeMinutes}-${palette.dark}"
            style.addImage(imageId, markerBitmap(context, marker, palette))
            val symbol = manager.create(
                SymbolOptions()
                    .withLatLng(LatLng(latitude, longitude))
                    .withIconImage(imageId),
            )
            markersBySymbol[symbol.id] = marker
        }

        if (pins.isNotEmpty()) style.addImage(PIN_IMAGE, pinBitmap(context))
        pins.forEach { pin ->
            val symbol = manager.create(
                SymbolOptions()
                    .withLatLng(LatLng(pin.latitude, pin.longitude))
                    .withIconImage(PIN_IMAGE)
                    // The tip marks the spot, not the middle of the drawing.
                    .withIconAnchor(Property.ICON_ANCHOR_BOTTOM)
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

/** Control that floats over the map, shaped like the app's other surfaces. */
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
        shape = RoundedCornerShape(FirepitSpacing.cardCorner),
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
    // Sharing is not named here: the banner directly above says which room and
    // how long is left, and saying it twice in two lines reads as two things.
    return buildList {
        add(if (people == 1) "1 person" else "$people people")
        if (live > 0) add("$live live")
        if (state.pins.isNotEmpty()) {
            add(if (state.pins.size == 1) "1 pin" else "${state.pins.size} pins")
        }
    }.joinToString(" · ")
}

private val CONTROL_SIZE = 48.dp

/** Long enough to read a line, short enough not to become furniture. */
private val NOTICE_LINGER = 6.seconds

/**
 * Marker sizes in dp, the same numbers iOS draws in points (UX spec §6.6).
 *
 * MapLibre shows a bitmap at the screen's density, so every size is multiplied by it: a marker is the same physical
 * size on every phone, and the same as on an iPhone.
 */
private object MarkerSize {
    const val DISC = 34f
    const val RING = 3f
    const val SELF_RING = 4f
    const val TAG = 14f
    /** A tag shrinks rather than leaves the disc: Meshtastic allows four characters. */
    const val TAG_WIDTH_SHARE = 0.78f
    /** Leaves the disc's colour reading as a ring around a role symbol rather than a sliver. */
    const val ICON_SHARE = 0.62f
    const val LABEL = 12f
    const val PILL_PAD_X = 6f
    const val PILL_PAD_Y = 2f
    const val PILL_CORNER = 6f
    const val PILL_GAP = 3f
    const val ARROW_WIDTH = 10f
    const val ARROW_HEIGHT = 8f
    const val ARROW_GAP = 2f
    const val PIN = 28f
    /** A truncated fix describes an area, so the disc is softened to say so. */
    const val APPROXIMATE_ALPHA = 0.6f
}

/** The theme's colours as ARGB, for the Canvas that paints marker bitmaps outside composition. */
private data class MarkerPalette(
    val dark: Boolean,
    val live: Int,
    val stale: Int,
    val pill: Int,
    val text: Int,
    val textMuted: Int,
    val outline: Int,
) {
    companion object {
        fun from(colors: FirepitColors) = MarkerPalette(
            dark = colors.isDark,
            live = colors.live.toArgb(),
            stale = colors.stale.toArgb(),
            pill = colors.surface2.toArgb(),
            text = colors.textPrimary.toArgb(),
            textMuted = colors.textSecondary.toArgb(),
            outline = colors.outline.toArgb(),
        )
    }
}

/**
 * A tag disc in the identity colour, ringed while it is live, with the name on a pill beneath it.
 *
 * The label is drawn into the same bitmap rather than left to the style's text layer so it keeps its pill on any
 * basemap; the disc stays at the bitmap's centre so the icon still lands on the coordinate.
 *
 * Your own disc carries your name and initials rather than the radio's: the radio is what the mesh addresses, not who
 * is holding it.
 */
private fun markerBitmap(context: Context, marker: MapMarker, palette: MarkerPalette): Bitmap {
    val density = context.resources.displayMetrics.density
    fun dp(value: Float) = value * density

    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (marker.isLive) palette.text else palette.textMuted
        textAlign = Paint.Align.CENTER
        textSize = dp(MarkerSize.LABEL)
        typeface = Typeface.create(Typeface.DEFAULT, SEMIBOLD, false)
    }
    val text = markerLabel(marker)
    val pillWidth = label.measureText(text) + dp(MarkerSize.PILL_PAD_X) * 2
    val pillHeight = label.descent() - label.ascent() + dp(MarkerSize.PILL_PAD_Y) * 2
    val disc = dp(MarkerSize.DISC)
    // Padded equally above so the disc, not the whole bitmap, sits on the fix.
    val extra = (dp(MarkerSize.PILL_GAP) + pillHeight) * 2
    val width = maxOf(disc, pillWidth).toInt() + 2
    val height = (disc + extra).toInt()
    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)
    val centreX = width / 2f
    val centreY = height / 2f
    val radius = disc / 2f

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = identityColorFor(marker.node.nodeNum, palette.dark, marker.colourSlot).toArgb()
        alpha = if (marker.isApproximate) (255 * MarkerSize.APPROXIMATE_ALPHA).toInt() else 255
    }
    val ringWidth = dp(if (marker.isSelf) MarkerSize.SELF_RING else MarkerSize.RING)
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Your own disc is ringed blue, the colour every map uses for you, so it
        // stays findable among a dozen discs that all look like this one.
        color = when {
            marker.isSelf -> SELF_RING
            marker.isLive -> palette.live
            else -> palette.stale
        }
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
    }
    val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // The disc inverts between themes, so flat white would sit on a light fill in dark mode.
        color = onIdentityColorFor(marker.node.nodeNum, palette.dark, marker.colourSlot).toArgb()
        textAlign = Paint.Align.CENTER
        textSize = dp(MarkerSize.TAG)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    canvas.drawCircle(centreX, centreY, radius, fill)
    // Inside the disc's edge, so the ring never makes one disc larger than another.
    canvas.drawCircle(centreX, centreY, radius - ringWidth / 2f, ring)
    // Which way they are going, when they are actually going somewhere. A
    // parked node's last course is a memory, not a direction.
    marker.course?.let { course ->
        val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (marker.isLive) palette.live else palette.stale
            style = Paint.Style.FILL
        }
        canvas.withRotation(course, centreX, centreY) {
            val base = centreY - radius - dp(MarkerSize.ARROW_GAP)
            val half = dp(MarkerSize.ARROW_WIDTH) / 2f
            val path = Path().apply {
                moveTo(centreX, base - dp(MarkerSize.ARROW_HEIGHT))
                lineTo(centreX - half, base)
                lineTo(centreX + half, base)
                close()
            }
            drawPath(path, arrow)
        }
    }
    val roleIcon = when (marker.role) {
        NodeRole.BASE -> FirepitIcons.RoleBase
        NodeRole.ROUTER -> FirepitIcons.RoleRouter
        else -> null
    }
    if (roleIcon != null) {
        drawRoleIcon(context, canvas, roleIcon, centreX, centreY, disc, tag.color)
    } else {
        // Meshtastic allows four characters, and a tag cut to two makes SJ2 and
        // SJ1 the same node. Shrink to fit the disc rather than drop what it says.
        val tagText = marker.tag.uppercase()
        val widest = disc * MarkerSize.TAG_WIDTH_SHARE
        val measured = tag.measureText(tagText)
        if (measured > widest) tag.textSize *= widest / measured
        canvas.drawText(tagText, centreX, centreY - (tag.descent() + tag.ascent()) / 2f, tag)
    }

    val pillTop = centreY + radius + dp(MarkerSize.PILL_GAP)
    val pillRect = RectF(centreX - pillWidth / 2f, pillTop, centreX + pillWidth / 2f, pillTop + pillHeight)
    val corner = dp(MarkerSize.PILL_CORNER)
    canvas.drawRoundRect(pillRect, corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.pill })
    canvas.drawRoundRect(
        pillRect,
        corner,
        corner,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.outline
            style = Paint.Style.STROKE
            strokeWidth = density
        },
    )
    canvas.drawText(text, centreX, pillRect.centerY() - (label.descent() + label.ascent()) / 2f, label)
    return bitmap
}

/**
 * Paints a role symbol inside the disc, in place of a tag.
 *
 * A base or a router is hardware, not a person, and its two initials tell a reader nothing.
 */
private fun drawRoleIcon(
    context: Context,
    canvas: Canvas,
    @DrawableRes icon: Int,
    centreX: Float,
    centreY: Float,
    disc: Float,
    tint: Int,
) {
    val drawable = ContextCompat.getDrawable(context, icon)?.mutate() ?: return
    drawable.setTint(tint)
    val half = (disc * MarkerSize.ICON_SHARE / 2f).toInt()
    drawable.setBounds(
        (centreX - half).toInt(),
        (centreY - half).toInt(),
        (centreX + half).toInt(),
        (centreY + half).toInt(),
    )
    drawable.draw(canvas)
}

/**
 * Names how long ago somebody was standing here.
 *
 * Shown however recently they were heard: a radio that answers but has not
 * moved carries an old fix, and drawing it as current would be a guess
 * presented as a fact.
 */
private fun markerLabel(marker: MapMarker): String {
    val name = marker.name
    if (marker.isSelf) return name
    val minutes = marker.fixAgeMinutes ?: return name
    return when {
        minutes < 1 -> name
        minutes < 60 -> "$name · ${minutes}m"
        minutes < 60 * 24 -> "$name · ${minutes / 60}h"
        else -> "$name · ${minutes / (60 * 24)}d"
    }
}

/**
 * A teardrop in the pin colour, distinct from the round node discs. Drawn to the same proportions as the iOS pin
 * (`MapPinShape`), and anchored at its tip.
 */
private fun pinBitmap(context: Context): Bitmap {
    val size = MarkerSize.PIN * context.resources.displayMetrics.density
    val bitmap = createBitmap(size.toInt(), size.toInt())
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PIN_COLOR }
    val radius = size / 3f
    val centreX = size / 2f
    val centreY = radius + size / 18f
    canvas.drawCircle(centreX, centreY, radius, paint)
    val tail = android.graphics.Path().apply {
        moveTo(centreX - radius * 0.6f, radius + size / 6f)
        lineTo(centreX, size - size / 18f)
        lineTo(centreX + radius * 0.6f, radius + size / 6f)
        close()
    }
    canvas.drawPath(tail, paint)
    canvas.drawCircle(centreX, centreY, radius * 0.38f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    return bitmap
}

internal const val OPEN_FREE_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
/** Map-only colours, the same literals on iOS: your own ring, and pins. */
private const val SELF_RING = 0xFF1B73E8.toInt()

private const val PIN_IMAGE = "map-pin"
private const val PIN_COLOR = 0xFFF59E0B.toInt()

/** Typeface weight for the name pill; the same weight iOS uses. */
private const val SEMIBOLD = 600

/** The only font family the OpenFreeMap style serves glyphs for. */
private const val STYLE_FONT = "Noto Sans Regular"
