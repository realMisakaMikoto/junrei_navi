package cn.anitabi.navigator.ui.discovery.map

import kotlin.math.floor

data class ProjectedDiscoveryPoint(val id: String, val screen: ScreenPoint)

data class DiscoveryCluster(
    val id: String,
    val memberIds: List<String>,
    val anchorId: String,
    val screen: ScreenPoint,
    val selected: Boolean,
    val decoration: DiscoveryMarkerDecoration = DiscoveryMarkerDecoration.DOT,
    val imageUrl: String? = null,
)

enum class DiscoveryMarkerDecoration { DOT, LABEL, IMAGE }

/** All members survive; only optional labels/images participate in collision avoidance. */
fun clusterDiscoveryPoints(
    projected: List<ProjectedDiscoveryPoint>,
    points: Map<String, DiscoveryMapPoint>,
    selectedIds: Set<String>,
    focusedId: String?,
    content: ScreenRect,
    cellSizePx: Float,
    zoom: Float,
    imagesEnabled: Boolean,
    density: Float = 1f,
    markerBounds: ((DiscoveryCluster, DiscoveryMarkerDecoration) -> ScreenRect)? = null,
    checkActive: () -> Unit = {},
): List<DiscoveryCluster> {
    require(cellSizePx > 0)
    val groups = linkedMapOf<Pair<Int, Int>, MutableList<ProjectedDiscoveryPoint>>()
    projected.forEachIndexed { index, point ->
        if (index % 256 == 0) checkActive()
        if (content.contains(point.screen)) {
            val cell = floor(point.screen.x / cellSizePx).toInt() to floor(point.screen.y / cellSizePx).toInt()
            groups.getOrPut(cell) { mutableListOf() }.add(point)
        }
    }
    val clusters = groups.values.map { members ->
        checkActive()
        val anchor = members.firstOrNull { it.id == focusedId } ?: members.minBy { it.id }
        val ids = members.map { it.id }.sorted()
        // The anchor is an actual geographic member, not an averaged/wrapped coordinate.
        DiscoveryCluster(
            id = if (ids.size == 1) "point:${ids.single()}" else "cluster:${ids.first()}",
            memberIds = ids,
            anchorId = anchor.id,
            screen = anchor.screen,
            selected = ids.any { it in selectedIds || it == focusedId },
        )
    }.sortedWith(compareByDescending<DiscoveryCluster> { focusedId in it.memberIds }.thenByDescending { it.selected }.thenBy { it.id })
    fun bounds(cluster: DiscoveryCluster, decoration: DiscoveryMarkerDecoration): ScreenRect {
        val relative = markerBounds?.invoke(cluster, decoration) ?: when (decoration) {
            DiscoveryMarkerDecoration.DOT -> ScreenRect(-24f * density, -24f * density, 24f * density, 24f * density)
            DiscoveryMarkerDecoration.LABEL -> ScreenRect(-70f * density, -40f * density, 70f * density, 8f * density)
            DiscoveryMarkerDecoration.IMAGE -> ScreenRect(-44f * density, -72f * density, 44f * density, 8f * density)
        }
        return ScreenRect(cluster.screen.x + relative.left, cluster.screen.y + relative.top,
            cluster.screen.x + relative.right, cluster.screen.y + relative.bottom)
    }
    val occupied = DecorationOccupancy(96f * density)
    clusters.forEach { cluster ->
        occupied.add(bounds(cluster, DiscoveryMarkerDecoration.DOT), cluster.id)
    }
    val visibleCovers = hashSetOf<Long>()
    return clusters.map { cluster ->
        checkActive()
        val point = points[cluster.anchorId] ?: return@map cluster
        val imageUrl = when {
            zoom >= 15f -> point.imageUrl
            zoom < 10f && cluster.memberIds.size == 1 && point.subjectId !in visibleCovers -> point.subjectImageUrl
            else -> null
        }
        val canImage = imagesEnabled && imageUrl != null && cluster.memberIds.size == 1
        val canLabel = zoom >= 15f && point.title.isNotBlank() && cluster.memberIds.size == 1
        if (!canImage && !canLabel) return@map cluster
        val decoration = if (canImage) DiscoveryMarkerDecoration.IMAGE else DiscoveryMarkerDecoration.LABEL
        val box = bounds(cluster, decoration)
        if (box.left < content.left || box.right > content.right || box.top < content.top || box.bottom > content.bottom || occupied.collides(box, cluster.id)) return@map cluster
        occupied.add(box, cluster.id)
        if (canImage && zoom < 10f) visibleCovers.add(point.subjectId)
        cluster.copy(decoration = decoration, imageUrl = imageUrl)
    }
}

private class DecorationOccupancy(private val cellSize: Float) {
    private val cells = hashMapOf<Pair<Int, Int>, MutableList<Pair<ScreenRect, String>>>()
    private fun cells(rect: ScreenRect): Sequence<Pair<Int, Int>> = sequence {
        for (x in floor(rect.left / cellSize).toInt()..floor(rect.right / cellSize).toInt()) {
            for (y in floor(rect.top / cellSize).toInt()..floor(rect.bottom / cellSize).toInt()) yield(x to y)
        }
    }
    fun add(rect: ScreenRect, owner: String) { cells(rect).forEach { cells.getOrPut(it) { mutableListOf() }.add(rect to owner) } }
    fun collides(rect: ScreenRect, owner: String): Boolean = cells(rect).any { cell ->
        cells[cell]?.any { (other, otherOwner) -> otherOwner != owner && rect.intersects(other) } == true
    }
}

data class DiscoveryMarkerDelta(val added: Set<String>, val removed: Set<String>, val retained: Set<String>)

fun discoveryMarkerDelta(previousIds: Set<String>, nextIds: Set<String>): DiscoveryMarkerDelta =
    DiscoveryMarkerDelta(nextIds - previousIds, previousIds - nextIds, previousIds intersect nextIds)
