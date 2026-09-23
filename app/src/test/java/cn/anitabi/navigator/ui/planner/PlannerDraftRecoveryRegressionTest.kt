package cn.anitabi.navigator.ui.planner

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TransitTimeMode
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.core.routing.RoadRoute
import cn.anitabi.navigator.core.routing.RoadRouteSegment
import cn.anitabi.navigator.core.routing.RoadRoutingProvider
import cn.anitabi.navigator.core.routing.TourPlanner
import cn.anitabi.navigator.core.routing.TransitJourney
import cn.anitabi.navigator.core.routing.TransitJourneyProvider
import cn.anitabi.navigator.core.routing.TransitJourneyQuery
import cn.anitabi.navigator.core.routing.TravelMatrix
import cn.anitabi.navigator.data.local.PilgrimageCacheDao
import cn.anitabi.navigator.data.local.PilgrimageCacheEntity
import cn.anitabi.navigator.data.local.TourPlanDao
import cn.anitabi.navigator.data.local.TourPlanEntity
import cn.anitabi.navigator.data.network.ApiHttpClient
import cn.anitabi.navigator.data.network.UserAgentInterceptor
import cn.anitabi.navigator.data.network.anitabi.AnitabiApi
import cn.anitabi.navigator.data.network.bangumi.BangumiApi
import cn.anitabi.navigator.data.repository.PilgrimageRepository
import cn.anitabi.navigator.data.repository.TourRepository
import cn.anitabi.navigator.data.repository.PlannerDraftRepository
import cn.anitabi.navigator.data.repository.FilePlannerDraftStorage
import cn.anitabi.navigator.data.repository.PlannerDraftStorage
import cn.anitabi.navigator.data.repository.PlannerDraftRead
import cn.anitabi.navigator.data.repository.PlannerDraftEnvelope
import kotlinx.coroutines.CompletableDeferred
import cn.anitabi.navigator.navigation.CurrentLocationProvider
import cn.anitabi.navigator.ui.search.SearchViewModel
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** Fresh owners exercise production recovery entry points; this is not a device process-death test. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlannerDraftRecoveryRegressionTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val temporary = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    private val dao = DraftRecoveryTourDao()
    private val json = ApiHttpClient.defaultJson
    private val clock = Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneId.of("UTC"))
    private val anime = Anime(101, "TEST_ONLY_DRAFT")
    private val points = (1..3).map {
        PilgrimagePoint("point_$it", "TEST_ONLY_$it", GeoPoint(it.toDouble(), 0.0))
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        scopes.forEach(CoroutineScope::cancel)
        Dispatchers.resetMain()
    }

    @Test
    fun `fresh selection owner restores unsaved point snapshots without a saved tour`() = runTest(dispatcher) {
        val original = searchOwner()
        advanceUntilIdle()
        original.selectDiscoveryPoints(anime, points)
        advanceUntilIdle()

        val restored = searchOwner()
        advanceUntilIdle()

        assertEquals(original.state.value.selectedPointIds, restored.state.value.selectedPointIds)
        assertEquals(original.state.value.selectedAnimeData, restored.state.value.selectedAnimeData)
        assertTrue(dao.entities.isEmpty())
    }

    @Test
    fun `current draft wins over an older saved tour after owners are recreated`() = runTest(dispatcher) {
        saveOldTour()
        val original = searchOwner()
        advanceUntilIdle()
        original.clearSelection()
        original.selectDiscoveryPoints(anime, points)
        advanceUntilIdle()

        val restored = searchOwner()
        advanceUntilIdle()

        assertEquals(original.state.value.selectedPointIds, restored.state.value.selectedPointIds)
        assertTrue(restored.state.value.selectedPointIds.none { it.startsWith("202::") })
    }

    @Test
    fun `cleared selection does not resurrect the last saved tour after owner recreation`() = runTest(dispatcher) {
        saveOldTour()
        val original = searchOwner()
        advanceUntilIdle()
        original.clearSelection()
        advanceUntilIdle()

        val restored = searchOwner()
        advanceUntilIdle()

        assertTrue(restored.state.value.selectedPointIds.isEmpty())
        assertEquals(1, dao.entities.size)
    }

    @Test
    fun `fresh planner owner restores edited inputs without location or routing requests`() = runTest(dispatcher) {
        val original = plannerOwner()
        original.configure(anime, points)
        original.setMode(TravelMode.BIKE)
        original.setObjective(RouteObjective.SHORTEST)
        original.setEndPolicy(EndPolicy.FIXED)
        original.setDwellMinutes("27")
        original.setTransitSchedule(TransitTimeMode.DEPART_AT, LocalDate.of(2026, 9, 22), LocalTime.of(9, 45))
        advanceUntilIdle()

        val restored = plannerOwner()
        restored.restoreDraft(requireNotNull(original.state.value.draftId))
        advanceUntilIdle()

        assertEquals(original.state.value.anime, restored.state.value.anime)
        assertEquals(original.state.value.selectedPoints, restored.state.value.selectedPoints)
        assertEquals(original.state.value.mode, restored.state.value.mode)
        assertEquals(original.state.value.objective, restored.state.value.objective)
        assertEquals(original.state.value.endPolicy, restored.state.value.endPolicy)
        assertEquals(original.state.value.dwellMinutesInput, restored.state.value.dwellMinutesInput)
        assertEquals(original.state.value.transitDate, restored.state.value.transitDate)
        assertEquals(original.state.value.transitTime, restored.state.value.transitTime)
        assertEquals(original.state.value.transitZoneId, restored.state.value.transitZoneId)
        assertFalse(restored.state.value.isLoading)
        assertTrue(dao.entities.isEmpty())
    }

    @Test
    fun `empty restored planner reports actionable failure instead of silently ignoring generate`() = runTest(dispatcher) {
        val restored = plannerOwner()

        restored.generate()
        advanceUntilIdle()

        assertFalse(restored.state.value.isLoading)
        assertTrue(!restored.state.value.errorMessage.isNullOrBlank())
        assertTrue(dao.entities.isEmpty())
    }

    @Test
    fun `shared selection and planner use one draft and reopening includes new selection`() = runTest(dispatcher) {
        val shared = drafts()
        val search = searchOwner(shared)
        search.selectDiscoveryPoints(anime, points.take(2))
        val id = requireNotNull(search.preparePlanner())
        val planner = plannerOwner(shared)
        planner.restoreDraft(id)
        advanceUntilIdle()
        planner.setDwellMinutes("42")
        search.selectDiscoveryPoints(anime, listOf(points.last()))
        val sameId = requireNotNull(search.preparePlanner())
        assertEquals(id, sameId)
        planner.restoreDraft(sameId)
        advanceUntilIdle()
        assertEquals(3, planner.state.value.selectedPoints.size)
        assertEquals("42", planner.state.value.dwellMinutesInput)
        assertEquals(search.state.value.selectedPointIds, planner.state.value.selectedPoints.map { it.id }.toSet())
    }

    @Test
    fun `restoring in another device zone retains selected local schedule and IANA zone`() = runTest(dispatcher) {
        val original = plannerOwner(ownerClock = clock.withZone(ZoneId.of("Asia/Tokyo")))
        original.configure(anime, points)
        original.setTransitSchedule(TransitTimeMode.DEPART_AT, LocalDate.of(2026, 10, 2), LocalTime.of(18, 15))
        assertNotNull(original.flushDraft())
        val restored = plannerOwner(ownerClock = clock.withZone(ZoneId.of("America/New_York")))
        restored.restoreDraft(requireNotNull(original.state.value.draftId))
        advanceUntilIdle()
        assertEquals("Asia/Tokyo", restored.state.value.transitZoneId)
        assertEquals(original.state.value.transitDate, restored.state.value.transitDate)
        assertEquals(original.state.value.transitTime, restored.state.value.transitTime)
        assertNull(restored.state.value.plan)
    }

    @Test
    fun `saved tour draft restores inputs without requesting location or routes`() = runTest(dispatcher) {
        saveOldTour()
        val saved = requireNotNull(TourRepository(dao, json).getMostRecent())
        assertEquals(TravelMode.WALK, saved.plan.mode)
        assertEquals(TransitTimeMode.DEPART_AT, saved.plan.transitTimeMode)
        assertNull(saved.plan.transitAnchorTime)
        val original = plannerOwner()
        val id = requireNotNull(original.prepareSavedDraft(saved))
        val restored = plannerOwner()
        restored.restoreDraft(id)
        advanceUntilIdle()
        assertEquals(saved.storedTour.id, restored.state.value.restoredTourId)
        assertEquals(saved.storedTour.selectedPoints, restored.state.value.selectedPoints)
        assertNull(restored.state.value.plan)
        assertFalse(restored.state.value.isLoading)
        assertTrue(restored.state.value.canGenerate)
        assertEquals(1, dao.entities.size)
    }

    @Test
    fun `unchanged saved transit schedule restores in its draft zone after the device zone changes`() = runTest(dispatcher) {
        saveOldTour()
        val repository = TourRepository(dao, json)
        val plan = requireNotNull(repository.getMostRecent()).plan.copy(
            mode = TravelMode.TRANSIT,
            transitTimeMode = TransitTimeMode.DEPART_AT,
            transitAnchorTime = "2026-09-22T09:00:00Z",
        )
        repository.saveUnresolved(plan)
        val original = plannerOwner(ownerClock = clock.withZone(ZoneId.of("Asia/Tokyo")))
        val id = requireNotNull(original.prepareSavedDraft(requireNotNull(repository.get(plan.id))))
        val restored = plannerOwner(ownerClock = clock.withZone(ZoneId.of("America/New_York")))
        restored.restoreDraft(id)
        advanceUntilIdle()

        assertEquals(plan.id, restored.state.value.restoredTourId)
        assertNull(restored.state.value.draftRecoveryError)
        assertTrue(restored.state.value.canGenerate)
        assertEquals("Asia/Tokyo", restored.state.value.transitZoneId)
        assertEquals(LocalDate.of(2026, 9, 22), restored.state.value.transitDate)
        assertEquals(LocalTime.of(18, 0), restored.state.value.transitTime)
        assertNull(restored.state.value.plan)
    }

    @Test
    fun `saved schedule changed while process was absent cannot replace the visible draft schedule silently`() = runTest(dispatcher) {
        saveOldTour()
        val repository = TourRepository(dao, json)
        val originalPlan = requireNotNull(repository.getMostRecent()).plan.copy(
            mode = TravelMode.TRANSIT,
            transitTimeMode = TransitTimeMode.DEPART_AT,
            transitAnchorTime = "2026-09-22T09:00:00Z",
        )
        repository.saveUnresolved(originalPlan)
        val original = plannerOwner()
        val id = requireNotNull(original.prepareSavedDraft(requireNotNull(repository.get(originalPlan.id))))
        repository.saveUnresolved(originalPlan.copy(transitAnchorTime = "2026-09-22T12:00:00Z"))

        val restored = plannerOwner()
        restored.restoreDraft(id)
        advanceUntilIdle()
        assertFalse(restored.state.value.canGenerate)
        assertNotNull(restored.state.value.draftRecoveryError)
        assertNull(restored.state.value.plan)
        assertEquals("2026-09-22T12:00:00Z", repository.get(originalPlan.id)?.plan?.transitAnchorTime)
    }

    @Test
    fun `missing draft id disables generation with a recoverable reason`() = runTest(dispatcher) {
        val restored = plannerOwner()
        restored.restoreDraft("TEST_ONLY_MISSING")
        advanceUntilIdle()
        assertFalse(restored.state.value.canGenerate)
        assertNotNull(restored.state.value.draftRecoveryError)
        assertFalse(restored.state.value.isRestoringDraft)
    }

    @Test
    fun `selected coordinate snapshots remain unchanged when directory metadata changes`() = runTest(dispatcher) {
        val search = searchOwner()
        search.addSelections(points.map { anime to it })
        val expected = search.state.value.selectedAnimeData
        search.selectDiscoveryPoints(anime, points.map { it.copy(coordinate = GeoPoint(8.0, 9.0)) })
        assertNotNull(search.preparePlanner())
        val restored = searchOwner()
        advanceUntilIdle()
        assertEquals(expected, restored.state.value.selectedAnimeData)
    }

    @Test
    fun `dragged order survives new owner and explicit regenerate without another optimization`() = runTest(dispatcher) {
        var matrixCalls = 0
        val road = object : RoadRoutingProvider {
            override suspend fun matrix(mode: TravelMode, points: List<GeoPoint>, objective: RouteObjective): TravelMatrix {
                matrixCalls += 1
                val values = List(points.size) { from -> List<Double?>(points.size) { to -> kotlin.math.abs(from - to).toDouble() } }
                return TravelMatrix(values, values)
            }
            override suspend fun directions(mode: TravelMode, points: List<GeoPoint>): RoadRoute = RoadRoute(
                points.zipWithNext().map { (a, b) -> RoadRouteSegment(listOf(a, b), emptyList(), 10.0, 10.0) },
            )
        }
        val original = plannerOwner(roadProvider = road)
        original.configure(anime, points)
        original.generate()
        advanceUntilIdle()
        assertNotNull(original.state.value.plan)
        original.moveDraft(1, 2)
        val expected = original.state.value.draftOrder
        assertTrue(original.state.value.manualOrderRequested)
        original.clearPlan()
        original.setObjective(RouteObjective.SHORTEST)
        assertEquals(expected, original.state.value.draftOrder)
        val id = requireNotNull(original.flushDraft())
        val callsBeforeRecovery = matrixCalls
        val restored = plannerOwner(roadProvider = road)
        restored.restoreDraft(id)
        advanceUntilIdle()
        assertNull(restored.state.value.plan)
        assertEquals(callsBeforeRecovery, matrixCalls)
        restored.generate()
        advanceUntilIdle()
        assertEquals(expected, restored.state.value.plan?.orderedPoints)
        assertEquals(callsBeforeRecovery, matrixCalls)
    }

    @Test
    fun `late permission callback cannot strand a draft restoration in loading state`() = runTest(dispatcher) {
        val original = plannerOwner()
        original.configure(anime, points)
        val id = requireNotNull(original.flushDraft())
        val storage = FilePlannerDraftStorage(temporary.root, dispatcher)
        val release = CompletableDeferred<Unit>()
        val delayed = object : PlannerDraftStorage {
            override suspend fun read(): PlannerDraftRead {
                val old = storage.read()
                release.await()
                return old
            }
            override suspend fun write(envelope: PlannerDraftEnvelope) = storage.write(envelope)
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also(scopes::add)
        val restored = plannerOwner(PlannerDraftRepository(delayed, scope))
        restored.restoreDraft(id)
        runCurrent()
        assertTrue(restored.state.value.isRestoringDraft)
        restored.setUseCurrentLocation()
        release.complete(Unit)
        advanceUntilIdle()
        assertFalse(restored.state.value.isRestoringDraft)
        assertEquals(id, restored.state.value.draftId)
        assertTrue(restored.state.value.canGenerate)
    }

    private fun searchOwner(draftRepository: PlannerDraftRepository = drafts()): SearchViewModel {
        val http = ApiHttpClient(UserAgentInterceptor("TEST_ONLY", "1", "mailto:test@example.invalid"))
        return SearchViewModel(
            BangumiApi(http),
            PilgrimageRepository(AnitabiApi(http), object : PilgrimageCacheDao {
                override suspend fun get(subjectId: Long): PilgrimageCacheEntity? = null
                override suspend fun upsert(entity: PilgrimageCacheEntity) = error("No public data writes expected")
            }, json),
            draftRepository = draftRepository,
        )
    }

    private fun plannerOwner(
        draftRepository: PlannerDraftRepository = drafts(),
        ownerClock: Clock = clock,
        roadProvider: RoadRoutingProvider? = null,
    ): PlannerViewModel = PlannerViewModel(
        planner = TourPlanner(roadProvider ?: object : RoadRoutingProvider {
            override suspend fun matrix(mode: TravelMode, points: List<GeoPoint>, objective: RouteObjective): TravelMatrix =
                error("Recovery must not call a routing provider")
            override suspend fun directions(mode: TravelMode, points: List<GeoPoint>): RoadRoute =
                error("Recovery must not call a routing provider")
        }, object : TransitJourneyProvider {
            override suspend fun journey(from: GeoPoint, to: GeoPoint, query: TransitJourneyQuery): TransitJourney =
                error("Recovery must not call a routing provider")
        }),
        repository = TourRepository(dao, json),
        locationProvider = object : CurrentLocationProvider {
            override suspend fun currentLocation(): GeoPoint = error("Recovery must not access location")
        },
        clock = ownerClock,
        draftRepository = draftRepository,
    )

    private fun drafts(): PlannerDraftRepository {
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also(scopes::add)
        return PlannerDraftRepository(FilePlannerDraftStorage(temporary.root, dispatcher), scope)
    }

    private suspend fun saveOldTour() {
        val savedPoints = points.take(2).map { it.copy(id = "202::${it.id}") }
        TourRepository(dao, json).saveUnresolved(TourPlan(
            id = "TEST_ONLY_SAVED",
            anime = Anime(202, "TEST_ONLY_OLD"),
            selectedPoints = savedPoints,
            orderedPoints = savedPoints,
            legs = emptyList(),
            mode = TravelMode.WALK,
            objective = RouteObjective.FASTEST,
            endPolicy = EndPolicy.OPEN,
            estimatedDurationSeconds = 0.0,
            attribution = emptyList(),
            initialStart = savedPoints.first().coordinate,
        ))
    }
}

private class DraftRecoveryTourDao : TourPlanDao {
    val entities = linkedMapOf<String, TourPlanEntity>()
    override suspend fun get(id: String): TourPlanEntity? = entities[id]
    override suspend fun getMostRecent(): TourPlanEntity? = entities.values.lastOrNull()
    override suspend fun getIdsMostRecentFirst(): List<String> = entities.keys.toList().asReversed()
    override suspend fun upsert(entity: TourPlanEntity) { entities[entity.id] = entity }
    override suspend fun finishLegacyMigration(id: String, storedTourJson: String, updatedAtEpochMillis: Long) = Unit
    override suspend fun recordMigrationError(id: String, message: String) = Unit
    override suspend fun getMostRecentMigrationError(): String? = null
}
