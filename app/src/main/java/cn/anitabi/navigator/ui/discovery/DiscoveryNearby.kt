package cn.anitabi.navigator.ui.discovery

import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.routing.TourOptimizer
import cn.anitabi.navigator.data.discovery.DiscoveryLoadCounter
import cn.anitabi.navigator.data.discovery.DiscoveryLoadPhase
import cn.anitabi.navigator.data.discovery.DiscoveryLoadTrace
import cn.anitabi.navigator.data.discovery.DiscoveryPoint
import cn.anitabi.navigator.data.discovery.measure

data class DiscoveryNearbyKey(val revision: Long, val location: GeoPoint, val filters: Set<Long>)
data class DiscoveryNearbyEntry(val pointId: String, val distanceMeters: Double)
data class DiscoveryNearbyResult(val key: DiscoveryNearbyKey, val entries: List<DiscoveryNearbyEntry>)

/** Only IDs and measured distances survive the worker; rows resolve current display metadata. */
internal fun calculateDiscoveryNearby(
    points: Collection<DiscoveryPoint>,
    key: DiscoveryNearbyKey,
    trace: DiscoveryLoadTrace = DiscoveryLoadTrace(),
    checkCancellation: () -> Unit = {},
): DiscoveryNearbyResult {
    val filtered = points.filter {
        checkCancellation()
        key.filters.isEmpty() || it.subjectId in key.filters
    }
    trace.increment(DiscoveryLoadCounter.NEARBY_SORT_COUNT)
    var computations = 0L
    try {
        return trace.measure(DiscoveryLoadPhase.NEARBY_SORT, filtered.size.toLong()) {
            val entries = filtered.mapTo(ArrayList(filtered.size)) { point ->
                checkCancellation()
                computations++
                DiscoveryNearbyEntry(point.id, TourOptimizer.haversineMeters(key.location, point.coordinate))
            }
            entries.sortWith { first, second ->
                checkCancellation()
                val distanceOrder = first.distanceMeters.compareTo(second.distanceMeters)
                if (distanceOrder != 0) distanceOrder else first.pointId.compareTo(second.pointId)
            }
            checkCancellation()
            DiscoveryNearbyResult(key, entries)
        }
    } finally {
        trace.increment(DiscoveryLoadCounter.DISTANCE_COMPUTATION_COUNT, computations)
    }
}
