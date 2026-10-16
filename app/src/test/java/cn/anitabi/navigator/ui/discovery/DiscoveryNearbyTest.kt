package cn.anitabi.navigator.ui.discovery

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.TerritoryRegion
import cn.anitabi.navigator.core.routing.TourOptimizer
import cn.anitabi.navigator.data.discovery.DiscoveryLoadCounter
import cn.anitabi.navigator.data.discovery.DiscoveryLoadOutcome
import cn.anitabi.navigator.data.discovery.DiscoveryLoadPhase
import cn.anitabi.navigator.data.discovery.DiscoveryLoadTrace
import cn.anitabi.navigator.data.discovery.DiscoveryLoadTraceConfig
import cn.anitabi.navigator.data.discovery.DiscoveryPoint
import cn.anitabi.navigator.data.discovery.DiscoverySnapshot
import cn.anitabi.navigator.data.discovery.DiscoverySubject
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryNearbyTest {
    private val location = GeoPoint(1.0, 2.0)

    @Test fun completeSyntheticCataloguesMeasureEachDistanceOnceAndKeepEveryMember() {
        for (size in listOf(1_000, 10_000, 100_000)) {
            val points = List(size) { index -> point(index.toString(), GeoPoint(1.0 + index * .000001, 2.0)) }
            val trace = trace()
            val result = calculateDiscoveryNearby(points.shuffled(Random(37)), key(), trace)
            assertEquals(points.map { it.id }, result.entries.map { it.pointId })
            assertEquals(size.toLong(), trace.snapshot().counters[DiscoveryLoadCounter.DISTANCE_COMPUTATION_COUNT])
            assertEquals(1L, trace.snapshot().counters[DiscoveryLoadCounter.NEARBY_SORT_COUNT])
            result.entries.forEachIndexed { index, entry ->
                assertEquals(TourOptimizer.haversineMeters(location, points[index].coordinate), entry.distanceMeters, 0.0)
            }
        }
    }

    @Test fun equalDistancesUseStableIdsAcrossSourceOrderAndOnlyEligibleMembersAreMeasured() {
        val points = listOf(point("c"), point("a"), point("b"), point("other", subjectId = 2))
        for (seed in 0 until 20) {
            val trace = trace()
            val result = calculateDiscoveryNearby(points.shuffled(Random(seed)), key(setOf(1)), trace)
            assertEquals(listOf("1::a", "1::b", "1::c"), result.entries.map { it.pointId })
            assertTrue(result.entries.all { it.distanceMeters == 0.0 })
            assertEquals(3L, trace.snapshot().counters[DiscoveryLoadCounter.DISTANCE_COMPUTATION_COUNT])
        }
    }

    @Test fun emptyFilterResultHasAnExplicitEmptySpanAndNoDistanceCalculations() {
        val trace = trace()
        assertTrue(calculateDiscoveryNearby(listOf(point("a")), key(setOf(2)), trace).entries.isEmpty())
        assertEquals(0L, trace.snapshot().counters[DiscoveryLoadCounter.DISTANCE_COMPUTATION_COUNT])
        assertEquals(DiscoveryLoadOutcome.EMPTY, trace.snapshot().events.single().outcome)
    }

    @Test fun cancellationDuringDistanceWorkStopsBeforeEveryDistanceIsMeasured() {
        val size = 100
        val trace = trace()
        var checks = 0
        expectCancellation {
            calculateDiscoveryNearby(List(size) { point(it.toString()) }, key(), trace) {
                if (++checks == size + 17) throw CancellationException("Synthetic distance cancellation")
            }
        }
        assertTrue(trace.snapshot().counters.getValue(DiscoveryLoadCounter.DISTANCE_COMPUTATION_COUNT) in 1 until size.toLong())
        assertCancelled(trace)
    }

    @Test fun cancellationDuringComparisonsStopsAnAlreadyMeasuredCatalogue() {
        val size = 100
        val trace = trace()
        var checks = 0
        expectCancellation {
            calculateDiscoveryNearby(List(size) { point(it.toString()) }, key(), trace) {
                if (++checks == size * 2 + 4) throw CancellationException("Synthetic comparison cancellation")
            }
        }
        assertEquals(size.toLong(), trace.snapshot().counters[DiscoveryLoadCounter.DISTANCE_COMPUTATION_COUNT])
        assertCancelled(trace)
    }

    @Test fun nearbyRevisionIgnoresMetadataGenerationOrderingAndProviderChanges() {
        val first = prepareDiscoveryData(snapshot(listOf(point("a"), point("b"))), null, { TerritoryRegion.OTHER })
        val updated = first.snapshot.copy(version = "synthetic-v2", checkedAtMillis = 200,
            points = first.snapshot.points.reversed().map { it.copy(name = "SYNTHETIC_NEW", imageUrl = "https://image.anitabi.cn/points/synthetic.jpg") })
        val second = prepareDiscoveryData(updated, first, { null })
        assertEquals(first.nearbyRevision, second.nearbyRevision)
        assertNotEquals(first.mapDataVersion, second.mapDataVersion)
        assertTrue(second.mapPoints.isEmpty())
        assertEquals(first.pointsById.keys, second.pointsById.keys)
        assertEquals(second.pointsById.keys,
            calculateDiscoveryNearby(second.pointsById.values, key()).entries.map { it.pointId }.toSet())
    }

    @Test fun nearbyRevisionChangesForCoordinatesMembersAndFilterMembership() {
        val first = prepareDiscoveryData(snapshot(listOf(point("a"), point("b"))), null, { TerritoryRegion.OTHER })
        val changes = listOf(
            first.snapshot.points.map { if (it.rawId == "a") it.copy(coordinate = GeoPoint(2.0, 3.0)) else it },
            first.snapshot.points + point("c"),
            first.snapshot.points.drop(1),
            first.snapshot.points.map { if (it.rawId == "a") it.copy(subjectId = 2) else it },
        )
        changes.forEach { points ->
            val result = prepareDiscoveryData(snapshot(points), first, { TerritoryRegion.OTHER })
            assertEquals(first.nearbyRevision + 1, result.nearbyRevision)
        }
    }

    private fun expectCancellation(block: () -> Unit) {
        try { block(); error("Cancellation must escape without a result") } catch (_: CancellationException) { }
    }

    private fun assertCancelled(trace: DiscoveryLoadTrace) {
        assertTrue(trace.snapshot().pendingSpans.isEmpty())
        assertEquals(DiscoveryLoadPhase.NEARBY_SORT, trace.snapshot().events.single().phase)
        assertEquals(DiscoveryLoadOutcome.CANCELLED, trace.snapshot().events.single().outcome)
    }

    private fun key(filters: Set<Long> = emptySet()) = DiscoveryNearbyKey(1, location, filters)
    private fun trace() = DiscoveryLoadTrace(DiscoveryLoadTraceConfig(enabled = true))
    private fun point(rawId: String, coordinate: GeoPoint = location, subjectId: Long = 1) = DiscoveryPoint(subjectId, rawId, coordinate)
    private fun snapshot(points: List<DiscoveryPoint>) = DiscoverySnapshot("synthetic-v1", 100, 1,
        listOf(DiscoverySubject(Anime(1, "SYNTHETIC"), pointIds = points.map { it.id })), points)
}
