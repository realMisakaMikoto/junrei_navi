package cn.anitabi.navigator.ui.map

import android.content.Context
import cn.anitabi.navigator.core.model.CoordinateSystem
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.TourLeg
import cn.anitabi.navigator.core.model.TourPlan
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.model.LatLng

internal data class AmapDisplayCoordinate(
    val latitude: Double,
    val longitude: Double,
) {
    fun toLatLng(): LatLng = LatLng(latitude, longitude)
}

internal fun interface Wgs84ToGcj02Converter {
    fun convert(point: GeoPoint): AmapDisplayCoordinate
}

internal class OfficialAmapCoordinateConverter(context: Context) : Wgs84ToGcj02Converter {
    private val converter = CoordinateConverter(context.applicationContext)

    override fun convert(point: GeoPoint): AmapDisplayCoordinate {
        val converted = converter
            .from(CoordinateConverter.CoordType.GPS)
            .coord(LatLng(point.latitude, point.longitude))
            .convert()
            ?: error("AMap coordinate conversion returned no coordinate")
        return AmapDisplayCoordinate(converted.latitude, converted.longitude)
    }
}

internal fun convertPersistedWgs84Points(
    points: List<GeoPoint>,
    converter: Wgs84ToGcj02Converter,
): List<AmapDisplayCoordinate> = points.map(converter::convert)

internal fun amapRouteGeometryForDisplay(leg: TourLeg): List<AmapDisplayCoordinate> {
    require(leg.provider == MapProvider.AMAP) { "AMap geometry requires the AMap provider" }
    require(leg.coordinateSystem == CoordinateSystem.GCJ02) { "AMap geometry must already be GCJ02" }
    return leg.geometry.map { point -> AmapDisplayCoordinate(point.latitude, point.longitude) }
}

internal fun mapContentMismatch(
    selectedProvider: MapProvider,
    plan: TourPlan,
): String? {
    if (plan.mapProvider != selectedProvider) return "Map provider does not match the classified trip"
    if (selectedProvider == MapProvider.AMAP && plan.regionDataVersion.isNullOrBlank()) {
        return "AMap content requires approved region data"
    }
    if (plan.legs.any { it.provider != selectedProvider }) {
        return "Route leg provider does not match the classified trip"
    }
    return when (selectedProvider) {
        MapProvider.GOOGLE -> if (
            plan.coordinateSystem != CoordinateSystem.WGS84 ||
            plan.legs.any { it.coordinateSystem != CoordinateSystem.WGS84 }
        ) {
            "Google map content must be WGS84"
        } else {
            null
        }
        MapProvider.AMAP -> {
            val geometryLegs = plan.legs.filter { it.geometry.isNotEmpty() }
            when {
                geometryLegs.any { it.coordinateSystem != CoordinateSystem.GCJ02 } ->
                    "AMap route geometry must already be GCJ02"
                geometryLegs.isNotEmpty() && plan.coordinateSystem != CoordinateSystem.GCJ02 ->
                    "AMap plan geometry CRS does not match its route legs"
                else -> null
            }
        }
    }
}
