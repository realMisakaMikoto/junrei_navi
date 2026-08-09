package cn.anitabi.navigator.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredTourV2Test {
    private val json = Json { explicitNulls = false }

    @Test
    fun `stored tour keeps user state but excludes resolved route content`() {
        val plan = legacyPlan()
        val progress = NavigationProgress(
            tourId = plan.id,
            legIndex = 1,
            stepIndex = 2,
            completedPointIds = setOf("101::a"),
            state = NavigationState.NAVIGATING,
            lastRerouteEpochMillis = 1234L,
        )

        val stored = StoredTourV2.from(plan, progress)
        val encoded = json.encodeToString(StoredTourV2.serializer(), stored)

        assertEquals(listOf(101L, 202L), stored.selectedAnimes.map(Anime::subjectId))
        assertEquals(listOf("101::a", "202::b"), stored.manualOrderPointIds)
        assertEquals(setOf("101::a"), stored.completedPointIds)
        assertEquals(NavigationState.NAVIGATING, stored.navigationState)
        assertTrue(stored.toUnresolvedPlan().legs.isEmpty())
        assertFalse(encoded.contains("geometry"))
        assertFalse(encoded.contains("steps"))
        assertFalse(encoded.contains("estimatedDurationSeconds"))
        assertFalse(encoded.contains("Google polyline"))
    }

    @Test
    fun `stored tour conversion is idempotent`() {
        val first = StoredTourV2.from(legacyPlan(), null)
        val decoded = json.decodeFromString(StoredTourV2.serializer(), json.encodeToString(first))

        assertEquals(first, decoded)
        assertEquals(first.manualOrderPointIds, decoded.toUnresolvedPlan().orderedPoints.map(PilgrimagePoint::id))
    }

    @Test
    fun `v0_2_4 records without fallback marker remain normal routes`() {
        val planJson = json.encodeToString(TourPlan.serializer(), legacyPlan())
        val storedJson = json.encodeToString(
            StoredTourV2.serializer(),
            StoredTourV2.from(legacyPlan(), null),
        )

        assertFalse(planJson.contains("externalRouteFallback"))
        assertFalse(storedJson.contains("externalRouteFallback"))
        assertFalse(json.decodeFromString(TourPlan.serializer(), planJson).externalRouteFallback)
        val restored = json.decodeFromString(StoredTourV2.serializer(), storedJson)
        assertFalse(restored.externalRouteFallback)
        assertFalse(restored.toUnresolvedPlan().externalRouteFallback)
    }

    @Test
    fun `stored tour keeps user owned transit time and preference without route content`() {
        val plan = legacyPlan().copy(
            mode = TravelMode.TRANSIT,
            departureTime = "2026-07-31T09:05:00+09:00",
            arrivalTime = "2026-07-31T11:50:00+09:00",
            transitTimeMode = TransitTimeMode.ARRIVE_BY,
            transitAnchorTime = "2026-07-31T12:00:00+09:00",
            transitRoutingPreference = TransitRoutingPreference.FEWER_TRANSFERS,
            transitTravelModes = setOf(TransitTravelMode.BUS, TransitTravelMode.TRAIN),
        )

        val stored = StoredTourV2.from(plan, null)
        val encoded = json.encodeToString(StoredTourV2.serializer(), stored)
        val restored = json.decodeFromString(
            StoredTourV2.serializer(),
            encoded,
        ).toUnresolvedPlan()

        assertEquals(TransitTimeMode.ARRIVE_BY, restored.transitTimeMode)
        assertEquals(TransitRoutingPreference.FEWER_TRANSFERS, restored.transitRoutingPreference)
        assertEquals(setOf(TransitTravelMode.BUS, TransitTravelMode.TRAIN), restored.transitTravelModes)
        assertEquals("2026-07-31T12:00:00+09:00", restored.transitAnchorTime)
        assertNull(restored.departureTime)
        assertNull(restored.arrivalTime)
        assertFalse(encoded.contains("2026-07-31T09:05:00+09:00"))
        assertFalse(encoded.contains("2026-07-31T11:50:00+09:00"))
        assertTrue(restored.legs.isEmpty())
    }

    @Test
    fun `stored v2 transit departure without new fields migrates as depart at`() {
        val legacyStored = StoredTourV2.from(
            legacyPlan().copy(
                mode = TravelMode.TRANSIT,
                departureTime = "2026-07-31T09:00:00+09:00",
            ),
            null,
        ).copy(
            departureTime = "2026-07-31T09:00:00+09:00",
            transitTimeMode = null,
            transitAnchorTime = null,
        )

        val encoded = json.encodeToString(StoredTourV2.serializer(), legacyStored)
        val restored = json.decodeFromString(StoredTourV2.serializer(), encoded).toUnresolvedPlan()

        assertFalse(encoded.contains("transitTimeMode"))
        assertEquals(TransitTimeMode.DEPART_AT, restored.transitTimeMode)
        assertEquals("2026-07-31T09:00:00+09:00", restored.transitAnchorTime)
        assertTrue(restored.transitTravelModes.isEmpty())
    }

    @Test
    fun `legacy plan transit departure migrates into the user anchor`() {
        val legacyPlanJson = json.encodeToString(
            TourPlan.serializer(),
            legacyPlan().copy(
                mode = TravelMode.TRANSIT,
                departureTime = "2026-07-31T09:00:00+09:00",
            ),
        )

        val decoded = json.decodeFromString(TourPlan.serializer(), legacyPlanJson)
        val stored = StoredTourV2.from(decoded, null)

        assertFalse(legacyPlanJson.contains("transitTimeMode"))
        assertEquals(TransitTimeMode.DEPART_AT, stored.transitTimeMode)
        assertEquals("2026-07-31T09:00:00+09:00", stored.transitAnchorTime)
    }

    private fun legacyPlan(): TourPlan {
        val first = PilgrimagePoint("101::a", "《作品甲》· A", GeoPoint(35.0, 139.0))
        val second = PilgrimagePoint("202::b", "《作品乙》· B", GeoPoint(35.1, 139.1))
        return TourPlan(
            id = "legacy-tour",
            anime = Anime(0, "作品甲 + 作品乙", "2 部作品联合巡礼"),
            selectedPoints = listOf(first, second),
            orderedPoints = listOf(first, second),
            legs = listOf(
                TourLeg(
                    from = first.coordinate,
                    to = second.coordinate,
                    mode = TravelMode.WALK,
                    geometry = listOf(first.coordinate, GeoPoint(35.05, 139.05), second.coordinate),
                    steps = listOf(RouteStep("Google polyline", 10.0, 20.0)),
                    distanceMeters = 10.0,
                    durationSeconds = 20.0,
                    source = "legacy resolved route",
                    destinationPointId = second.id,
                ),
            ),
            mode = TravelMode.WALK,
            objective = RouteObjective.FASTEST,
            endPolicy = EndPolicy.OPEN,
            estimatedDurationSeconds = 20.0,
            attribution = listOf("legacy attribution"),
            initialStart = first.coordinate,
        )
    }
}
