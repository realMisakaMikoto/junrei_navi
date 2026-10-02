package cn.anitabi.navigator.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.FileObserver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.anitabi.navigator.AppContainer
import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.NavigationProgress
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.PlannerDraft
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.StoredTourV2
import cn.anitabi.navigator.core.model.TerritoryRegion
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TransitTimeMode
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.core.routing.RoadRoute
import cn.anitabi.navigator.core.routing.RoadRoutingProvider
import cn.anitabi.navigator.core.routing.TransitJourney
import cn.anitabi.navigator.core.routing.TransitJourneyProvider
import cn.anitabi.navigator.core.routing.TransitJourneyQuery
import cn.anitabi.navigator.core.routing.TravelMatrix
import cn.anitabi.navigator.data.discovery.DiscoveryCache
import cn.anitabi.navigator.data.discovery.DiscoveryParser
import cn.anitabi.navigator.data.discovery.DiscoverySnapshot
import cn.anitabi.navigator.data.discovery.DiscoverySource
import cn.anitabi.navigator.data.local.AnitabiDatabase
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/** Real AppContainer collector + Room + atomic draft file, entirely within a synthetic namespace. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PlannerDraftCompletionInstrumentedTest {
    @get:Rule val caseName = TestName()
    private val baseContext get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun committedTerminalRecordsClearLinkedDraftDurablyWithoutRemovingFormalTours() = withFixture { fixture ->
        for (terminal in listOf(NavigationState.COMPLETED, NavigationState.ENDED)) {
            val plan = plan("synthetic-${terminal.name.lowercase()}")
            val active = NavigationProgress(plan.id, state = NavigationState.NAVIGATING)
            fixture.container.tourRepository.saveUnresolved(plan, active)
            val linked = fixture.link(draft("draft-${terminal.name.lowercase()}"), plan.id)
            val nonterminal = active.copy(state = NavigationState.DWELLING, dwellingUntilEpochMillis = 123_456)
            fixture.container.tourRepository.saveUnresolved(plan, nonterminal)
            fixture.assertPreserved(linked)
            fixture.flags["${terminal.name.lowercase()}NonterminalPreserved"] = true

            val tombstone = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.awaitDurableTombstone(linked.revision)
            }
            fixture.container.tourRepository.saveUnresolved(plan, nonterminal.copy(
                state = terminal, completedPointIds = plan.selectedPoints.map { it.id }.toSet(),
                dwellingUntilEpochMillis = null,
            ))
            withTimeout(10_000) {
                fixture.container.plannerDraftRepository.state.first { it.draft == null && it.revision > linked.revision }
            }
            val durable = tombstone.await()
            assertNull(durable.draft)
            assertTrue(durable.revision > linked.revision)
            // No flush()/completeGeneratedTour() call here: the actual AppContainer collector owns this write.
            assertEquals(terminal, fixture.formalRecord(plan.id).navigationState)
            assertEquals(plan.selectedPoints, fixture.formalRecord(plan.id).selectedPoints)
            assertEquals(plan.selectedPoints.map { it.id }.toSet(), fixture.formalRecord(plan.id).completedPointIds)
            fixture.flags["${terminal.name.lowercase()}DurableCollectorTombstone"] = true
            fixture.flags["${terminal.name.lowercase()}FormalRecordRemains"] = true
        }
    }

    @Test fun oldAndUnrelatedTerminalSavesPreserveTheNewerLinkedOrUnlinkedDraft() = withFixture { fixture ->
        val old = plan("synthetic-old")
        val newer = plan("synthetic-newer")
        val unrelated = plan("synthetic-unrelated")
        fixture.container.tourRepository.saveUnresolved(old, NavigationProgress(old.id, state = NavigationState.NAVIGATING))
        fixture.link(draft("draft-old"), old.id)
        fixture.container.tourRepository.saveUnresolved(newer, NavigationProgress(newer.id, state = NavigationState.NAVIGATING))
        val linked = fixture.link(draft("draft-newer"), newer.id)

        fixture.container.tourRepository.saveUnresolved(old, NavigationProgress(old.id, state = NavigationState.COMPLETED))
        fixture.assertPreserved(linked)
        fixture.container.tourRepository.saveUnresolved(unrelated, NavigationProgress(unrelated.id, state = NavigationState.ENDED))
        fixture.assertPreserved(linked)
        fixture.flags["oldAndUnrelatedTerminalPreserveNewLinkedDraft"] = true

        val unlinked = draft("draft-new-input")
        fixture.container.plannerDraftRepository.replace(unlinked)
        assertTrue(fixture.container.plannerDraftRepository.flush(unlinked.draftId))
        val current = fixture.container.plannerDraftRepository.state.value
        assertNull(current.draft?.generatedTourId)
        fixture.container.tourRepository.saveUnresolved(newer, NavigationProgress(newer.id, state = NavigationState.ENDED))
        fixture.assertPreserved(current)
        fixture.flags["terminalSavePreservesNewUnlinkedDraft"] = true
        assertEquals(NavigationState.COMPLETED, fixture.formalRecord(old.id).navigationState)
        assertEquals(NavigationState.ENDED, fixture.formalRecord(newer.id).navigationState)
        assertEquals(NavigationState.ENDED, fixture.formalRecord(unrelated.id).navigationState)
        fixture.flags["allFormalRecordsRemain"] = true
    }

    private fun withFixture(block: suspend kotlinx.coroutines.CoroutineScope.(Fixture) -> Unit) = runBlocking {
        // Context namespaces cannot isolate AndroidKeyStore. Refuse the constructor's legacy-key migration if it could delete anything.
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse("Use a dedicated test UID without the legacy routing key", keyStore.containsAlias("anitabi_ors_key_v1"))
        val isolated = IsolatedContext(baseContext)
        val fixture = Fixture(isolated)
        try {
            withTimeout(60_000) {
                fixture.container.plannerDraftRepository.awaitLoaded()
                block(fixture)
                assertEquals(0, fixture.sourceCalls.get())
                assertEquals(0, fixture.routeCalls.get())
                fixture.flags["noDiscoveryOrRoutingCalls"] = true
            }
        } finally {
            // AppContainer's process-lifetime collector/Room handles are deliberately not exposed or reflected into.
            // Keep this isolated namespace until the instrumentation process exits; do not unlink an open SQLite file.
            val directory = File(baseContext.getExternalFilesDir(null), "frontend-fix-v2").apply { mkdirs() }
            File(directory, "planner-draft-completion-${caseName.methodName}.json").writeText(buildJsonObject {
                put("realAppContainerCollector", true); put("realRoom", true); put("defaultFileDraftStorage", true)
                put("completionMethodCalledByTest", false); put("terminalFlushCalledByTest", false)
                put("namespace", isolated.namespace); put("ownedFilesRelativePath", "instrumentation/${isolated.namespace}")
                put("ownedPreferencesPrefix", "${isolated.namespace}_")
                put("cleanupAfterProcessExitRequired", true); put("fixtureContextUsesOriginalDatabaseOrDraft", false)
                put("runnerUsesNormalApplicationStartup", true)
                put("sourceCalls", fixture.sourceCalls.get()); put("routeCalls", fixture.routeCalls.get())
                put("checks", buildJsonObject { fixture.flags.forEach { (name, passed) -> put(name, passed) } })
            }.toString())
        }
    }

    private class Fixture(private val context: IsolatedContext) {
        val flags = linkedMapOf<String, Boolean>()
        val sourceCalls = AtomicInteger()
        val routeCalls = AtomicInteger()
        private val snapshot = DiscoveryParser.index(Json.parseToJsonElement(
            """[[[101,"SYNTHETIC",0,0,0,0,0,0,0,0,0,0,["first",1.125,2.25,0],0,0,0,0,0]],1,100]""",
        ))
        val container = AppContainer(
            context, classifyTerritoryOverride = { TerritoryRegion.OTHER }, regionDataVersionOverride = { REGION },
            discoverySourceOverride = object : DiscoverySource {
                override suspend fun index(cacheToken: String): JsonElement = unexpectedSource()
                override suspend fun page(page: Int, cacheToken: String): JsonElement = unexpectedSource()
                override suspend fun subject(subjectId: Long): JsonElement = unexpectedSource()
                private fun unexpectedSource(): Nothing { sourceCalls.incrementAndGet(); error("Unexpected synthetic source request") }
            },
            discoveryCacheOverride = object : DiscoveryCache {
                override fun read() = snapshot
                override fun write(snapshot: DiscoverySnapshot) = Unit
            },
            roadProviderOverride = object : RoadRoutingProvider {
                override suspend fun matrix(mode: TravelMode, points: List<GeoPoint>, objective: RouteObjective): TravelMatrix = unexpectedRoute()
                override suspend fun directions(mode: TravelMode, points: List<GeoPoint>): RoadRoute = unexpectedRoute()
            },
            transitProviderOverride = object : TransitJourneyProvider {
                override suspend fun journey(from: GeoPoint, to: GeoPoint, query: TransitJourneyQuery): TransitJourney = unexpectedRoute()
            },
        )
        private val draftDirectory = File(context.filesDir, "planner-draft")
        private val storage = FilePlannerDraftStorage(draftDirectory)
        private fun unexpectedRoute(): Nothing { routeCalls.incrementAndGet(); error("Unexpected routing request") }

        suspend fun link(draft: PlannerDraft, tourId: String): PlannerDraftState {
            val repository = container.plannerDraftRepository
            repository.replace(draft)
            assertTrue(repository.linkGeneratedTour(draft.draftId, repository.state.value.revision, tourId))
            assertTrue(repository.flush(draft.draftId))
            val state = repository.state.value
            val disk = storage.read() as PlannerDraftRead.Loaded
            assertEquals(state.draft, disk.envelope.draft)
            assertEquals(state.revision, disk.envelope.revision)
            return state
        }

        suspend fun assertPreserved(expected: PlannerDraftState) {
            // Observe forbidden changes after a committed Room write; the positive test separately waits for actual filesystem events.
            val changed = withTimeoutOrNull(1_000) {
                container.plannerDraftRepository.state.first { it.draft != expected.draft || it.revision != expected.revision }
            }
            assertNull("A nonmatching persisted state must preserve the current draft", changed)
            assertEquals(expected.draft, container.plannerDraftRepository.state.value.draft)
            val disk = storage.read() as PlannerDraftRead.Loaded
            assertEquals(expected.draft, disk.envelope.draft)
            assertEquals(expected.revision, disk.envelope.revision)
        }

        suspend fun awaitDurableTombstone(afterRevision: Long): PlannerDraftEnvelope = withTimeout(10_000) {
            callbackFlow {
                @Suppress("DEPRECATION")
                val observer = object : FileObserver(draftDirectory.absolutePath, FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE) {
                    override fun onEvent(event: Int, path: String?) { if (path == "current.json") trySend(Unit) }
                }
                observer.startWatching()
                trySend(Unit)
                awaitClose { observer.stopWatching() }
            }.buffer(Channel.CONFLATED).map { storage.read() }.first { result ->
                result is PlannerDraftRead.Loaded && result.envelope.revision > afterRevision && result.envelope.draft == null
            }.let { (it as PlannerDraftRead.Loaded).envelope }
        }

        suspend fun formalRecord(id: String): StoredTourV2 {
            // Independent Room connection proves the formal row remains on disk, not in TourRepository's route cache.
            val reader = AnitabiDatabase.create(context)
            return try {
                val entity = requireNotNull(reader.tourPlanDao().get(id))
                assertTrue(File(reader.openHelper.readableDatabase.path).canonicalPath.startsWith(context.root.canonicalPath + File.separator))
                Json.decodeFromString(StoredTourV2.serializer(), requireNotNull(entity.storedTourJson))
            } finally { reader.close() }
        }
    }

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        val namespace = "planner-draft-completion-${UUID.randomUUID()}"
        val root = File(base.filesDir, "instrumentation/$namespace").apply { check(mkdirs()) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory("files")
        override fun getCacheDir(): File = directory("cache")
        override fun getCodeCacheDir(): File = directory("code-cache")
        override fun getNoBackupFilesDir(): File = directory("no-backup")
        override fun getDataDir(): File = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            require(name.matches(Regex("[A-Za-z0-9_.-]+")))
            return baseContext.getSharedPreferences("${namespace}_$name", mode)
        }
        override fun getDatabasePath(name: String): File {
            require(name == "anitabi.db")
            return File(directory("databases"), name)
        }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
        override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        override fun databaseList(): Array<String> = if (getDatabasePath("anitabi.db").isFile) arrayOf("anitabi.db") else emptyArray()
        private fun directory(name: String) = File(root, name).apply { check(isDirectory || mkdirs()) }
    }

    companion object {
        private const val REGION = "TEST_ONLY_completion_v1"
        private val anime = Anime(101, "SYNTHETIC_COMPLETION")
        private val points = listOf(PilgrimagePoint("101::first", "SYNTHETIC_FIRST", GeoPoint(1.125, 2.25)))
        private fun draft(id: String) = PlannerDraft(id, listOf(anime), anime, points,
            transitDate = "2026-09-24", transitTime = "12:00", transitZoneId = "UTC")
        private fun plan(id: String) = TourPlan(id, anime, points, points, emptyList(), TravelMode.WALK,
            RouteObjective.FASTEST, EndPolicy.OPEN, 0.0, emptyList(), initialStart = points.first().coordinate,
            state = NavigationState.NAVIGATING, transitTimeMode = TransitTimeMode.NOW, regionDataVersion = REGION)
    }
}
