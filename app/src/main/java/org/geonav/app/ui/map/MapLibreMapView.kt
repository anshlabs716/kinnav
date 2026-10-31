package org.geonav.app.ui.map

import android.graphics.Color
import android.os.Bundle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.geonav.app.data.model.GeoPoint
import org.geonav.app.data.model.MapStyleType
import org.geonav.app.data.model.Route
import org.geonav.family.domain.PeerLocationState
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val ROUTE_SOURCE_ID = "geonav_route_source"
private const val ROUTE_LAYER_ID = "geonav_route_layer"
private const val PUCK_SOURCE_ID = "geonav_puck_source"
private const val PUCK_LAYER_ID = "geonav_puck_layer"
private const val PUCK_ACCURACY_LAYER_ID = "geonav_puck_accuracy_layer"
private const val PEOPLE_SOURCE_ID = "geonav_people_source"
private const val PEOPLE_LAYER_ID = "geonav_people_layer"
private const val PEOPLE_LABEL_LAYER_ID = "geonav_people_label_layer"

@Composable
fun MapLibreMapView(
    modifier: Modifier = Modifier,
    mapStyle: MapStyleType,
    customSatelliteUrl: String? = null,
    userLocation: GeoPoint? = null,
    isFollowingLocation: Boolean = true,
    recenterTrigger: Long = 0L,
    tilt: Double = 0.0,
    activeRoute: Route? = null,
    destinationPoint: GeoPoint? = null,
    people: List<PeerLocationState> = emptyList(),
    showPeopleLayer: Boolean = true,
    onPersonClicked: (PeerLocationState) -> Unit = {},
    onMapClicked: (GeoPoint) -> Unit = {},
    onUserGesture: () -> Unit = {},
    onCameraHeadingChanged: (Float) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }
    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var currentStyleObject by remember { mutableStateOf<Style?>(null) }

    // Map lifecycle
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(Bundle())
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Load or switch map style
    val styleUri = remember(mapStyle, customSatelliteUrl) {
        mapStyle.getStyleUri(customSatelliteUrl)
    }

    LaunchedEffect(styleUri) {
        mapInstance?.setStyle(Style.Builder().fromUri(styleUri)) { style ->
            currentStyleObject = style
            updateRouteLayer(style, activeRoute)
            updateLocationPuck(style, userLocation)
            updatePeopleLayer(style, if (showPeopleLayer) people else emptyList())
        }
    }

    // Update the People layer (contacts sharing their position) when the list changes.
    LaunchedEffect(people, showPeopleLayer, currentStyleObject) {
        currentStyleObject?.let { style ->
            updatePeopleLayer(style, if (showPeopleLayer) people else emptyList())
        }
    }

    // Camera follow user location
    LaunchedEffect(userLocation, isFollowingLocation) {
        if (isFollowingLocation && userLocation != null) {
            mapInstance?.let { map ->
                val currentZoom = map.cameraPosition.zoom.coerceAtLeast(14.0)
                val target = LatLng(userLocation.latitude, userLocation.longitude)
                val cameraPosition = CameraPosition.Builder()
                    .target(target)
                    .zoom(currentZoom)
                    .tilt(tilt)
                    .build()
                map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), 750)
            }
        }
    }

    // Explicit Recenter button trigger
    LaunchedEffect(recenterTrigger) {
        if (recenterTrigger > 0L && userLocation != null) {
            mapInstance?.let { map ->
                val target = LatLng(userLocation.latitude, userLocation.longitude)
                val cameraPosition = CameraPosition.Builder()
                    .target(target)
                    .zoom(16.0)
                    .tilt(tilt)
                    .bearing(0.0) // Reset to North
                    .build()
                map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), 600)
            }
        }
    }

    // Perspective / 3D tilt update
    LaunchedEffect(tilt) {
        mapInstance?.let { map ->
            val pos = CameraPosition.Builder(map.cameraPosition)
                .tilt(tilt)
                .build()
            map.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 400)
        }
    }

    // Update location puck on the map
    LaunchedEffect(userLocation, currentStyleObject) {
        currentStyleObject?.let { style ->
            updateLocationPuck(style, userLocation)
        }
    }

    // Update route polyline on the map
    LaunchedEffect(activeRoute, currentStyleObject) {
        currentStyleObject?.let { style ->
            updateRouteLayer(style, activeRoute)
            // Fit route in camera overview if activeRoute just arrived
            if (activeRoute != null && activeRoute.points.size >= 2) {
                mapInstance?.let { map ->
                    val minLat = activeRoute.points.minOf { it.latitude }
                    val maxLat = activeRoute.points.maxOf { it.latitude }
                    val minLon = activeRoute.points.minOf { it.longitude }
                    val maxLon = activeRoute.points.maxOf { it.longitude }
                    val centerLat = (minLat + maxLat) / 2.0
                    val centerLon = (minLon + maxLon) / 2.0
                    val pos = CameraPosition.Builder()
                        .target(LatLng(centerLat, centerLon))
                        .zoom(13.0)
                        .tilt(tilt)
                        .build()
                    map.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 900)
                }
            }
        }
    }

    AndroidView(
        factory = {
            mapView.apply {
                getMapAsync { map ->
                    mapInstance = map

                    // CRITICAL: Ensure full gestures without restriction
                    map.uiSettings.apply {
                        isZoomGesturesEnabled = true
                        isScrollGesturesEnabled = true
                        isRotateGesturesEnabled = true
                        isTiltGesturesEnabled = true
                        isDoubleTapGesturesEnabled = true
                        isQuickZoomGesturesEnabled = true
                        isCompassEnabled = false // Custom M3 compass FAB used
                        isAttributionEnabled = true
                        isLogoEnabled = false
                    }

                    // Camera listeners
                    map.addOnCameraMoveStartedListener { reason ->
                        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                            onUserGesture()
                        }
                    }

                    map.addOnCameraMoveListener {
                        onCameraHeadingChanged(map.cameraPosition.bearing.toFloat())
                    }

                    map.addOnMapClickListener { latLng ->
                        // Prefer a tapped person marker over an empty-map long-press target.
                        val tappedPerson = queryPersonAt(
                            map,
                            latLng,
                            if (showPeopleLayer) people else emptyList()
                        )
                        if (tappedPerson != null) {
                            onPersonClicked(tappedPerson)
                        } else {
                            onMapClicked(GeoPoint(latLng.latitude, latLng.longitude))
                        }
                        true
                    }

                    // Set initial style
                    map.setStyle(Style.Builder().fromUri(styleUri)) { style ->
                        currentStyleObject = style
                        updateRouteLayer(style, activeRoute)
                        updateLocationPuck(style, userLocation)
                        updatePeopleLayer(style, if (showPeopleLayer) people else emptyList())
                    }

                    // Initial camera center
                    val initialCenter = userLocation ?: GeoPoint(37.7749, -122.4194)
                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(initialCenter.latitude, initialCenter.longitude))
                        .zoom(if (userLocation != null) 15.0 else 12.0)
                        .tilt(tilt)
                        .bearing(0.0)
                        .build()
                }
            }
        },
        modifier = modifier.fillMaxSize()
    )
}

private fun updateLocationPuck(style: Style, userLocation: GeoPoint?) {
    if (!style.isFullyLoaded) return

    val point = userLocation?.let { Point.fromLngLat(it.longitude, it.latitude) }
    val feature = point?.let { Feature.fromGeometry(it) }
    val collection = feature?.let { FeatureCollection.fromFeature(it) } ?: FeatureCollection.fromFeatures(emptyList())

    val existingSource = style.getSourceAs<GeoJsonSource>(PUCK_SOURCE_ID)
    if (existingSource != null) {
        existingSource.setGeoJson(collection)
    } else {
        val newSource = GeoJsonSource(PUCK_SOURCE_ID, collection)
        style.addSource(newSource)

        // Outer accuracy pulse circle
        val accuracyLayer = CircleLayer(PUCK_ACCURACY_LAYER_ID, PUCK_SOURCE_ID).apply {
            setProperties(
                circleRadius(16f),
                circleColor(Color.argb(50, 0, 150, 136)),
                circleStrokeColor(Color.argb(120, 0, 150, 136)),
                circleStrokeWidth(1.5f)
            )
        }
        style.addLayer(accuracyLayer)

        // Inner puck solid dot
        val puckLayer = CircleLayer(PUCK_LAYER_ID, PUCK_SOURCE_ID).apply {
            setProperties(
                circleRadius(8.5f),
                circleColor(Color.rgb(0, 137, 123)),
                circleStrokeColor(Color.WHITE),
                circleStrokeWidth(2.5f)
            )
        }
        style.addLayer(puckLayer)
    }
}

private fun updateRouteLayer(style: Style, activeRoute: Route?) {
    if (!style.isFullyLoaded) return

    val coordinates = activeRoute?.points?.map { Point.fromLngLat(it.longitude, it.latitude) } ?: emptyList()
    val lineString = if (coordinates.size >= 2) LineString.fromLngLats(coordinates) else null
    val feature = lineString?.let { Feature.fromGeometry(it) }
    val collection = feature?.let { FeatureCollection.fromFeature(it) } ?: FeatureCollection.fromFeatures(emptyList())

    val existingSource = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
    if (existingSource != null) {
        existingSource.setGeoJson(collection)
    } else {
        val newSource = GeoJsonSource(ROUTE_SOURCE_ID, collection)
        style.addSource(newSource)

        val lineLayer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).apply {
            setProperties(
                lineWidth(6.5f),
                lineColor(Color.rgb(0, 150, 136)),
                lineCap("round"),
                lineJoin("round")
            )
        }
        style.addLayer(lineLayer)
    }
}

// ---------------------------------------------------------------------------- People layer

private const val PROP_NAME = "geonav_person_name"
private const val PROP_COLOR = "geonav_person_color"
private const val PROP_OPACITY = "geonav_person_opacity"
private const val HIT_SLOP_PX = 48f

/**
 * Draws the People layer: every contact who is currently sharing their position.
 *
 * Each person is a dot tinted with their configured colour, ringed white so it reads over
 * any map style, with their name above it. Stale sightings are dimmed rather than hidden,
 * so "someone was here recently" is still visible without implying they are live now.
 */
private fun updatePeopleLayer(style: Style, people: List<PeerLocationState>) {
    if (!style.isFullyLoaded) return

    val features = people.map { peer ->
        val point = Point.fromLngLat(peer.longitude, peer.latitude)
        Feature.fromGeometry(point).apply {
            // Properties drive the label text and the per-person colour/opacity.
            addStringProperty(PROP_NAME, peer.displayName)
            addNumberProperty(PROP_COLOR, peer.colorArgb.toFloat())
            addNumberProperty(PROP_OPACITY, if (peer.isStale()) 0.45f else 1.0f)
        }
    }
    val collection = FeatureCollection.fromFeatures(features.toTypedArray())

    val existingSource = style.getSourceAs<GeoJsonSource>(PEOPLE_SOURCE_ID)
    if (existingSource != null) {
        existingSource.setGeoJson(collection)
        return
    }

    style.addSource(GeoJsonSource(PEOPLE_SOURCE_ID, collection))

    // Per-person fill colour: the feature property holds an ARGB int, so a data-driven
    // get() is all that's needed — no expression gymnastics.
    val personColor = Expression.get(PROP_COLOR)

    val dotLayer = CircleLayer(PEOPLE_LAYER_ID, PEOPLE_SOURCE_ID).apply {
        setProperties(
            circleRadius(9f),
            circleColor(personColor),
            circleStrokeColor(Color.WHITE),
            circleStrokeWidth(2.5f),
            // Stale sightings are dimmed instead of hidden.
            circleOpacity(Expression.get(PROP_OPACITY)),
        )
    }
    style.addLayer(dotLayer)

    val labelLayer = SymbolLayer(PEOPLE_LABEL_LAYER_ID, PEOPLE_SOURCE_ID).apply {
        setProperties(
            textField(Expression.get(PROP_NAME)),
            textSize(12f),
            textAnchor("bottom"),
            textOffset(arrayOf(0f, 1.4f)),
            textColor(Color.rgb(33, 37, 41)),
            textHaloColor(Color.WHITE),
            textHaloWidth(1.5f),
        )
    }
    style.addLayer(labelLayer)
}

/** Finds a person whose dot was within a few pixels of the tap, if any. */
private fun queryPersonAt(
    map: MapLibreMap,
    latLng: LatLng,
    people: List<PeerLocationState>,
): PeerLocationState? {
    if (people.isEmpty()) return null
    val screenPoint = map.projection?.toScreenLocation(latLng) ?: return null
    val box = android.graphics.RectF(
        screenPoint.x - HIT_SLOP_PX,
        screenPoint.y - HIT_SLOP_PX,
        screenPoint.x + HIT_SLOP_PX,
        screenPoint.y + HIT_SLOP_PX,
    )
    val hits = runCatching {
        map.queryRenderedFeatures(box, PEOPLE_LAYER_ID)
    }.getOrNull() ?: return null
    if (hits.isEmpty()) return null
    val name = hits.first().getStringProperty(PROP_NAME) ?: return null
    return people.firstOrNull { it.displayName == name }
}
