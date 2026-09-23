package cn.anitabi.navigator.ui.discovery.map

import cn.anitabi.navigator.core.model.GeoPoint

/** Display-only cache: unchanged source input is converted once, never in place. */
internal class DiscoveryDisplayCoordinates(private val convert: (GeoPoint) -> GeoPoint) {
    private val coordinates = mutableMapOf<String, Pair<GeoPoint, GeoPoint>>()

    fun get(id: String, wgs84: GeoPoint): GeoPoint {
        val cached = coordinates[id]
        if (cached?.first == wgs84) return cached.second
        return convert(wgs84).also { coordinates[id] = wgs84 to it }
    }

    fun retain(ids: Set<String>) { coordinates.keys.retainAll(ids) }
    fun clear() = coordinates.clear()
}
