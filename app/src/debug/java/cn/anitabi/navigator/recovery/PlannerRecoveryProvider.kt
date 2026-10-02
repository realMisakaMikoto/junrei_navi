package cn.anitabi.navigator.recovery

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import androidx.lifecycle.ViewModelProvider
import cn.anitabi.navigator.MainActivity
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.PlannerDraft
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TransitTimeMode
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.ui.planner.PlannerUiState
import cn.anitabi.navigator.ui.planner.PlannerViewModel
import cn.anitabi.navigator.ui.search.SearchViewModel
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Debug manifest additionally requires android.permission.DUMP; no exported release component. */
class PlannerRecoveryProvider : ContentProvider() {
    private val application get() = requireNotNull(context).applicationContext as PlannerRecoveryApplication
    private val json = Json { encodeDefaults = true }
    private val expectedFile get() = File(application.harnessDirectory, "expected.json")

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(Binder.getCallingUid() in setOf(0, 2000)) { "Shell-only fixture" }
        check(application.fixtureEnabled) { "Recovery fixture disabled" }
        require(arg == null && (extras == null || extras.isEmpty)) { "Fixture accepts no arbitrary input" }
        require(method in setOf("prepare", "edit", "clear", "status")) { "Unknown fixture method" }
        val report = runBlocking {
            withTimeout(15_000) {
                when (method) {
                    "prepare" -> prepare()
                    "edit" -> edit()
                    "clear" -> {
                        onActivity { ViewModelProvider(it)[SearchViewModel::class.java].clearSelection() }
                        check(application.container.plannerDraftRepository.flush())
                    }
                }
                status()
            }
        }
        return Bundle().apply { putString("report", report) }
    }

    private suspend fun prepare() {
        check(application.routingAttemptCount() == 0)
        val drafts = application.container.plannerDraftRepository
        drafts.awaitLoaded()
        drafts.clear()
        check(drafts.flush())
        onActivity {
            val selection = ViewModelProvider(it)[SearchViewModel::class.java]
            selection.clearSelection()
            selection.addSelections(RecoveryDiscoverySource.points.map { point -> RecoveryDiscoverySource.anime to point })
        }
        check(drafts.flush())
    }

    private suspend fun edit() {
        val planner = onActivity { ViewModelProvider(it)[PlannerViewModel::class.java] }
        withContext(Dispatchers.Main) {
            check(planner.state.value.draftId != null && planner.state.value.selectedPoints.size == 4)
            check(!planner.state.value.isRestoringDraft)
            planner.setMode(TravelMode.BIKE)
            planner.setObjective(RouteObjective.SHORTEST)
            planner.setEndPolicy(EndPolicy.FIXED)
            planner.setDwellMinutes("27")
            planner.setTransitSchedule(TransitTimeMode.DEPART_AT, LocalDate.of(2026, 10, 2), LocalTime.of(9, 45))
            planner.moveDraft(1, 2)
        }
        check(planner.flushDraft() != null)
        val expected = requireNotNull(application.container.plannerDraftRepository.state.value.draft)
        check(expected.manualOrderRequested && expected.manualOrderPointIds != expected.selectedPoints.map(PilgrimagePoint::id))
        withContext(Dispatchers.IO) {
            FileOutputStream(expectedFile).use {
                it.write(json.encodeToString(PlannerDraft.serializer(), expected).toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
        }
    }

    private suspend fun status(): String {
        val expected = withContext(Dispatchers.IO) {
            if (expectedFile.isFile) json.decodeFromString(PlannerDraft.serializer(), expectedFile.readText()) else null
        }
        val drafts = application.container.plannerDraftRepository.awaitLoaded()
        return withContext(Dispatchers.Main) {
            val activity = application.activity.get()
            val planner = activity?.let { runCatching { ViewModelProvider(it)[PlannerViewModel::class.java].state.value }.getOrNull() }
            val selection = activity?.let { runCatching { ViewModelProvider(it)[SearchViewModel::class.java].state.value }.getOrNull() }
            buildJsonObject {
                put("schema", 1)
                put("pid", Process.myPid())
                put("taskId", activity?.taskId ?: -1)
                put("activityReady", activity != null && planner != null && selection != null)
                put("activityStopped", application.activityStopped)
                put("activityRestored", application.activityRestored)
                put("savedStateCallbacks", application.savedStateCallbacks)
                put("routeAttemptCount", application.routingAttemptCount())
                put("selectedCount", selection?.selectedPointIds?.size ?: -1)
                put("plannerReady", planner?.canGenerate == true)
                put("plannerHasDraft", planner?.draftId != null)
                put("plannerHasPlan", planner?.plan != null)
                put("recoveryError", planner?.draftRecoveryError != null)
                put("restoring", planner?.isRestoringDraft == true)
                put("draftPresent", drafts.draft != null)
                put("draftProblem", drafts.problem?.name ?: "NONE")
                put("draftIdMatches", expected != null && planner?.draftId == expected.draftId)
                put("diskInputMatches", expected != null && drafts.draft == expected)
                put("plannerInputMatches", expected != null && planner?.matches(expected) == true)
                put("selectionMatches", expected != null && selection?.selectedPointIds == expected.selectedPoints.mapTo(hashSetOf(), PilgrimagePoint::id))
            }.toString()
        }
    }

    private suspend fun <T> onActivity(block: (MainActivity) -> T): T = withContext(Dispatchers.Main) {
        block(requireNotNull(application.activity.get()) { "Main activity unavailable" })
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

private fun PlannerUiState.matches(expected: PlannerDraft): Boolean =
    anime == expected.displayAnime && selectedPoints == expected.selectedPoints &&
        draftOrder.map(PilgrimagePoint::id) == expected.manualOrderPointIds && manualOrderRequested == expected.manualOrderRequested &&
        mode == expected.mode && objective == expected.objective && endPolicy == expected.endPolicy &&
        startPointId == expected.startPointId && useCurrentLocation == expected.useCurrentLocation && fixedEndPointId == expected.fixedEndPointId &&
        dwellMinutesInput == expected.dwellMinutesInput && transitTimeMode == expected.transitTimeMode &&
        transitDate.toString() == expected.transitDate && transitTime.toString() == expected.transitTime && transitZoneId == expected.transitZoneId &&
        transitRoutingPreference == expected.transitRoutingPreference && transitTravelModes == expected.transitTravelModes
