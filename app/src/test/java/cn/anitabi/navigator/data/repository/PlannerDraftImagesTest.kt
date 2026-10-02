package cn.anitabi.navigator.data.repository

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.PlannerDraft
import cn.anitabi.navigator.data.discovery.DiscoveryCache
import cn.anitabi.navigator.data.discovery.DiscoveryPoint
import cn.anitabi.navigator.data.discovery.DiscoveryRepository
import cn.anitabi.navigator.data.discovery.DiscoverySnapshot
import cn.anitabi.navigator.data.discovery.DiscoverySource
import cn.anitabi.navigator.data.discovery.DiscoverySubject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlannerDraftImagesTest {
    private val anime = Anime(101, "TEST_ONLY_METADATA")
    private val point = PilgrimagePoint("101::point", "TEST_ONLY_SAVED", GeoPoint(1.0, 2.0))
    private val image = "https://image.anitabi.cn/points/synthetic/image.jpg?v=2"
    private fun draft(id: String = "draft", selected: PilgrimagePoint = point) = PlannerDraft(
        id, listOf(anime), anime, listOf(selected), transitDate = "2026-10-02", transitTime = "10:00", transitZoneId = "UTC",
    )
    private fun snapshot(url: String?, metadata: Int): DiscoverySnapshot {
        val version = "TEST_ONLY_GENERATION"
        val source = DiscoveryPoint(101, "point", GeoPoint(8.0, 9.0), imageUrl = url,
            detailsVersion = version, imageMetadataVersion = metadata)
        return DiscoverySnapshot(version, 100, 1, listOf(DiscoverySubject(anime, pointIds = listOf(source.id))),
            listOf(source), loadedPages = setOf(0), endVersionVerified = true)
    }

    @Test fun cachedReferenceSupplementsTheLinkedDraftWithoutRequestsOrInputChanges() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val storage = ImageDraftStorage()
        val drafts = PlannerDraftRepository(storage, scope)
        val source = ImageMetadataSource()
        val discovery = DiscoveryRepository(source, ImageMetadataCache(snapshot("/images/points/synthetic/image.jpg?v=2", 0)),
            scope, now = { testScheduler.currentTime })
        try {
            drafts.awaitLoaded()
            drafts.replace(draft())
            assertTrue(drafts.linkGeneratedTour("draft", drafts.state.value.revision, "TEST_ONLY_TOUR"))
            observePlannerDraftImages(drafts, discovery, scope)
            discovery.initialize()
            advanceUntilIdle()
            val expected = draft().copy(selectedPoints = listOf(point.copy(imageUrl = image)), generatedTourId = "TEST_ONLY_TOUR")
            assertEquals(expected, drafts.state.value.draft)
            assertEquals(expected, storage.envelope?.draft)
            assertEquals(0, source.subjectCalls)
        } finally { scope.cancel() }
    }

    @Test fun legacyNullUsesOneProductionSubjectMergeAndKeepsTheSavedCoordinate() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val drafts = PlannerDraftRepository(ImageDraftStorage(), scope)
        val source = ImageMetadataSource().apply {
            document = Json.parseToJsonElement("""[{"id":"point","name":"TEST_ONLY_REFRESHED","image":"/images/points/synthetic/image.jpg?v=2"}]""")
        }
        val discovery = DiscoveryRepository(source, ImageMetadataCache(snapshot(null, 0)), scope,
            now = { testScheduler.currentTime })
        try {
            drafts.awaitLoaded(); drafts.replace(draft())
            observePlannerDraftImages(drafts, discovery, scope)
            discovery.initialize()
            advanceUntilIdle()
            assertEquals(1, source.subjectCalls)
            assertEquals(point.copy(imageUrl = image), drafts.state.value.draft?.selectedPoints?.single())
            assertEquals(GeoPoint(8.0, 9.0), discovery.state.value.snapshot?.points?.single()?.coordinate)
        } finally { scope.cancel() }
    }

    @Test fun emptyMetadataResponseDoesNotLoopAndExistingManualRetryStillWorks() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val drafts = PlannerDraftRepository(ImageDraftStorage(), scope)
        val source = ImageMetadataSource()
        val discovery = DiscoveryRepository(source, ImageMetadataCache(snapshot(null, 0)), scope,
            now = { testScheduler.currentTime })
        try {
            drafts.awaitLoaded(); drafts.replace(draft())
            observePlannerDraftImages(drafts, discovery, scope)
            discovery.initialize()
            advanceUntilIdle()
            assertEquals(1, source.subjectCalls)
            assertEquals(point, drafts.state.value.draft?.selectedPoints?.single())
            source.document = Json.parseToJsonElement("""[{"id":"point","image":"/images/points/synthetic/image.jpg?v=2"}]""")
            discovery.ensureSubjectDetails(101)
            advanceUntilIdle()
            assertEquals(2, source.subjectCalls)
            assertEquals(image, drafts.state.value.draft?.selectedPoints?.single()?.imageUrl)
        } finally { scope.cancel() }
    }

    @Test fun currentNoImageAndAnUnknownPointNeverInventOrFetchReferences() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val drafts = PlannerDraftRepository(ImageDraftStorage(), scope)
        val source = ImageMetadataSource()
        val discovery = DiscoveryRepository(source, ImageMetadataCache(snapshot(null, 1)), scope,
            now = { testScheduler.currentTime })
        try {
            drafts.awaitLoaded(); drafts.replace(draft())
            observePlannerDraftImages(drafts, discovery, scope)
            discovery.initialize(); advanceUntilIdle()
            assertEquals(point, drafts.state.value.draft?.selectedPoints?.single())
            drafts.replace(draft("unknown", point.copy(id = "101::unknown")))
            advanceUntilIdle()
            assertEquals(0, source.subjectCalls)
            assertNull(drafts.state.value.draft?.selectedPoints?.single()?.imageUrl)
        } finally { scope.cancel() }
    }

    @Test fun aPausedSourceDoesNotConsumeTheAutomaticMetadataAttempt() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val drafts = PlannerDraftRepository(ImageDraftStorage(), scope)
        val source = ImageMetadataSource()
        val discovery = DiscoveryRepository(source, ImageMetadataCache(snapshot(null, 0)), scope,
            now = { testScheduler.currentTime })
        try {
            drafts.awaitLoaded(); drafts.replace(draft())
            discovery.setForeground(false)
            observePlannerDraftImages(drafts, discovery, scope)
            discovery.initialize(); runCurrent()
            assertEquals(0, source.subjectCalls)
            discovery.setForeground(true)
            advanceUntilIdle()
            assertEquals(1, source.subjectCalls)
        } finally { scope.cancel() }
    }

    @Test fun pauseDuringFirstSubjectDoesNotConsumeTheNextSubjectsAttempt() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val first = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val calls = mutableListOf<Long>()
        val secondAnime = Anime(102, "TEST_ONLY_SECOND")
        val second = point.copy(id = "102::point")
        val initial = snapshot(null, 0).let { base -> base.copy(
            subjects = base.subjects + DiscoverySubject(secondAnime, pointIds = listOf(second.id)),
            points = base.points + base.points.single().copy(subjectId = 102),
            pageSize = 2,
        ) }
        val source = object : DiscoverySource {
            override suspend fun index(cacheToken: String): JsonElement = error("Unexpected index request")
            override suspend fun page(page: Int, cacheToken: String): JsonElement = error("Unexpected page request")
            override suspend fun subject(subjectId: Long): JsonElement {
                calls += subjectId
                if (subjectId == 101L) first.await()
                if (subjectId == 102L) secondStarted.complete(Unit)
                return Json.parseToJsonElement("[]")
            }
        }
        val drafts = PlannerDraftRepository(ImageDraftStorage(), scope)
        val discovery = DiscoveryRepository(source, ImageMetadataCache(initial), scope,
            now = { testScheduler.currentTime })
        try {
            drafts.awaitLoaded()
            drafts.replace(draft().copy(selectedAnimes = listOf(anime, secondAnime),
                selectedPoints = listOf(point, second), manualOrderPointIds = listOf(point.id, second.id)))
            observePlannerDraftImages(drafts, discovery, scope)
            discovery.initialize(); runCurrent()
            assertEquals(listOf(101L), calls)
            discovery.setForeground(false); runCurrent()
            assertEquals(listOf(101L), calls)
            first.complete(Unit)
            discovery.setForeground(true)
            // The Repository writes its public cache on real IO; virtual scheduler idleness
            // does not mean that the observer has resumed after that write.
            secondStarted.await()
            advanceUntilIdle()
            assertEquals("Both missing subjects must remain eligible after the cancelled request", listOf(101L, 101L, 102L), calls)
        } finally { first.complete(Unit); scope.cancel() }
    }
}

private class ImageDraftStorage : PlannerDraftStorage {
    var envelope: PlannerDraftEnvelope? = null
    override suspend fun read() = envelope?.let { PlannerDraftRead.Loaded(it) }
        ?: PlannerDraftRead.Unavailable(PlannerDraftProblem.MISSING)
    override suspend fun write(envelope: PlannerDraftEnvelope) { this.envelope = envelope }
}

private class ImageMetadataCache(private var value: DiscoverySnapshot) : DiscoveryCache {
    override fun read() = value
    override fun write(snapshot: DiscoverySnapshot) { value = snapshot }
}

private class ImageMetadataSource : DiscoverySource {
    var subjectCalls = 0
    var document: JsonElement = Json.parseToJsonElement("[]")
    override suspend fun index(cacheToken: String): JsonElement = error("Unexpected index request")
    override suspend fun page(page: Int, cacheToken: String): JsonElement = error("Unexpected page request")
    override suspend fun subject(subjectId: Long): JsonElement {
        require(subjectId == 101L)
        subjectCalls++
        return document
    }
}
