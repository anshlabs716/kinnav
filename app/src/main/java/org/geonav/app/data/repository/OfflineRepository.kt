package org.geonav.app.data.repository

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.geonav.app.data.local.OfflineRegionEntity
import org.geonav.app.data.local.OfflineRegionsDao
import org.geonav.app.data.model.MapStyleType
import org.geonav.app.data.model.OfflineRegion
import org.geonav.app.data.model.OfflineRegionStatus
import org.geonav.app.data.offline.MapLibreOfflineBridge

sealed class DownloadResult {
    data object Success : DownloadResult()
    data class Error(val message: String) : DownloadResult()
}

/**
 * Catalogue and lifecycle of offline map regions.
 *
 * Downloads are real MapLibre offline packs (see [MapLibreOfflineBridge]): the renderer
 * reads them itself, so a region marked DOWNLOADED actually renders without network.
 * Sizes stored in the database come from the pack status, never from an estimate.
 *
 * Pack downloads are tied to the style the map currently uses (MapLibre serves a pack
 * only under the style it was built for), which is why the style URL is taken from the
 * user's live settings rather than a constant.
 */
class OfflineRepository(
    private val context: Context,
    private val dao: OfflineRegionsDao,
    private val settingsRepository: SettingsRepository,
    private val bridge: MapLibreOfflineBridge
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val regions: Flow<List<OfflineRegion>> = dao.getAllRegions().map { entities ->
        entities.map { it.toDomain() }
    }

    init {
        // Stream download progress into the database so the UI shows real percentages.
        bridge.progressListener = { regionId, percent, sizeBytes ->
            scope.launch {
                val current = dao.getAllRegions().first().firstOrNull { it.id == regionId } ?: return@launch
                if (current.status != OfflineRegionStatus.DOWNLOADING.name) return@launch
                dao.updateRegion(
                    current.copy(downloadProgressPercent = percent, sizeBytes = sizeBytes)
                )
            }
        }
    }

    /**
     * The style URL packs are downloaded under: the user's current map style, exactly
     * what [org.geonav.app.ui.map.MapLibreMapView] renders with.
     */
    private suspend fun currentStyleUrl(): String {
        val style = settingsRepository.currentStyle.value
        return style.getStyleUri(settingsRepository.satelliteProviderUrl.value)
    }

    suspend fun downloadRegion(region: OfflineRegion): DownloadResult = withContext(Dispatchers.IO) {
        try {
            dao.updateRegion(
                OfflineRegionEntity.fromDomain(
                    region.copy(status = OfflineRegionStatus.DOWNLOADING, downloadProgressPercent = 0)
                )
            )
            val sizeBytes = bridge.downloadRegion(region, currentStyleUrl())
            val styleName = settingsRepository.currentStyle.value.title
            dao.updateRegion(
                OfflineRegionEntity.fromDomain(
                    region.copy(
                        status = OfflineRegionStatus.DOWNLOADED,
                        sizeBytes = sizeBytes,
                        lastUpdatedDate = "Updated just now",
                        downloadProgressPercent = 100,
                        updateAvailable = false
                    )
                )
            )
            DownloadResult.Success.also {
                android.util.Log.i("KinNavOffline", "region ${region.name} downloaded (${sizeBytes / 1024} KiB, style $styleName)")
            }
        } catch (e: MapLibreOfflineBridge.OfflineException) {
            dao.updateRegion(OfflineRegionEntity.fromDomain(region.copy(status = OfflineRegionStatus.FAILED)))
            DownloadResult.Error(e.message ?: "Offline download failed")
        } catch (e: Exception) {
            dao.updateRegion(OfflineRegionEntity.fromDomain(region.copy(status = OfflineRegionStatus.FAILED)))
            DownloadResult.Error("Offline download failed: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    /** Kept for call sites that used the old name. */
    suspend fun updateRegion(region: OfflineRegion): DownloadResult = downloadRegion(region)

    suspend fun deleteRegion(id: String) = withContext(Dispatchers.IO) {
        runCatching { bridge.deletePack(id) }
            .onFailure { android.util.Log.w("KinNavOffline", "pack delete failed: ${it.message}") }
        dao.deleteRegion(id)
    }

    /**
     * Reconciles the database with what the renderer actually holds: a pack deleted
     * outside the app (or lost with the cache) flips its region back to Not Downloaded,
     * and a pack that exists flips its region to Downloaded with its real size.
     */
    suspend fun syncWithPacks() = withContext(Dispatchers.IO) {
        val packs = runCatching { bridge.listPacks() }.getOrElse { return@withContext }
        val stored = dao.getAllRegions().first()
        for (entity in stored) {
            val pack = packs[entity.id]
            val target = when {
                pack != null && entity.status != OfflineRegionStatus.DOWNLOADING.name ->
                    entity.copy(status = OfflineRegionStatus.DOWNLOADED.name, sizeBytes = pack.sizeBytes)
                pack == null && entity.status == OfflineRegionStatus.DOWNLOADED.name ->
                    entity.copy(status = OfflineRegionStatus.NOT_DOWNLOADED.name, sizeBytes = 0L)
                else -> null
            } ?: continue
            dao.updateRegion(target)
        }
    }

    suspend fun addRegion(region: OfflineRegion) {
        dao.insertRegion(OfflineRegionEntity.fromDomain(region))
    }
}
