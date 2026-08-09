package cn.anitabi.navigator.navigation

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.NavigationProgress
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.data.network.ApiException
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationFailureMessageTest {
    @Test
    fun `shared monthly quota error is localized and explains that billing has stopped`() {
        assertEquals(
            "本月共享路线额度已用尽，暂时无法开始；不会继续产生费用",
            navigationFailureMessage(ApiException.QuotaExhausted()),
        )
    }

    @Test
    fun `network error does not expose the exception message`() {
        assertEquals(
            "无法连接路线服务，请检查网络后重试",
            navigationFailureMessage(ApiException.Network(IOException("private network detail"))),
        )
    }

    @Test
    fun `upstream failure is not described as missing transit coverage`() {
        assertEquals(
            "路线服务暂时不可用，请稍后再试",
            navigationFailureMessage(ApiException.UpstreamUnavailable()),
        )
    }

    @Test
    fun `failed navigation becomes resumable instead of remaining in progress`() {
        val progress = NavigationProgress(
            tourId = "tour",
            legIndex = 3,
            state = NavigationState.NAVIGATING,
            offRouteSinceEpochMillis = 123L,
        )

        val resumable = requireNotNull(resumableProgressAfterFailure(progress))

        assertEquals(NavigationState.PLANNED, resumable.state)
        assertEquals(3, resumable.legIndex)
        assertNull(resumable.offRouteSinceEpochMillis)
    }

    @Test
    fun `completed navigation is never reopened after a later failure`() {
        val progress = NavigationProgress(tourId = "tour", state = NavigationState.COMPLETED)

        assertNull(resumableProgressAfterFailure(progress))
    }

    @Test
    fun `failure recovery never copies progress from another tour`() {
        val oldProgress = NavigationProgress(
            tourId = "old-tour",
            legIndex = 3,
            completedPointIds = setOf("old-stop"),
            state = NavigationState.NAVIGATING,
        )

        assertNull(
            resumableProgressForTourAfterFailure(
                tourId = "new-tour",
                engineProgress = oldProgress,
                runtimeProgress = oldProgress,
            ),
        )
    }

    @Test
    fun `failure state replaces the plan and clears stale progress from another tour`() {
        val oldPlan = emptyPlan("old-tour")
        val currentPlan = emptyPlan("new-tour")
        val oldProgress = NavigationProgress(
            tourId = oldPlan.id,
            legIndex = 3,
            state = NavigationState.NAVIGATING,
        )

        val result = navigationRuntimeAfterFailure(
            previous = NavigationRuntimeState(plan = oldPlan, progress = oldProgress, isRunning = true),
            currentPlan = currentPlan,
            progress = null,
            message = "failed",
        )

        assertEquals(currentPlan, result.plan)
        assertNull(result.progress)
        assertEquals("failed", result.errorMessage)
        assertEquals(false, result.isRunning)
    }

    @Test
    fun `superseded cleanup still persists rollback without stopping the new service`() = runBlocking {
        var persisted = false
        var stopped = false

        completeNavigationCleanup(
            expectedGeneration = 1L,
            currentGeneration = { 2L },
            persistRollback = { persisted = true },
            stopService = { stopped = true },
        )

        assertEquals(true, persisted)
        assertEquals(false, stopped)
    }

    private fun emptyPlan(id: String) = TourPlan(
        id = id,
        anime = Anime(subjectId = 1, name = "Test"),
        selectedPoints = emptyList(),
        orderedPoints = emptyList(),
        legs = emptyList(),
        mode = TravelMode.WALK,
        objective = RouteObjective.FASTEST,
        endPolicy = EndPolicy.OPEN,
        estimatedDurationSeconds = 0.0,
        attribution = emptyList(),
    )
}
