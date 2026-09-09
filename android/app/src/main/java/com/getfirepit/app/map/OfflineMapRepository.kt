package com.getfirepit.app.map

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/**
 * Downloaded map areas, so the map still works with no network.
 *
 * This is the point of choosing MapLibre: the platform map SDKs cannot
 * pre-cache an arbitrary region, which makes them useless exactly when Firepit
 * matters. Downloads are capped because OpenFreeMap is donation-funded and one
 * careless region can pull gigabytes.
 */
/** A downloaded map region. */
data class OfflineArea(
    val id: Long,
    val name: String,
    /** Epoch millis of the last completed download, or 0 when unknown. */
    val downloadedAt: Long,
    val tiles: Long,
    /** Null only if MapLibre reports a region shape we do not draw. */
    val bounds: LatLngBounds?,
) {
    val sizeLabel: String get() = TileEstimate.describe(tiles)
}

@Singleton
class OfflineMapRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    // MapLibre must be initialised before any of its file-backed APIs are
    // touched. The offline list runs before a MapView exists, so this cannot
    // rely on the map screen having been opened first.
    private val manager: OfflineManager by lazy {
        MapLibre.getInstance(context)
        OfflineManager.getInstance(context)
    }

    suspend fun areas(): List<OfflineArea> = suspendCancellableCoroutine { continuation ->
        manager.listOfflineRegions(
            object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    continuation.resume(offlineRegions.orEmpty().map { it.toArea() })
                }

                override fun onError(error: String) {
                    Log.w(TAG, "could not list offline areas: $error")
                    continuation.resume(emptyList())
                }
            },
        )
    }

    /**
     * Starts a download and reports progress until it finishes.
     *
     * [onProgress] receives 0..1. The estimated tile count is checked first so
     * an over-large area is refused before any bytes are fetched.
     */
    suspend fun download(
        name: String,
        bounds: LatLngBounds,
        styleUrl: String,
        minZoom: Double = MIN_ZOOM,
        maxZoom: Double = MAX_ZOOM,
        onProgress: (Float) -> Unit,
    ): Result<Unit> = suspendCancellableCoroutine { continuation ->
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            bounds,
            minZoom,
            maxZoom,
            context.resources.displayMetrics.density,
        )
        val metadata = metadataFor(name)

        manager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    offlineRegion.setObserver(
                        downloadObserver(offlineRegion, continuation, onProgress) {},
                    )
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    continuation.invokeOnCancellation {
                        offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    }
                }

                override fun onError(error: String) {
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(IllegalStateException(error)))
                    }
                }
            },
        )
    }

    suspend fun delete(area: OfflineArea): Result<Unit> = suspendCancellableCoroutine { continuation ->
        manager.listOfflineRegions(
            object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    val region = offlineRegions.orEmpty().firstOrNull { it.id == area.id }
                        ?: return continuation.resume(Result.success(Unit))

                    region.delete(
                        object : OfflineRegion.OfflineRegionDeleteCallback {
                            override fun onDelete() = continuation.resume(Result.success(Unit))

                            override fun onError(error: String) =
                                continuation.resume(Result.failure(IllegalStateException(error)))
                        },
                    )
                }

                override fun onError(error: String) {
                    continuation.resume(Result.failure(IllegalStateException(error)))
                }
            },
        )
    }

    private fun OfflineRegion.toArea(): OfflineArea {
        val metadata = runCatching { JSONObject(String(metadata)) }.getOrNull()
        val bounds = (definition as? OfflineTilePyramidRegionDefinition)?.bounds
        return OfflineArea(
            id = id,
            name = metadata?.optString(KEY_NAME)?.takeIf { it.isNotBlank() } ?: "Saved area",
            downloadedAt = metadata?.optLong(KEY_DOWNLOADED_AT) ?: 0L,
            tiles = bounds?.let {
                TileEstimate.tileCount(
                    north = it.latitudeNorth,
                    south = it.latitudeSouth,
                    east = it.longitudeEast,
                    west = it.longitudeWest,
                    minZoom = MIN_ZOOM.toInt(),
                    maxZoom = MAX_ZOOM.toInt(),
                )
            } ?: 0L,
            bounds = bounds,
        )
    }

    /**
     * Re-runs an existing area's download so its tiles pick up map changes.
     *
     * MapLibre only fetches what is missing or stale, so this is far cheaper
     * than deleting and downloading again.
     */
    suspend fun update(area: OfflineArea, onProgress: (Float) -> Unit): Result<Unit> =
        suspendCancellableCoroutine { continuation ->
            manager.listOfflineRegions(
                object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(offlineRegions: Array<OfflineRegion>?) {
                        val region = offlineRegions.orEmpty().firstOrNull { it.id == area.id }
                            ?: return continuation.resume(
                                Result.failure(IllegalStateException("That area is no longer saved")),
                            )

                        region.setObserver(
                            downloadObserver(region, continuation, onProgress) {
                                region.updateMetadata(
                                    metadataFor(area.name),
                                    object : OfflineRegion.OfflineRegionUpdateMetadataCallback {
                                        override fun onUpdate(metadata: ByteArray) = Unit

                                        override fun onError(error: String) {
                                            Log.w(TAG, "could not stamp update time: $error")
                                        }
                                    },
                                )
                            },
                        )
                        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                        continuation.invokeOnCancellation {
                            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        }
                    }

                    override fun onError(error: String) {
                        continuation.resume(Result.failure(IllegalStateException(error)))
                    }
                },
            )
        }

    private fun metadataFor(name: String): ByteArray = JSONObject()
        .put(KEY_NAME, name)
        .put(KEY_DOWNLOADED_AT, System.currentTimeMillis())
        .toString()
        .toByteArray()

    private fun downloadObserver(
        region: OfflineRegion,
        continuation: kotlinx.coroutines.CancellableContinuation<Result<Unit>>,
        onProgress: (Float) -> Unit,
        onComplete: () -> Unit,
    ) = object : OfflineRegion.OfflineRegionObserver {
        override fun onStatusChanged(status: OfflineRegionStatus) {
            val required = status.requiredResourceCount.coerceAtLeast(1)
            onProgress((status.completedResourceCount.toFloat() / required).coerceIn(0f, 1f))
            if (status.isComplete && continuation.isActive) {
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                onComplete()
                continuation.resume(Result.success(Unit))
            }
        }

        override fun onError(error: OfflineRegionError) {
            if (continuation.isActive) {
                continuation.resume(Result.failure(IllegalStateException(error.message)))
            }
        }

        override fun mapboxTileCountLimitExceeded(limit: Long) {
            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            if (continuation.isActive) {
                continuation.resume(
                    Result.failure(IllegalStateException("That area is too large. Zoom in and try again.")),
                )
            }
        }
    }

    private companion object {
        const val TAG = "FirepitOffline"
        const val KEY_NAME = "name"
        const val KEY_DOWNLOADED_AT = "downloadedAt"

        /** Street level only. Going deeper multiplies tiles by four per level. */
        const val MIN_ZOOM = 8.0
        const val MAX_ZOOM = 15.0
    }
}
