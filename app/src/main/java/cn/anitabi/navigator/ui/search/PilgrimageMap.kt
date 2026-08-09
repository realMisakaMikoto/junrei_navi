package cn.anitabi.navigator.ui.search

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.ui.map.AmapDisplayCoordinate
import cn.anitabi.navigator.ui.map.AmapMapView
import cn.anitabi.navigator.ui.map.NavigationMapView
import cn.anitabi.navigator.ui.map.OfficialAmapCoordinateConverter
import cn.anitabi.navigator.ui.map.isAmapMapCreationReady
import cn.anitabi.navigator.ui.map.pilgrimageMarkerOptions
import cn.anitabi.navigator.ui.map.withPositiveMapViewport
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory as AmapCameraUpdateFactory
import com.amap.api.maps.model.BitmapDescriptorFactory as AmapBitmapDescriptorFactory
import com.amap.api.maps.model.CircleOptions as AmapCircleOptions
import com.amap.api.maps.model.LatLngBounds as AmapLatLngBounds
import com.amap.api.maps.model.MarkerOptions as AmapMarkerOptions
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds

private const val SELECTED_HALO_RADIUS_METERS = 28.0

@Composable
fun PilgrimageMap(
    contentKey: String,
    points: List<PilgrimagePoint>,
    selectedPointIds: Set<String>,
    onPointToggle: (String) -> Unit,
    onVisibleBoundsChanged: (GeoBounds) -> Unit,
    onMapUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
    provider: MapProvider = MapProvider.GOOGLE,
    amapRegionDataReady: Boolean = false,
) {
    when (provider) {
        MapProvider.GOOGLE -> GooglePilgrimageMap(
            contentKey = contentKey,
            points = points,
            selectedPointIds = selectedPointIds,
            onPointToggle = onPointToggle,
            onVisibleBoundsChanged = onVisibleBoundsChanged,
            onMapUnavailable = onMapUnavailable,
            modifier = modifier,
        )
        MapProvider.AMAP -> AmapPilgrimageMap(
            contentKey = contentKey,
            points = points,
            selectedPointIds = selectedPointIds,
            onPointToggle = onPointToggle,
            onVisibleBoundsChanged = onVisibleBoundsChanged,
            onMapUnavailable = onMapUnavailable,
            modifier = modifier,
            regionDataReady = amapRegionDataReady,
        )
    }
}

@Composable
private fun GooglePilgrimageMap(
    contentKey: String,
    points: List<PilgrimagePoint>,
    selectedPointIds: Set<String>,
    onPointToggle: (String) -> Unit,
    onVisibleBoundsChanged: (GeoBounds) -> Unit,
    onMapUnavailable: () -> Unit,
    modifier: Modifier,
) {
    val currentOnPointToggle by rememberUpdatedState(onPointToggle)
    val currentOnBoundsChanged by rememberUpdatedState(onVisibleBoundsChanged)
    val currentOnMapUnavailable by rememberUpdatedState(onMapUnavailable)
    var map by remember { mutableStateOf<GoogleMap?>(null) }
    var centeredContentKey by remember { mutableStateOf<String?>(null) }
    var viewportWidth by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }

    NavigationMapView(
        modifier = modifier,
        onUnavailable = onMapUnavailable,
        onViewportSizeChanged = { width, height ->
            viewportWidth = width
            viewportHeight = height
        },
        onMapReady = { readyMap ->
            try {
                readyMap.uiSettings.isMapToolbarEnabled = false
                readyMap.setOnMarkerClickListener { marker ->
                    (marker.tag as? String)?.let(currentOnPointToggle)
                    true
                }
                readyMap.setOnCameraIdleListener {
                    try {
                        val bounds = readyMap.projection.visibleRegion.latLngBounds
                        currentOnBoundsChanged(
                            GeoBounds(
                                north = bounds.northeast.latitude,
                                east = bounds.northeast.longitude,
                                south = bounds.southwest.latitude,
                                west = bounds.southwest.longitude,
                            ),
                        )
                    } catch (error: RuntimeException) {
                        Log.w("PilgrimageMap", "GOOGLE_BOUNDS failed (${error.javaClass.name})")
                    }
                }
                map = readyMap
            } catch (error: RuntimeException) {
                Log.w("PilgrimageMap", "GOOGLE_SETUP failed (${error.javaClass.name})")
                map = null
                currentOnMapUnavailable()
            }
        },
    )

    LaunchedEffect(points, selectedPointIds, map) {
        val readyMap = map ?: return@LaunchedEffect
        try {
            readyMap.clear()
            points.forEach { point ->
                if (point.id in selectedPointIds) {
                    readyMap.addCircle(
                        CircleOptions()
                            .center(LatLng(point.coordinate.latitude, point.coordinate.longitude))
                            .radius(SELECTED_HALO_RADIUS_METERS)
                            .fillColor(0x33C93E4F)
                            .strokeColor(0xCCC93E4F.toInt())
                            .strokeWidth(2f)
                            .clickable(false)
                            .zIndex(0.5f),
                    )
                }
                readyMap.addMarker(pilgrimageMarkerOptions(point, point.id in selectedPointIds))?.tag = point.id
            }
        } catch (error: RuntimeException) {
            Log.w("PilgrimageMap", "GOOGLE_MARKERS failed (${error.javaClass.name})")
            map = null
            currentOnMapUnavailable()
        }
    }

    LaunchedEffect(contentKey, points, map, viewportWidth, viewportHeight) {
        val readyMap = map ?: return@LaunchedEffect
        try {
            if (points.isNotEmpty() && centeredContentKey != contentKey) {
                val cameraUpdate = withPositiveMapViewport(viewportWidth, viewportHeight) { width, height ->
                    if (points.size == 1) {
                        val point = points.single().coordinate
                        CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), 15f)
                    } else {
                        val bounds = LatLngBounds.Builder().also { builder ->
                            points.forEach { point ->
                                builder.include(LatLng(point.coordinate.latitude, point.coordinate.longitude))
                            }
                        }.build()
                        CameraUpdateFactory.newLatLngBounds(bounds, width, height, 88)
                    }
                } ?: return@LaunchedEffect
                readyMap.animateCamera(cameraUpdate)
                centeredContentKey = contentKey
            }
        } catch (error: RuntimeException) {
            Log.w("PilgrimageMap", "GOOGLE_FIT failed (${error.javaClass.name})")
        }
    }
}

@Composable
private fun AmapPilgrimageMap(
    contentKey: String,
    points: List<PilgrimagePoint>,
    selectedPointIds: Set<String>,
    onPointToggle: (String) -> Unit,
    onVisibleBoundsChanged: (GeoBounds) -> Unit,
    onMapUnavailable: () -> Unit,
    modifier: Modifier,
    regionDataReady: Boolean,
) {
    val context = LocalContext.current
    val privacyReady = regionDataReady && isAmapMapCreationReady(context)
    val converter = remember(context, privacyReady) {
        if (privacyReady) OfficialAmapCoordinateConverter(context) else null
    }
    val currentOnPointToggle by rememberUpdatedState(onPointToggle)
    val currentOnBoundsChanged by rememberUpdatedState(onVisibleBoundsChanged)
    val currentOnMapUnavailable by rememberUpdatedState(onMapUnavailable)
    val currentPoints by rememberUpdatedState(points)
    val markerPointIds = remember { mutableStateMapOf<String, String>() }
    val convertedByPointId = remember { mutableStateMapOf<String, AmapDisplayCoordinate>() }
    var map by remember { mutableStateOf<AMap?>(null) }
    var centeredContentKey by remember { mutableStateOf<String?>(null) }
    var viewportWidth by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }

    AmapMapView(
        privacyReady = privacyReady,
        modifier = modifier,
        onUnavailable = onMapUnavailable,
        onViewportSizeChanged = { width, height ->
            viewportWidth = width
            viewportHeight = height
        },
        onMapReady = { readyMap ->
            try {
                readyMap.uiSettings.isZoomControlsEnabled = false
                readyMap.setOnMarkerClickListener { marker ->
                    markerPointIds[marker.id]?.let(currentOnPointToggle)
                    true
                }
                readyMap.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
                    override fun onCameraChange(position: com.amap.api.maps.model.CameraPosition?) = Unit

                    override fun onCameraChangeFinish(position: com.amap.api.maps.model.CameraPosition?) {
                        val visibleBounds = runCatching {
                            readyMap.projection.visibleRegion.latLngBounds
                        }.getOrElse { error ->
                            Log.w("PilgrimageMap", "AMAP_BOUNDS failed (${error.javaClass.name})")
                            return
                        }
                        val visibleWgs84Points = currentPoints.filter { point ->
                            convertedByPointId[point.id]?.toLatLng()?.let(visibleBounds::contains) == true
                        }
                        currentOnBoundsChanged(visibleWgs84Points.toWgs84Bounds() ?: EMPTY_VISIBLE_MAP_BOUNDS)
                    }
                })
                map = readyMap
            } catch (error: RuntimeException) {
                Log.w("PilgrimageMap", "AMAP_SETUP failed (${error.javaClass.name})")
                map = null
                currentOnMapUnavailable()
            }
        },
    )

    LaunchedEffect(points, selectedPointIds, map, converter) {
        val readyMap = map ?: return@LaunchedEffect
        val readyConverter = converter ?: return@LaunchedEffect
        try {
            readyMap.clear()
            markerPointIds.clear()
            convertedByPointId.clear()
            points.forEach { point ->
                val converted = readyConverter.convert(point.coordinate)
                convertedByPointId[point.id] = converted
                if (point.id in selectedPointIds) {
                    readyMap.addCircle(
                        AmapCircleOptions()
                            .center(converted.toLatLng())
                            .radius(SELECTED_HALO_RADIUS_METERS)
                            .fillColor(0x33C93E4F)
                            .strokeColor(0xCCC93E4F.toInt())
                            .strokeWidth(2f)
                            .zIndex(0.5f),
                    )
                }
                readyMap.addMarker(
                    AmapMarkerOptions()
                        .position(converted.toLatLng())
                        .title(point.name)
                        .icon(
                            AmapBitmapDescriptorFactory.defaultMarker(
                                if (point.id in selectedPointIds) {
                                    AmapBitmapDescriptorFactory.HUE_GREEN
                                } else {
                                    AmapBitmapDescriptorFactory.HUE_RED
                                },
                            ),
                        ),
                )?.let { marker -> markerPointIds[marker.id] = point.id }
            }
        } catch (error: RuntimeException) {
            Log.w("PilgrimageMap", "AMAP_MARKERS failed (${error.javaClass.name})")
            map = null
            currentOnMapUnavailable()
        }
    }

    LaunchedEffect(contentKey, points, map, converter, viewportWidth, viewportHeight) {
        val readyMap = map ?: return@LaunchedEffect
        val readyConverter = converter ?: return@LaunchedEffect
        if (viewportWidth <= 0 || viewportHeight <= 0) return@LaunchedEffect
        try {
            if (points.isNotEmpty() && centeredContentKey != contentKey) {
                val coordinates = points.map { point ->
                    convertedByPointId[point.id] ?: readyConverter.convert(point.coordinate)
                }
                val cameraUpdate = if (coordinates.size == 1) {
                    AmapCameraUpdateFactory.newLatLngZoom(coordinates.single().toLatLng(), 15f)
                } else {
                    val bounds = AmapLatLngBounds.Builder().also { builder ->
                        coordinates.forEach { builder.include(it.toLatLng()) }
                    }.build()
                    AmapCameraUpdateFactory.newLatLngBounds(bounds, 88)
                }
                readyMap.animateCamera(cameraUpdate)
                centeredContentKey = contentKey
            }
        } catch (error: RuntimeException) {
            Log.w("PilgrimageMap", "AMAP_FIT failed (${error.javaClass.name})")
        }
    }
}

private fun List<PilgrimagePoint>.toWgs84Bounds(): GeoBounds? {
    if (isEmpty()) return null
    return GeoBounds(
        north = maxOf { it.coordinate.latitude },
        east = maxOf { it.coordinate.longitude },
        south = minOf { it.coordinate.latitude },
        west = minOf { it.coordinate.longitude },
    )
}

internal val EMPTY_VISIBLE_MAP_BOUNDS = GeoBounds(
    north = -90.0,
    east = -180.0,
    south = 90.0,
    west = 180.0,
)
