package cn.anitabi.navigator.navigation

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import cn.anitabi.navigator.AnitabiApplication
import cn.anitabi.navigator.MainActivity
import cn.anitabi.navigator.R
import cn.anitabi.navigator.core.model.CoordinateSystem
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.NavigationProgress
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.TourLeg
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.core.model.isExternalMapNavigation
import cn.anitabi.navigator.core.navigation.JapanExternalTransitEngine
import cn.anitabi.navigator.core.navigation.JapanExternalTransitRuntimeState
import cn.anitabi.navigator.core.navigation.JapanExternalTransitUpdate
import cn.anitabi.navigator.core.navigation.JapanTransitLocationSample
import cn.anitabi.navigator.core.navigation.NavigationEngine
import cn.anitabi.navigator.core.navigation.NavigationUpdate
import cn.anitabi.navigator.core.navigation.TransitRefreshPolicy
import cn.anitabi.navigator.core.navigation.afterRouteRefresh
import cn.anitabi.navigator.data.network.ApiException
import cn.anitabi.navigator.data.repository.ConcurrentTourUpdateException
import java.time.OffsetDateTime
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NavigationService : Service(), LocationListener, TextToSpeech.OnInitListener {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container by lazy { (application as AnitabiApplication).container }
    private val locationManager by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    private var plan: TourPlan? = null
    private var engine: NavigationEngine? = null
    private var japanEngine: JapanExternalTransitEngine? = null
    private var pendingHandoff: PendingHandoff? = null
    private var pendingResumeTourId: String? = null
    private var loadingTourId: String? = null
    private var overlayController: TransitOverlayController? = null
    private var ticker: Job? = null
    private var loadJob: Job? = null
    private var deferredStartJob: Job? = null
    private var reroute: Job? = null
    private var roadSyncJob: Job? = null
    private var progressSaveJob: Job? = null
    private var japanCommitJob: Job? = null
    private var japanCommitInFlight = false
    private var coldEndTourId: String? = null
    private var coldEndExpectedLegIndex: Int? = null
    private var coldEndLatestStartId = 0
    private val coldEndReceivers = mutableListOf<ResultReceiver>()
    private var cleanupJob: Job? = null
    private var roadNavigationSession: GoogleRoadNavigationSession? = null
    private var lastRoadSyncLegIndex: Int? = null
    private var nativeRemainingDistanceMeters: Double? = null
    private var nativeRerouting = false
    private var lastSavedProgress: NavigationProgress? = null
    private var lastSpokenKey: String? = null
    private var lastTransitRefreshKey: String? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var stopping = false
    private var navigationGeneration = 0L

    private data class PendingHandoff(
        val tourId: String,
        val expectedLegIndex: Int,
        val advance: Boolean,
        val receiver: ResultReceiver,
    )

    private data class PendingReload(
        val tourId: String,
        val receiver: ResultReceiver,
    )

    private data class PersistedNavigationStop(
        val plan: TourPlan,
        val progress: NavigationProgress,
    )

    private var pendingReload: PendingReload? = null

    override fun onCreate() {
        super.onCreate()
        NavigationControlAvailability.ensureChannel(this)
        overlayController = TransitOverlayController(this)
        tts = TextToSpeech(this, this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayController?.reflow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END_EXTERNAL) {
            endExternalNavigation(intent, startId)
            return START_NOT_STICKY
        }
        if (rejectExternalLocationActionWithoutPermission(intent, startId)) {
            return START_NOT_STICKY
        }
        if (intent.requiresFineLocationForeground()) {
            try {
                ensureForeground()
            } catch (exception: RuntimeException) {
                if (exception is SecurityException) {
                    reportExternalLocationPermissionFailure(intent, startId)
                } else {
                    val message = "Unable to start location navigation; open the app and retry"
                    intent?.resultReceiver()?.sendError(message)
                    stopAfterForegroundPromotionFailure(message, startId)
                }
                return START_NOT_STICKY
            }
        }
        when (intent?.action) {
            ACTION_STOP -> stopNavigation()
            ACTION_MANUAL_ARRIVAL -> processUpdate(engine?.manualArrival())
            ACTION_REFRESH_TRANSIT -> refreshTransitRoute()
            ACTION_START -> startNavigation(intent.getStringExtra(EXTRA_TOUR_ID), startId)
            ACTION_RELOAD_TOUR -> reloadNavigation(intent, startId)
            ACTION_PREPARE_HANDOFF -> prepareExternalHandoff(intent, advance = false)
            ACTION_PREPARE_NEXT_HANDOFF -> prepareExternalHandoff(intent, advance = true)
            ACTION_CONFIRM_EXTERNAL_ARRIVAL -> confirmExternalArrival(intent)
            ACTION_PAUSE_EXTERNAL -> {
                if (plan == null && japanEngine == null) {
                    stopSelf(startId)
                } else {
                    pauseExternalNavigation()
                }
            }
            ACTION_RESUME_EXTERNAL -> resumeExternalNavigation(intent.getStringExtra(EXTRA_TOUR_ID))
            else -> ActiveNavigationStore.get(this)?.let { startNavigation(it, startId) } ?: stopSelf(startId)
        }
        return START_STICKY
    }

    private fun rejectExternalLocationActionWithoutPermission(intent: Intent?, startId: Int): Boolean {
        if (
            !intent.requiresFineLocationForeground() ||
            AndroidLocationProvider.hasFineLocationPermission(this)
        ) {
            return false
        }
        reportExternalLocationPermissionFailure(intent, startId)
        return true
    }

    private fun reportExternalLocationPermissionFailure(intent: Intent?, startId: Int) {
        val strategy = plan?.executionStrategy ?: NavigationRuntime.state.value.plan?.executionStrategy
        val message = externalLocationPermissionMessage(
            strategy = strategy,
            resume = intent?.action == ACTION_RESUME_EXTERNAL,
        )
        intent?.resultReceiver()?.sendError(message)
        NavigationRuntime.update { state ->
            state.copy(
                isRunning = if (plan == null) false else state.isRunning,
                errorMessage = message,
            )
        }
        if (plan == null) {
            stopSelf(startId)
        } else {
            pauseExternalNavigation()
            runCatching { updateNotification(message) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLocationChanged(location: Location) {
        if (stopping) return
        val current = GeoPoint(location.latitude, location.longitude)
        if (plan.isExternalJapanTransit()) {
            NavigationRuntime.update { it.copy(currentLocation = current) }
            if (japanCommitInFlight) return
            val activeEngine = japanEngine ?: return
            val before = activeEngine.progress
            val update = activeEngine.onLocation(
                JapanTransitLocationSample(
                    coordinate = current,
                    accuracyMeters = if (location.hasAccuracy()) {
                        location.accuracy.toDouble()
                    } else {
                        Double.MAX_VALUE
                    },
                    elapsedRealtimeMillis = location.elapsedRealtimeNanos
                        .takeIf { it > 0L }
                        ?.div(1_000_000L)
                        ?: SystemClock.elapsedRealtime(),
                ),
            )
            commitJapanUpdateIfChanged(
                activeEngine = activeEngine,
                before = before,
                update = update,
            )
            return
        }
        if (plan?.mode != TravelMode.TRANSIT) {
            NavigationRuntime.update { it.copy(currentLocation = current) }
            return
        }
        val update = engine?.onLocation(current, System.currentTimeMillis()) ?: return
        val generation = navigationGeneration
        processUpdate(update, generation)
        if (update.requestReroute && reroute?.isActive != true) {
            reroute = serviceScope.launch { rerouteFrom(current, update.progress, generation) }
        }
    }

    @Deprecated("Required by LocationListener on Android 8")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onDestroy() {
        pendingReload?.receiver?.sendError("Navigation service stopped before the tour reloaded")
        pendingReload = null
        runCatching { locationManager.removeUpdates(this) }
        overlayController?.remove()
        overlayController = null
        ticker?.cancel()
        loadJob?.cancel()
        deferredStartJob?.cancel()
        reroute?.cancel()
        roadSyncJob?.cancel()
        cleanupJob?.cancel()
        japanCommitJob?.cancel()
        coldEndReceivers.clear()
        roadNavigationSession?.close()
        tts?.stop()
        tts?.shutdown()
        plan?.id?.let { container.tourRepository.clearRuntimeProgress(it) }
        NavigationRuntime.update { it.copy(isRunning = false, isRerouting = false) }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun reloadNavigation(intent: Intent, startId: Int) {
        val tourId = intent.getStringExtra(EXTRA_TOUR_ID)
        if (tourId.isNullOrBlank()) {
            intent.resultReceiver()?.sendError("Missing tour to reload")
            return
        }
        supersedePendingReload()
        intent.resultReceiver()?.let { receiver ->
            pendingReload = PendingReload(tourId, receiver)
        }
        startNavigation(tourId, startId, preservePendingReload = true)
    }

    private fun startNavigation(
        tourId: String?,
        startId: Int? = null,
        preservePendingReload: Boolean = false,
    ) {
        if (!preservePendingReload) supersedePendingReload()
        if (japanCommitInFlight) {
            val pendingCommit = japanCommitJob ?: return
            deferredStartJob?.cancel()
            deferredStartJob = serviceScope.launch {
                pendingCommit.join()
                startNavigation(tourId, startId, preservePendingReload)
            }
            return
        }
        val activeTourId = tourId ?: ActiveNavigationStore.get(this)
        if (activeTourId == null) {
            failAndStop("没有可恢复的巡礼路线")
            return
        }
        if (!AndroidLocationProvider.hasLocationPermission(this)) {
            stopAfterForegroundPromotionFailure(
                message = "Location permission is required to resume navigation",
                startId = startId,
            )
            return
        }
        val promoted = tryLocationForegroundPromotion(
            promote = { startForeground(NOTIFICATION_ID, notification("正在恢复巡礼路线…")) },
            onFailure = {
                stopAfterForegroundPromotionFailure(
                    message = "Unable to start location navigation; open the app and retry",
                    startId = startId,
                )
            },
        )
        if (!promoted) return
        val generation = ++navigationGeneration
        stopping = false
        loadingTourId = activeTourId
        val previousCleanup = cleanupJob
        val previousLoad = loadJob
        previousLoad?.cancel()
        runCatching { locationManager.removeUpdates(this) }
        ticker?.cancel()
        ticker = null
        val previousReroute = reroute
        previousReroute?.cancel()
        reroute = null
        roadSyncJob?.cancel()
        roadSyncJob = null
        roadNavigationSession?.close()
        roadNavigationSession = null
        engine = null
        japanEngine = null
        overlayController?.remove()
        plan = null
        lastSavedProgress = null
        lastRoadSyncLegIndex = null
        lastSpokenKey = null
        lastTransitRefreshKey = null
        nativeRemainingDistanceMeters = null
        nativeRerouting = false
        val pendingSave = progressSaveJob
        val pendingJapanCommit = japanCommitJob
        loadJob = serviceScope.launch {
            previousLoad?.join()
            previousReroute?.join()
            previousCleanup?.join()
            pendingSave?.join()
            pendingJapanCommit?.join()
            if (generation != navigationGeneration) return@launch
            loadAndStart(activeTourId, generation)
        }
    }

    private suspend fun loadAndStart(tourId: String?, generation: Long) {
        var routeRefreshRequired = false
        try {
            if (generation != navigationGeneration) return
            NavigationRuntime.update { it.copy(errorMessage = null) }
            runCatching { locationManager.removeUpdates(this) }
            val saved = tourId?.let { container.tourRepository.get(it) }
                ?: error("没有可恢复的巡礼路线")
            saved.routingError?.let { routingError -> error(storedRoutingErrorMessage(routingError)) }
            if (generation != navigationGeneration) return
            if (saved.progress?.state in setOf(NavigationState.COMPLETED, NavigationState.ENDED)) {
                error("这条巡礼路线已经结束")
            }
            ActiveNavigationStore.set(this, saved.plan.id)
            var loadedPlan = saved.plan
            plan = loadedPlan
            val externalMap = loadedPlan.isExternalJapanTransit()
            if (externalMap && !AndroidLocationProvider.hasFineLocationPermission(this)) {
                throw MissingLocationPermissionException()
            }
            val destinations = loadedPlan.legs.mapNotNull { it.destinationPointId }.toSet()
            val startPointIds = if (externalMap) {
                emptySet()
            } else {
                loadedPlan.orderedPoints
                    .filter { it.id !in destinations && it.coordinate == loadedPlan.initialStart }
                    .map { it.id }
                    .toSet() + listOfNotNull(saved.storedTour.startPointId)
            }
            var initialProgress = (saved.progress ?: NavigationProgress(tourId = loadedPlan.id)).let {
                it.copy(completedPointIds = it.completedPointIds + startPointIds)
            }
            if (saved.routeNeedsRefresh) {
                if (externalMap) {
                    loadedPlan = container.tourPlanner.rebuild(loadedPlan, loadedPlan.orderedPoints)
                    container.tourRepository.save(loadedPlan, initialProgress)
                } else {
                    routeRefreshRequired = true
                    NavigationRuntime.set(
                        NavigationRuntimeState(
                            plan = loadedPlan,
                            progress = initialProgress,
                            instruction = ROUTE_REFRESH_REQUIRED_MESSAGE,
                            errorMessage = ROUTE_REFRESH_REQUIRED_MESSAGE,
                        ),
                    )
                    val currentLocation = container.locationProvider.currentLocation()
                    if (generation != navigationGeneration) return
                    loadedPlan = container.tourPlanner.replanRemaining(
                        plan = loadedPlan,
                        currentLocation = currentLocation,
                        completedPointIds = initialProgress.completedPointIds,
                        currentTime = OffsetDateTime.now().toString(),
                    )
                    if (generation != navigationGeneration) return
                    initialProgress = initialProgress.afterRouteRefresh(loadedPlan.legs.isNotEmpty())
                    container.tourRepository.save(loadedPlan, initialProgress)
                    if (generation != navigationGeneration) return
                    routeRefreshRequired = false
                    NavigationRuntime.update { it.copy(errorMessage = null) }
                }
            }
            if (initialProgress.state in setOf(NavigationState.COMPLETED, NavigationState.ENDED)) {
                error("这条巡礼路线已经结束")
            }
            externalPlanStartMismatch(loadedPlan)?.let { message -> error(message) }
            container.tourRepository.noteRuntimeProgress(initialProgress)
            if (externalMap && initialProgress.state == NavigationState.PLANNED) {
                if (!NavigationControlAvailability.hasExternalTransitControl(this)) {
                    throw ExternalTransitControlUnavailableException()
                }
                val fix = container.locationProvider.currentLocationFix(
                    maxAgeMillis = 30_000L,
                    maxAccuracyMeters = 100.0,
                    requireFinePermission = true,
                )
                if (generation != navigationGeneration) return
                loadedPlan = container.tourPlanner.rebuild(
                    loadedPlan.copy(initialStart = fix.coordinate),
                    loadedPlan.orderedPoints,
                )
                container.tourRepository.save(loadedPlan, initialProgress)
            }
            if (externalMap) {
                val loadedJapanEngine = JapanExternalTransitEngine(loadedPlan, initialProgress)
                plan = loadedPlan
                engine = null
                val firstUpdate = if (initialProgress.state == NavigationState.PLANNED) {
                    loadedJapanEngine.start()
                } else {
                    loadedJapanEngine.onTick(System.currentTimeMillis(), SystemClock.elapsedRealtime())
                }
                container.tourRepository.noteRuntimeProgress(firstUpdate.progress)
                try {
                    if (firstUpdate.progress != initialProgress) {
                        container.tourRepository.saveProgressOnLatestPlan(
                            basePlan = loadedPlan,
                            expectedProgress = initialProgress,
                            updatedProgress = firstUpdate.progress,
                        )
                    }
                } catch (exception: Exception) {
                    container.tourRepository.noteRuntimeProgress(initialProgress)
                    japanEngine = JapanExternalTransitEngine(loadedPlan, initialProgress)
                    throw exception
                }
                if (generation != navigationGeneration) return
                japanEngine = loadedJapanEngine
                lastSavedProgress = firstUpdate.progress
                processJapanUpdate(firstUpdate, generation)
                if (generation != navigationGeneration) return
                if (firstUpdate.progress.state in setOf(NavigationState.COMPLETED, NavigationState.ENDED)) return
                if (!firstUpdate.progress.isPaused) startLocationUpdates(requireFinePermission = true)
                startTicker(generation)
                pendingResumeTourId?.takeIf { it == loadedPlan.id }?.let {
                    pendingResumeTourId = null
                    resumeExternalNavigation(loadedPlan.id)
                }
                completePendingHandoff(generation)
                completePendingReload(generation, loadedPlan.id)
                return
            }
            val loadedEngine = NavigationEngine(loadedPlan, initialProgress)
            plan = loadedPlan
            engine = loadedEngine
            japanEngine = null
            roadNavigationSession?.close()
            roadNavigationSession = if (loadedPlan.mode == TravelMode.TRANSIT) {
                null
            } else {
                GoogleRoadNavigationSession(
                    application = application,
                    backendApi = container.backendApi,
                    plan = loadedPlan,
                    initialLegIndex = initialProgress.legIndex.coerceAtLeast(0),
                    onArrival = { legIndex ->
                        serviceScope.launch {
                            if (generation == navigationGeneration) onNativeArrival(legIndex, generation)
                        }
                    },
                    onRemainingDistanceChanged = { meters ->
                        serviceScope.launch {
                            if (generation != navigationGeneration) return@launch
                            nativeRemainingDistanceMeters = meters
                            NavigationRuntime.update { it.copy(remainingDistanceMeters = meters) }
                        }
                    },
                    onReroutingChanged = { rerouting ->
                        serviceScope.launch {
                            if (generation != navigationGeneration) return@launch
                            nativeRerouting = rerouting
                            NavigationRuntime.update { it.copy(isRerouting = rerouting) }
                        }
                    },
                )
            }
            val firstUpdate = if (initialProgress.state == NavigationState.PLANNED) {
                loadedEngine.start()
            } else {
                loadedEngine.onTick(System.currentTimeMillis())
            }
            lastSavedProgress = initialProgress
            processUpdate(firstUpdate, generation)
            if (generation != navigationGeneration) return
            startLocationUpdates()
            startTicker(generation)
            completePendingReload(generation, loadedPlan.id)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            failAndStop(
                if (routeRefreshRequired) {
                    ROUTE_REFRESH_REQUIRED_MESSAGE
                } else {
                    navigationFailureMessage(exception)
                },
                saveAsUnresolved = routeRefreshRequired,
                expectedGeneration = generation,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates(requireFinePermission: Boolean = false) {
        if (
            (requireFinePermission && !AndroidLocationProvider.hasFineLocationPermission(this)) ||
            (!requireFinePermission && !AndroidLocationProvider.hasLocationPermission(this))
        ) {
            throw MissingLocationPermissionException()
        }
        var requested = false
        val minDistanceMeters = navigationLocationUpdateMinDistanceMeters(plan.isExternalJapanTransit())
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            if (locationManager.isProviderEnabled(provider)) {
                locationManager.requestLocationUpdates(
                    provider,
                    2_000L,
                    minDistanceMeters,
                    this,
                    Looper.getMainLooper(),
                )
                requested = true
            }
        }
        if (!requested) throw LocationUnavailableException("No location provider is enabled")
    }

    private fun startTicker(generation: Long) {
        ticker?.cancel()
        ticker = serviceScope.launch {
            while (isActive && generation == navigationGeneration) {
                delay(1_000L)
                if (plan.isExternalJapanTransit()) {
                    enforceExternalControlAvailability(generation)
                    if (japanCommitInFlight) continue
                    val activeEngine = japanEngine ?: continue
                    val before = activeEngine.progress
                    val update = activeEngine.onTick(
                        System.currentTimeMillis(),
                        SystemClock.elapsedRealtime(),
                    )
                    commitJapanUpdateIfChanged(activeEngine, before, update, generation)
                } else {
                    processUpdate(engine?.onTick(System.currentTimeMillis()), generation)
                }
            }
        }
    }

    private fun processJapanUpdate(
        update: JapanExternalTransitUpdate?,
        expectedGeneration: Long = navigationGeneration,
    ) {
        if (stopping || expectedGeneration != navigationGeneration || update == null) return
        val currentPlan = plan ?: return
        container.tourRepository.noteRuntimeProgress(update.progress)
        val instruction = externalMapInstruction(currentPlan, update.progress)
        val targetDistance = update.runtimeState.targetDistanceMeters
        NavigationRuntime.set(
            NavigationRuntimeState(
                plan = currentPlan,
                progress = update.progress,
                currentLocation = NavigationRuntime.state.value.currentLocation,
                instruction = instruction,
                remainingDistanceMeters = targetDistance ?: 0.0,
                currentTargetDistanceMeters = targetDistance,
                isRunning = update.progress.state !in setOf(NavigationState.COMPLETED, NavigationState.ENDED),
                isRerouting = false,
                errorMessage = NavigationRuntime.state.value.errorMessage,
            ),
        )
        updateNotification(instruction)
        overlayController?.render(currentPlan, update.progress, targetDistance)
        if (
            update.progress.state in setOf(
                NavigationState.ARRIVING,
                NavigationState.DWELLING,
                NavigationState.NEXT_STOP,
                NavigationState.COMPLETED,
            )
        ) {
            speak(instruction, "external:${update.progress.state}:${update.progress.legIndex}")
        }
        if (update.progress.state in setOf(NavigationState.COMPLETED, NavigationState.ENDED)) {
            ActiveNavigationStore.clear(this, currentPlan.id)
            container.tourRepository.clearRuntimeProgress(currentPlan.id, update.progress)
            finishCompletedNavigation(expectedGeneration)
        }
    }

    private fun commitJapanUpdateIfChanged(
        activeEngine: JapanExternalTransitEngine,
        before: NavigationProgress,
        update: JapanExternalTransitUpdate,
        expectedGeneration: Long = navigationGeneration,
        forcePersist: Boolean = false,
        onCommitted: (TourPlan) -> Unit = {},
        onFailure: (String) -> Unit = {},
    ): Boolean {
        val currentPlan = plan
        if (
            stopping || expectedGeneration != navigationGeneration || currentPlan == null ||
            japanEngine !== activeEngine
        ) {
            onFailure(STALE_EXTERNAL_TRANSIT_MESSAGE)
            return false
        }
        if (japanCommitInFlight) {
            onFailure(EXTERNAL_TRANSIT_SAVE_IN_PROGRESS_MESSAGE)
            return false
        }
        if (externalTransitOverlayMustHideImmediately(update.progress)) {
            overlayController?.remove()
        }
        if (!forcePersist && update.progress == before) {
            processJapanUpdate(update, expectedGeneration)
            onCommitted(currentPlan)
            return true
        }

        container.tourRepository.noteRuntimeProgress(update.progress)
        val commitJob = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                var committedPlan = currentPlan
                try {
                    committedPlan = container.tourRepository.saveProgressOnLatestPlan(
                        basePlan = currentPlan,
                        expectedProgress = before,
                        updatedProgress = update.progress,
                    )
                    if (
                        expectedGeneration != navigationGeneration ||
                        plan?.id != currentPlan.id || japanEngine !== activeEngine
                    ) {
                        error(STALE_EXTERNAL_TRANSIT_MESSAGE)
                    }
                    if (committedPlan != currentPlan) {
                        plan = committedPlan
                        japanEngine = JapanExternalTransitEngine(committedPlan, update.progress)
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    container.tourRepository.noteRuntimeProgress(before)
                    val message = navigationFailureMessage(exception)
                    if (
                        expectedGeneration == navigationGeneration &&
                        plan?.id == currentPlan.id && japanEngine === activeEngine
                    ) {
                        val restoredEngine = JapanExternalTransitEngine(currentPlan, before)
                        japanEngine = restoredEngine
                        runCatching {
                            processJapanUpdate(
                                JapanExternalTransitUpdate(
                                    progress = before,
                                    runtimeState = JapanExternalTransitRuntimeState(
                                        targetDistanceMeters =
                                            NavigationRuntime.state.value.currentTargetDistanceMeters,
                                    ),
                                ),
                                expectedGeneration,
                            )
                        }
                        NavigationRuntime.update { it.copy(errorMessage = message) }
                        runCatching { updateNotification(message) }
                    }
                    runCatching { onFailure(message) }
                    return@launch
                }

                lastSavedProgress = update.progress
                val displayFailure = runCatching {
                    processJapanUpdate(update, expectedGeneration)
                }.exceptionOrNull()
                if (displayFailure != null) {
                    val message = navigationFailureMessage(displayFailure)
                    NavigationRuntime.update { it.copy(errorMessage = message) }
                    runCatching { updateNotification(message) }
                }
                runCatching { onCommitted(committedPlan) }
            } finally {
                japanCommitInFlight = false
                japanCommitJob = null
            }
        }
        japanCommitJob = commitJob
        japanCommitInFlight = true
        commitJob.start()
        return true
    }

    private fun externalMapInstruction(currentPlan: TourPlan, progress: NavigationProgress): String {
        if (currentPlan.executionStrategy == TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN) {
            if (progress.isPaused) return "日本公交行程已暂停"
            val targetName = currentPlan.legs.getOrNull(progress.legIndex)?.destinationPointId?.let { id ->
                currentPlan.selectedPoints.firstOrNull { it.id == id }?.name
            } ?: "起点"
            return when (progress.state) {
                NavigationState.PLANNED -> "准备开始日本公交分段导航"
                NavigationState.NAVIGATING -> "前往 $targetName；路线、班次和换乘由 Google 地图提供"
                NavigationState.ARRIVING -> "已接近 $targetName，请手动确认到达"
                NavigationState.DWELLING -> "已到达 $targetName，正在停留"
                NavigationState.NEXT_STOP -> "停留结束，请手动开始下一段"
                NavigationState.COMPLETED -> "日本公交巡礼路线已完成"
                NavigationState.ENDED -> "日本公交巡礼行程已结束"
            }
        }
        if (progress.isPaused) return "高德地图外部分段导航已暂停"
        val targetName = currentPlan.legs.getOrNull(progress.legIndex)?.destinationPointId?.let { id ->
            currentPlan.selectedPoints.firstOrNull { it.id == id }?.name
        } ?: "起点"
        val modeLabel = currentPlan.mode.externalModeLabel()
        return when (progress.state) {
            NavigationState.PLANNED -> "准备开始高德地图外部分段$modeLabel"
            NavigationState.NAVIGATING -> "前往 $targetName；本段${modeLabel}由高德地图提供"
            NavigationState.ARRIVING -> "已接近 $targetName，请手动确认到达"
            NavigationState.DWELLING -> "已到达 $targetName，正在停留"
            NavigationState.NEXT_STOP -> "停留结束，请手动开始下一段"
            NavigationState.COMPLETED -> "高德地图巡礼路线已完成"
            NavigationState.ENDED -> "高德地图巡礼行程已结束"
        }
    }

    private fun processUpdate(update: NavigationUpdate?, expectedGeneration: Long = navigationGeneration) {
        if (stopping || expectedGeneration != navigationGeneration || update == null) return
        val currentPlan = plan ?: return
        container.tourRepository.noteRuntimeProgress(update.progress)
        val stateText = update.spokenText()
        val displayedText = if (
            currentPlan.mode != TravelMode.TRANSIT && update.progress.state == NavigationState.NAVIGATING
        ) {
            "Google 导航正在引导前往下一巡礼点"
        } else {
            stateText
        }
        NavigationRuntime.set(
            NavigationRuntimeState(
                plan = currentPlan,
                progress = update.progress,
                currentLocation = update.currentLocation ?: NavigationRuntime.state.value.currentLocation,
                instruction = displayedText,
                remainingDistanceMeters = if (currentPlan.mode == TravelMode.TRANSIT) {
                    update.remainingDistanceMeters
                } else {
                    nativeRemainingDistanceMeters ?: update.remainingDistanceMeters
                },
                isRunning = update.progress.state !in setOf(NavigationState.COMPLETED, NavigationState.ENDED),
                isRerouting = if (currentPlan.mode == TravelMode.TRANSIT) {
                    reroute?.isActive == true
                } else {
                    nativeRerouting
                },
                errorMessage = NavigationRuntime.state.value.errorMessage,
            ),
        )
        if (update.progress != lastSavedProgress) {
            val expectedProgress = lastSavedProgress ?: return
            lastSavedProgress = update.progress
            val previousSave = progressSaveJob
            progressSaveJob = serviceScope.launch {
                previousSave?.join()
                if (expectedGeneration != navigationGeneration) return@launch
                val committed = try {
                    container.tourRepository.saveProgressResultOnLatestPlan(
                        basePlan = currentPlan,
                        expectedProgress = expectedProgress,
                        updatedProgress = update.progress,
                    )
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: ConcurrentTourUpdateException) {
                    if (expectedGeneration == navigationGeneration && !stopping) {
                        startNavigation(currentPlan.id, preservePendingReload = true)
                    }
                    return@launch
                } catch (_: Exception) {
                    if (expectedGeneration == navigationGeneration && !stopping) {
                        stopAfterProgressPersistenceFailure(
                            currentPlan = currentPlan,
                            expectedProgress = expectedProgress,
                            expectedGeneration = expectedGeneration,
                        )
                    }
                    return@launch
                }
                finishPersistedTerminalIfCurrent(
                    persistedPlan = committed.plan,
                    persistedProgress = committed.progress,
                    expectedGeneration = expectedGeneration,
                )
            }
        }
        updateNotification(displayedText)
        if (currentPlan.mode == TravelMode.TRANSIT) {
            speak(stateText, "${update.progress.state}:${update.progress.legIndex}:${update.progress.stepIndex}")
        }
        synchronizeRoadNavigation(update.progress, expectedGeneration)
        refreshTransitWhenNeeded(update, expectedGeneration)
    }

    private fun finishPersistedTerminalIfCurrent(
        persistedPlan: TourPlan,
        persistedProgress: NavigationProgress,
        expectedGeneration: Long,
    ) {
        if (
            !shouldFinalizePersistedTerminalNavigation(
                persistedPlan = persistedPlan,
                persistedProgress = persistedProgress,
                expectedGeneration = expectedGeneration,
                currentGeneration = navigationGeneration,
                stopping = stopping,
                currentPlan = plan,
                currentProgress = engine?.progress,
            )
        ) return
        ActiveNavigationStore.replaceIfCurrent(
            context = this,
            expectedTourId = persistedPlan.id,
            tourId = null,
            durable = true,
        )
        container.tourRepository.clearRuntimeProgress(persistedPlan.id, persistedProgress)
        finishCompletedNavigation(expectedGeneration, waitForProgressSave = false)
    }

    private fun stopAfterProgressPersistenceFailure(
        currentPlan: TourPlan,
        expectedProgress: NavigationProgress,
        expectedGeneration: Long,
    ) {
        if (expectedGeneration != navigationGeneration) return
        ++navigationGeneration
        stopping = true
        container.tourRepository.noteRuntimeProgress(expectedProgress)
        lastSavedProgress = expectedProgress
        runCatching { locationManager.removeUpdates(this) }
        overlayController?.remove()
        ticker?.cancel()
        ticker = null
        reroute?.cancel()
        reroute = null
        roadSyncJob?.cancel()
        roadSyncJob = null
        roadNavigationSession?.close()
        roadNavigationSession = null
        engine = null
        japanEngine = null
        NavigationRuntime.update {
            it.copy(
                plan = currentPlan,
                progress = expectedProgress,
                instruction = PROGRESS_SAVE_FAILED_MESSAGE,
                isRunning = false,
                isRerouting = false,
                errorMessage = PROGRESS_SAVE_FAILED_MESSAGE,
            )
        }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun synchronizeRoadNavigation(progress: NavigationProgress, expectedGeneration: Long) {
        val session = roadNavigationSession ?: return
        when (progress.state) {
            NavigationState.NAVIGATING -> {
                if (lastRoadSyncLegIndex == progress.legIndex) return
                lastRoadSyncLegIndex = progress.legIndex
                roadSyncJob?.cancel()
                roadSyncJob = serviceScope.launch {
                    try {
                        session.synchronize(progress.legIndex)
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        lastRoadSyncLegIndex = null
                        failAndStop(
                            navigationFailureMessage(exception),
                            expectedGeneration = expectedGeneration,
                        )
                    }
                }
            }
            NavigationState.ARRIVING,
            NavigationState.DWELLING,
            NavigationState.NEXT_STOP,
            -> session.pauseGuidance()
            NavigationState.COMPLETED, NavigationState.ENDED -> session.close()
            NavigationState.PLANNED -> Unit
        }
    }

    private fun onNativeArrival(legIndex: Int, expectedGeneration: Long) {
        if (expectedGeneration != navigationGeneration) return
        val activeEngine = engine ?: return
        if (
            activeEngine.progress.state == NavigationState.NAVIGATING &&
            activeEngine.progress.legIndex == legIndex
        ) {
            processUpdate(activeEngine.manualArrival(), expectedGeneration)
        }
    }

    private suspend fun rerouteFrom(
        location: GeoPoint,
        progress: NavigationProgress,
        expectedGeneration: Long,
    ) {
        progressSaveJob?.join()
        if (expectedGeneration != navigationGeneration) return
        val oldPlan = plan ?: return
        if (oldPlan.isExternalJapanTransit()) return
        NavigationRuntime.update { it.copy(isRerouting = true, errorMessage = null) }
        var persistedTerminal: Pair<TourPlan, NavigationProgress>? = null
        try {
            val updatedPlan = container.tourPlanner.replanRemaining(
                plan = oldPlan,
                currentLocation = location,
                completedPointIds = progress.completedPointIds,
                currentTime = OffsetDateTime.now().toString(),
            )
            if (expectedGeneration != navigationGeneration) return
            val updatedProgress = progress.copy(
                legIndex = 0,
                stepIndex = 0,
                state = if (updatedPlan.legs.isEmpty()) NavigationState.COMPLETED else NavigationState.NAVIGATING,
                offRouteSinceEpochMillis = null,
            )
            progressSaveJob?.join()
            if (
                expectedGeneration != navigationGeneration ||
                stopping ||
                engine?.progress != progress
            ) return
            val saved = container.tourRepository.saveActiveEditIfCurrent(
                expectedPlan = oldPlan,
                expectedProgress = progress,
                updatedPlan = updatedPlan,
                updatedProgress = updatedProgress,
            )
            if (
                !saved || expectedGeneration != navigationGeneration ||
                stopping || engine?.progress != progress
            ) return
            container.tourRepository.noteRuntimeProgress(updatedProgress)
            plan = updatedPlan
            engine = NavigationEngine(updatedPlan, updatedProgress)
            lastSavedProgress = updatedProgress
            if (expectedGeneration != navigationGeneration) return
            processUpdate(engine?.onTick(System.currentTimeMillis()), expectedGeneration)
            if (updatedProgress.state in setOf(NavigationState.COMPLETED, NavigationState.ENDED)) {
                persistedTerminal = updatedPlan to updatedProgress
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (expectedGeneration == navigationGeneration) {
                NavigationRuntime.update {
                    it.copy(isRerouting = false, errorMessage = "路线重算失败，已继续使用原路线")
                }
            }
        } finally {
            if (expectedGeneration == navigationGeneration) {
                NavigationRuntime.update { it.copy(isRerouting = false) }
            }
        }
        persistedTerminal?.let { (persistedPlan, persistedProgress) ->
            finishPersistedTerminalIfCurrent(
                persistedPlan = persistedPlan,
                persistedProgress = persistedProgress,
                expectedGeneration = expectedGeneration,
            )
        }
    }

    private fun refreshTransitWhenNeeded(update: NavigationUpdate, expectedGeneration: Long) {
        if (expectedGeneration != navigationGeneration) return
        val currentPlan = plan ?: return
        if (
            currentPlan.mode != TravelMode.TRANSIT ||
            currentPlan.isExternalJapanTransit() ||
            reroute?.isActive == true
        ) return
        val currentLeg = currentPlan.legs.getOrNull(update.progress.legIndex)
        if (!TransitRefreshPolicy.shouldRefresh(
                currentPlan,
                update.progress,
                update.targetPointId,
                nowEpochMillis = System.currentTimeMillis(),
            )
        ) return
        val cancelledLeg = currentLeg?.transit?.cancelled == true
        val key = "${update.progress.completedPointIds.size}:${update.progress.legIndex}:$cancelledLeg"
        if (key == lastTransitRefreshKey) return
        lastTransitRefreshKey = key
        val location = update.currentLocation
            ?: NavigationRuntime.state.value.currentLocation
            ?: currentLeg?.to
            ?: return
        reroute = serviceScope.launch { rerouteFrom(location, update.progress, expectedGeneration) }
    }

    private fun refreshTransitRoute() {
        val generation = navigationGeneration
        val currentPlan = plan ?: return
        val progress = engine?.progress ?: return
        if (
            currentPlan.mode != TravelMode.TRANSIT ||
            currentPlan.isExternalJapanTransit() ||
            reroute?.isActive == true
        ) return
        val location = NavigationRuntime.state.value.currentLocation
            ?: currentPlan.legs.getOrNull(progress.legIndex)?.from
            ?: return
        reroute = serviceScope.launch { rerouteFrom(location, progress, generation) }
    }

    private fun prepareExternalHandoff(intent: Intent, advance: Boolean) {
        val receiver = intent.resultReceiver() ?: return
        val tourId = intent.getStringExtra(EXTRA_TOUR_ID)
        if (tourId.isNullOrBlank()) {
            receiver.sendError("缺少要打开的行程")
            return
        }
        val request = PendingHandoff(
            tourId = tourId,
            expectedLegIndex = intent.getIntExtra(EXTRA_EXPECTED_LEG_INDEX, 0),
            advance = advance,
            receiver = receiver,
        )
        pendingHandoff?.receiver?.sendError("已有新的分段操作，请重试")
        pendingHandoff = request
        if (plan?.id != tourId || japanEngine == null) {
            if (loadJob?.isActive != true || loadingTourId != tourId) {
                startNavigation(tourId)
            }
            return
        }
        completePendingHandoff(navigationGeneration)
    }

    private fun completePendingHandoff(expectedGeneration: Long) {
        val request = pendingHandoff ?: return
        pendingHandoff = null
        serviceScope.launch {
            try {
                if (expectedGeneration != navigationGeneration) error("行程状态已变化，请重试")
                if (!hasUsableExternalControl()) {
                    throw ExternalTransitControlUnavailableException()
                }
                val currentPlan = plan?.takeIf {
                    it.id == request.tourId && it.isExternalJapanTransit()
                } ?: error("当前外部分段行程已变化，请返回应用确认")
                val activeEngine = japanEngine ?: error("外部分段行程尚未准备完成")
                val before = activeEngine.progress
                if (japanCommitInFlight) error(EXTERNAL_TRANSIT_SAVE_IN_PROGRESS_MESSAGE)
                if (before.isPaused) error("行程已暂停，请先恢复")
                if (before.legIndex != request.expectedLegIndex) {
                    error("当前分段已变化，请返回应用确认")
                }
                val update = if (request.advance) {
                    when (before.state) {
                        NavigationState.DWELLING -> activeEngine.leaveDwellEarlyAndStartNextLeg()
                        NavigationState.NEXT_STOP -> activeEngine.startNextLeg()
                        else -> error("当前还不能开始下一段")
                    }
                        .also {
                            if (
                                it.progress.state != NavigationState.COMPLETED &&
                                it.progress.legIndex == before.legIndex
                            ) {
                                error("当前还不能开始下一段")
                            }
                        }
                } else {
                    if (before.state !in setOf(NavigationState.NAVIGATING, NavigationState.ARRIVING)) {
                        error("当前状态不能打开本段")
                    }
                    JapanExternalTransitUpdate(before, activeEngine.runtimeState)
                }
                commitJapanUpdateIfChanged(
                    activeEngine = activeEngine,
                    before = before,
                    update = update,
                    expectedGeneration = expectedGeneration,
                    forcePersist = true,
                    onCommitted = { committedPlan ->
                        if (update.progress.state == NavigationState.COMPLETED) {
                            request.receiver.send(RESULT_COMPLETED, Bundle.EMPTY)
                            return@commitJapanUpdateIfChanged
                        }
                        val leg = committedPlan.legs.getOrNull(update.progress.legIndex)
                        if (leg == null) {
                            request.receiver.sendError("当前分段不存在")
                            return@commitJapanUpdateIfChanged
                        }
                        externalHandoffMismatch(committedPlan, leg)?.let { mismatch ->
                            request.receiver.sendError(mismatch)
                            return@commitJapanUpdateIfChanged
                        }
                        val (originName, destinationName) = externalHandoffNames(
                            committedPlan,
                            update.progress.legIndex,
                        )
                        request.receiver.send(
                            RESULT_HANDOFF_READY,
                            Bundle().apply {
                                putDouble(TransitHandoffActivity.EXTRA_ORIGIN_LATITUDE, leg.from.latitude)
                                putDouble(TransitHandoffActivity.EXTRA_ORIGIN_LONGITUDE, leg.from.longitude)
                                putDouble(
                                    TransitHandoffActivity.EXTRA_DESTINATION_LATITUDE,
                                    leg.to.latitude,
                                )
                                putDouble(
                                    TransitHandoffActivity.EXTRA_DESTINATION_LONGITUDE,
                                    leg.to.longitude,
                                )
                                putInt(EXTRA_EXPECTED_LEG_INDEX, update.progress.legIndex)
                                putString(
                                    TransitHandoffActivity.EXTRA_EXECUTION_STRATEGY,
                                    committedPlan.executionStrategy.name,
                                )
                                putString(TransitHandoffActivity.EXTRA_TRAVEL_MODE, leg.mode.name)
                                putString(TransitHandoffActivity.EXTRA_ORIGIN_NAME, originName)
                                putString(TransitHandoffActivity.EXTRA_DESTINATION_NAME, destinationName)
                            },
                        )
                    },
                    onFailure = request.receiver::sendError,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                request.receiver.sendError(navigationFailureMessage(exception))
            }
        }
    }

    private fun completePendingReload(expectedGeneration: Long, tourId: String) {
        if (expectedGeneration != navigationGeneration) return
        val request = pendingReload ?: return
        if (request.tourId != tourId) {
            supersedePendingReload()
            return
        }
        val runtime = NavigationRuntime.state.value
        if (runtime.plan?.id != tourId || !runtime.isRunning) {
            request.receiver.sendError("The reloaded navigation is not active")
        } else {
            request.receiver.send(RESULT_RELOADED, Bundle.EMPTY)
        }
        pendingReload = null
    }

    private fun supersedePendingReload() {
        pendingReload?.receiver?.send(RESULT_RELOAD_SUPERSEDED, Bundle.EMPTY)
        pendingReload = null
    }

    private fun confirmExternalArrival(intent: Intent) {
        val receiver = intent.resultReceiver() ?: return
        val currentPlan = plan
        val activeEngine = japanEngine
        if (
            currentPlan == null || activeEngine == null ||
            currentPlan.id != intent.getStringExtra(EXTRA_TOUR_ID) ||
            activeEngine.progress.legIndex != intent.getIntExtra(EXTRA_EXPECTED_LEG_INDEX, -1)
        ) {
            receiver.sendError("当前分段已变化，请返回应用确认")
            return
        }
        if (japanCommitInFlight) {
            receiver.sendError(EXTERNAL_TRANSIT_SAVE_IN_PROGRESS_MESSAGE)
            return
        }
        val before = activeEngine.progress
        val update = activeEngine.confirmArrival(
            nowEpochMillis = System.currentTimeMillis(),
            confirmEarly = intent.getBooleanExtra(EXTRA_CONFIRM_EARLY, false),
        )
        if (update.requiresEarlyArrivalConfirmation) {
            receiver.send(RESULT_EARLY_CONFIRMATION_REQUIRED, Bundle.EMPTY)
            return
        }
        if (update.progress == before) {
            receiver.sendError("当前状态不能确认到达")
            return
        }
        commitJapanUpdateIfChanged(
            activeEngine = activeEngine,
            before = before,
            update = update,
            onCommitted = { _ -> receiver.send(RESULT_ARRIVAL_CONFIRMED, Bundle.EMPTY) },
            onFailure = receiver::sendError,
        )
    }

    private fun pauseExternalNavigation() {
        val activeEngine = japanEngine ?: return
        if (japanCommitInFlight) return
        val currentPlan = plan ?: return
        val before = activeEngine.progress
        val update = activeEngine.pause(System.currentTimeMillis())
        if (!update.progress.isPaused) return
        commitJapanUpdateIfChanged(
            activeEngine = activeEngine,
            before = before,
            update = update,
            onCommitted = { _ ->
                runCatching { locationManager.removeUpdates(this) }
                overlayController?.remove()
            },
            onFailure = {
                if (plan?.id == currentPlan.id) {
                    NavigationRuntime.update { state -> state.copy(errorMessage = it) }
                }
            },
        )
    }

    private fun resumeExternalNavigation(requestedTourId: String? = null) {
        val tourId = requestedTourId ?: plan?.id ?: ActiveNavigationStore.get(this)
        if (stopping && tourId != null) {
            pendingResumeTourId = tourId
            startNavigation(tourId)
            return
        }
        val activeEngine = japanEngine
        if (activeEngine == null || plan?.id != tourId) {
            if (tourId != null) {
                pendingResumeTourId = tourId
                if (loadJob?.isActive != true || loadingTourId != tourId) startNavigation(tourId)
            }
            return
        }
        if (!NavigationControlAvailability.hasExternalTransitControl(this)) {
            NavigationRuntime.update {
                it.copy(errorMessage = EXTERNAL_CONTROL_REQUIRED_MESSAGE)
            }
            updateNotification(EXTERNAL_CONTROL_REQUIRED_MESSAGE)
            return
        }
        try {
            if (japanCommitInFlight) throw IllegalStateException(EXTERNAL_TRANSIT_SAVE_IN_PROGRESS_MESSAGE)
            if (!AndroidLocationProvider.hasFineLocationPermission(this)) {
                throw MissingLocationPermissionException()
            }
            startLocationUpdates(requireFinePermission = true)
            val before = activeEngine.progress
            val update = activeEngine.resume(System.currentTimeMillis())
            if (update.progress.isPaused) return
            NavigationRuntime.update { it.copy(errorMessage = null) }
            commitJapanUpdateIfChanged(
                activeEngine = activeEngine,
                before = before,
                update = update,
                onFailure = {
                    runCatching { locationManager.removeUpdates(this) }
                },
            )
        } catch (exception: Exception) {
            runCatching { locationManager.removeUpdates(this) }
            NavigationRuntime.update { it.copy(errorMessage = navigationFailureMessage(exception)) }
            updateNotification(navigationFailureMessage(exception))
        }
    }

    private fun endExternalNavigation(intent: Intent, startId: Int) {
        val receiver = intent.resultReceiver()
        val requestedTourId = intent.getStringExtra(EXTRA_TOUR_ID)
        val expectedLegIndex = intent.getIntExtra(EXTRA_EXPECTED_LEG_INDEX, -1)
        val currentPlan = plan
        val activeEngine = japanEngine
        if (coldEndTourId != null && japanCommitInFlight) {
            coldEndLatestStartId = maxOf(coldEndLatestStartId, startId)
            if (
                coldEndTourId == requestedTourId &&
                coldEndExpectedLegIndex == expectedLegIndex
            ) {
                receiver?.let(coldEndReceivers::add)
            } else {
                receiver?.sendError(STALE_EXTERNAL_TRANSIT_MESSAGE)
            }
            return
        }
        if (requestedTourId.isNullOrBlank()) {
            receiver?.sendError("没有正在进行的外部分段导航")
            return
        }
        if (currentPlan != null && currentPlan.id != requestedTourId) {
            receiver?.sendError("当前行程已变化，请返回应用确认")
            return
        }
        if (japanCommitInFlight) {
            receiver?.sendError(EXTERNAL_TRANSIT_SAVE_IN_PROGRESS_MESSAGE)
            return
        }
        if (currentPlan == null || activeEngine == null) {
            endStoredExternalNavigation(requestedTourId, expectedLegIndex, receiver, startId)
            return
        }
        if (activeEngine.progress.legIndex != expectedLegIndex) {
            receiver?.sendError("当前分段已变化，请返回应用确认")
            return
        }
        completeExternalEnd(currentPlan, activeEngine, receiver)
    }

    private fun completeExternalEnd(
        currentPlan: TourPlan,
        activeEngine: JapanExternalTransitEngine,
        receiver: ResultReceiver?,
    ) {
        if (japanCommitInFlight) {
            receiver?.sendError(EXTERNAL_TRANSIT_SAVE_IN_PROGRESS_MESSAGE)
            return
        }
        val before = activeEngine.progress
        val update = activeEngine.end()
        commitJapanUpdateIfChanged(
            activeEngine = activeEngine,
            before = before,
            update = update,
            forcePersist = true,
            onCommitted = { _ ->
                ActiveNavigationStore.clear(this, currentPlan.id)
                receiver?.send(RESULT_ENDED, Bundle.EMPTY)
            },
            onFailure = { receiver?.sendError(it) },
        )
    }

    private fun endStoredExternalNavigation(
        requestedTourId: String,
        expectedLegIndex: Int,
        receiver: ResultReceiver?,
        startId: Int,
    ) {
        val generation = ++navigationGeneration
        stopping = true
        coldEndTourId = requestedTourId
        coldEndExpectedLegIndex = expectedLegIndex
        coldEndLatestStartId = startId
        coldEndReceivers.clear()
        receiver?.let(coldEndReceivers::add)
        loadJob?.cancel()
        loadJob = null
        ticker?.cancel()
        ticker = null
        runCatching { locationManager.removeUpdates(this) }
        overlayController?.remove()
        val terminalJob = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                val saved = container.tourRepository.get(requestedTourId)
                    ?: error("没有正在进行的外部分段导航")
                val storedPlan = saved.plan.takeIf { it.isExternalJapanTransit() }
                    ?: error("当前行程不是外部分段导航")
                val before = saved.progress ?: NavigationProgress(tourId = storedPlan.id)
                if (before.legIndex != expectedLegIndex) {
                    error("当前分段已变化，请返回应用确认")
                }
                val ended = JapanExternalTransitEngine(storedPlan, before).end().progress
                val committedPlan = container.tourRepository.saveProgressOnLatestPlan(
                    basePlan = storedPlan,
                    expectedProgress = before,
                    updatedProgress = ended,
                )
                if (generation != navigationGeneration) error(STALE_EXTERNAL_TRANSIT_MESSAGE)
                ActiveNavigationStore.clear(this@NavigationService, committedPlan.id)
                plan = committedPlan
                japanEngine = JapanExternalTransitEngine(committedPlan, ended)
                lastSavedProgress = ended
                NavigationRuntime.set(
                    NavigationRuntimeState(
                        plan = committedPlan,
                        progress = ended,
                        currentLocation = NavigationRuntime.state.value.currentLocation,
                        instruction = externalMapInstruction(committedPlan, ended),
                        isRunning = false,
                    ),
                )
                coldEndReceivers.toList().forEach { it.send(RESULT_ENDED, Bundle.EMPTY) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                val message = navigationFailureMessage(exception)
                NavigationRuntime.update { it.copy(errorMessage = message) }
                coldEndReceivers.toList().forEach { it.sendError(message) }
            } finally {
                val latestEndStartId = coldEndLatestStartId
                coldEndTourId = null
                coldEndExpectedLegIndex = null
                coldEndLatestStartId = 0
                coldEndReceivers.clear()
                japanCommitInFlight = false
                japanCommitJob = null
                if (generation == navigationGeneration) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(latestEndStartId)
                }
            }
        }
        japanCommitJob = terminalJob
        japanCommitInFlight = true
        terminalJob.start()
    }

    private fun enforceExternalControlAvailability(expectedGeneration: Long) {
        val activeEngine = japanEngine ?: return
        if (
            japanCommitInFlight ||
            activeEngine.progress.isPaused ||
            activeEngine.progress.state in setOf(NavigationState.COMPLETED, NavigationState.ENDED) ||
            hasUsableExternalControl()
        ) return
        NavigationRuntime.update { it.copy(errorMessage = EXTERNAL_CONTROL_REQUIRED_MESSAGE) }
        runCatching { locationManager.removeUpdates(this) }
        overlayController?.remove()
        val before = activeEngine.progress
        val update = activeEngine.pause(System.currentTimeMillis())
        commitJapanUpdateIfChanged(
            activeEngine = activeEngine,
            before = before,
            update = update,
            expectedGeneration = expectedGeneration,
            onCommitted = { _ -> stopAfterExternalControlLoss(expectedGeneration) },
            onFailure = { stopAfterExternalControlLoss(expectedGeneration) },
        )
    }

    private fun stopAfterExternalControlLoss(expectedGeneration: Long) {
        if (expectedGeneration != navigationGeneration) return
        stopping = true
        ticker?.cancel()
        ticker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun hasUsableExternalControl(): Boolean =
        NavigationControlAvailability.notificationsVisible(this) ||
            (NavigationControlAvailability.overlayVisible(this) && overlayController?.isShowing == true)

    private fun stopNavigation() {
        supersedePendingReload()
        if (plan.isExternalJapanTransit()) {
            pauseExternalNavigation()
            return
        }
        val generation = navigationGeneration
        stopping = true
        val currentPlan = plan
        val expectedProgress = engine?.progress
            ?: currentPlan?.let { loadedPlan ->
                NavigationRuntime.state.value.progress?.takeIf { it.tourId == loadedPlan.id }
            }
        val activeTourId = currentPlan?.id ?: loadingTourId ?: ActiveNavigationStore.get(this)
        runCatching { locationManager.removeUpdates(this) }
        val previousCleanup = cleanupJob
        ticker?.cancel()
        ticker = null
        val previousLoad = loadJob
        previousLoad?.cancel()
        val previousReroute = reroute
        previousReroute?.cancel()
        reroute = null
        roadSyncJob?.cancel()
        roadSyncJob = null
        roadNavigationSession?.close()
        roadNavigationSession = null
        engine = null
        nativeRemainingDistanceMeters = null
        nativeRerouting = false
        NavigationRuntime.update {
            it.copy(
                progress = expectedProgress ?: it.progress,
                instruction = "正在保存暂停状态…",
                isRunning = false,
                isRerouting = false,
            )
        }
        val pendingSave = progressSaveJob
        cleanupJob = serviceScope.launch {
            previousLoad?.join()
            previousReroute?.join()
            previousCleanup?.join()
            pendingSave?.join()
            if (generation != navigationGeneration) return@launch
            try {
                val persisted = activeTourId?.let { persistStoppedNavigation(it) }
                    ?: error("没有可暂停的巡礼路线")
                if (generation != navigationGeneration) return@launch
                ActiveNavigationStore.clear(this@NavigationService, persisted.plan.id)
                container.tourRepository.clearRuntimeProgress(persisted.plan.id, persisted.progress)
                plan = persisted.plan
                lastSavedProgress = persisted.progress
                NavigationRuntime.set(
                    NavigationRuntimeState(
                        plan = persisted.plan,
                        progress = persisted.progress,
                        currentLocation = NavigationRuntime.state.value.currentLocation,
                        instruction = "导航已暂停，可从路线预览再次开始",
                        isRunning = false,
                    ),
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                if (generation == navigationGeneration) {
                    val latest = activeTourId?.let { runCatching { container.tourRepository.get(it) }.getOrNull() }
                    latest?.progress?.let(container.tourRepository::noteRuntimeProgress)
                    NavigationRuntime.update {
                        it.copy(
                            plan = latest?.plan ?: currentPlan ?: it.plan,
                            progress = latest?.progress ?: expectedProgress ?: it.progress,
                            instruction = STOP_SAVE_FAILED_MESSAGE,
                            isRunning = false,
                            isRerouting = false,
                            errorMessage = STOP_SAVE_FAILED_MESSAGE,
                        )
                    }
                }
            } finally {
                if (generation == navigationGeneration) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private suspend fun persistStoppedNavigation(tourId: String): PersistedNavigationStop {
        var saved = container.tourRepository.get(tourId) ?: throw ConcurrentTourUpdateException()
        repeat(2) {
            val latestProgress = saved.progress ?: throw ConcurrentTourUpdateException()
            if (latestProgress.state in setOf(NavigationState.COMPLETED, NavigationState.ENDED)) {
                return PersistedNavigationStop(saved.plan, latestProgress)
            }
            val stoppedProgress = resumableProgressAfterFailure(latestProgress)
                ?: throw ConcurrentTourUpdateException()
            if (stoppedProgress == latestProgress) {
                container.tourRepository.noteRuntimeProgress(latestProgress)
                return PersistedNavigationStop(saved.plan, latestProgress)
            }
            container.tourRepository.noteRuntimeProgress(stoppedProgress)
            if (
                container.tourRepository.saveActiveEditIfCurrent(
                    expectedPlan = saved.plan,
                    expectedProgress = latestProgress,
                    updatedPlan = saved.plan,
                    updatedProgress = stoppedProgress,
                )
            ) {
                return PersistedNavigationStop(saved.plan, stoppedProgress)
            }
            saved = container.tourRepository.get(tourId) ?: throw ConcurrentTourUpdateException()
        }
        throw ConcurrentTourUpdateException()
    }

    private fun finishCompletedNavigation(
        expectedGeneration: Long,
        waitForProgressSave: Boolean = true,
    ) {
        if (expectedGeneration != navigationGeneration) return
        stopping = true
        runCatching { locationManager.removeUpdates(this) }
        overlayController?.remove()
        ticker?.cancel()
        ticker = null
        reroute?.cancel()
        reroute = null
        roadSyncJob?.cancel()
        roadSyncJob = null
        roadNavigationSession?.close()
        roadNavigationSession = null
        val previousCleanup = cleanupJob
        val pendingSave = progressSaveJob.takeIf { waitForProgressSave }
        cleanupJob = serviceScope.launch {
            previousCleanup?.join()
            pendingSave?.join()
            delay(2_000L)
            if (expectedGeneration != navigationGeneration) return@launch
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun failAndStop(
        message: String,
        saveAsUnresolved: Boolean = false,
        expectedGeneration: Long = navigationGeneration,
    ) {
        if (stopping || expectedGeneration != navigationGeneration) return
        stopping = true
        val currentPlan = plan
        val expectedProgress = currentPlan?.let { loadedPlan ->
            if (loadedPlan.isExternalJapanTransit()) {
                japanEngine?.progress
                    ?: NavigationRuntime.state.value.progress?.takeIf { it.tourId == loadedPlan.id }
            } else {
                engine?.progress
                    ?: NavigationRuntime.state.value.progress?.takeIf { it.tourId == loadedPlan.id }
            }
        }
        val progress = currentPlan?.let { loadedPlan ->
            if (loadedPlan.isExternalJapanTransit()) expectedProgress else resumableProgressAfterFailure(expectedProgress)
        }
        pendingHandoff?.receiver?.sendError(message)
        pendingHandoff = null
        pendingReload?.receiver?.sendError(message)
        pendingReload = null
        runCatching { locationManager.removeUpdates(this) }
        overlayController?.remove()
        ticker?.cancel()
        ticker = null
        val pendingReroute = reroute
        pendingReroute?.cancel()
        reroute = null
        roadSyncJob?.cancel()
        roadSyncJob = null
        roadNavigationSession?.close()
        roadNavigationSession = null
        engine = null
        japanEngine = null
        lastRoadSyncLegIndex = null
        nativeRemainingDistanceMeters = null
        nativeRerouting = false
        val previousCleanup = cleanupJob
        val pendingSave = progressSaveJob
        NavigationRuntime.update { previous ->
            navigationRuntimeAfterFailure(previous, currentPlan, progress, message)
        }
        cleanupJob = serviceScope.launch {
            previousCleanup?.join()
            pendingReroute?.join()
            pendingSave?.join()
            completeNavigationCleanup(
                expectedGeneration = expectedGeneration,
                currentGeneration = { navigationGeneration },
                persistRollback = {
                    if (
                        currentPlan != null && expectedProgress != null && progress != null &&
                        !currentPlan.isExternalJapanTransit()
                    ) {
                        runCatching {
                            if (saveAsUnresolved) {
                                container.tourRepository.saveUnresolvedProgressOnLatestPlan(
                                    currentPlan,
                                    expectedProgress,
                                    progress,
                                )
                            } else {
                                container.tourRepository.saveProgressOnLatestPlan(
                                    currentPlan,
                                    expectedProgress,
                                    progress,
                                )
                            }
                        }.onSuccess {
                            if (expectedGeneration == navigationGeneration) {
                                ActiveNavigationStore.clear(this@NavigationService, currentPlan.id)
                            }
                        }
                    }
                },
                stopService = {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                },
            )
        }
    }

    private fun NavigationUpdate.spokenText(): String = when (progress.state) {
        NavigationState.PLANNED -> "准备开始巡礼"
        NavigationState.NAVIGATING -> instruction
        NavigationState.ARRIVING -> if (targetPointId == null && plan?.mode == TravelMode.TRANSIT) {
            "即将到达本段终点，请准备下车或换乘"
        } else {
            "已到达当前巡礼点"
        }
        NavigationState.DWELLING -> "已到达，开始停留"
        NavigationState.NEXT_STOP -> if (targetPointId == null && plan?.mode == TravelMode.TRANSIT) {
            "继续下一换乘段"
        } else {
            "准备前往下一站"
        }
        NavigationState.COMPLETED -> "巡礼路线已完成"
        NavigationState.ENDED -> "巡礼行程已结束"
    }

    private fun speak(text: String, key: String) {
        if (!ttsReady || key == lastSpokenKey) return
        lastSpokenKey = key
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "navigation-$key")
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val openIntent = userVisibleActivityPendingIntent(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_navigation_notification)
            .setContentTitle("巡礼手帳 · 连续导航")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(navigationNotificationCategory())

        val currentPlan = plan
        val progress = japanEngine?.progress
        if (currentPlan.isExternalJapanTransit() && currentPlan != null && progress != null) {
            val legIndex = progress.legIndex
            when {
                progress.isPaused -> builder.addAction(
                    0,
                    "恢复",
                    servicePendingIntent(11, ACTION_RESUME_EXTERNAL),
                )
                progress.state == NavigationState.NAVIGATING -> builder.addAction(
                    0,
                    "打开本段",
                    handoffPendingIntent(12, TransitHandoffActivity.MODE_OPEN, currentPlan.id, legIndex),
                )
                progress.state == NavigationState.ARRIVING -> builder.addAction(
                    0,
                    "确认到达",
                    handoffPendingIntent(
                        13,
                        TransitHandoffActivity.MODE_CONFIRM_ARRIVAL,
                        currentPlan.id,
                        legIndex,
                    ),
                )
                progress.state == NavigationState.DWELLING -> builder.addAction(
                    0,
                    "提前离开",
                    handoffPendingIntent(17, TransitHandoffActivity.MODE_NEXT, currentPlan.id, legIndex),
                )
                progress.state == NavigationState.NEXT_STOP -> builder.addAction(
                    0,
                    "开始下一段",
                    handoffPendingIntent(14, TransitHandoffActivity.MODE_NEXT, currentPlan.id, legIndex),
                )
                else -> Unit
            }
            if (!progress.isPaused) {
                builder.addAction(0, "暂停", servicePendingIntent(15, ACTION_PAUSE_EXTERNAL))
            }
            builder.addAction(
                0,
                "结束",
                handoffPendingIntent(16, TransitHandoffActivity.MODE_END, currentPlan.id, legIndex),
            )
        } else if (currentPlan != null && !currentPlan.isExternalJapanTransit()) {
            builder.addAction(0, "结束", servicePendingIntent(1, ACTION_STOP))
        }
        return builder.build()
    }

    private fun stopAfterForegroundPromotionFailure(message: String, startId: Int?) {
        ++navigationGeneration
        stopping = true
        pendingHandoff?.receiver?.sendError(message)
        pendingHandoff = null
        pendingReload?.receiver?.sendError(message)
        pendingReload = null
        runCatching { locationManager.removeUpdates(this) }
        overlayController?.remove()
        ticker?.cancel()
        ticker = null
        loadJob?.cancel()
        deferredStartJob?.cancel()
        reroute?.cancel()
        reroute = null
        roadSyncJob?.cancel()
        roadSyncJob = null
        roadNavigationSession?.close()
        roadNavigationSession = null
        engine = null
        japanEngine = null
        NavigationRuntime.update { navigationRuntimeAfterForegroundPromotionFailure(it, message) }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        if (startId == null) stopSelf() else stopSelf(startId)
    }

    private fun servicePendingIntent(requestCode: Int, action: String): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, NavigationService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun handoffPendingIntent(
        requestCode: Int,
        mode: String,
        tourId: String,
        legIndex: Int,
    ): PendingIntent = userVisibleActivityPendingIntent(
        this,
        requestCode + legIndex * 100,
        TransitHandoffActivity.createIntent(this, mode, tourId, legIndex),
    )

    private fun ensureForeground() {
        NavigationControlAvailability.ensureChannel(this)
        startForeground(NOTIFICATION_ID, notification(NavigationRuntime.state.value.instruction))
    }

    companion object {
        const val ACTION_START = "cn.anitabi.navigator.navigation.START"
        const val ACTION_STOP = "cn.anitabi.navigator.navigation.STOP"
        const val ACTION_MANUAL_ARRIVAL = "cn.anitabi.navigator.navigation.MANUAL_ARRIVAL"
        const val ACTION_REFRESH_TRANSIT = "cn.anitabi.navigator.navigation.REFRESH_TRANSIT"
        const val ACTION_RELOAD_TOUR = "cn.anitabi.navigator.navigation.RELOAD_TOUR"
        const val ACTION_PREPARE_HANDOFF = "cn.anitabi.navigator.navigation.PREPARE_HANDOFF"
        const val ACTION_PREPARE_NEXT_HANDOFF = "cn.anitabi.navigator.navigation.PREPARE_NEXT_HANDOFF"
        const val ACTION_CONFIRM_EXTERNAL_ARRIVAL =
            "cn.anitabi.navigator.navigation.CONFIRM_EXTERNAL_ARRIVAL"
        const val ACTION_PAUSE_EXTERNAL = "cn.anitabi.navigator.navigation.PAUSE_EXTERNAL"
        const val ACTION_RESUME_EXTERNAL = "cn.anitabi.navigator.navigation.RESUME_EXTERNAL"
        const val ACTION_END_EXTERNAL = "cn.anitabi.navigator.navigation.END_EXTERNAL"
        const val EXTRA_TOUR_ID = "tour_id"
        const val EXTRA_EXPECTED_LEG_INDEX = "expected_leg_index"
        const val EXTRA_CONFIRM_EARLY = "confirm_early"
        const val EXTRA_RESULT_RECEIVER = "result_receiver"
        const val EXTRA_RESULT_MESSAGE = "result_message"
        const val EXTRA_REQUIRE_FINE_LOCATION = "require_fine_location"

        const val RESULT_HANDOFF_READY = 1
        const val RESULT_ARRIVAL_CONFIRMED = 2
        const val RESULT_EARLY_CONFIRMATION_REQUIRED = 3
        const val RESULT_ENDED = 4
        const val RESULT_RELOADED = 5
        const val RESULT_RELOAD_SUPERSEDED = 6
        const val RESULT_COMPLETED = 7
        const val RESULT_ERROR = -1
        private const val ROUTE_REFRESH_REQUIRED_MESSAGE =
            "路线暂时无法刷新，请联网后重试；行程顺序和导航进度已保留"
        private const val EXTERNAL_CONTROL_REQUIRED_MESSAGE =
            "请至少启用悬浮窗或可见的导航通知后再继续外部分段导航"
        private const val EXTERNAL_TRANSIT_SAVE_IN_PROGRESS_MESSAGE =
            "正在保存当前分段，请稍后重试"
        private const val STALE_EXTERNAL_TRANSIT_MESSAGE = "行程状态已变化，请重试"
        private const val PROGRESS_SAVE_FAILED_MESSAGE =
            "导航进度保存失败，已停止导航；恢复入口仍保留，请重试"
        private const val STOP_SAVE_FAILED_MESSAGE =
            "无法保存暂停状态，导航已停止；恢复入口仍保留，请重试"
        internal const val CHANNEL_ID = "continuous_navigation"
        private const val NOTIFICATION_ID = 1001
    }
}

private fun TourPlan?.isExternalJapanTransit(): Boolean =
    this?.executionStrategy?.isExternalMapNavigation() == true

private fun TravelMode.externalModeLabel(): String = when (this) {
    TravelMode.DRIVE -> "驾车导航"
    TravelMode.BIKE -> "骑行导航"
    TravelMode.WALK -> "步行导航"
    TravelMode.TRANSIT -> "公交路线"
}

internal fun externalHandoffMismatch(plan: TourPlan, leg: TourLeg): String? = when {
    plan.executionStrategy == TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES ->
        "当前行程不是外部分段导航"
    plan.executionStrategy == TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN &&
        (plan.mode != TravelMode.TRANSIT || leg.mode != TravelMode.TRANSIT) ->
        "Google 日本外部交接只支持公交分段"
    plan.executionStrategy == TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN &&
        (plan.mapProvider != MapProvider.GOOGLE || leg.provider != MapProvider.GOOGLE) ->
        "Google 外部交接提供方不一致"
    plan.executionStrategy == TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN &&
        (plan.coordinateSystem != CoordinateSystem.WGS84 || leg.coordinateSystem != CoordinateSystem.WGS84) ->
        "Google 外部交接坐标系不一致"
    plan.executionStrategy == TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND &&
        (plan.mapProvider != MapProvider.AMAP || leg.provider != MapProvider.AMAP) ->
        "高德外部交接提供方不一致"
    plan.executionStrategy == TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND &&
        leg.geometry.isNotEmpty() &&
        (plan.coordinateSystem != CoordinateSystem.GCJ02 || leg.coordinateSystem != CoordinateSystem.GCJ02) ->
        "高德路线几何必须为 GCJ02"
    else -> null
}

internal fun externalHandoffNames(plan: TourPlan, legIndex: Int): Pair<String, String> {
    val leg = plan.legs.getOrNull(legIndex) ?: return "起点" to "终点"
    fun pointName(pointId: String?): String? = pointId?.let { id ->
        plan.selectedPoints.firstOrNull { it.id == id }?.name
    }
    val originName = pointName(plan.legs.getOrNull(legIndex - 1)?.destinationPointId)
        ?: plan.selectedPoints.firstOrNull { it.coordinate == leg.from }?.name
        ?: if (legIndex == 0) "当前位置" else "上一站"
    val destinationName = pointName(leg.destinationPointId)
        ?: plan.selectedPoints.firstOrNull { it.coordinate == leg.to }?.name
        ?: "终点"
    return originName to destinationName
}

internal fun externalLocationPermissionMessage(
    strategy: TransitExecutionStrategy?,
    resume: Boolean,
): String = when {
    strategy == TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN && resume ->
        "需要精确定位权限才能恢复日本公交行程"
    strategy == TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN ->
        "需要精确定位权限才能继续日本公交行程"
    strategy == TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND && resume ->
        "需要精确定位权限才能恢复高德地图外部分段导航"
    strategy == TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND ->
        "需要精确定位权限才能继续高德地图外部分段导航"
    resume -> "需要精确定位权限才能恢复外部分段导航"
    else -> "需要精确定位权限才能继续外部分段导航"
}

internal fun externalTransitOverlayMustHideImmediately(progress: NavigationProgress): Boolean =
    progress.isPaused || progress.state in setOf(NavigationState.COMPLETED, NavigationState.ENDED)

internal fun navigationLocationUpdateMinDistanceMeters(isExternalJapanTransit: Boolean): Float =
    if (isExternalJapanTransit) 0f else 2f

internal fun shouldFinalizePersistedTerminalNavigation(
    persistedPlan: TourPlan,
    persistedProgress: NavigationProgress,
    expectedGeneration: Long,
    currentGeneration: Long,
    stopping: Boolean,
    currentPlan: TourPlan?,
    currentProgress: NavigationProgress?,
): Boolean =
    persistedProgress.state in setOf(NavigationState.COMPLETED, NavigationState.ENDED) &&
        expectedGeneration == currentGeneration &&
        !stopping &&
        currentPlan == persistedPlan &&
        currentProgress == persistedProgress

@Suppress("DEPRECATION")
private fun Intent.resultReceiver(): ResultReceiver? =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(NavigationService.EXTRA_RESULT_RECEIVER, ResultReceiver::class.java)
    } else {
        getParcelableExtra(NavigationService.EXTRA_RESULT_RECEIVER)
    }

private fun ResultReceiver.sendError(message: String) {
    send(
        NavigationService.RESULT_ERROR,
        Bundle().apply { putString(NavigationService.EXTRA_RESULT_MESSAGE, message) },
    )
}

internal fun externalLocationForegroundActionRequiresFinePermission(action: String?): Boolean = when (action) {
    NavigationService.ACTION_PREPARE_HANDOFF,
    NavigationService.ACTION_PREPARE_NEXT_HANDOFF,
    NavigationService.ACTION_CONFIRM_EXTERNAL_ARRIVAL,
    NavigationService.ACTION_RESUME_EXTERNAL,
    -> true
    else -> false
}

private fun Intent?.requiresFineLocationForeground(): Boolean =
    locationForegroundRequestRequiresFinePermission(
        action = this?.action,
        requireFineLocation = this?.getBooleanExtra(
            NavigationService.EXTRA_REQUIRE_FINE_LOCATION,
            false,
        ) == true,
    )

internal fun locationForegroundRequestRequiresFinePermission(
    action: String?,
    requireFineLocation: Boolean,
): Boolean = requireFineLocation || externalLocationForegroundActionRequiresFinePermission(action)

internal fun navigationPlanRequiresFineLocationForeground(
    @Suppress("UNUSED_PARAMETER") mode: TravelMode,
    executionStrategy: TransitExecutionStrategy,
): Boolean = executionStrategy.isExternalMapNavigation()

internal fun tryLocationForegroundPromotion(
    promote: () -> Unit,
    onFailure: (RuntimeException) -> Unit,
): Boolean = try {
    promote()
    true
} catch (exception: RuntimeException) {
    onFailure(exception)
    false
}

internal fun resumableProgressAfterFailure(progress: NavigationProgress?): NavigationProgress? =
    progress?.takeUnless { it.state in setOf(NavigationState.COMPLETED, NavigationState.ENDED) }?.copy(
        state = NavigationState.PLANNED,
        dwellingUntilEpochMillis = null,
        offRouteSinceEpochMillis = null,
    )

internal fun resumableProgressForTourAfterFailure(
    tourId: String,
    engineProgress: NavigationProgress?,
    runtimeProgress: NavigationProgress?,
): NavigationProgress? = resumableProgressAfterFailure(
    engineProgress?.takeIf { it.tourId == tourId }
        ?: runtimeProgress?.takeIf { it.tourId == tourId },
)

internal fun navigationRuntimeAfterFailure(
    previous: NavigationRuntimeState,
    currentPlan: TourPlan?,
    progress: NavigationProgress?,
    message: String,
): NavigationRuntimeState = previous.copy(
    plan = currentPlan ?: previous.plan,
    progress = if (currentPlan != null) progress else previous.progress,
    instruction = "导航未开始，请返回路线预览后重试",
    isRunning = false,
    isRerouting = false,
    errorMessage = message,
)

internal fun navigationRuntimeAfterForegroundPromotionFailure(
    previous: NavigationRuntimeState,
    message: String,
): NavigationRuntimeState = previous.copy(
    instruction = "导航已暂停，请打开应用重试",
    isRunning = false,
    isRerouting = false,
    errorMessage = message,
)

internal suspend fun completeNavigationCleanup(
    expectedGeneration: Long,
    currentGeneration: () -> Long,
    persistRollback: suspend () -> Unit,
    stopService: () -> Unit,
) {
    try {
        persistRollback()
    } finally {
        if (expectedGeneration == currentGeneration()) stopService()
    }
}

internal fun navigationFailureMessage(throwable: Throwable): String = when (throwable) {
    is ApiException.QuotaExhausted -> "本月共享路线额度已用尽，暂时无法开始；不会继续产生费用"
    is ApiException.RateLimited -> "请求过于频繁，请稍后再试"
    is ApiException.Unauthenticated -> "匿名连接失败，请检查网络后重试"
    is ApiException.InvalidArgument -> "路线请求参数无效，请返回重新生成路线"
    is ApiException.NoRoute, is ApiException.NotFound -> "未找到可用路线，请返回重新生成"
    is ApiException.UpstreamUnavailable -> "路线服务暂时不可用，请稍后再试"
    is ApiException.BackendUnavailable, is ApiException.Server ->
        "路线服务暂时不可用；行程和导航进度仍保留在本机"
    is ApiException.Network -> "无法连接路线服务，请检查网络后重试"
    is ApiException.InvalidResponse -> "路线服务返回了无法识别的数据"
    is ApiException.InvalidCredentials, is ApiException.Forbidden, is ApiException.Http ->
        "路线请求失败，请稍后再试"
    is MissingLocationPermissionException -> "需要定位权限才能开始导航"
    is LocationUnavailableException -> "暂时无法取得当前位置，请检查系统定位开关"
    is ExternalTransitControlUnavailableException ->
        "请至少启用悬浮窗或可见的导航通知后再继续外部分段导航"
    else -> throwable.message ?: "无法开始连续导航"
}

internal class ExternalTransitControlUnavailableException : Exception("External transit control is unavailable")
