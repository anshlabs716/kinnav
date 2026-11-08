package org.geonav.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.geonav.app.data.local.GeoNavDatabase
import org.geonav.app.data.offline.MapLibreOfflineBridge
import org.geonav.app.data.repository.OfflineRepository
import org.geonav.app.data.repository.PlacesRepository
import org.geonav.app.data.repository.PositioningRepository
import org.geonav.app.data.repository.RoutingRepository
import org.geonav.app.data.repository.SearchRepository
import org.geonav.app.data.repository.SettingsRepository
import org.geonav.app.data.storage.GeoNavDataExchangeManager
import org.geonav.family.Family
import org.geonav.family.FamilyHooks
import org.geonav.family.domain.ActivityState
import org.geonav.family.domain.SharedFix
import org.maplibre.android.MapLibre

class GeoNavApplication : Application() {

    lateinit var database: GeoNavDatabase
        private set

    lateinit var placesRepository: PlacesRepository
        private set

    lateinit var searchRepository: SearchRepository
        private set

    lateinit var routingRepository: RoutingRepository
        private set

    lateinit var positioningRepository: PositioningRepository
        private set

    lateinit var offlineRepository: OfflineRepository
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var dataExchangeManager: GeoNavDataExchangeManager
        private set

    private val familyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize MapLibre Native SDK
        MapLibre.getInstance(this)

        // Initialize local Room database
        database = GeoNavDatabase.getDatabase(this)

        // Initialize repositories
        settingsRepository = SettingsRepository(this)
        placesRepository = PlacesRepository(database.savedPlacesDao())
        searchRepository = SearchRepository(database.searchHistoryDao(), database.savedPlacesDao())
        routingRepository = RoutingRepository()
        positioningRepository = PositioningRepository(this) { settingsRepository.trueNorth.value }
        offlineRepository = OfflineRepository(
            this,
            database.offlineRegionsDao(),
            settingsRepository,
            MapLibreOfflineBridge(this)
        )
        dataExchangeManager = GeoNavDataExchangeManager()

        // Location sharing (the Luogo port in :family). Fully opt-in: nothing is sent
        // until the user turns sharing on from the People screen.
        Family.init(this, GeoNavFamilyHooks())

        // Feed the in-memory fix cache for item-finding helper reports even when the
        // sharing master switch is off. Collection only; transmission stays gated.
        observeFusionForSharing()
    }

    /**
     * The seam between the sharing engine and GeoNav's positioning stack.
     *
     * The family module never touches LocationManager itself; it asks for fixes through
     * this hook and receives them via [org.geonav.family.repository.FamilyRepository.submitFix].
     */
    private inner class GeoNavFamilyHooks : FamilyHooks {

        override fun startLocationUpdates() {
            positioningRepository.startPositioning()
            observeFusionForSharing()
        }

        override fun stopLocationUpdates() {
            // Intentionally does not stop positioning: the map owns the positioning
            // session for navigation and the puck. The sharing bridge keeps collecting
            // but submitFix gates on the master switch, so nothing is sent.
        }

        override fun diagnosticRows(): List<Pair<String, String>> {
            val fusion = positioningRepository.fusionState.value
            val satellites = fusion.satellitesInUse?.let { s ->
                "$s / ${fusion.totalSatellites ?: "?"}"
            } ?: "n/a"
            val tunnel = if (fusion.isTunnelMode) "on" else "off"
            return listOf(
                Pair("Positioning source", fusion.source?.displayName ?: "none"),
                Pair("Fusion confidence", fusion.confidence.label),
                Pair(
                    "Fusion accuracy",
                    fusion.accuracyMeters?.let { "%.1f m".format(it) } ?: "n/a",
                ),
                Pair("Satellites", satellites),
                Pair("Tunnel mode", tunnel),
                Pair(
                    "Available sensors",
                    fusion.availableSensors.joinToString().ifEmpty { "none" },
                ),
            )
        }
    }

    private var sharingBridgeStarted = false

    /**
     * Bridges fusion fixes into the sharing engine.
     *
     * Collects the fused position and submits it to the repository, which decides (master
     * switch, visibility radius, throttle, per-circle opt-in) whether anything is sent.
     */
    private fun observeFusionForSharing() {
        if (sharingBridgeStarted) return
        sharingBridgeStarted = true
        familyScope.launch {
            positioningRepository.fusionState.collect { state ->
                val location = state.location ?: return@collect
                if (!state.hasFix) return@collect
                val repo = Family.repository ?: return@collect
                repo.submitFix(
                    SharedFix(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        timestampMs = state.timestamp,
                        horizontalAccuracyMeters = state.accuracyMeters ?: 50f,
                        altitudeMeters = state.altitudeMeters,
                        speedMps = state.speedKmh / 3.6f,
                        bearingDegrees = state.headingDegrees,
                        sourceSummary = state.source?.displayName ?: "GNSS",
                        activityState = ActivityState.fromSpeed(state.speedKmh / 3.6f),
                        // The fusion engine flags tunnel/dead-reckoning estimates; those are
                        // not real fixes and are never shared.
                        isSuspiciousJump = state.isTunnelMode ||
                            state.confidence == org.geonav.app.data.model.FusionConfidence.LOST,
                    )
                )
            }
        }
    }

    companion object {
        lateinit var instance: GeoNavApplication
            private set
    }
}
