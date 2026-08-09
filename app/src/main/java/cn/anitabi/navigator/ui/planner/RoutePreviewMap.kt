package cn.anitabi.navigator.ui.planner

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.ui.map.AmapDisplayCoordinate
import cn.anitabi.navigator.ui.map.AmapMapView
import cn.anitabi.navigator.ui.map.NavigationMapView
import cn.anitabi.navigator.ui.map.OfficialAmapCoordinateConverter
import cn.anitabi.navigator.ui.map.amapRouteGeometryForDisplay
import cn.anitabi.navigator.ui.map.currentLocationMarkerOptions
import cn.anitabi.navigator.ui.map.isAmapMapCreationReady
import cn.anitabi.navigator.ui.map.mapContentMismatch
import cn.anitabi.navigator.ui.map.routePointMarkerOptions
import cn.anitabi.navigator.ui.map.toGoogleLatLng
import cn.anitabi.navigator.ui.map.withPositiveMapViewport
import cn.anitabi.navigator.ui.theme.Moss
import cn.anitabi.navigator.ui.theme.Vermilion
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory as AmapCameraUpdateFactory
import com.amap.api.maps.model.BitmapDescriptorFactory as AmapBitmapDescriptorFactory
import com.amap.api.maps.model.LatLngBounds as AmapLatLngBounds
import com.amap.api.maps.model.MarkerOptions as AmapMarkerOptions
import com.amap.api.maps.model.PolylineOptions as AmapPolylineOptions
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.Dot
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.PolylineOptions

@Composable
fun RoutePreviewMap(
    plan: TourPlan,
    modifier: Modifier = Modifier,
    currentLocation: GeoPoint? = null,
    followCurrentLocation: Boolean = false,
) {
    val mismatch = remember(plan) { mapContentMismatch(plan.mapProvider, plan) }
    if (mismatch != null) {
        ProviderContentRejectedPanel(plan.mapProvider, modifier)
        return
    }
    when (plan.mapProvider) {
        MapProvider.GOOGLE -> GoogleRoutePreviewMap(plan, modifier, currentLocation, followCurrentLocation)
        MapProvider.AMAP -> AmapRoutePreviewMap(plan, modifier, currentLocation, followCurrentLocation)
    }
}

@Composable
private fun GoogleRoutePreviewMap(
    plan: TourPlan,
    modifier: Modifier,
    currentLocation: GeoPoint?,
    followCurrentLocation: Boolean,
) {
    var map by remember { mutableStateOf<GoogleMap?>(null) }
    var viewportWidth by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }

    NavigationMapView(
        modifier = modifier,
        onViewportSizeChanged = { width, height ->
            viewportWidth = width
            viewportHeight = height
        },
        onUnavailable = { map = null },
        onMapReady = { readyMap ->
            readyMap.uiSettings.isMapToolbarEnabled = false
            map = readyMap
        },
    )

    LaunchedEffect(plan, currentLocation, map) {
        val readyMap = map ?: return@LaunchedEffect
        try {
            readyMap.clear()
            plan.legs.forEach { leg ->
                val geometry = leg.geometry.ifEmpty { listOf(leg.from, leg.to) }
                    .withoutConsecutiveGeoDuplicates()
                if (geometry.size >= 2) {
                    val walking = plan.mode == TravelMode.TRANSIT && leg.mode == TravelMode.WALK
                    val options = PolylineOptions()
                        .addAll(geometry.map(GeoPoint::toGoogleLatLng))
                        .color((if (walking) Moss else Vermilion).toArgb())
                        .width(if (walking) 8f else 10f)
                    if (walking) options.pattern(listOf(Dot(), Gap(14f)))
                    readyMap.addPolyline(options)
                }
            }
            plan.orderedPoints.forEach { point -> readyMap.addMarker(routePointMarkerOptions(point)) }
            currentLocation?.let { readyMap.addMarker(currentLocationMarkerOptions(it, "当前位置")) }
        } catch (error: RuntimeException) {
            Log.w("RoutePreviewMap", "GOOGLE_DRAW failed (${error.javaClass.name})")
        }
    }

    LaunchedEffect(currentLocation, followCurrentLocation, map, viewportWidth, viewportHeight) {
        val location = currentLocation ?: return@LaunchedEffect
        val readyMap = map ?: return@LaunchedEffect
        if (!followCurrentLocation) return@LaunchedEffect
        runCatching {
            withPositiveMapViewport(viewportWidth, viewportHeight) { _, _ ->
                CameraUpdateFactory.newLatLngZoom(location.toGoogleLatLng(), 16f)
            }?.let(readyMap::animateCamera)
        }.onFailure { error ->
            Log.w("RoutePreviewMap", "GOOGLE_FOLLOW failed (${error.javaClass.name})")
        }
    }

    LaunchedEffect(plan.id, plan.legs, map, viewportWidth, viewportHeight) {
        val readyMap = map ?: return@LaunchedEffect
        val coordinates = plan.legs.flatMap { it.geometry.ifEmpty { listOf(it.from, it.to) } }
            .ifEmpty { plan.orderedPoints.map { it.coordinate } }
            .withoutConsecutiveGeoDuplicates()
        runCatching {
            withPositiveMapViewport(viewportWidth, viewportHeight) { width, height ->
                when (coordinates.size) {
                    0 -> null
                    1 -> CameraUpdateFactory.newLatLngZoom(coordinates.single().toGoogleLatLng(), 15f)
                    else -> LatLngBounds.Builder().also { builder ->
                        coordinates.forEach { builder.include(it.toGoogleLatLng()) }
                    }.build().let { CameraUpdateFactory.newLatLngBounds(it, width, height, 76) }
                }
            }?.let(readyMap::animateCamera)
        }.onFailure { error ->
            Log.w("RoutePreviewMap", "GOOGLE_FIT failed (${error.javaClass.name})")
        }
    }
}

@Composable
private fun AmapRoutePreviewMap(
    plan: TourPlan,
    modifier: Modifier,
    currentLocation: GeoPoint?,
    followCurrentLocation: Boolean,
) {
    val context = LocalContext.current
    val privacyReady = isAmapMapCreationReady(context)
    val converter = remember(context, privacyReady) {
        if (privacyReady) OfficialAmapCoordinateConverter(context) else null
    }
    var map by remember { mutableStateOf<AMap?>(null) }
    var viewportWidth by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }

    AmapMapView(
        privacyReady = privacyReady,
        modifier = modifier,
        onViewportSizeChanged = { width, height ->
            viewportWidth = width
            viewportHeight = height
        },
        onUnavailable = { map = null },
        onMapReady = { readyMap ->
            readyMap.uiSettings.isZoomControlsEnabled = false
            map = readyMap
        },
    )

    LaunchedEffect(plan, currentLocation, map, converter) {
        val readyMap = map ?: return@LaunchedEffect
        val readyConverter = converter ?: return@LaunchedEffect
        runCatching {
            readyMap.clear()
            plan.legs.filter { it.geometry.isNotEmpty() }.forEach { leg ->
                val geometry = amapRouteGeometryForDisplay(leg).withoutConsecutiveAmapDuplicates()
                if (geometry.size >= 2) {
                    val walking = plan.mode == TravelMode.TRANSIT && leg.mode == TravelMode.WALK
                    readyMap.addPolyline(
                        AmapPolylineOptions()
                            .addAll(geometry.map(AmapDisplayCoordinate::toLatLng))
                            .color((if (walking) Moss else Vermilion).toArgb())
                            .width(if (walking) 8f else 10f),
                    )
                }
            }
            plan.orderedPoints.forEachIndexed { index, point ->
                val coordinate = readyConverter.convert(point.coordinate).toLatLng()
                readyMap.addMarker(
                    AmapMarkerOptions()
                        .position(coordinate)
                        .title(point.name)
                        .icon(
                            AmapBitmapDescriptorFactory.defaultMarker(
                                if (index == plan.orderedPoints.lastIndex) {
                                    AmapBitmapDescriptorFactory.HUE_GREEN
                                } else {
                                    AmapBitmapDescriptorFactory.HUE_RED
                                },
                            ),
                        ),
                )
            }
            currentLocation?.let { location ->
                readyMap.addMarker(
                    AmapMarkerOptions()
                        .position(readyConverter.convert(location).toLatLng())
                        .title("当前位置")
                        .icon(AmapBitmapDescriptorFactory.defaultMarker(AmapBitmapDescriptorFactory.HUE_AZURE)),
                )
            }
        }.onFailure { error ->
            Log.w("RoutePreviewMap", "AMAP_DRAW failed (${error.javaClass.name})")
            map = null
        }
    }

    LaunchedEffect(currentLocation, followCurrentLocation, map, converter, viewportWidth, viewportHeight) {
        val location = currentLocation ?: return@LaunchedEffect
        val readyMap = map ?: return@LaunchedEffect
        val readyConverter = converter ?: return@LaunchedEffect
        if (!followCurrentLocation || viewportWidth <= 0 || viewportHeight <= 0) return@LaunchedEffect
        runCatching {
            readyMap.animateCamera(
                AmapCameraUpdateFactory.newLatLngZoom(readyConverter.convert(location).toLatLng(), 16f),
            )
        }.onFailure { error ->
            Log.w("RoutePreviewMap", "AMAP_FOLLOW failed (${error.javaClass.name})")
        }
    }

    LaunchedEffect(plan.id, plan.legs, map, converter, viewportWidth, viewportHeight) {
        val readyMap = map ?: return@LaunchedEffect
        val readyConverter = converter ?: return@LaunchedEffect
        if (viewportWidth <= 0 || viewportHeight <= 0) return@LaunchedEffect
        runCatching {
            val geometry = plan.legs.filter { it.geometry.isNotEmpty() }
                .flatMap(::amapRouteGeometryForDisplay)
            val coordinates = geometry.ifEmpty {
                plan.orderedPoints.map { readyConverter.convert(it.coordinate) }
            }.withoutConsecutiveAmapDuplicates()
            when (coordinates.size) {
                0 -> Unit
                1 -> readyMap.animateCamera(
                    AmapCameraUpdateFactory.newLatLngZoom(coordinates.single().toLatLng(), 15f),
                )
                else -> {
                    val bounds = AmapLatLngBounds.Builder().also { builder ->
                        coordinates.forEach { builder.include(it.toLatLng()) }
                    }.build()
                    readyMap.animateCamera(AmapCameraUpdateFactory.newLatLngBounds(bounds, 76))
                }
            }
        }.onFailure { error ->
            Log.w("RoutePreviewMap", "AMAP_FIT failed (${error.javaClass.name})")
        }
    }
}

@Composable
private fun ProviderContentRejectedPanel(provider: MapProvider, modifier: Modifier) {
    Surface(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("地图内容无法安全显示", style = MaterialTheme.typography.titleMedium)
            Text(
                "${if (provider == MapProvider.AMAP) "高德" else "Google"}提供方与坐标系不一致，已停止加载地图。",
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun List<GeoPoint>.withoutConsecutiveGeoDuplicates(): List<GeoPoint> =
    fold(mutableListOf()) { output, point ->
        if (output.lastOrNull() != point) output += point
        output
    }

private fun List<AmapDisplayCoordinate>.withoutConsecutiveAmapDuplicates(): List<AmapDisplayCoordinate> =
    fold(mutableListOf()) { output, point ->
        if (output.lastOrNull() != point) output += point
        output
    }
