package cn.anitabi.navigator.ui.discovery.map

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.navigation.normalizePhoneHeading
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
    fun setUserLocation(displayCoordinate: GeoPoint?, headingDegrees: Float?, density: Float, dark: Boolean)
    fun restore(position: DiscoveryCameraPosition, onSettled: () -> Unit = {})
    fun focus(coordinate: GeoPoint, zoom: Float, content: ScreenRect, minimallyPan: Boolean = false, onSettled: () -> Unit = {})
    fun fit(coordinates: List<GeoPoint>, padding: DiscoveryMapPadding, width: Int, height: Int, onSettled: () -> Unit = {})
    fun resetBearing(onSettled: () -> Unit = {})
    fun stop()
    fun close()
}

internal class GoogleDiscoveryMapAdapter(private val map: GoogleMap) : DiscoveryMapAdapter {
    private val markers = mutableMapOf<String, Marker>()
    private var userLocation: Marker? = null
    private var userLocationStyle: DiscoveryUserLocationStyle? = null
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
    override fun setUserLocation(displayCoordinate: GeoPoint?, headingDegrees: Float?, density: Float, dark: Boolean) {
        if (displayCoordinate == null) {
            userLocation?.remove()
            userLocation = null
            userLocationStyle = null
            return
        }
        val heading = headingDegrees?.let(::normalizePhoneHeading)
        val style = DiscoveryUserLocationStyle(density, dark, heading != null)
        val position = displayCoordinate.google()
        val marker = userLocation
        if (marker == null) {
            val icon = discoveryUserLocationArtwork(style)
            userLocation = map.addMarker(MarkerOptions().position(position)
                .icon(BitmapDescriptorFactory.fromBitmap(icon.bitmap)).anchor(.5f, .5f)
                .flat(true).rotation(heading ?: 0f).zIndex(3f))
        } else {
            if (marker.position != position) marker.position = position
            marker.rotation = heading ?: 0f
            if (style != userLocationStyle) {
                marker.setIcon(BitmapDescriptorFactory.fromBitmap(discoveryUserLocationArtwork(style).bitmap))
            }
        }
        userLocationStyle = style
    }
    override fun restore(position: DiscoveryCameraPosition, onSettled: () -> Unit) {
        if (position.provider == provider) map.moveCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition(position.center.google(), position.zoom, position.tilt, position.bearing),
        ))
        onSettled()
    }
    override fun focus(coordinate: GeoPoint, zoom: Float, content: ScreenRect, minimallyPan: Boolean, onSettled: () -> Unit) {
        if (!minimallyPan) map.moveCamera(CameraUpdateFactory.newLatLngZoom(coordinate.google(), zoom))
        val offset = focusPan(project(coordinate), content, minimallyPan)
        if (offset.x != 0f || offset.y != 0f) map.moveCamera(CameraUpdateFactory.scrollBy(offset.x, offset.y))
        onSettled()
    }
    override fun fit(coordinates: List<GeoPoint>, padding: DiscoveryMapPadding, width: Int, height: Int, onSettled: () -> Unit) {
        if (coordinates.isEmpty() || width <= 0 || height <= 0) return onSettled()
        if (coordinates.distinct().size == 1) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(coordinates.first().google(), 16f.coerceAtMost(maxZoom)))
            return onSettled()
        }
        val bounds = LatLngBounds.builder().also { builder -> coordinates.forEach { builder.include(it.google()) } }.build()
        val content = padding.content(width, height)
        // Both the SDK callback and measured layout have completed before this method.
        // This overload uses the map's padded viewport, including the measured sheet.
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds,
            (minOf(content.right - content.left, content.bottom - content.top) * .08f).toInt()))
        onSettled()
    }
    override fun resetBearing(onSettled: () -> Unit) {
        map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.builder(map.cameraPosition).bearing(0f).tilt(0f).build()))
        onSettled()
    }
    override fun stop() { map.stopAnimation() }
    override fun close() {
        map.setOnCameraIdleListener(null)
        map.setOnCameraMoveStartedListener(null)
        map.setOnCameraMoveListener(null)
        map.setOnMarkerClickListener(null)
        markers.values.forEach { it.remove() }
        markers.clear()
        userLocation?.remove()
        userLocation = null
        userLocationStyle = null
    }
}

internal class AmapDiscoveryMapAdapter(context: Context, private val map: AMap, private val onFailure: () -> Unit) : DiscoveryMapAdapter {
    // Constructed only after the existing application privacy gate permits SDK use.
    private val converter = OfficialAmapCoordinateConverter(context)
    private val coordinates = DiscoveryDisplayCoordinates { source ->
        converter.convert(source).let { GeoPoint(it.latitude, it.longitude) }
    }
    private val markers = mutableMapOf<String, AmapMarker>()
    private var userLocation: AmapMarker? = null
    private var userLocationStyle: DiscoveryUserLocationStyle? = null
    private val cameraSequence = DiscoveryCameraSequence()
    private var cameraMoving = false
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
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
            override fun onCameraChange(position: AmapCameraPosition) = callback {
                cameraMoving = true
                onMove()
            }
            override fun onCameraChangeFinish(position: AmapCameraPosition) = callback {
                cameraMoving = false
                cameraSequence.onCameraFinish()
                if (!cameraSequence.isPending) onIdle()
            }
        })
        map.setOnMapTouchListener { event ->
            if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                callback { cameraSequence.cancel(); onGesture() }
            }
        }
        map.setOnMarkerClickListener { marker -> (marker.`object` as? String)?.let { id -> callback { onMarker(id) } }; true }
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
    override fun setUserLocation(displayCoordinate: GeoPoint?, headingDegrees: Float?, density: Float, dark: Boolean) {
        if (displayCoordinate == null) {
            userLocation?.remove()
            userLocation = null
            userLocationStyle = null
            return
        }
        val heading = headingDegrees?.let(::normalizePhoneHeading)
        val style = DiscoveryUserLocationStyle(density, dark, heading != null)
        val position = displayCoordinate.amap()
        val rotation = heading?.let { normalizePhoneHeading(-it) } ?: 0f
        val marker = userLocation
        if (marker == null) {
            val icon = discoveryUserLocationArtwork(style)
            userLocation = map.addMarker(AmapMarkerOptions().position(position)
                .icon(AmapBitmapDescriptorFactory.fromBitmap(icon.bitmap)).anchor(.5f, .5f)
                .setFlat(true).rotateAngle(rotation).zIndex(3f))?.also { it.setInfoWindowEnable(false) }
        } else {
            if (marker.position != position) marker.position = position
            marker.rotateAngle = rotation
            if (style != userLocationStyle) {
                marker.setIcon(AmapBitmapDescriptorFactory.fromBitmap(discoveryUserLocationArtwork(style).bitmap))
            }
        }
        userLocationStyle = style
    }
    override fun restore(position: DiscoveryCameraPosition, onSettled: () -> Unit) {
        if (position.provider != provider) { cameraSequence.cancel(); return onSettled() }
        val canSkip = !cameraMoving && !cameraSequence.isPending
        cameraSequence.start(listOf(DiscoveryCameraSequence.Step(
            isComplete = { camera().near(position) },
            apply = { move(AmapCameraUpdateFactory.newCameraPosition(
                AmapCameraPosition(position.center.amap(), position.zoom, position.tilt, position.bearing))) },
            canSkip = canSkip,
        )), onSettled)
    }
    override fun focus(coordinate: GeoPoint, zoom: Float, content: ScreenRect, minimallyPan: Boolean, onSettled: () -> Unit) {
        val canSkip = !cameraMoving && !cameraSequence.isPending
        val steps = buildList {
            if (!minimallyPan) add(DiscoveryCameraSequence.Step(
                isComplete = { camera().let { it.center.near(coordinate) && kotlin.math.abs(it.zoom - zoom) < .001f } },
                apply = { move(AmapCameraUpdateFactory.newLatLngZoom(coordinate.amap(), zoom)) },
                canSkip = canSkip,
            ))
            add(DiscoveryCameraSequence.Step(
                isComplete = { focusPan(project(coordinate), content, minimallyPan).let {
                    kotlin.math.abs(it.x) <= 1f && kotlin.math.abs(it.y) <= 1f
                } },
                apply = { focusPan(project(coordinate), content, minimallyPan).let {
                    move(AmapCameraUpdateFactory.scrollBy(it.x, it.y))
                } },
                canSkip = !minimallyPan || canSkip,
            ))
        }
        cameraSequence.start(steps, onSettled)
    }
    override fun fit(coordinates: List<GeoPoint>, padding: DiscoveryMapPadding, width: Int, height: Int, onSettled: () -> Unit) {
        if (coordinates.isEmpty() || width <= 0 || height <= 0) { cameraSequence.cancel(); return onSettled() }
        if (coordinates.distinct().size == 1) {
            return focus(coordinates.first(), 16f.coerceAtMost(maxZoom), padding.content(width, height), onSettled = onSettled)
        }
        val bounds = AmapLatLngBounds.builder().also { builder -> coordinates.forEach { builder.include(it.amap()) } }.build()
        val content = padding.content(width, height)
        val margin = (minOf(content.right - content.left, content.bottom - content.top) * .08f).toInt()
        cameraSequence.start(listOf(DiscoveryCameraSequence.Step(
            isComplete = { coordinates.all { content.contains(project(it)) } },
            apply = { move(AmapCameraUpdateFactory.newLatLngBoundsRect(bounds,
                content.left.toInt() + margin, width - content.right.toInt() + margin,
                content.top.toInt() + margin, height - content.bottom.toInt() + margin)) },
            canSkip = false,
        )), onSettled)
    }
    override fun resetBearing(onSettled: () -> Unit) = restore(camera().copy(bearing = 0f, tilt = 0f), onSettled)
    private fun move(update: com.amap.api.maps.CameraUpdate) { cameraMoving = true; map.moveCamera(update) }
    private fun callback(action: () -> Unit) {
        val invoke = Runnable {
            if (!closed) try { action() } catch (_: RuntimeException) { cameraSequence.cancel(); onFailure() }
        }
        if (Looper.myLooper() == main.looper) invoke.run() else main.post(invoke)
    }
    override fun stop() { cameraSequence.cancel(); map.stopAnimation() }
    override fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        cameraSequence.cancel()
        map.setOnCameraChangeListener(null)
        map.setOnMapTouchListener(null)
        map.setOnMarkerClickListener(null)
        markers.values.forEach { it.remove() }
        markers.clear()
        userLocation?.remove()
        userLocation = null
        userLocationStyle = null
        coordinates.clear()
    }
}

private fun GeoPoint.near(other: GeoPoint): Boolean = kotlin.math.abs(latitude - other.latitude) < .000001 &&
    kotlin.math.abs(longitude - other.longitude) < .000001
private fun DiscoveryCameraPosition.near(other: DiscoveryCameraPosition): Boolean = center.near(other.center) &&
    kotlin.math.abs(zoom - other.zoom) < .001f && kotlin.math.abs(tilt - other.tilt) < .001f &&
    kotlin.math.abs(bearing - other.bearing) < .001f

private fun GeoPoint.google() = LatLng(latitude, longitude)
private fun GeoPoint.amap() = AmapLatLng(latitude, longitude)
