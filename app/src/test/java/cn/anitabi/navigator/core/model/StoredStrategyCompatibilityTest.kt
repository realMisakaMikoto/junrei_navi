package cn.anitabi.navigator.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoredStrategyCompatibilityTest {
    @Test
    fun `existing serialized strategy names remain unchanged`() {
        val json = Json

        assertEquals(
            TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES,
            json.decodeFromString<TransitExecutionStrategy>("\"IN_APP_GOOGLE_ROUTES\""),
        )
        assertEquals(
            TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN,
            json.decodeFromString<TransitExecutionStrategy>("\"EXTERNAL_GOOGLE_MAPS_JAPAN\""),
        )
        assertEquals(
            "\"EXTERNAL_GOOGLE_MAPS_JAPAN\"",
            json.encodeToString(TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN),
        )
    }

    @Test
    fun `AMap strategy uses its exact new serialized name`() {
        assertEquals(
            "\"EXTERNAL_AMAP_MAINLAND\"",
            Json.encodeToString(TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND),
        )
    }

    @Test
    fun `pre-v0_2_5 stored tour can omit provider metadata`() {
        val encoded = Json.encodeToString(
            StoredTourV2(
                id = "TEST_ONLY",
                displayAnime = Anime(1, "TEST_ONLY"),
                selectedAnimes = listOf(Anime(1, "TEST_ONLY")),
                selectedPoints = listOf(
                    PilgrimagePoint("a", "A", GeoPoint(1.0, 1.0)),
                    PilgrimagePoint("b", "B", GeoPoint(2.0, 2.0)),
                ),
                manualOrderPointIds = listOf("a", "b"),
                start = GeoPoint(1.0, 1.0),
                mode = TravelMode.WALK,
                objective = RouteObjective.FASTEST,
                endPolicy = EndPolicy.OPEN,
            ),
        ).replace(Regex(",?\"(?:mapProvider|regionDataVersion)\":null"), "")

        val restored = Json { ignoreUnknownKeys = true }.decodeFromString<StoredTourV2>(encoded)

        assertNull(restored.mapProvider)
        assertNull(restored.regionDataVersion)
    }
}
