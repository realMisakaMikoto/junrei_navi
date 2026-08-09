package cn.anitabi.navigator.core.region

import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.TerritoryRegion
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

interface TerritoryClassifier {
    val metadata: TerritoryRegionMetadata?

    /** A null result is deliberately unresolved and must stop routing. */
    fun classify(point: GeoPoint): TerritoryRegion?
}

data class TerritoryRegionMetadata(
    val version: String,
    val sourceName: String,
    val licenseName: String,
    val reviewAuthority: String,
    val reviewApprovalId: String,
    val reviewedAt: String,
    val checksumSha256: String,
)

class TerritoryRegionDataException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class ApprovedTerritoryClassifier private constructor(
    override val metadata: TerritoryRegionMetadata,
    private val features: List<RegionFeature>,
) : TerritoryClassifier {
    override fun classify(point: GeoPoint): TerritoryRegion? {
        if (
            !point.latitude.isFinite() || point.latitude !in -90.0..90.0 ||
            !point.longitude.isFinite() || point.longitude !in -180.0..180.0
        ) {
            return null
        }
        val coordinate = TerritoryCoordinate(point.longitude, point.latitude)
        val matches = mutableSetOf<TerritoryRegion>()
        features.forEach { feature ->
            when (feature.relationTo(coordinate)) {
                TerritoryPointRelation.BOUNDARY -> return null
                TerritoryPointRelation.INSIDE -> matches += feature.region
                TerritoryPointRelation.OUTSIDE -> Unit
            }
        }
        return when (matches.size) {
            0 -> TerritoryRegion.OTHER
            1 -> matches.single()
            else -> null
        }
    }

    companion object {
        const val ASSET_PATH = "approved_regions/territory_regions_v1.json"

        fun load(openAsset: (String) -> InputStream): ApprovedTerritoryClassifier = try {
            val content = openAsset(ASSET_PATH).use { stream ->
                readBoundedUtf8(stream)
            }
            fromJson(content)
        } catch (exception: TerritoryRegionDataException) {
            throw exception
        } catch (exception: Exception) {
            throw TerritoryRegionDataException("Approved region data could not be loaded", exception)
        }

        internal fun fromJson(content: String): ApprovedTerritoryClassifier = try {
            requireData(content.toByteArray(StandardCharsets.UTF_8).size <= MAX_DOCUMENT_BYTES) {
                "Region data document is too large"
            }
            val root = Json.parseToJsonElement(content).jsonObject
            root.requireExactKeys(ROOT_KEYS, "Region data root")
            requireData(root["schemaVersion"]?.jsonPrimitive?.intOrNull == SCHEMA_VERSION) {
                "Unsupported region data schema"
            }
            val version = root.requireText("regionDataVersion", 64)
            val source = root.requireMetadataLink("source")
            val license = root.requireMetadataLink("license")
            val review = root["review"]?.jsonObject ?: invalidData("Region review metadata is missing")
            review.requireExactKeys(REVIEW_KEYS, "review")
            val reviewAuthority = review.requireText("authority", 256)
            val reviewApprovalId = review.requireText("approvalId", 256)
            val reviewedAt = review.requireText("reviewedAt", 64)
            requireData(REVIEWED_AT_REGEX.matches(reviewedAt) && runCatching { Instant.parse(reviewedAt) }.isSuccess) {
                "reviewedAt must be a UTC instant"
            }
            requireData(review["approved"]?.jsonPrimitive?.booleanOrNull == true) {
                "Region data is not approved"
            }
            val expectedChecksum = root.requireText("checksumSha256", 64).lowercase()
            requireData(SHA256_REGEX.matches(expectedChecksum)) { "Region data checksum is invalid" }
            val featureValues = root["features"]?.jsonArray ?: invalidData("Region features are missing")
            requireData(featureValues.size in 1..MAX_FEATURES) { "Region data features are invalid" }

            val normalizedFeatures = mutableListOf<JsonElement>()
            val geometryBudget = GeometryBudget()
            val features = featureValues.map { featureValue ->
                val feature = featureValue.jsonObject
                feature.requireExactKeys(FEATURE_KEYS, "feature")
                val region = feature["region"]?.jsonPrimitive?.contentOrNull
                    ?.let { value -> POLYGON_REGIONS.singleOrNull { it.name == value } }
                    ?: invalidData("Region data contains an unsupported region")
                val geometry = feature["geometry"]?.jsonObject ?: invalidData("Region geometry is missing")
                geometry.requireExactKeys(GEOMETRY_KEYS, "geometry")
                val geometryType = geometry["type"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it == "Polygon" || it == "MultiPolygon" }
                    ?: invalidData("Region geometry type is invalid")
                val coordinates = geometry["coordinates"] ?: invalidData("Region geometry coordinates are missing")
                normalizedFeatures += JsonObject(
                    mapOf(
                        "region" to JsonPrimitive(region.name),
                        "geometry" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive(geometryType),
                                "coordinates" to coordinates,
                            ),
                        ),
                    ),
                )
                RegionFeature(region, parseGeometry(geometryType, coordinates, geometryBudget))
            }
            POLYGON_REGIONS.forEach { required ->
                requireData(features.any { it.region == required }) { "Region data is missing ${required.name}" }
            }

            val checksumPayload = JsonObject(
                mapOf(
                    "schemaVersion" to JsonPrimitive(SCHEMA_VERSION),
                    "regionDataVersion" to JsonPrimitive(version),
                    "source" to source.normalized,
                    "license" to license.normalized,
                    "review" to JsonObject(
                        mapOf(
                            "authority" to JsonPrimitive(reviewAuthority),
                            "approvalId" to JsonPrimitive(reviewApprovalId),
                            "reviewedAt" to JsonPrimitive(reviewedAt),
                            "approved" to JsonPrimitive(true),
                        ),
                    ),
                    "features" to JsonArray(normalizedFeatures),
                ),
            )
            val actualChecksum = computeRegionDataChecksum(checksumPayload)
            requireData(actualChecksum == expectedChecksum) { "Region data checksum does not match" }

            ApprovedTerritoryClassifier(
                metadata = TerritoryRegionMetadata(
                    version = version,
                    sourceName = source.name,
                    licenseName = license.name,
                    reviewAuthority = reviewAuthority,
                    reviewApprovalId = reviewApprovalId,
                    reviewedAt = reviewedAt,
                    checksumSha256 = expectedChecksum,
                ),
                features = features,
            )
        } catch (exception: TerritoryRegionDataException) {
            throw exception
        } catch (exception: Exception) {
            throw TerritoryRegionDataException("Approved region data is invalid", exception)
        }

        internal fun computeRegionDataChecksum(payload: JsonElement): String {
            val bytes = stableJson(payload).toByteArray(StandardCharsets.UTF_8)
            return MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

        internal fun stableJson(value: JsonElement): String = when (value) {
            JsonNull -> "null"
            is JsonArray -> value.joinToString(prefix = "[", postfix = "]", separator = ",", transform = ::stableJson)
            is JsonObject -> value.entries.sortedBy { it.key }
                .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, element) ->
                    "${JsonPrimitive(key)}:${stableJson(element)}"
                }
            is JsonPrimitive -> when {
                value.isString -> value.toString()
                value.booleanOrNull != null -> value.booleanOrNull.toString()
                else -> ecmaScriptNumber(value.double)
            }
        }

        private fun ecmaScriptNumber(value: Double): String {
            requireData(value.isFinite()) { "Region data contains a non-finite number" }
            if (value == 0.0) return "0"
            val absolute = abs(value)
            val decimal = BigDecimal.valueOf(value).stripTrailingZeros()
            if (absolute in 1e-6..<1e21) return decimal.toPlainString()
            val sign = if (value < 0.0) "-" else ""
            val unsigned = decimal.abs()
            val digits = unsigned.unscaledValue().toString()
            val exponent = digits.length - unsigned.scale() - 1
            val mantissa = if (digits.length == 1) digits else "${digits.first()}.${digits.drop(1)}"
            return "$sign$mantissa" + "e" + if (exponent >= 0) "+$exponent" else exponent.toString()
        }

        private fun parseGeometry(
            type: String,
            coordinates: JsonElement,
            budget: GeometryBudget,
        ): List<TerritoryPolygon> {
            val polygons = if (type == "Polygon") {
                listOf(coordinates)
            } else {
                coordinates.jsonArray
            }
            requireData(polygons.isNotEmpty()) { "Region geometry polygons are invalid" }
            budget.addPolygons(polygons.size)
            return polygons.map { polygonValue ->
                val ringValues = polygonValue.jsonArray
                requireData(ringValues.isNotEmpty()) { "Region polygon rings are invalid" }
                budget.addRings(ringValues.size)
                val rings = ringValues.map { parseRing(it, budget) }
                TerritoryPolygon(rings.first(), rings.drop(1))
            }
        }

        private fun parseRing(value: JsonElement, budget: GeometryBudget): TerritoryRing {
            val positions = value.jsonArray
            requireData(positions.size in 4..MAX_POSITIONS_PER_RING) { "Region ring size is invalid" }
            budget.addPositions(positions.size)
            val parsed = positions.map { positionValue ->
                val position = positionValue.jsonArray
                requireData(position.size == 2) { "Region position is invalid" }
                TerritoryCoordinate(
                    longitude = position[0].jsonPrimitive.double,
                    latitude = position[1].jsonPrimitive.double,
                ).also { coordinate ->
                    requireData(
                        coordinate.longitude.isFinite() && coordinate.longitude in -180.0..180.0 &&
                            coordinate.latitude.isFinite() && coordinate.latitude in -90.0..90.0,
                    ) { "Region position is outside WGS84 bounds" }
                }
            }
            requireData(parsed.first() == parsed.last()) { "Region ring is not closed" }
            requireData(parsed.dropLast(1).toSet().size >= 3) { "Region ring is degenerate" }
            requireData(abs(signedArea(parsed)) > AREA_EPSILON) { "Region ring has zero area" }
            return TerritoryRing(parsed)
        }

        private fun JsonObject.requireMetadataLink(name: String): MetadataLink {
            val record = this[name]?.jsonObject ?: invalidData("$name metadata is missing")
            record.requireExactKeys(METADATA_LINK_KEYS, name)
            val linkName = record.requireText("name", 256)
            val url = record.requireText("url", 2_048)
            val parsedUrl = runCatching { URI(url) }.getOrNull()
            requireData(
                parsedUrl?.isAbsolute == true &&
                    !parsedUrl.isOpaque &&
                    parsedUrl.scheme.equals("https", ignoreCase = true) &&
                    url.startsWith("https://", ignoreCase = true) &&
                    !parsedUrl.host.isNullOrBlank() &&
                    parsedUrl.rawUserInfo == null,
            ) { "$name URL must use an absolute credential-free HTTPS URL" }
            return MetadataLink(
                name = linkName,
                normalized = JsonObject(mapOf("name" to JsonPrimitive(linkName), "url" to JsonPrimitive(url))),
            )
        }

        private fun JsonObject.requireExactKeys(expectedKeys: Set<String>, name: String) {
            requireData(keys == expectedKeys) { "$name contains unsupported or missing fields" }
        }

        private fun JsonObject.requireText(name: String, maximumLength: Int): String =
            this[name]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotEmpty() && it.length <= maximumLength }
                ?: invalidData("$name is invalid")

        private inline fun requireData(value: Boolean, message: () -> String) {
            if (!value) invalidData(message())
        }

        private fun readBoundedUtf8(stream: InputStream): String {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                requireData(total <= MAX_DOCUMENT_BYTES) { "Region data document is too large" }
                output.write(buffer, 0, read)
            }
            return output.toString(StandardCharsets.UTF_8.name())
        }

        private fun invalidData(message: String): Nothing = throw TerritoryRegionDataException(message)

        private class GeometryBudget {
            private var polygons = 0
            private var rings = 0
            private var positions = 0

            fun addPolygons(count: Int) {
                polygons += count
                requireData(polygons <= MAX_POLYGONS) { "Region data has too many polygons" }
            }

            fun addRings(count: Int) {
                rings += count
                requireData(rings <= MAX_RINGS) { "Region data has too many rings" }
            }

            fun addPositions(count: Int) {
                positions += count
                requireData(positions <= MAX_TOTAL_POSITIONS) { "Region data has too many positions" }
            }
        }

        private val POLYGON_REGIONS = setOf(
            TerritoryRegion.MAINLAND_CHINA,
            TerritoryRegion.CHINA_OFFICIAL_MAP_ONLY,
            TerritoryRegion.HONG_KONG_SAR,
            TerritoryRegion.MACAO_SAR,
            TerritoryRegion.CHINA_TAIWAN,
            TerritoryRegion.JAPAN,
        )
        private val ROOT_KEYS = setOf(
            "schemaVersion",
            "regionDataVersion",
            "source",
            "license",
            "review",
            "checksumSha256",
            "features",
        )
        private val METADATA_LINK_KEYS = setOf("name", "url")
        private val REVIEW_KEYS = setOf("authority", "approvalId", "reviewedAt", "approved")
        private val FEATURE_KEYS = setOf("region", "geometry")
        private val GEOMETRY_KEYS = setOf("type", "coordinates")
        private const val SCHEMA_VERSION = 1
        private const val MAX_DOCUMENT_BYTES = 64 * 1024 * 1024
        private const val MAX_FEATURES = 64
        private const val MAX_POLYGONS = 10_000
        private const val MAX_RINGS = 5_000
        private const val MAX_TOTAL_POSITIONS = 2_000_000
        private const val MAX_POSITIONS_PER_RING = 1_000_000
        private const val EPSILON = 1e-10
        private const val AREA_EPSILON = 1e-14
        private val SHA256_REGEX = Regex("^[a-f0-9]{64}$")
        private val REVIEWED_AT_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{3})?Z$")
    }
}

class FailClosedTerritoryClassifier private constructor(
    private val approved: ApprovedTerritoryClassifier?,
) : TerritoryClassifier {
    override val metadata: TerritoryRegionMetadata?
        get() = approved?.metadata

    override fun classify(point: GeoPoint): TerritoryRegion? = approved?.classify(point)

    companion object {
        fun load(openAsset: (String) -> InputStream): FailClosedTerritoryClassifier =
            FailClosedTerritoryClassifier(runCatching { ApprovedTerritoryClassifier.load(openAsset) }.getOrNull())
    }
}

private data class MetadataLink(val name: String, val normalized: JsonObject)

private data class RegionFeature(
    val region: TerritoryRegion,
    val polygons: List<TerritoryPolygon>,
) {
    fun relationTo(point: TerritoryCoordinate): TerritoryPointRelation {
        var inside = false
        polygons.forEach { polygon ->
            when (polygon.relationTo(point)) {
                TerritoryPointRelation.BOUNDARY -> return TerritoryPointRelation.BOUNDARY
                TerritoryPointRelation.INSIDE -> inside = true
                TerritoryPointRelation.OUTSIDE -> Unit
            }
        }
        return if (inside) TerritoryPointRelation.INSIDE else TerritoryPointRelation.OUTSIDE
    }
}

private data class TerritoryPolygon(val outer: TerritoryRing, val holes: List<TerritoryRing>) {
    fun relationTo(point: TerritoryCoordinate): TerritoryPointRelation {
        if (!outer.bounds.contains(point)) return TerritoryPointRelation.OUTSIDE
        when (val relation = outer.relationTo(point)) {
            TerritoryPointRelation.OUTSIDE,
            TerritoryPointRelation.BOUNDARY -> return relation
            TerritoryPointRelation.INSIDE -> Unit
        }
        holes.forEach { hole ->
            when (hole.relationTo(point)) {
                TerritoryPointRelation.BOUNDARY -> return TerritoryPointRelation.BOUNDARY
                TerritoryPointRelation.INSIDE -> return TerritoryPointRelation.OUTSIDE
                TerritoryPointRelation.OUTSIDE -> Unit
            }
        }
        return TerritoryPointRelation.INSIDE
    }
}

private data class TerritoryRing(val positions: List<TerritoryCoordinate>) {
    val bounds = TerritoryBounds.enclosing(positions)

    fun relationTo(point: TerritoryCoordinate): TerritoryPointRelation {
        if (!bounds.contains(point)) return TerritoryPointRelation.OUTSIDE
        var inside = false
        for (index in 1 until positions.size) {
            val from = positions[index - 1]
            val to = positions[index]
            if (onSegment(point, from, to)) return TerritoryPointRelation.BOUNDARY
            val crosses = (from.latitude > point.latitude) != (to.latitude > point.latitude)
            if (crosses) {
                val longitude = (to.longitude - from.longitude) *
                    (point.latitude - from.latitude) / (to.latitude - from.latitude) + from.longitude
                if (point.longitude < longitude) inside = !inside
            }
        }
        return if (inside) TerritoryPointRelation.INSIDE else TerritoryPointRelation.OUTSIDE
    }
}

private data class TerritoryCoordinate(val longitude: Double, val latitude: Double)

private data class TerritoryBounds(
    val minimumLongitude: Double,
    val minimumLatitude: Double,
    val maximumLongitude: Double,
    val maximumLatitude: Double,
) {
    fun contains(point: TerritoryCoordinate): Boolean =
        point.longitude >= minimumLongitude - EPSILON &&
            point.longitude <= maximumLongitude + EPSILON &&
            point.latitude >= minimumLatitude - EPSILON &&
            point.latitude <= maximumLatitude + EPSILON

    companion object {
        fun enclosing(points: List<TerritoryCoordinate>) = TerritoryBounds(
            minimumLongitude = points.minOf(TerritoryCoordinate::longitude),
            minimumLatitude = points.minOf(TerritoryCoordinate::latitude),
            maximumLongitude = points.maxOf(TerritoryCoordinate::longitude),
            maximumLatitude = points.maxOf(TerritoryCoordinate::latitude),
        )
    }
}

private enum class TerritoryPointRelation { OUTSIDE, BOUNDARY, INSIDE }

private fun signedArea(points: List<TerritoryCoordinate>): Double = (1 until points.size).sumOf { index ->
    val from = points[index - 1]
    val to = points[index]
    from.longitude * to.latitude - to.longitude * from.latitude
} / 2.0

private fun onSegment(
    point: TerritoryCoordinate,
    from: TerritoryCoordinate,
    to: TerritoryCoordinate,
): Boolean {
    val deltaLongitude = to.longitude - from.longitude
    val deltaLatitude = to.latitude - from.latitude
    val cross = (point.longitude - from.longitude) * deltaLatitude -
        (point.latitude - from.latitude) * deltaLongitude
    val tolerance = EPSILON * (abs(deltaLongitude) + abs(deltaLatitude) + 1)
    return abs(cross) <= tolerance &&
        point.longitude >= minOf(from.longitude, to.longitude) - EPSILON &&
        point.longitude <= maxOf(from.longitude, to.longitude) + EPSILON &&
        point.latitude >= minOf(from.latitude, to.latitude) - EPSILON &&
        point.latitude <= maxOf(from.latitude, to.latitude) + EPSILON
}

private const val EPSILON = 1e-10
