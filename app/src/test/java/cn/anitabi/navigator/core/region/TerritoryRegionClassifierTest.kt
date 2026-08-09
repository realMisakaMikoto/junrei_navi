package cn.anitabi.navigator.core.region

import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.TerritoryRegion
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class TerritoryRegionClassifierTest {
    @Test
    fun `shared TEST_ONLY fixture checksum and classifications match backend vectors`() {
        val classifier = ApprovedTerritoryClassifier.fromJson(regionFixture())

        assertEquals(REGION_SHA, classifier.metadata.checksumSha256)
        assertEquals(TerritoryRegion.MAINLAND_CHINA, classifier.classify(GeoPoint(20.5, 100.5)))
        assertEquals(TerritoryRegion.CHINA_OFFICIAL_MAP_ONLY, classifier.classify(GeoPoint(20.5, 102.5)))
        assertEquals(TerritoryRegion.HONG_KONG_SAR, classifier.classify(GeoPoint(20.5, 104.5)))
        assertEquals(TerritoryRegion.MACAO_SAR, classifier.classify(GeoPoint(20.5, 106.5)))
        assertEquals(TerritoryRegion.CHINA_TAIWAN, classifier.classify(GeoPoint(20.5, 108.5)))
        assertEquals(TerritoryRegion.JAPAN, classifier.classify(GeoPoint(20.5, 110.5)))
        assertNull(classifier.classify(GeoPoint(20.5, 100.0)))
        assertEquals(TerritoryRegion.OTHER, classifier.classify(GeoPoint(0.0, 0.0)))
    }

    @Test
    fun `canonical numbers use the fixed cross-runtime ECMAScript bytes`() {
        val vector = Json.parseToJsonElement(resource("region/canonical_numbers_TEST_ONLY.json")).jsonObject
        val payload = requireNotNull(vector["payload"])

        assertEquals(CANONICAL_NUMBERS, ApprovedTerritoryClassifier.stableJson(payload))
        assertEquals(
            vector.getValue("sha256").toString().trim('"'),
            ApprovedTerritoryClassifier.computeRegionDataChecksum(payload),
        )
    }

    @Test
    fun `corrupt checksum fails closed`() {
        val corrupt = regionFixture().replace(REGION_SHA, "0".repeat(64))

        assertThrows(TerritoryRegionDataException::class.java) {
            ApprovedTerritoryClassifier.fromJson(corrupt)
        }
    }

    @Test
    fun `overlapping territories are unresolved`() {
        val overlapping = mutateFixture { root ->
            val features = root.getValue("features").jsonArray
            val mainlandGeometry = features.first().jsonObject.getValue("geometry")
            val changed = features.map { featureValue ->
                val feature = featureValue.jsonObject
                if (feature.getValue("region") == JsonPrimitive("JAPAN")) {
                    JsonObject(feature + ("geometry" to mainlandGeometry))
                } else {
                    feature
                }
            }
            root["features"] = JsonArray(changed)
        }

        val classifier = ApprovedTerritoryClassifier.fromJson(overlapping)
        assertNull(classifier.classify(GeoPoint(20.5, 100.5)))
    }

    @Test
    fun `metadata URLs must be hierarchical credential-free HTTPS URLs with hosts`() {
        listOf(
            "http://example.test/source",
            "https:opaque",
            "https:///missing-authority",
            "https://user:secret@example.test/source",
            "https://@example.test/source",
        ).forEach { url ->
            val invalid = mutateFixture { root ->
                val source = root.getValue("source").jsonObject
                root["source"] = JsonObject(source + ("url" to JsonPrimitive(url)))
            }

            assertThrows(TerritoryRegionDataException::class.java) {
                ApprovedTerritoryClassifier.fromJson(invalid)
            }
        }
    }

    @Test
    fun `fixed region schema objects reject unknown fields`() {
        val variants = listOf(
            mutateFixtureKeepingChecksum { root ->
                root["unexpected"] = JsonPrimitive("TEST_ONLY")
            },
            mutateFixtureKeepingChecksum { root ->
                val source = root.getValue("source").jsonObject
                root["source"] = JsonObject(source + ("unexpected" to JsonPrimitive("TEST_ONLY")))
            },
            mutateFixtureKeepingChecksum { root ->
                val license = root.getValue("license").jsonObject
                root["license"] = JsonObject(license + ("unexpected" to JsonPrimitive("TEST_ONLY")))
            },
            mutateFixtureKeepingChecksum { root ->
                val review = root.getValue("review").jsonObject
                root["review"] = JsonObject(review + ("unexpected" to JsonPrimitive("TEST_ONLY")))
            },
            mutateFixtureKeepingChecksum { root ->
                val features = root.getValue("features").jsonArray.toMutableList()
                features[0] = JsonObject(
                    features.first().jsonObject + ("unexpected" to JsonPrimitive("TEST_ONLY")),
                )
                root["features"] = JsonArray(features)
            },
            mutateFixtureKeepingChecksum { root ->
                val features = root.getValue("features").jsonArray.toMutableList()
                val feature = features.first().jsonObject
                val geometry = feature.getValue("geometry").jsonObject
                features[0] = JsonObject(
                    feature + (
                        "geometry" to JsonObject(
                            geometry + ("unexpected" to JsonPrimitive("TEST_ONLY")),
                        )
                    ),
                )
                root["features"] = JsonArray(features)
            },
        )

        variants.forEach { invalid ->
            val exception = assertThrows(TerritoryRegionDataException::class.java) {
                ApprovedTerritoryClassifier.fromJson(invalid)
            }
            assertTrue(exception.message, exception.message?.contains("unsupported or missing fields") == true)
        }
    }

    @Test
    fun `missing production asset exposes an unavailable classifier instead of throwing`() {
        val classifier = FailClosedTerritoryClassifier.load { throw FileNotFoundException("TEST_ONLY missing") }

        assertNull(classifier.metadata)
        assertNull(classifier.classify(GeoPoint(20.5, 100.5)))
    }

    @Test
    fun `packaged production region asset is approved when supplied by release CI`() {
        val asset = productionAssetPath()
        assumeTrue("Production region asset is not present in this checkout", asset != null)

        val classifier = ApprovedTerritoryClassifier.load { requestedPath ->
            require(requestedPath == ApprovedTerritoryClassifier.ASSET_PATH)
            Files.newInputStream(requireNotNull(asset))
        }
        val expectedVersion = System.getenv("ANITABI_V025_REGION_DATA_VERSION")
            ?.takeIf(String::isNotBlank)
        if (expectedVersion != null) {
            assertEquals(expectedVersion, classifier.metadata.version)
        }
        assertEquals(TerritoryRegion.MAINLAND_CHINA, classifier.classify(GeoPoint(39.9042, 116.4074)))
        assertEquals(TerritoryRegion.CHINA_OFFICIAL_MAP_ONLY, classifier.classify(GeoPoint(25.75, 123.5)))
        assertEquals(TerritoryRegion.HONG_KONG_SAR, classifier.classify(GeoPoint(22.3193, 114.1694)))
        assertEquals(TerritoryRegion.MACAO_SAR, classifier.classify(GeoPoint(22.1987, 113.5439)))
        assertEquals(TerritoryRegion.CHINA_TAIWAN, classifier.classify(GeoPoint(25.033, 121.5654)))
        assertEquals(TerritoryRegion.JAPAN, classifier.classify(GeoPoint(35.6762, 139.6503)))
        assertEquals(TerritoryRegion.OTHER, classifier.classify(GeoPoint(40.7128, -74.006)))
    }

    private fun mutateFixture(change: (MutableMap<String, JsonElement>) -> Unit): String {
        val root = Json.parseToJsonElement(regionFixture()).jsonObject.toMutableMap()
        root.remove("checksumSha256")
        change(root)
        root["checksumSha256"] = JsonPrimitive(
            ApprovedTerritoryClassifier.computeRegionDataChecksum(JsonObject(root)),
        )
        return JsonObject(root).toString()
    }

    private fun mutateFixtureKeepingChecksum(change: (MutableMap<String, JsonElement>) -> Unit): String {
        val root = Json.parseToJsonElement(regionFixture()).jsonObject.toMutableMap()
        change(root)
        return JsonObject(root).toString()
    }

    private fun regionFixture(): String = resource("region/territory_regions_v1_TEST_ONLY.json")

    private fun productionAssetPath(): Path? = listOf(
        Paths.get("src", "main", "assets", ApprovedTerritoryClassifier.ASSET_PATH),
        Paths.get("app", "src", "main", "assets", ApprovedTerritoryClassifier.ASSET_PATH),
    ).firstOrNull { path -> Files.isRegularFile(path) }

    private fun resource(path: String): String = requireNotNull(javaClass.classLoader?.getResource(path))
        .readText()

    private companion object {
        const val REGION_SHA = "6064f6807cf334739553285108ae4b7247b8079253aa27a75b0ca816cfbf8873"
        const val CANONICAL_NUMBERS =
            "{\"belowOneMicro\":1e-7,\"fraction\":12.3405,\"largePlain\":100000000000000000000," +
                "\"negativeExponent\":-2.5e-7,\"negativeZero\":0,\"oneMicro\":0.000001," +
                "\"scientific\":1e+21,\"zero\":0}"
    }
}
