package cn.anitabi.navigator.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TerritoryRegion {
    MAINLAND_CHINA,
    CHINA_OFFICIAL_MAP_ONLY,
    HONG_KONG_SAR,
    MACAO_SAR,
    CHINA_TAIWAN,
    JAPAN,
    OTHER,
}

@Serializable
enum class MapProvider {
    GOOGLE,
    AMAP,
}

@Serializable
enum class CoordinateSystem {
    WGS84,
    GCJ02,
}

val TerritoryRegion.mapProvider: MapProvider
    get() = when (this) {
        TerritoryRegion.MAINLAND_CHINA,
        TerritoryRegion.CHINA_OFFICIAL_MAP_ONLY -> MapProvider.AMAP
        TerritoryRegion.HONG_KONG_SAR,
        TerritoryRegion.MACAO_SAR,
        TerritoryRegion.CHINA_TAIWAN,
        TerritoryRegion.JAPAN,
        TerritoryRegion.OTHER -> MapProvider.GOOGLE
    }

fun TransitExecutionStrategy.isExternalMapNavigation(): Boolean = when (this) {
    TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN,
    TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND -> true
    TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES -> false
}
