package cn.anitabi.navigator.ui.discovery.map

import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.system.measureNanoTime
import java.util.concurrent.CancellationException

/** All coordinates, subjects and identities here are synthetic fixtures. */
class DiscoveryMapEngineTest {
    private val key = DiscoveryIndexKey("synthetic-v1", MapProvider.GOOGLE)

    @Test fun dateLineQueryReturnsBothSidesWithoutDuplicates() {
        val fixtures = listOf(
            IndexedDiscoveryPoint("1::a", GeoPoint(1.0, 179.9)),
            IndexedDiscoveryPoint("1::b", GeoPoint(1.0, -179.9)),
            IndexedDiscoveryPoint("2::a", GeoPoint(1.0, 0.0)),
        )
        val index = DiscoverySpatialIndex(key, fixtures)
        assertEquals(setOf("1::a", "1::b"), index.query(DiscoveryGeoBounds(-2.0, 170.0, 2.0, -170.0)).map { it.id }.toSet())
        assertEquals(3, index.query(DiscoveryGeoBounds(-90.0, -180.0, 90.0, 180.0)).size)
    }

    @Test fun indexMatchesBruteForceForWrappedAndOrdinaryQueries() {
        val random = Random(2718)
        val fixtures = List(10_000) { IndexedDiscoveryPoint("synthetic::$it", GeoPoint(random.nextDouble(-89.0, 89.0), random.nextDouble(-180.0, 180.0))) }
        val index = DiscoverySpatialIndex(key, fixtures)
        repeat(100) {
            val south = random.nextDouble(-89.0, 80.0)
            val north = (south + random.nextDouble(0.0, 9.0)).coerceAtMost(90.0)
            val bounds = DiscoveryGeoBounds(south, random.nextDouble(-180.0, 180.0), north, random.nextDouble(-180.0, 180.0))
            assertEquals(fixtures.filter { bounds.contains(it.coordinate) }.map { it.id }.toSet(), index.query(bounds).map { it.id }.toSet())
        }
    }

    @Test(expected = IllegalArgumentException::class) fun duplicateCompositeIdentityIsRejected() {
        DiscoverySpatialIndex(key, List(2) { IndexedDiscoveryPoint("1::same", GeoPoint(0.0, 0.0)) })
    }

    @Test fun sameBarePointIdInDifferentSubjectsIsPreserved() {
        val entries = listOf("1::same", "2::same").map { IndexedDiscoveryPoint(it, GeoPoint(0.0, 0.0)) }
        assertEquals(2, DiscoverySpatialIndex(key, entries).size)
    }

    @Test fun cameraDataAndProviderGenerationsRejectOldResults() {
        val generation = DiscoveryRequestGeneration()
        val first = generation.next(key)
        val moved = generation.next(key)
        assertFalse(generation.accepts(first))
        assertTrue(generation.accepts(moved))
        val changedData = generation.next(key.copy(dataVersion = "synthetic-v2"))
        assertFalse(generation.accepts(moved))
        val changedProvider = generation.next(key.copy(provider = MapProvider.AMAP))
        assertFalse(generation.accepts(changedData))
        assertTrue(generation.accepts(changedProvider))
        generation.invalidate()
        assertFalse(generation.accepts(changedProvider))
    }

    @Test fun displayConversionRunsOnceAndDoesNotMutateCanonicalPrecision() {
        val source = GeoPoint(1.123456789123, 2.987654321987)
        val seen = mutableListOf<GeoPoint>()
        val cache = DiscoveryDisplayCoordinates { input ->
            seen += input
            GeoPoint(input.latitude + .01, input.longitude + .02)
        }
        val first = cache.get("synthetic::one", source)
        assertEquals(first, cache.get("synthetic::one", source.copy()))
        assertEquals(1, seen.size)
        assertEquals(source, seen.single())
        assertEquals(1.123456789123, source.latitude, 0.0)
        assertEquals(2.987654321987, source.longitude, 0.0)
        val moved = source.copy(latitude = source.latitude + .1)
        cache.get("synthetic::one", moved)
        assertEquals(2, seen.size)
        assertEquals(moved, seen.last())
        cache.retain(emptySet())
        cache.get("synthetic::one", moved)
        assertEquals(3, seen.size)
    }

    @Test fun largeIndexBuildCanBeCancelledBeforeConsumingAllEntries() {
        val source = List(100_000) { IndexedDiscoveryPoint("synthetic::$it", GeoPoint(0.0, 0.0)) }
        var checkpoints = 0
        var cancelled = false
        try {
            DiscoverySpatialIndex(key, source) {
                if (++checkpoints == 3) throw CancellationException()
            }
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(3, checkpoints)
    }

    @Test fun imagesOffOrUnavailableNeverRemoveMembers() {
        val metadata = List(12) { point(it) }.associateBy { it.id }
        val projected = metadata.keys.mapIndexed { index, id -> ProjectedDiscoveryPoint(id, ScreenPoint(90f + index * 100f, 200f)) }
        for (imagesEnabled in listOf(false, true)) {
            val clusters = clusterDiscoveryPoints(projected, metadata, emptySet(), null, ScreenRect(0f, 0f, 1400f, 500f), 48f, 17f, imagesEnabled)
            assertEquals(metadata.keys, clusters.flatMap { it.memberIds }.toSet())
            assertEquals(metadata.size, clusters.sumOf { it.memberIds.size })
        }
    }

    @Test fun decorationsDoNotCollideButAllDenseMembersRemainSelectable() {
        val metadata = List(20) { point(it) }.associateBy { it.id }
        val projected = metadata.keys.mapIndexed { index, id -> ProjectedDiscoveryPoint(id, ScreenPoint(90f + index * 30f, 150f)) }
        val clusters = clusterDiscoveryPoints(projected, metadata, setOf("subject::4"), "subject::4", ScreenRect(0f, 0f, 900f, 300f), 28f, 19f, true)
        assertEquals(20, clusters.sumOf { it.memberIds.size })
        assertTrue(clusters.single { "subject::4" in it.memberIds }.selected)
        assertTrue(clusters.count { it.decoration == DiscoveryMarkerDecoration.IMAGE } < 20)
        val projectedById = projected.associateBy { it.id }
        clusters.forEach { assertEquals(projectedById[it.anchorId]?.screen, it.screen) }
    }

    @Test fun exactOverlapRetainsAllMembersAtMaximumZoom() {
        val metadata = List(100) { point(it) }.associateBy { it.id }
        val projected = metadata.keys.map { ProjectedDiscoveryPoint(it, ScreenPoint(100f, 100f)) }
        val cluster = clusterDiscoveryPoints(projected, metadata, setOf("subject::99"), null, ScreenRect(0f, 0f, 400f, 400f), 28f, 21f, true).single()
        assertEquals(metadata.keys, cluster.memberIds.toSet())
        assertTrue(cluster.selected)
        assertTrue(cluster.anchorId in metadata)
    }

    @Test fun expandedArtworkBoundsPreventDecorationsFromCoveringAdjacentMarkers() {
        val metadata = List(2) { point(it) }.associateBy { it.id }
        val projected = listOf(
            ProjectedDiscoveryPoint("subject::0", ScreenPoint(180f, 200f)),
            ProjectedDiscoveryPoint("subject::1", ScreenPoint(290f, 200f)),
        )
        val normal = clusterDiscoveryPoints(projected, metadata, emptySet(), null,
            ScreenRect(0f, 0f, 600f, 400f), 28f, 19f, false)
        assertTrue(normal.any { it.decoration == DiscoveryMarkerDecoration.LABEL })
        val expanded = clusterDiscoveryPoints(projected, metadata, emptySet(), null,
            ScreenRect(0f, 0f, 600f, 400f), 28f, 19f, false,
            markerBounds = { _, decoration ->
                if (decoration == DiscoveryMarkerDecoration.DOT) ScreenRect(-24f, -24f, 24f, 24f)
                else ScreenRect(-140f, -72f, 140f, 7f)
            })
        assertTrue(expanded.all { it.decoration == DiscoveryMarkerDecoration.DOT })
        assertEquals(normal.map { it.memberIds }, expanded.map { it.memberIds })
        assertEquals(normal.map { it.screen }, expanded.map { it.screen })
        assertEquals(normal.map { it.anchorId }, expanded.map { it.anchorId })
    }

    @Test fun selectionAndDecorationsKeepStableMarkerIdentity() {
        val metadata = List(2) { point(it) }.associateBy { it.id }
        val projected = metadata.keys.map { ProjectedDiscoveryPoint(it, ScreenPoint(200f, 200f)) }
        val before = clusterDiscoveryPoints(projected, metadata, emptySet(), null, ScreenRect(0f, 0f, 500f, 500f), 48f, 15f, true)
        val after = clusterDiscoveryPoints(projected, metadata, metadata.keys, "subject::1", ScreenRect(0f, 0f, 500f, 500f), 48f, 15f, false)
        val delta = discoveryMarkerDelta(before.map { it.id }.toSet(), after.map { it.id }.toSet())
        assertTrue(delta.added.isEmpty())
        assertTrue(delta.removed.isEmpty())
        assertEquals(1, delta.retained.size)
        assertEquals(before.single().memberIds, after.single().memberIds)
    }

    @Test fun expandedPanelRemovesCoveredMembersAndPreservesVisibleSelection() {
        val metadata = List(3) { point(it) }.associateBy { it.id }
        val projected = listOf(
            ProjectedDiscoveryPoint("subject::0", ScreenPoint(150f, 180f)),
            ProjectedDiscoveryPoint("subject::1", ScreenPoint(300f, 450f)),
            ProjectedDiscoveryPoint("subject::2", ScreenPoint(450f, 650f)),
        )
        val before = clusterDiscoveryPoints(projected, metadata, emptySet(), null,
            DiscoveryMapPadding(bottom = 100).content(600, 800), 48f, 17f, true)
        val after = clusterDiscoveryPoints(projected, metadata, setOf("subject::0"), "subject::0",
            DiscoveryMapPadding(bottom = 400).content(600, 800), 48f, 17f, false)
        assertEquals(3, before.sumOf { it.memberIds.size })
        assertEquals(listOf("subject::0"), after.single().memberIds)
        assertTrue(after.single().selected)
        assertEquals(DiscoveryMarkerDecoration.LABEL, after.single().decoration)
        val delta = discoveryMarkerDelta(before.map { it.id }.toSet(), after.map { it.id }.toSet())
        assertEquals(setOf("point:subject::1", "point:subject::2"), delta.removed)
        assertEquals(setOf("point:subject::0"), delta.retained)
    }

    @Test fun metadataRefreshUsesNewImageWithoutChangingAnchorOrMembership() {
        val original = point(0)
        val projected = listOf(ProjectedDiscoveryPoint(original.id, ScreenPoint(200f, 200f)))
        val content = ScreenRect(0f, 0f, 500f, 500f)
        val before = clusterDiscoveryPoints(projected, mapOf(original.id to original), emptySet(), null,
            content, 48f, 17f, true).single()
        val updated = original.copy(imageUrl = "https://image.anitabi.cn/synthetic-replacement.jpg")
        val after = clusterDiscoveryPoints(projected, mapOf(updated.id to updated), emptySet(), null,
            content, 48f, 17f, true).single()
        assertEquals(original.imageUrl, before.imageUrl)
        assertEquals(updated.imageUrl, after.imageUrl)
        assertEquals(before.id, after.id)
        assertEquals(before.memberIds, after.memberIds)
        assertEquals(before.anchorId, after.anchorId)
        assertEquals(before.screen, after.screen)
    }

    @Test fun tapUsesMinimumPanAndSearchUsesMeasuredVisibleCentre() {
        val content = DiscoveryMapPadding(left = 100, top = 90, right = 30, bottom = 310).content(1000, 800)
        val alreadyVisible = ScreenPoint(250f, 180f)
        assertEquals(ScreenPoint(0f, 0f), focusPan(alreadyVisible, content, true))
        assertEquals(ScreenPoint(0f, 210f), focusPan(ScreenPoint(300f, 700f), content, true))
        assertEquals(ScreenPoint(-285f, -110f), focusPan(alreadyVisible, content, false))
    }

    @Test fun oversizedPanelPaddingStillHasAValidVisibleArea() {
        val content = DiscoveryMapPadding(2000, 2000, 2000, 2000).content(400, 300)
        assertTrue(content.right > content.left)
        assertTrue(content.bottom > content.top)
        assertTrue(content.left >= 0 && content.right <= 400)
        assertTrue(content.top >= 0 && content.bottom <= 300)
    }

    @Test fun syntheticScaleBenchmarkRetainsEveryMember() {
        for (size in listOf(1_000, 10_000, 100_000)) {
            val metadata = List(size) { point(it) }.associateBy { it.id }
            val source = metadata.values.map { IndexedDiscoveryPoint(it.id, it.coordinate) }
            val projected = metadata.keys.mapIndexed { i, id ->
                ProjectedDiscoveryPoint(id, ScreenPoint((i % 1000).toFloat() + .5f, ((i / 1000) % 600).toFloat() + .5f))
            }
            val runtime = Runtime.getRuntime()
            val heapBefore = runtime.totalMemory() - runtime.freeMemory()
            var index: DiscoverySpatialIndex? = null
            val indexNanos = measureNanoTime { index = DiscoverySpatialIndex(key, source) }
            var visible: List<IndexedDiscoveryPoint> = emptyList()
            val queryNanos = measureNanoTime { visible = requireNotNull(index).query(DiscoveryGeoBounds(-90.0, -180.0, 90.0, 180.0)) }
            var clusters: List<DiscoveryCluster> = emptyList()
            val clusterNanos = measureNanoTime {
                clusters = clusterDiscoveryPoints(projected, metadata, emptySet(), null, ScreenRect(0f, 0f, 1200f, 700f), 48f, 8f, false)
            }
            assertEquals(size, visible.size)
            assertEquals(size, clusters.sumOf { it.memberIds.size })
            assertEquals(metadata.keys, clusters.flatMap { it.memberIds }.toSet())
            val deltaNanos = measureNanoTime {
                val stableIds = clusters.map { it.id }.toSet()
                val delta = discoveryMarkerDelta(stableIds, stableIds)
                assertTrue(delta.added.isEmpty() && delta.removed.isEmpty())
                assertEquals(clusters.size, delta.retained.size)
            }
            val heapAfter = runtime.totalMemory() - runtime.freeMemory()
            // Only aggregate synthetic metrics are emitted. JVM timings are not device frame evidence.
            println("Discovery synthetic size=$size indexMs=${indexNanos / 1_000_000} queryMs=${queryNanos / 1_000_000} clusterMs=${clusterNanos / 1_000_000} deltaMs=${deltaNanos / 1_000_000} markers=${clusters.size} heapBeforeKiB=${heapBefore / 1024} heapAfterKiB=${heapAfter / 1024}")
        }
    }

    private fun point(index: Int): DiscoveryMapPoint = DiscoveryMapPoint(
        id = "subject::$index", subjectId = 1L,
        coordinate = GeoPoint((index % 170) - 85.0 + .25, (index % 350) - 175.0 + .25),
        provider = MapProvider.GOOGLE, title = "Synthetic point", colorArgb = 0xff426b62.toInt(),
        imageUrl = "https://image.anitabi.cn/synthetic-test-only.jpg",
    )
}
