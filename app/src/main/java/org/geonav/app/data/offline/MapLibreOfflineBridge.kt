package org.geonav.app.data.offline

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.geonav.app.data.model.OfflineRegion
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion as MapLibreOfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.coroutines.resume

/**
 * Bridge between KinNav's offline-region catalogue and MapLibre Native's offline pack
 * database — the mechanism the map renderer itself reads. A finished pack is served
 * from disk whenever the tile source is unreachable, so "Downloaded" finally means
 * "renders offline".
 *
 * Packs are created against the same style URL the map displays, because MapLibre only
 * serves a pack to requests made under the style it was downloaded with. Zoom range
 * 8–15 keeps a city-sized pack to a manageable download while covering street-level
 * navigation; the range is a deliberate constant, not a guess printed in the UI.
 *
 * Every failure surfaces as a real error string. Nothing here pretends.
 */
class MapLibreOfflineBridge(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    private val manager: OfflineManager
        get() = OfflineManager.getInstance(appContext)

    /** Live progress for the region currently downloading, if any. */
    var progressListener: ((regionId: String, percent: Int, sizeBytes: Long) -> Unit)? = null

    data class PackInfo(
        val kinnavRegionId: String,
        val sizeBytes: Long,
        val styleUrl: String
    )

    // ------------------------------------------------------------------ download

    /**
     * Downloads (or re-downloads) [region] as an offline pack for [styleUrl].
     *
     * Suspends until the pack is complete or failed; progress is streamed through
     * [progressListener] as the MapLibre observer fires. If a pack for this region
     * already exists it is deleted first, which is what "Update" means.
     *
     * @return the pack's real size in bytes.
     * @throws OfflineException with a user-presentable reason on failure.
     */
    suspend fun downloadRegion(region: OfflineRegion, styleUrl: String): Long {
        deletePack(region.id)

        val bounds = LatLngBounds.from(
            region.maxLat, region.maxLon, region.minLat, region.minLon
        )
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl, bounds, MIN_ZOOM, MAX_ZOOM, PIXEL_RATIO
        )
        val metadata = json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("kinnavRegionId", kotlinx.serialization.json.JsonPrimitive(region.id))
                put("name", kotlinx.serialization.json.JsonPrimitive(region.name))
            }
        ).toByteArray(Charsets.UTF_8)

        val region1 = suspendCancellableCoroutine { cont ->
            manager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: MapLibreOfflineRegion) {
                    if (cont.isActive) cont.resume(offlineRegion)
                }

                override fun onError(error: String) {
                    if (cont.isActive) cont.resumeWith(Result.failure(OfflineException("MapLibre refused to create the offline region: $error")))
                }
            })
        }

        return suspendCancellableCoroutine { cont ->
            region1.setObserver(object : MapLibreOfflineRegion.OfflineRegionObserver {
                override fun onStatusChanged(status: OfflineRegionStatus) {
                    val required = status.requiredResourceCount
                    val percent = if (required > 0) {
                        (status.completedResourceCount * 100L / required).toInt().coerceIn(0, 100)
                    } else 0
                    progressListener?.invoke(region.id, percent, status.completedResourceSize)
                    if (status.isComplete) {
                        region1.setDownloadState(MapLibreOfflineRegion.STATE_INACTIVE)
                        region1.setObserver(null)
                        if (cont.isActive) cont.resume(status.completedResourceSize)
                    }
                }

                override fun onError(error: OfflineRegionError) {
                    when (error.reason) {
                        // Non-fatal: MapLibre reports recoverable hiccups and keeps retrying
                        // while the region is active. Failing here would lie about the cause.
                        OfflineRegionError.REASON_SUCCESS, OfflineRegionError.REASON_CONNECTION -> Unit
                        else -> {
                            region1.setDownloadState(MapLibreOfflineRegion.STATE_INACTIVE)
                            region1.setObserver(null)
                            if (cont.isActive) {
                                cont.resumeWith(
                                    Result.failure(
                                        OfflineException("Download failed: ${error.reason} — ${error.message}")
                                    )
                                )
                            }
                        }
                    }
                }

                override fun mapboxTileCountLimitExceeded(limit: Long) {
                    // Only raised for Mapbox-hosted tile sources, which KinNav does not use.
                    onDebugLog("mapbox tile count limit hit ($limit); ignored for non-Mapbox sources")
                }
            })
            region1.setDownloadState(MapLibreOfflineRegion.STATE_ACTIVE)
            cont.invokeOnCancellation {
                region1.setDownloadState(MapLibreOfflineRegion.STATE_INACTIVE)
                region1.setObserver(null)
            }
        }
    }

    // --------------------------------------------------------------------- list

    /** All KinNav offline packs currently in the renderer's database, keyed by region id. */
    suspend fun listPacks(): Map<String, PackInfo> =
        suspendCancellableCoroutine { cont ->
            manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<MapLibreOfflineRegion>?) {
                    val regions = offlineRegions.orEmpty()
                    val map = HashMap<String, PackInfo>()
                    for (region in regions) {
                        val id = parseRegionId(region.metadata) ?: continue
                        map[id] = PackInfo(id, 0L, parseStyleUrl(region.definition))
                    }
                    // Real sizes need a status call per pack; fetch them in parallel.
                    var pending = map.size
                    if (pending == 0) {
                        if (cont.isActive) cont.resume(emptyMap())
                        return
                    }
                    for (region in regions) {
                        val id = parseRegionId(region.metadata) ?: run { pending--; continue }
                        region.getStatus(object : MapLibreOfflineRegion.OfflineRegionStatusCallback {
                            override fun onStatus(status: OfflineRegionStatus?) {
                                if (status == null) {
                                    if (--pending == 0 && cont.isActive) cont.resume(map)
                                    return
                                }
                                map[id] = map[id]?.copy(sizeBytes = status.completedResourceSize) ?: PackInfo(
                                    id, status.completedResourceSize, parseStyleUrl(region.definition)
                                )
                                if (--pending == 0 && cont.isActive) cont.resume(map)
                            }

                            override fun onError(error: String?) {
                                if (--pending == 0 && cont.isActive) cont.resume(map)
                            }
                        })
                    }
                }

                override fun onError(error: String) {
                    if (cont.isActive) cont.resumeWith(Result.failure(OfflineException("Couldn't list offline packs: $error")))
                }
            })
        }

    // -------------------------------------------------------------------- delete

    /** Deletes the pack for [regionId] if present. Returns true when it is gone. */
    suspend fun deletePack(regionId: String): Boolean =
        suspendCancellableCoroutine { cont ->
            manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<MapLibreOfflineRegion>?) {
                    val match = offlineRegions.orEmpty().firstOrNull { parseRegionId(it.metadata) == regionId }
                    if (match == null) {
                        if (cont.isActive) cont.resume(true)
                        return
                    }
                    match.delete(object : MapLibreOfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onError(error: String) {
                            if (cont.isActive) cont.resumeWith(Result.failure(OfflineException("Couldn't delete the offline pack: $error")))
                        }
                    })
                }

                override fun onError(error: String) {
                    if (cont.isActive) cont.resumeWith(Result.failure(OfflineException("Couldn't list offline packs: $error")))
                }
            })
        }

    // ------------------------------------------------------------------ helpers

    private fun parseRegionId(metadata: ByteArray?): String? {
        if (metadata == null || metadata.isEmpty()) return null
        return runCatching {
            json.parseToJsonElement(String(metadata, Charsets.UTF_8))
                .jsonObject["kinnavRegionId"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    private fun parseStyleUrl(definition: org.maplibre.android.offline.OfflineRegionDefinition): String =
        (definition as? OfflineTilePyramidRegionDefinition)?.styleURL.orEmpty()

    private fun onDebugLog(message: String) {
        android.util.Log.d("KinNavOffline", message)
    }

    class OfflineException(message: String) : Exception(message)

    companion object {
        /** Street-level offline coverage without planet-sized downloads. */
        const val MIN_ZOOM = 8.0
        const val MAX_ZOOM = 15.0

        /** 1.0 keeps packs small; retina rendering can be raised later. */
        const val PIXEL_RATIO = 1.0f
    }
}
