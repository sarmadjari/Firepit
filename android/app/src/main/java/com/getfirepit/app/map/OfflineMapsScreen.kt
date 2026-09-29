package com.getfirepit.app.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.core.designsystem.component.BackButton
import com.getfirepit.core.designsystem.component.FirepitDetailBar
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap

/**
 * Pick an area on the map and keep it for when there is no signal.
 *
 * The visible map is the selection: whatever is on screen when Download is
 * tapped is what gets saved, which avoids a corner-dragging interaction that is
 * awkward on a phone and impossible on a folded cover screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    viewModel: OfflineMapsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val myPosition by viewModel.myPosition.collectAsStateWithLifecycle()
    var bounds by remember { mutableStateOf<LatLngBounds?>(null) }
    var naming by remember { mutableStateOf(false) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    // Only the first fix moves the camera, or panning away would be undone the
    // next time the position updated.
    var framed by remember { mutableStateOf(false) }

    LaunchedEffect(map, myPosition) {
        val ready = map ?: return@LaunchedEffect
        val here = myPosition ?: return@LaunchedEffect
        if (framed) return@LaunchedEffect
        framed = true
        ready.frameAround(here)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            FirepitDetailBar(title = "Offline areas", onBack = onBack)
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(260.dp),
            ) {
                MapLibreView(
                    styleUrl = OPEN_FREE_MAP_STYLE,
                    modifier = Modifier.fillMaxSize(),
                ) { ready, _ ->
                    map = ready
                    ready.addOnCameraIdleListener {
                        bounds = ready.projection.visibleRegion.latLngBounds
                    }
                    bounds = ready.projection.visibleRegion.latLngBounds
                }
            }

            Column(
                modifier = Modifier.padding(FirepitSpacing.screenMargin),
                verticalArrangement = Arrangement.spacedBy(FirepitSpacing.s),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Offline maps only", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (state.areas.isEmpty() && state.offlineOnly) {
                                "Nothing is downloaded, so the map will be blank."
                            } else {
                                "Never fetch tiles over the network. Ground you have not " +
                                    "downloaded shows grey."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.areas.isEmpty() && state.offlineOnly) {
                                FirepitTheme.colors.warn
                            } else {
                                FirepitTheme.colors.textSecondary
                            },
                        )
                    }
                    Switch(checked = state.offlineOnly, onCheckedChange = viewModel::setOfflineOnly)
                }

                if (state.suggestOfflineOnly) {
                    Text(
                        text = "Downloaded. Turn on offline maps only, and the map stops asking the " +
                            "tile server for anything — including tiles that would show where you are.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FirepitTheme.colors.textSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.s)) {
                        Button(
                            onClick = {
                                viewModel.setOfflineOnly(true)
                                viewModel.dismissOfflineSuggestion()
                            },
                        ) { Text("Offline only") }
                        TextButton(onClick = viewModel::dismissOfflineSuggestion) { Text("Not now") }
                    }
                }

                HorizontalDivider()

                Text(
                    text = "Move the map to the area you want, then download it. " +
                        "Street level only, and large areas are refused.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FirepitTheme.colors.textSecondary,
                )

                myPosition?.let { here ->
                    TextButton(
                        onClick = { map?.frameAround(here) },
                        contentPadding = PaddingValues(0.dp),
                    ) { Text("Around me (${AROUND_ME_RADIUS_KM.toInt()} km)") }
                }

                bounds?.let { visible ->
                    val tiles = remember(visible) {
                        TileEstimate.tileCount(
                            north = visible.latitudeNorth,
                            south = visible.latitudeSouth,
                            east = visible.longitudeEast,
                            west = visible.longitudeWest,
                            minZoom = MIN_ZOOM,
                            maxZoom = MAX_ZOOM,
                        )
                    }
                    Text(
                        text = "This view is about %,d tiles, %s.".format(tiles, TileEstimate.describe(tiles)),
                        style = MaterialTheme.typography.bodyMedium,
                        // The byte figure is an estimate, so it is worded as one.
                        color = if (tiles > LARGE_AREA_TILES) {
                            FirepitTheme.colors.warn
                        } else {
                            FirepitTheme.colors.textPrimary
                        },
                    )
                }

                state.progress?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Downloading ${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                state.error?.let {
                    Text(it, color = FirepitTheme.colors.danger, style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = { naming = true },
                    enabled = bounds != null && state.progress == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Download this area") }
            }

            HorizontalDivider()

            if (state.areas.isEmpty()) {
                Text(
                    text = "No areas saved yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FirepitTheme.colors.textSecondary,
                    modifier = Modifier.padding(FirepitSpacing.screenMargin),
                )
            } else {
                LazyColumn {
                    items(state.areas, key = { it.id }) { area ->
                        ListItem(
                            headlineContent = { Text(area.name) },
                            supportingContent = {
                                Text("${area.sizeLabel} · ${downloadedLabel(area.downloadedAt)}")
                            },
                            trailingContent = {
                                Row {
                                    TextButton(
                                        onClick = { viewModel.update(area) },
                                        enabled = state.progress == null,
                                    ) { Text("Update") }
                                    TextButton(onClick = { viewModel.delete(area) }) { Text("Delete") }
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (naming) {
        NameAreaDialog(
            onDismiss = { naming = false },
            onConfirm = { name ->
                naming = false
                bounds?.let { viewModel.download(name, it, OPEN_FREE_MAP_STYLE) }
            },
        )
    }
}

@Composable
private fun NameAreaDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this area") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Campsite") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim().ifBlank { "Saved area" }) },
            ) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Points the camera at a [radiusKm] box around a point.
 *
 * The whole-world view this screen used to open on estimated tens of terabytes,
 * which is not a choice anyone was going to make. Where you are standing is.
 */
private fun MapLibreMap.frameAround(centre: LatLng, radiusKm: Double = AROUND_ME_RADIUS_KM) {
    val box = boxAround(centre.latitude, centre.longitude, radiusKm)
    moveCamera(
        CameraUpdateFactory.newLatLngBounds(
            LatLngBounds.from(box.north, box.east, box.south, box.west),
            FRAME_PADDING_PX,
        ),
    )
}

private fun downloadedLabel(epochMillis: Long): String {
    if (epochMillis <= 0) return "downloaded before this was recorded"
    val elapsed = System.currentTimeMillis() - epochMillis
    val days = TimeUnit.MILLISECONDS.toDays(elapsed)
    val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
    return when {
        hours < 1 -> "downloaded just now"
        hours < 24 -> "downloaded ${hours}h ago"
        days < 30 -> "downloaded ${days}d ago"
        else -> "downloaded ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis))}"
    }
}

/** Matches the repository's download range so the estimate is not a different question. */
private const val MIN_ZOOM = 8
private const val MAX_ZOOM = 15

/** Past this the download is worth a second look before starting. */
private const val LARGE_AREA_TILES = 20_000L

/** Roughly an hour's walk in every direction, and a few thousand tiles rather than millions. */
private const val AROUND_ME_RADIUS_KM = 20.0

private const val FRAME_PADDING_PX = 24
