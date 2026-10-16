package cn.anitabi.navigator.diagnostics

import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.ui.discovery.DiscoveryViewportToken
import cn.anitabi.navigator.ui.discovery.map.DiscoveryMapAdapter
import cn.anitabi.navigator.ui.discovery.map.DiscoveryMarkerBitmap
import cn.anitabi.navigator.ui.discovery.map.ScreenRect

/** Main-only references to icons actually committed to the current SDK. Never serialized. */
internal data class DiscoveryVisualMarker(
    val displayCoordinate: GeoPoint,
    val icon: DiscoveryMarkerBitmap,
    val memberCount: Int,
    val isImage: Boolean,
)

internal data class DiscoveryVisualFrame(
    val adapter: DiscoveryMapAdapter,
    val viewportToken: DiscoveryViewportToken?,
    val content: ScreenRect,
    val width: Int,
    val height: Int,
    val markers: List<DiscoveryVisualMarker>,
    /** Checked on Main again after asynchronous copying; input invalidation rejects old pixels. */
    val isCurrent: () -> Boolean,
)
