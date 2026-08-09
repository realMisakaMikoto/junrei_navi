package cn.anitabi.navigator.core.region

import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.TerritoryRegion
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.core.model.mapProvider

data class ResolvedJourneyProvider(
    val provider: MapProvider,
    val regions: Set<TerritoryRegion>,
)

sealed class JourneyProviderResolutionException(
    val code: String,
) : IllegalArgumentException(code) {
    class RegionUnresolved : JourneyProviderResolutionException("REGION_UNRESOLVED")
    class MixedMapProviders : JourneyProviderResolutionException("MIXED_MAP_PROVIDERS")
    class MixedTransitRegions : JourneyProviderResolutionException("MIXED_TRANSIT_REGIONS")
}

fun resolveJourneyProvider(
    start: GeoPoint,
    destinations: List<GeoPoint>,
    mode: TravelMode,
    classify: (GeoPoint) -> TerritoryRegion?,
): ResolvedJourneyProvider {
    val coordinates = listOf(start) + destinations
    val regions = coordinates.mapTo(linkedSetOf()) { coordinate ->
        classify(coordinate) ?: throw JourneyProviderResolutionException.RegionUnresolved()
    }
    val providers = regions.mapTo(mutableSetOf(), TerritoryRegion::mapProvider)
    if (providers.size != 1) throw JourneyProviderResolutionException.MixedMapProviders()
    if (mode == TravelMode.TRANSIT && TerritoryRegion.JAPAN in regions && regions.size > 1) {
        throw JourneyProviderResolutionException.MixedTransitRegions()
    }
    return ResolvedJourneyProvider(providers.single(), regions)
}
