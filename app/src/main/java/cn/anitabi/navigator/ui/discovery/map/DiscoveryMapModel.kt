package cn.anitabi.navigator.ui.discovery.map

import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider

/** Source coordinates remain WGS84; the adapter owns all display conversions. */
data class DiscoveryMapPoint(
    val id: String,
    val subjectId: Long,
    val coordinate: GeoPoint,
    val provider: MapProvider,
    val title: String,
    val colorArgb: Int,
    val imageUrl: String? = null,
    val subjectImageUrl: String? = null,
)

data class DiscoveryMapPadding(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0) {
    fun content(width: Int, height: Int): ScreenRect = ScreenRect(
        left.coerceIn(0, (width - 1).coerceAtLeast(0)).toFloat(),
        top.coerceIn(0, (height - 1).coerceAtLeast(0)).toFloat(),
        (width - right).coerceIn((left + 1).coerceAtMost(width), width).toFloat(),
        (height - bottom).coerceIn((top + 1).coerceAtMost(height), height).toFloat(),
    )
}

/** View-only position in the named SDK's display CRS; never a pilgrimage coordinate. */
data class DiscoveryCameraPosition(
    val center: GeoPoint,
    val zoom: Float,
    val bearing: Float = 0f,
    val tilt: Float = 0f,
    val provider: MapProvider = MapProvider.GOOGLE,
)

sealed interface DiscoveryCameraCommand {
    val sequence: Long
    data class Focus(override val sequence: Long, val pointId: String, val minimallyPan: Boolean = false) : DiscoveryCameraCommand
    data class FitAll(override val sequence: Long, val pointIds: Set<String>? = null) : DiscoveryCameraCommand
    data class Restore(override val sequence: Long, val camera: DiscoveryCameraPosition) : DiscoveryCameraCommand
    data class ResetBearing(override val sequence: Long) : DiscoveryCameraCommand
    data class Locate(override val sequence: Long, val coordinate: GeoPoint) : DiscoveryCameraCommand
}

data class ScreenPoint(val x: Float, val y: Float)

data class ScreenRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val center: ScreenPoint get() = ScreenPoint((left + right) / 2, (top + bottom) / 2)
    fun contains(point: ScreenPoint): Boolean = point.x in left..right && point.y in top..bottom
    fun intersects(other: ScreenRect): Boolean = left < other.right && right > other.left && top < other.bottom && bottom > other.top
    fun inset(pixels: Float): ScreenRect {
        val x = pixels.coerceAtMost((right - left) / 3)
        val y = pixels.coerceAtMost((bottom - top) / 3)
        return ScreenRect(left + x, top + y, right - x, bottom - y)
    }
}

/** A pan offset changes the camera only; it never alters a point's geographic anchor. */
fun focusPan(point: ScreenPoint, content: ScreenRect, minimallyPan: Boolean): ScreenPoint =
    if (minimallyPan) {
        ScreenPoint(point.x - point.x.coerceIn(content.left, content.right), point.y - point.y.coerceIn(content.top, content.bottom))
    } else {
        ScreenPoint(point.x - content.center.x, point.y - content.center.y)
    }
