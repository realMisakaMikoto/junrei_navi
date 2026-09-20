package cn.anitabi.navigator.ui.discovery.map

import android.content.Context
import android.graphics.Bitmap
import android.view.MotionEvent
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.ui.map.OfficialAmapCoordinateConverter
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapColorScheme
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory as AmapCameraUpdateFactory
import com.amap.api.maps.model.CameraPosition as AmapCameraPosition
import com.amap.api.maps.model.LatLng as AmapLatLng
import com.amap.api.maps.model.LatLngBounds as AmapLatLngBounds
import com.amap.api.maps.model.Marker as AmapMarker
import com.amap.api.maps.model.MarkerOptions as AmapMarkerOptions
import com.amap.api.maps.model.BitmapDescriptorFactory as AmapBitmapDescriptorFactory

internal data class DiscoveryMarkerBitmap(val bitmap: Bitmap, val anchorX: Float = .5f, val anchorY: Float = .5f)

/** Every method is called on Main; this interface never exposes SDK objects to workers. */
internal interface DiscoveryMapAdapter {
    val provider: MapProvider
    val maxZoom: Float
    fun displayCoordinate(id: String, source: GeoPoint): GeoPoint
    fun trimCoordinates(ids: Set<String>)
    fun project(displayCoordinate: GeoPoint): ScreenPoint
    fun projector(): (GeoPoint) -> ScreenPoint
    fun bounds(): DiscoveryGeoBounds
    fun camera(): DiscoveryCameraPosition
    fun configure(dark: Boolean, padding: DiscoveryMapPadding, width: Int, height: Int)
    fun listen(onIdle: () -> Unit, onMove: () -> Unit, onGesture: () -> Unit, onMarker: (String) -> Unit)
    fun upsert(id: String, coordinate: GeoPoint, title: String, icon: DiscoveryMarkerBitmap, selected: Boolean)
    fun remove(id: String)
    fun restore(position: DiscoveryCameraPosition)
    fun center(coordinate: GeoPoint, zoom: Float)
    fun fit(coordinates: List<GeoPoint>, padding: DiscoveryMapPadding, width: Int, height: Int)
    fun pan(offset: ScreenPoint)
    fun resetBearing()
    fun stop()
    fun close()
}

internal class GoogleDiscoveryMapAdapter(private val map: GoogleMap) : DiscoveryMapAdapter {
    private val markers = mutableMapOf<String, Marker>()
    override val provider = MapProvider.GOOGLE
    override val maxZoom: Float get() = map.maxZoomLevel
    override fun displayCoordinate(id: String, source: GeoPoint): GeoPoint = source
    override fun trimCoordinates(ids: Set<String>) = Unit
    override fun project(displayCoordinate: GeoPoint): ScreenPoint = map.projection.toScreenLocation(displayCoordinate.google()).let { ScreenPoint(it.x.toFloat(), it.y.toFloat()) }
    override fun projector(): (GeoPoint) -> ScreenPoint {
        val projection = map.projection
        return { coordinate -> projection.toScreenLocation(coordinate.google()).let { ScreenPoint(it.x.toFloat(), it.y.toFloat()) } }
    }
    override fun bounds(): DiscoveryGeoBounds = map.projection.visibleRegion.latLngBounds.let {
        DiscoveryGeoBounds(it.southwest.latitude, it.southwest.longitude, it.northeast.latitude, it.northeast.longitude)
    }
    override fun camera(): DiscoveryCameraPosition = map.cameraPosition.let {
        DiscoveryCameraPosition(GeoPoint(it.target.latitude, it.target.longitude), it.zoom, it.bearing, it.tilt, provider)
    }
    override fun configure(dark: Boolean, padding: DiscoveryMapPadding, width: Int, height: Int) {
        map.mapType = GoogleMap.MAP_TYPE_NORMAL
        map.setMapColorScheme(if (dark) MapColorScheme.DARK else MapColorScheme.LIGHT)
        map.uiSettings.isMapToolbarEnabled = false
        map.uiSettings.isZoomControlsEnabled = false
        // Discovery owns these controls; navigation owns its own separate session UI.
        map.uiSettings.isCompassEnabled = false
        val content = padding.content(width, height)
        map.setPadding(content.left.toInt(), content.top.toInt(), width - content.right.toInt(), height - content.bottom.toInt())
    }
    override fun listen(onIdle: () -> Unit, onMove: () -> Unit, onGesture: () -> Unit, onMarker: (String) -> Unit) {
        map.setOnCameraIdleListener(onIdle)
        map.setOnCameraMoveStartedListener { reason ->
            onMove()
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) onGesture()
        }
        map.setOnCameraMoveListener(onMove)
        map.setOnMarkerClickListener { marker -> (marker.tag as? String)?.let(onMarker); true }
    }
    override fun upsert(id: String, coordinate: GeoPoint, title: String, icon: DiscoveryMarkerBitmap, selected: Boolean) {
        val descriptor = BitmapDescriptorFactory.fromBitmap(icon.bitmap)
        val marker = markers[id]
        if (marker == null) {
            map.addMarker(MarkerOptions().position(coordinate.google()).title(title).icon(descriptor)
                .anchor(icon.anchorX, icon.anchorY).zIndex(if (selected) 2f else 1f))?.let {
                it.tag = id
                markers[id] = it
            }
        } else {
            marker.position = coordinate.google()
            marker.title = title
            marker.setIcon(descriptor)
            marker.setAnchor(icon.anchorX, icon.anchorY)
            marker.zIndex = if (selected) 2f else 1f
        }
    }
    override fun remove(id: String) { markers.remove(id)?.remove() }
    override fun restore(position: DiscoveryCameraPosition) {
        if (position.provider == provider) map.moveCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition(position.center.google(), position.zoom, position.tilt, position.bearing),
        ))
    }
    override fun center(coordinate: GeoPoint, zoom: Float) { map.moveCamera(CameraUpdateFactory.newLatLngZoom(coordinate.google(), zoom)) }
    override fun fit(coordinates: List<GeoPoint>, padding: DiscoveryMapPadding, width: Int, height: Int) {
        if (coordinates.isEmpty() || width <= 0 || height <= 0) return
        if (coordinates.distinct().size == 1) return center(coordinates.first(), 16f.coerceAtMost(maxZoom))
        val bounds = LatLngBounds.builder().also { builder -> coordinates.forEach { builder.include(it.google()) } }.build()
        val content = padding.content(width, height)
        // Both the SDK callback and measured layout have completed before this method.
        // This overload uses the map's padded viewport, including the measured sheet.
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds,
            (minOf(content.right - content.left, content.bottom - content.top) * .08f).toInt()))
    }
    override fun pan(offset: ScreenPoint) { if (offset.x != 0f || offset.y != 0f) map.moveCamera(CameraUpdateFactory.scrollBy(offset.x, offset.y)) }
    override fun resetBearing() { map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.builder(map.cameraPosition).bearing(0f).tilt(0f).build())) }
    override fun stop() { map.stopAnimation() }
    override fun close() {
        map.setOnCameraIdleListener(null)
        map.setOnCameraMoveStartedListener(null)
        map.setOnCameraMoveListener(null)
        map.setOnMarkerClickListener(null)
        markers.values.forEach { it.remove() }
        markers.clear()
    }
}

internal class AmapDiscoveryMapAdapter(context: Context, private val map: AMap) : DiscoveryMapAdapter {
    // Constructed only after the existing application privacy gate permits SDK use.
    private val converter = OfficialAmapCoordinateConverter(context)
    private val coordinates = DiscoveryDisplayCoordinates { source ->
        converter.convert(source).let { GeoPoint(it.latitude, it.longitude) }
    }
    private val markers = mutableMapOf<String, AmapMarker>()
    override val provider = MapProvider.AMAP
    override val maxZoom: Float get() = map.maxZoomLevel
    override fun displayCoordinate(id: String, source: GeoPoint): GeoPoint = coordinates.get(id, source)
    override fun trimCoordinates(ids: Set<String>) { coordinates.retain(ids) }
    override fun project(displayCoordinate: GeoPoint): ScreenPoint = map.projection.toScreenLocation(displayCoordinate.amap()).let { ScreenPoint(it.x.toFloat(), it.y.toFloat()) }
    override fun projector(): (GeoPoint) -> ScreenPoint {
        val projection = map.projection
        return { coordinate -> projection.toScreenLocation(coordinate.amap()).let { ScreenPoint(it.x.toFloat(), it.y.toFloat()) } }
    }
    override fun bounds(): DiscoveryGeoBounds = map.projection.visibleRegion.latLngBounds.let {
        DiscoveryGeoBounds(it.southwest.latitude, it.southwest.longitude, it.northeast.latitude, it.northeast.longitude)
    }
    override fun camera(): DiscoveryCameraPosition = map.cameraPosition.let {
        DiscoveryCameraPosition(GeoPoint(it.target.latitude, it.target.longitude), it.zoom, it.bearing, it.tilt, provider)
    }
    override fun configure(dark: Boolean, padding: DiscoveryMapPadding, width: Int, height: Int) {
        map.mapType = if (dark) AMap.MAP_TYPE_NIGHT else AMap.MAP_TYPE_NORMAL
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isCompassEnabled = false
        // AMap's bounds update accepts asymmetric padding. Do not change the rotation
        // pivot here: merely expanding a sheet must not move an already visible point.
        map.uiSettings.setLogoBottomMargin(padding.bottom.coerceIn(0, height))
        map.uiSettings.setLogoLeftMargin(padding.left.coerceIn(0, width))
    }
    override fun listen(onIdle: () -> Unit, onMove: () -> Unit, onGesture: () -> Unit, onMarker: (String) -> Unit) {
        map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(position: AmapCameraPosition) = onMove()
            override fun onCameraChangeFinish(position: AmapCameraPosition) = onIdle()
        })
        map.setOnMapTouchListener { event ->
            if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) onGesture()
        }
        map.setOnMarkerClickListener { marker -> (marker.`object` as? String)?.let(onMarker); true }
    }
    override fun upsert(id: String, coordinate: GeoPoint, title: String, icon: DiscoveryMarkerBitmap, selected: Boolean) {
        val descriptor = AmapBitmapDescriptorFactory.fromBitmap(icon.bitmap)
        val marker = markers[id]
        if (marker == null) {
            map.addMarker(AmapMarkerOptions().position(coordinate.amap()).title(title).icon(descriptor)
                .anchor(icon.anchorX, icon.anchorY).zIndex(if (selected) 2f else 1f))?.let {
                it.`object` = id
                markers[id] = it
            }
        } else {
            marker.position = coordinate.amap()
            marker.title = title
            marker.setIcon(descriptor)
            marker.setAnchor(icon.anchorX, icon.anchorY)
            marker.zIndex = if (selected) 2f else 1f
        }
    }
    override fun remove(id: String) { markers.remove(id)?.remove() }
    override fun restore(position: DiscoveryCameraPosition) {
        if (position.provider == provider) map.moveCamera(AmapCameraUpdateFactory.newCameraPosition(
            AmapCameraPosition(position.center.amap(), position.zoom, position.tilt, position.bearing),
        ))
    }
    override fun center(coordinate: GeoPoint, zoom: Float) { map.moveCamera(AmapCameraUpdateFactory.newLatLngZoom(coordinate.amap(), zoom)) }
    override fun fit(coordinates: List<GeoPoint>, padding: DiscoveryMapPadding, width: Int, height: Int) {
        if (coordinates.isEmpty() || width <= 0 || height <= 0) return
        if (coordinates.distinct().size == 1) {
            center(coordinates.first(), 16f.coerceAtMost(maxZoom))
            pan(focusPan(project(coordinates.first()), padding.content(width, height), false))
            return
        }
        val bounds = AmapLatLngBounds.builder().also { builder -> coordinates.forEach { builder.include(it.amap()) } }.build()
        val content = padding.content(width, height)
        val margin = (minOf(content.right - content.left, content.bottom - content.top) * .08f).toInt()
        map.moveCamera(AmapCameraUpdateFactory.newLatLngBoundsRect(bounds,
            content.left.toInt() + margin, width - content.right.toInt() + margin,
            content.top.toInt() + margin, height - content.bottom.toInt() + margin))
    }
    override fun pan(offset: ScreenPoint) { if (offset.x != 0f || offset.y != 0f) map.moveCamera(AmapCameraUpdateFactory.scrollBy(offset.x, offset.y)) }
    override fun resetBearing() { map.moveCamera(AmapCameraUpdateFactory.newCameraPosition(AmapCameraPosition.builder(map.cameraPosition).bearing(0f).tilt(0f).build())) }
    override fun stop() { map.stopAnimation() }
    override fun close() {
        map.setOnCameraChangeListener(null)
        map.setOnMapTouchListener(null)
        map.setOnMarkerClickListener(null)
        markers.values.forEach { it.remove() }
        markers.clear()
        coordinates.clear()
    }
}

private fun GeoPoint.google() = LatLng(latitude, longitude)
private fun GeoPoint.amap() = AmapLatLng(latitude, longitude)
