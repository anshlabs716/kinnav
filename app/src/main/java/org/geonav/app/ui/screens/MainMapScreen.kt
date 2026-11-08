package org.geonav.app.ui.screens

import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.geonav.app.GeoNavApplication
import org.geonav.app.data.model.GeoPoint
import org.geonav.app.data.model.MapStyleType
import org.geonav.app.data.model.Place
import org.geonav.app.data.model.Route
import org.geonav.app.data.model.RouteStep
import org.geonav.app.data.model.SearchResult
import org.geonav.app.data.model.TransportMode
import org.geonav.app.domain.engine.GuidanceState
import org.geonav.app.domain.engine.TurnByTurnEngine
import org.geonav.app.ui.components.FloatingSearchBar
import org.geonav.app.ui.components.ManeuverCard
import org.geonav.app.ui.components.MapControlsBar
import org.geonav.app.ui.components.NavigationBottomBar
import org.geonav.app.ui.components.PlaceDetailSheet
import org.geonav.app.ui.components.PositioningStatusBadge
import org.geonav.app.ui.components.RoutePreviewSheet
import org.geonav.app.ui.map.MapLibreMapView
import org.geonav.family.Family
import org.geonav.family.domain.PeerLocationState
import org.geonav.app.ui.components.PersonSheet
import java.util.Locale

enum class NavigationUiState {
    IDLE,
    PLACE_SELECTED,
    ROUTE_PREVIEW,
    NAVIGATING
}

@Composable
fun MainMapScreen(
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = GeoNavApplication.instance
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // State flows from repositories
    val fusionState by app.positioningRepository.fusionState.collectAsState()
    val currentMapStyle by app.settingsRepository.currentStyle.collectAsState()
    val satelliteProviderUrl by app.settingsRepository.satelliteProviderUrl.collectAsState()
    val speedUnit by app.settingsRepository.speedUnit.collectAsState()
    val autoZoomEnabled by app.settingsRepository.autoZoom.collectAsState()
    val savedPlaces by app.placesRepository.allSavedPlaces.collectAsState(initial = emptyList())
    val recentSearches by app.searchRepository.recentSearches.collectAsState(initial = emptyList())

    // People layer: contacts currently sharing their position (from the :family module).
    // With sharing off and no circles, this is always empty and the app is a plain map.
    val familyRepo = remember { Family.repository }
    val peers by (familyRepo?.peers ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList<PeerLocationState>())).collectAsState()
    var showPeopleLayer by remember { mutableStateOf(true) }
    var selectedPeer by remember { mutableStateOf<PeerLocationState?>(null) }

    // Camera and map state
    var cameraTilt by remember { mutableDoubleStateOf(0.0) }
    var cameraBearing by remember { mutableFloatStateOf(0.0f) }
    var isFollowingLocation by remember { mutableStateOf(true) }
    var recenterCounter by remember { mutableLongStateOf(0L) }

    // Navigation and UI workflow states
    var uiState by remember { mutableStateOf(NavigationUiState.IDLE) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var selectedPlace by remember { mutableStateOf<Place?>(null) }
    var availableRoutes by remember { mutableStateOf<List<Route>>(emptyList()) }
    var selectedRoute by remember { mutableStateOf<Route?>(null) }
    var selectedMode by remember { mutableStateOf(TransportMode.DRIVE) }

    // Turn-by-turn guidance: the engine consumes real GPS fixes and advances the
    // maneuver index itself; `guidance` mirrors its output for the UI.
    val guidanceEngine = remember { TurnByTurnEngine() }
    var guidance by remember { mutableStateOf<GuidanceState?>(null) }
    var rerouting by remember { mutableStateOf(false) }
    var lastRerouteMs by remember { mutableLongStateOf(0L) }
    var announcedArrival by remember { mutableStateOf(false) }
    var lastSpokenStep by remember { mutableStateOf<RouteStep?>(null) }
    val voiceGuidance by app.settingsRepository.voiceGuidance.collectAsState()

    // Voice guidance: a real Android TTS engine, created once and shut down with the
    // screen. Speech happens only through speakGuidance(), which honours the setting.
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        val engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
            }
        }
        tts = engine
        onDispose {
            engine.shutdown()
            tts = null
        }
    }

    fun speakGuidance(text: String) {
        if (!voiceGuidance) return
        val engine = tts ?: return
        runCatching {
            if (engine.isLanguageAvailable(Locale.getDefault()) >= TextToSpeech.LANG_AVAILABLE) {
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kinnav-guidance")
            }
        }
    }

    // Asynchronous search
    fun performSearch(query: String) {
        searchQuery = query
        if (query.isNotBlank()) {
            coroutineScope.launch {
                searchResults = app.searchRepository.search(query, fusionState.location)
            }
        } else {
            searchResults = emptyList()
        }
    }

    // Direct toggle layers in sequence
    fun cycleMapLayer() {
        val nextStyle = when (currentMapStyle) {
            MapStyleType.STANDARD -> MapStyleType.DARK
            MapStyleType.DARK -> MapStyleType.TERRAIN
            MapStyleType.TERRAIN -> MapStyleType.CYCLING
            MapStyleType.CYCLING -> MapStyleType.SATELLITE
            MapStyleType.SATELLITE -> MapStyleType.STANDARD
            else -> MapStyleType.STANDARD
        }
        app.settingsRepository.setMapStyle(nextStyle)

        if (nextStyle.isSatelliteRelated && satelliteProviderUrl.isBlank()) {
            Toast.makeText(context, "Satellite: Provider not configured (Using standard fallback)", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Map Layer: ${nextStyle.title}", Toast.LENGTH_SHORT).show()
        }
    }

    // Turn-by-turn: every real fix feeds the engine, which advances maneuvers, detects
    // arrival and flags deviation. Speech, rerouting and arrival handling all hang off
    // this single source of truth so UI and voice stay in lockstep with actual position.
    LaunchedEffect(uiState, selectedRoute?.id, fusionState.location) {
        if (uiState != NavigationUiState.NAVIGATING) return@LaunchedEffect
        val route = selectedRoute ?: return@LaunchedEffect
        val location = fusionState.location ?: return@LaunchedEffect
        val state = guidanceEngine.updateProgress(location) ?: return@LaunchedEffect
        guidance = state

        if (state.isArrived && !announcedArrival) {
            announcedArrival = true
            speakGuidance("You have arrived.")
            // Teardown in a coroutine so a cancellation mid-snackbar cannot strand the
            // navigation state half-torn-down.
            coroutineScope.launch {
                guidanceEngine.stopRoute()
                guidance = null
                tts?.stop()
                cameraTilt = 0.0
                uiState = NavigationUiState.IDLE
                selectedRoute = null
                snackbarHostState.showSnackbar("You have arrived")
            }
            return@LaunchedEffect
        }

        // Speak only when the maneuver actually changes (the engine advances at 25 m).
        if (state.currentStep !== lastSpokenStep) {
            lastSpokenStep = state.currentStep
            speakGuidance(state.currentStep.instruction)
        }

        // Deviation: recalculate with a cooldown so a single bad fix cannot loop, and
        // keep the reroute off this effect so a fix arriving mid-request cannot strand
        // the in-flight flag.
        val now = System.currentTimeMillis()
        if (state.isOffRoute && !rerouting && now - lastRerouteMs > 15_000L) {
            rerouting = true
            lastRerouteMs = now
            val destination = route.steps.lastOrNull()?.location ?: route.points.lastOrNull()
            coroutineScope.launch {
                try {
                    if (destination == null) {
                        snackbarHostState.showSnackbar("Off route — no destination to recalculate to")
                        return@launch
                    }
                    val reroutes = app.routingRepository.calculateRoutes(
                        origin = location,
                        destination = destination,
                        mode = route.mode
                    )
                    val newRoute = reroutes.firstOrNull()
                    if (newRoute != null) {
                        selectedRoute = newRoute
                        guidanceEngine.startRoute(newRoute)
                        guidance = null
                        lastSpokenStep = null
                        snackbarHostState.showSnackbar("Route recalculated")
                    } else {
                        snackbarHostState.showSnackbar("Off route — couldn't recalculate")
                    }
                } finally {
                    rerouting = false
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("main_map_screen")
    ) {
        // MapLibre View
        MapLibreMapView(
            mapStyle = currentMapStyle,
            customSatelliteUrl = satelliteProviderUrl,
            userLocation = fusionState.location,
            speedKmh = fusionState.speedKmh,
            autoZoomBySpeed = autoZoomEnabled,
            isFollowingLocation = isFollowingLocation,
            recenterTrigger = recenterCounter,
            tilt = cameraTilt,
            activeRoute = selectedRoute,
            destinationPoint = selectedPlace?.location,
            people = if (showPeopleLayer) peers else emptyList(),
            showPeopleLayer = showPeopleLayer,
            onPersonClicked = { peer ->
                selectedPeer = peer
            },
            onMapClicked = {
                if (uiState == NavigationUiState.PLACE_SELECTED) {
                    uiState = NavigationUiState.IDLE
                    selectedPlace = null
                }
            },
            onUserGesture = {
                // When user pans or pinches map, stop yanking camera back
                isFollowingLocation = false
            },
            onCameraHeadingChanged = { heading ->
                cameraBearing = heading
            }
        )

        // Top Search Bar (visible when not actively navigating)
        if (uiState != NavigationUiState.NAVIGATING) {
            FloatingSearchBar(
                query = searchQuery,
                onQueryChange = { performSearch(it) },
                searchResults = searchResults,
                onPlaceSelected = { place ->
                    // A chosen result is the committed search — record it (move-to-top)
                    // so the recent list reflects what the user actually looked up.
                    if (searchQuery.isNotBlank()) {
                        coroutineScope.launch { app.searchRepository.saveSearchQuery(searchQuery) }
                    }
                    selectedPlace = place
                    searchQuery = ""
                    searchResults = emptyList()
                    uiState = NavigationUiState.PLACE_SELECTED
                },
                onMenuClicked = onOpenDrawer,
                onCategoryFilter = { cat ->
                    performSearch(cat)
                },
                recentSearches = recentSearches.map { it.query },
                onRecentSearch = { performSearch(it) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
            )
        }

        // Active Navigation Turn Maneuver Top Card
        if (uiState == NavigationUiState.NAVIGATING && selectedRoute != null) {
            val g = guidance
            val step = g?.currentStep ?: selectedRoute!!.steps.firstOrNull()
            if (step != null) {
                val dist = g?.distanceToNextManeuverMeters
                    ?: fusionState.location?.distanceTo(step.location)
                    ?: step.distanceMeters
                ManeuverCard(
                    currentStep = step,
                    distanceMeters = dist,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                )
            }
        }

        // Right-Side Map Controls Bar
        MapControlsBar(
            compassHeading = cameraBearing,
            is3D = cameraTilt > 10.0,
            isFollowingLocation = isFollowingLocation,
            showPeopleLayer = showPeopleLayer,
            onCompassClick = {
                recenterCounter++
            },
            on3DToggle = {
                cameraTilt = if (cameraTilt > 10.0) 0.0 else 55.0
            },
            onLayersClick = {
                cycleMapLayer()
            },
            onPeopleToggle = {
                showPeopleLayer = !showPeopleLayer
            },
            onRecenterClick = {
                if (fusionState.location == null) {
                    app.positioningRepository.startPositioning()
                    Toast.makeText(context, "Acquiring GPS fix...", Toast.LENGTH_SHORT).show()
                } else {
                    recenterCounter++
                    isFollowingLocation = true
                }
            },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
        )

        // Bottom-Left Positioning Status Badge
        if (uiState != NavigationUiState.NAVIGATING) {
            PositioningStatusBadge(
                state = fusionState,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 24.dp)
            )
        }

        // Person Sheet (a contact tapped on the map)
        AnimatedVisibility(
            visible = selectedPeer != null,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            selectedPeer?.let { peer ->
                PersonSheet(
                    peer = peer,
                    onNavigateHere = { target ->
                        val origin = fusionState.location ?: GeoPoint(37.7749, -122.4194)
                        coroutineScope.launch {
                            val routes = app.routingRepository.calculateRoutes(
                                origin = origin,
                                destination = GeoPoint(target.latitude, target.longitude),
                                mode = selectedMode
                            )
                            availableRoutes = routes
                            selectedRoute = routes.firstOrNull()
                            uiState = if (routes.isNotEmpty()) {
                                NavigationUiState.ROUTE_PREVIEW
                            } else {
                                Toast.makeText(context, "No route found", Toast.LENGTH_SHORT).show()
                                NavigationUiState.IDLE
                            }
                        }
                        selectedPeer = null
                    },
                    onDismiss = { selectedPeer = null }
                )
            }
        }

        // Place Detail Sheet
        AnimatedVisibility(
            visible = uiState == NavigationUiState.PLACE_SELECTED && selectedPlace != null,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            selectedPlace?.let { place ->
                val isSaved = savedPlaces.any { it.id == place.id }
                PlaceDetailSheet(
                    place = place,
                    isSaved = isSaved,
                    onGetDirections = { targetPlace ->
                        val origin = fusionState.location ?: GeoPoint(37.7749, -122.4194)
                        coroutineScope.launch {
                            val routes = app.routingRepository.calculateRoutes(
                                origin = origin,
                                destination = targetPlace.location,
                                mode = selectedMode
                            )
                            availableRoutes = routes
                            selectedRoute = routes.firstOrNull()
                            uiState = NavigationUiState.ROUTE_PREVIEW
                        }
                    },
                    onToggleSave = { targetPlace ->
                        coroutineScope.launch {
                            if (isSaved) {
                                app.placesRepository.removePlace(targetPlace.id)
                            } else {
                                app.placesRepository.savePlace(targetPlace)
                            }
                        }
                    },
                    onSelectEntrance = { entrance ->
                        val origin = fusionState.location ?: GeoPoint(37.7749, -122.4194)
                        coroutineScope.launch {
                            val routes = app.routingRepository.calculateRoutes(
                                origin = origin,
                                destination = entrance.location,
                                mode = selectedMode
                            )
                            availableRoutes = routes
                            selectedRoute = routes.firstOrNull()
                            uiState = NavigationUiState.ROUTE_PREVIEW
                        }
                    },
                    onDismiss = {
                        uiState = NavigationUiState.IDLE
                        selectedPlace = null
                    }
                )
            }
        }

        // Route Preview Sheet
        AnimatedVisibility(
            visible = uiState == NavigationUiState.ROUTE_PREVIEW && selectedRoute != null,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            RoutePreviewSheet(
                routes = availableRoutes,
                selectedRoute = selectedRoute,
                selectedMode = selectedMode,
                onModeSelected = { mode ->
                    selectedMode = mode
                    selectedPlace?.let { place ->
                        val origin = fusionState.location ?: GeoPoint(37.7749, -122.4194)
                        coroutineScope.launch {
                            val routes = app.routingRepository.calculateRoutes(
                                origin = origin,
                                destination = place.location,
                                mode = mode
                            )
                            availableRoutes = routes
                            selectedRoute = routes.firstOrNull()
                        }
                    }
                },
                onRouteSelected = { route ->
                    selectedRoute = route
                },
                onStartNavigation = { route ->
                    guidanceEngine.startRoute(route)
                    guidance = null
                    announcedArrival = false
                    lastSpokenStep = null
                    cameraTilt = 55.0 // Switch to 3D perspective for driving
                    isFollowingLocation = true
                    uiState = NavigationUiState.NAVIGATING
                },
                onDismiss = {
                    guidanceEngine.stopRoute()
                    guidance = null
                    uiState = NavigationUiState.IDLE
                    selectedRoute = null
                }
            )
        }

        // Active Navigation Bottom Bar
        AnimatedVisibility(
            visible = uiState == NavigationUiState.NAVIGATING && selectedRoute != null,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val route = selectedRoute!!
            NavigationBottomBar(
                remainingSeconds = guidance?.remainingDurationSeconds ?: route.durationSeconds,
                remainingDistanceMeters = guidance?.remainingDistanceMeters ?: route.distanceMeters,
                currentSpeedKmh = fusionState.speedKmh,
                speedUnit = speedUnit,
                speedLimitKmh = fusionState.speedLimitKmh,
                onStopNavigation = {
                    guidanceEngine.stopRoute()
                    guidance = null
                    tts?.stop()
                    cameraTilt = 0.0
                    uiState = NavigationUiState.IDLE
                    selectedRoute = null
                }
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
