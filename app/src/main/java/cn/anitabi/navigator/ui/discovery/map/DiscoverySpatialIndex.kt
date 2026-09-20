package cn.anitabi.navigator.ui.discovery.map

import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import kotlin.math.floor

data class DiscoveryIndexKey(val dataVersion: String, val provider: MapProvider)

/** Longitude intervals wrap across the date line when west > east. */
data class DiscoveryGeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun contains(point: GeoPoint): Boolean = point.latitude in south..north &&
        (if (west <= east) point.longitude in west..east else point.longitude >= west || point.longitude <= east)
}

data class IndexedDiscoveryPoint(val id: String, val coordinate: GeoPoint)

/** Latitude bands make nearby queries independent of the whole catalogue size. */
class DiscoverySpatialIndex(
    val key: DiscoveryIndexKey,
    entries: List<IndexedDiscoveryPoint>,
    checkActive: () -> Unit = {},
) {
    private val bands: Map<Int, List<IndexedDiscoveryPoint>>
    val size: Int

    init {
        val identities = HashSet<String>(entries.size)
        val grouped = mutableMapOf<Int, MutableList<IndexedDiscoveryPoint>>()
        entries.forEachIndexed { index, entry ->
            if (index % 256 == 0) checkActive()
            require(identities.add(entry.id)) { "Discovery IDs must be unique" }
            require(entry.coordinate.latitude.isFinite() && entry.coordinate.longitude.isFinite() &&
                entry.coordinate.latitude in -90.0..90.0 && entry.coordinate.longitude in -180.0..180.0) { "Invalid discovery geometry" }
            grouped.getOrPut(band(entry.coordinate.latitude)) { mutableListOf() }.add(entry)
        }
        size = entries.size
        bands = grouped.mapValues { (_, points) ->
            checkActive()
            points.sortBy { it.coordinate.longitude }
            points
        }
    }

    fun query(bounds: DiscoveryGeoBounds, checkActive: () -> Unit = {}): List<IndexedDiscoveryPoint> = buildList {
        for (latitudeBand in band(bounds.south)..band(bounds.north)) {
            checkActive()
            val entries = bands[latitudeBand] ?: continue
            if (bounds.west <= bounds.east) appendRange(entries, bounds, bounds.west, bounds.east)
            else {
                appendRange(entries, bounds, bounds.west, 180.0)
                appendRange(entries, bounds, -180.0, bounds.east)
            }
        }
    }

    private fun MutableList<IndexedDiscoveryPoint>.appendRange(
        entries: List<IndexedDiscoveryPoint>, bounds: DiscoveryGeoBounds, west: Double, east: Double,
    ) {
        var low = 0
        var high = entries.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (entries[mid].coordinate.longitude < west) low = mid + 1 else high = mid
        }
        for (index in low until entries.size) {
            val entry = entries[index]
            if (entry.coordinate.longitude > east) break
            if (entry.coordinate.latitude in bounds.south..bounds.north) add(entry)
        }
    }

    private fun band(latitude: Double): Int = floor(latitude * 2).toInt()
}

/** Each new camera/data/provider request invalidates results from every earlier request. */
class DiscoveryRequestGeneration {
    private var sequence = 0L
    private var current: DiscoveryCalculationToken? = null
    fun next(key: DiscoveryIndexKey): DiscoveryCalculationToken = DiscoveryCalculationToken(key, ++sequence).also { current = it }
    fun invalidate() { sequence++; current = null }
    fun accepts(token: DiscoveryCalculationToken): Boolean = current == token
}

data class DiscoveryCalculationToken(val key: DiscoveryIndexKey, val sequence: Long)
