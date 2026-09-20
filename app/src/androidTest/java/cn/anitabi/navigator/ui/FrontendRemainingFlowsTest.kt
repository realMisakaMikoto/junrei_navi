package cn.anitabi.navigator.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import cn.anitabi.navigator.SyntheticDiscoveryFixture
import cn.anitabi.navigator.TEST_REGION_DATA_VERSION
import cn.anitabi.navigator.TestAnitabiApplication
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.NavigationProgress
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TerritoryRegion
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.data.local.TourPlanDao
import cn.anitabi.navigator.data.local.TourPlanEntity
import cn.anitabi.navigator.data.repository.SavedTour
import cn.anitabi.navigator.data.repository.TourRepository
import cn.anitabi.navigator.navigation.ActiveNavigationStore
import cn.anitabi.navigator.navigation.NavigationRuntime
import cn.anitabi.navigator.navigation.NavigationRuntimeState
import cn.anitabi.navigator.navigation.NavigationViewModel
import cn.anitabi.navigator.ui.discovery.DiscoveryImageViewer
import cn.anitabi.navigator.ui.navigation.NavigationRoute
import cn.anitabi.navigator.ui.search.SearchUiState
import cn.anitabi.navigator.ui.theme.AnitabiTheme
import cn.anitabi.navigator.ui.trips.SavedTourRoute
import cn.anitabi.navigator.ui.trips.TripsRoute
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Production UI with shared synthetic points; no SDK views, route requests or public image fetches. */
class FrontendRemainingFlowsTest {
    @get:Rule val composeRule = createComposeRule()
    private val application = ApplicationProvider.getApplicationContext<TestAnitabiApplication>()

    @Test
    fun savedTripsEntryKeepsOrderAndProgressAndResumesOnlyOnExplicitAction() = runBlocking<Unit> {
        val repository = repository()
        val plan = fixturePlan()
        val progress = NavigationProgress(
            tourId = plan.id, completedPointIds = setOf(plan.selectedPoints.first().id),
            state = NavigationState.DWELLING,
        )
        repository.save(plan, progress)
        val expected = requireNotNull(repository.get(plan.id))
        var recordId by mutableStateOf<String?>(null)
        var resumed: SavedTour? = null
        composeRule.setContent {
            AnitabiTheme {
                if (recordId == null) TripsRoute(
                    repository, SearchUiState(), NavigationRuntimeState(),
                    onSettings = {}, onDiscover = {}, onPlan = {}, onEditSelection = {},
                    onResume = {}, onSaved = { recordId = it },
                ) else SavedTourRoute(
                    repository, requireNotNull(recordId), onBack = { recordId = null },
                    onResume = { resumed = it }, onReplan = { error("Unexpected replan") },
                )
            }
        }
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(VIEW_RECORD))
        composeRule.onNodeWithText(VIEW_RECORD).performClick()
        composeRule.onNodeWithText(CONTINUE).assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText(plan.orderedPoints.first().name).assertIsDisplayed()
        composeRule.onNodeWithText(plan.orderedPoints.last().name).assertIsDisplayed()
        assertTrue(
            composeRule.onNodeWithText(plan.orderedPoints.first().name).fetchSemanticsNode().boundsInRoot.top <
                composeRule.onNodeWithText(plan.orderedPoints.last().name).fetchSemanticsNode().boundsInRoot.top,
        )
        composeRule.onRoot().captureFrontendReview("saved-record-progress")
        composeRule.runOnIdle { assertEquals(null, resumed) }
        composeRule.onNodeWithText(CONTINUE).performClick()
        composeRule.runOnIdle { assertEquals(expected, resumed) }
        assertEquals(expected, repository.get(plan.id))
        Espresso.pressBack()
        composeRule.onNodeWithTag("trips-screen").assertIsDisplayed()
    }

    @Test
    fun savedRecordReplanIsExplicitAndReadFailureKeepsBackAvailable() = runBlocking<Unit> {
        val dao = MemoryTourDao()
        val repository = repository(dao)
        val plan = fixturePlan()
        repository.save(plan)
        val expected = requireNotNull(repository.get(plan.id))
        var recordId by mutableStateOf(plan.id)
        var replanned: SavedTour? = null
        var backCount = 0
        composeRule.setContent {
            AnitabiTheme {
                SavedTourRoute(repository, recordId, onBack = { backCount++ },
                    onResume = { error("Unexpected resume") }, onReplan = { replanned = it })
            }
        }
        composeRule.onNodeWithText(REPLAN).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(expected, replanned) }
        assertEquals(expected, repository.get(plan.id))
        composeRule.runOnIdle { dao.failReads = true; recordId = "synthetic-load-failure" }
        composeRule.onNodeWithText(RECOVERY_FAILED).assertIsDisplayed()
        composeRule.onRoot().captureFrontendReview("saved-record-read-failure")
        Espresso.pressBack()
        composeRule.runOnIdle { assertEquals(1, backCount) }
    }

    @Test
    fun futureEditorKeepsFixedEndAndShowsConcurrentStateFailureWithoutSaving() = runBlocking<Unit> {
        val repository = repository()
        val base = fixturePlan()
        val fixedEnd = base.selectedPoints.last().copy(id = "synthetic-fixed-end", name = "Synthetic fixed end")
        val plan = base.copy(
            selectedPoints = base.selectedPoints + fixedEnd,
            orderedPoints = listOf(base.selectedPoints.last(), fixedEnd), endPolicy = EndPolicy.FIXED,
        )
        val progress = NavigationProgress(
            tourId = plan.id, legIndex = -1, state = NavigationState.DWELLING,
            completedPointIds = setOf(plan.selectedPoints.first().id),
        )
        repository.save(plan, progress)
        val expected = requireNotNull(repository.get(plan.id))
        val previousRuntime = NavigationRuntime.state.value
        val previousActiveId = ActiveNavigationStore.get(application)
        val store = ViewModelStore()
        lateinit var viewModel: NavigationViewModel
        try {
            composeRule.runOnIdle {
                // Skip startup recovery, then display a stopped no-leg recovery view: no SDK is created.
                NavigationRuntime.set(NavigationRuntimeState(plan = plan, progress = progress, isRunning = true))
                viewModel = NavigationViewModel(application, repository, application.container.tourPlanner)
                store.put("remaining-editor", viewModel)
                NavigationRuntime.update { it.copy(isRunning = false) }
            }
            composeRule.setContent {
                AnitabiTheme { NavigationRoute(viewModel, plan.selectedPoints, onBack = {}) }
            }
            // The dialog is private; enter through its real public ViewModel action, not reflection.
            composeRule.runOnIdle { viewModel.openFutureEditor(plan.selectedPoints) }
            composeRule.onNodeWithText(EDITOR_TITLE).assertIsDisplayed()
            composeRule.onAllNodes(hasText(DELETE) and isEnabled()).assertCountEquals(1)
            composeRule.onAllNodes(hasText(DELETE) and !isEnabled()).assertCountEquals(1)
            composeRule.onNode(isDialog()).captureFrontendReview("future-editor-fixed-end", nativeWindow = true)
            composeRule.onNode(hasText(DELETE) and isEnabled()).performScrollTo().performClick()
            composeRule.runOnIdle {
                assertEquals(listOf(fixedEnd.id), viewModel.editState.value.futurePoints.map { it.id })
                // Ending navigation during editing is rejected before the planner can request a route.
                NavigationRuntime.update { it.copy(progress = progress.copy(state = NavigationState.ENDED)) }
            }
            composeRule.onNodeWithText(SAVE).performClick()
            composeRule.onNodeWithText(EDIT_FAILED).assertIsDisplayed()
            composeRule.onNodeWithText(SAVE).assertIsEnabled()
            composeRule.onNode(isDialog()).captureFrontendReview("future-editor-save-failure", nativeWindow = true)
            assertEquals(expected, repository.get(plan.id))
            composeRule.onNodeWithText(CANCEL).performClick()
            composeRule.runOnIdle { assertFalse(viewModel.editState.value.isOpen) }
        } finally {
            composeRule.runOnIdle {
                store.clear()
                NavigationRuntime.set(previousRuntime)
                if (previousActiveId == null) ActiveNavigationStore.clear(application)
                else ActiveNavigationStore.set(application, previousActiveId)
            }
        }
    }

    @OptIn(DelicateCoilApi::class)
    @Test
    fun fullscreenImageClosesWithBackAndFailureKeepsCloseAction() {
        val previousLoader = SingletonImageLoader.get(application)
        val requests = AtomicInteger()
        val loader = ImageLoader.Builder(application).components {
            add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                    val url = chain.request.data
                    check(url == IMAGE_OK || url == IMAGE_FAIL) { "Unexpected image fixture request" }
                    requests.incrementAndGet()
                    return if (url == IMAGE_FAIL) ErrorResult(
                        image = null, request = chain.request, throwable = IOException("Synthetic image failure"),
                    ) else SuccessResult(
                        image = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
                            .apply { eraseColor(Color.rgb(66, 107, 98)) }.asImage(),
                        request = chain.request, dataSource = DataSource.MEMORY,
                    )
                }
            })
        }.build()
        SingletonImageLoader.setUnsafe(loader)
        try {
            var image by mutableStateOf<String?>(null)
            composeRule.setContent {
                AnitabiTheme {
                    Column {
                        Button(onClick = { image = IMAGE_OK }) { Text("Open fixture image") }
                        Button(onClick = { image = IMAGE_FAIL }) { Text("Open failed fixture image") }
                    }
                    image?.let { DiscoveryImageViewer(it, onClose = { image = null }) }
                }
            }
            composeRule.onNodeWithText("Open fixture image").performClick()
            composeRule.waitUntil(5_000) { requests.get() == 1 }
            composeRule.onNodeWithContentDescription(CLOSE_IMAGE).assertIsDisplayed()
            composeRule.onNode(isDialog()).captureFrontendReview("fullscreen-image", nativeWindow = true)
            Espresso.pressBack()
            composeRule.onNodeWithContentDescription(CLOSE_IMAGE).assertDoesNotExist()
            composeRule.onNodeWithText("Open failed fixture image").performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(IMAGE_FAILED).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(IMAGE_FAILED).assertIsDisplayed()
            composeRule.onNode(isDialog()).captureFrontendReview("fullscreen-image-failure", nativeWindow = true)
            composeRule.onNodeWithContentDescription(CLOSE_IMAGE).performClick()
            composeRule.runOnIdle { assertEquals(null, image); assertEquals(2, requests.get()) }
        } finally {
            SingletonImageLoader.setUnsafe(previousLoader)
            loader.shutdown()
        }
    }

    private fun fixturePlan(): TourPlan {
        val snapshot = SyntheticDiscoveryFixture.snapshot()
        val points = snapshot.points.map { it.toPilgrimagePoint() }
        return TourPlan(
            id = "synthetic-remaining-flows", anime = snapshot.subjects.single().anime,
            selectedPoints = points, orderedPoints = points.reversed(), legs = emptyList(),
            mode = TravelMode.WALK, objective = RouteObjective.SHORTEST, endPolicy = EndPolicy.OPEN,
            estimatedDurationSeconds = 0.0, attribution = emptyList(), initialStart = points.first().coordinate,
            regionDataVersion = TEST_REGION_DATA_VERSION,
        )
    }

    private fun repository(dao: MemoryTourDao = MemoryTourDao()) = TourRepository(
        dao = dao, json = Json, classifyTerritory = { TerritoryRegion.OTHER },
        regionDataVersion = { TEST_REGION_DATA_VERSION },
    )

    private class MemoryTourDao : TourPlanDao {
        private val entities = linkedMapOf<String, TourPlanEntity>()
        var failReads = false
        override suspend fun get(id: String): TourPlanEntity? {
            check(!failReads) { "Synthetic read failure" }
            return entities[id]
        }
        override suspend fun getMostRecent() = entities.values.lastOrNull()
        override suspend fun getIdsMostRecentFirst() = entities.keys.toList().asReversed()
        override suspend fun upsert(entity: TourPlanEntity) { entities[entity.id] = entity }
        override suspend fun finishLegacyMigration(id: String, storedTourJson: String, updatedAtEpochMillis: Long) = Unit
        override suspend fun recordMigrationError(id: String, message: String) = Unit
        override suspend fun getMostRecentMigrationError(): String? = null
    }

    private companion object {
        const val VIEW_RECORD = "\u67e5\u770b\u884c\u7a0b"
        const val CONTINUE = "\u7ee7\u7eed\u884c\u7a0b"
        const val REPLAN = "\u6309\u539f\u987a\u5e8f\u5237\u65b0\u8def\u7ebf"
        const val RECOVERY_FAILED = "\u884c\u7a0b\u6062\u590d\u5931\u8d25\uff0c\u8bf7\u8fd4\u56de\u540e\u91cd\u8bd5"
        const val EDITOR_TITLE = "\u7f16\u8f91\u540e\u7eed\u5de1\u793c\u70b9"
        const val DELETE = "\u5220\u9664"
        const val SAVE = "\u4fdd\u5b58"
        const val CANCEL = "\u53d6\u6d88"
        const val EDIT_FAILED = "\u65e0\u6cd5\u4fdd\u5b58\u540e\u7eed\u70b9\uff0c\u8bf7\u4fdd\u6301\u5df2\u5b8c\u6210\u70b9\u548c\u5f53\u524d\u70b9\u4e0d\u53d8"
        const val CLOSE_IMAGE = "\u5173\u95ed\u56fe\u7247"
        const val IMAGE_FAILED = "\u56fe\u7247\u6682\u65f6\u65e0\u6cd5\u52a0\u8f7d"
        const val IMAGE_OK = "https://image.anitabi.cn/synthetic-frontend-image.png"
        const val IMAGE_FAIL = "https://image.anitabi.cn/synthetic-frontend-failed.png"
    }
}
