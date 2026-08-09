package cn.anitabi.navigator.navigation

import android.app.Application
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.core.navigation.RoadNavigationAction
import cn.anitabi.navigator.core.navigation.RoadNavigationBatchCoordinator
import cn.anitabi.navigator.data.network.backend.BackendApi
import com.google.android.libraries.navigation.AudioGuidanceSettings
import com.google.android.libraries.navigation.ListenableResultFuture
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.Waypoint
import java.util.concurrent.CancellationException as FutureCancellationException
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class GoogleRoadNavigationSession(
    private val application: Application,
    private val backendApi: BackendApi,
    private val plan: TourPlan,
    initialLegIndex: Int,
    private val onArrival: (Int) -> Unit,
    private val onRemainingDistanceChanged: (Double) -> Unit,
    private val onReroutingChanged: (Boolean) -> Unit,
) {
    private val coordinator = RoadNavigationBatchCoordinator(
        destinationCount = plan.legs.size,
        initialLegIndex = initialLegIndex,
    )
    private val mutex = Mutex()
    private var navigator: Navigator? = null
    private var closed = false

    private val arrivalListener = Navigator.ArrivalListener {
        onArrival(coordinator.currentLegIndex())
    }
    private val remainingListener = Navigator.RemainingTimeOrDistanceChangedListener {
        val remainingMeters = runCatching { navigator?.currentTimeAndDistance?.meters }.getOrNull()
        if (remainingMeters != null && remainingMeters >= 0) {
            onRemainingDistanceChanged(remainingMeters.toDouble())
        }
    }
    private val reroutingListener = Navigator.ReroutingListener {
        onReroutingChanged(true)
    }
    private val routeChangedListener = Navigator.RouteChangedListener {
        onReroutingChanged(false)
    }

    init {
        require(plan.mode != TravelMode.TRANSIT) { "Transit does not use native road navigation" }
    }

    suspend fun synchronize(legIndex: Int) {
        mutex.withLock {
            check(!closed) { "Navigation session is closed" }
            val activeNavigator = navigator ?: acquireNavigator().also { ready ->
                navigator = ready
                configure(ready)
            }
            when (val action = coordinator.actionFor(legIndex)) {
                is RoadNavigationAction.Load -> loadBatch(activeNavigator, action.legIndexes)
                RoadNavigationAction.Continue -> {
                    activeNavigator.continueToNextDestination()
                    activeNavigator.startGuidance()
                }
                RoadNavigationAction.Complete -> activeNavigator.stopGuidance()
                RoadNavigationAction.None -> Unit
            }
        }
    }

    fun pauseGuidance() {
        navigator?.stopGuidance()
    }

    fun close() {
        if (closed) return
        closed = true
        navigator?.let { activeNavigator ->
            activeNavigator.stopGuidance()
            activeNavigator.removeArrivalListener(arrivalListener)
            activeNavigator.removeRemainingTimeOrDistanceChangedListener(remainingListener)
            activeNavigator.removeReroutingListener(reroutingListener)
            activeNavigator.removeRouteChangedListener(routeChangedListener)
            activeNavigator.clearDestinations()
            activeNavigator.cleanup()
        }
        navigator = null
    }

    private fun configure(activeNavigator: Navigator) {
        activeNavigator.setAudioGuidanceSettings(
            AudioGuidanceSettings.builder()
                .setGuidanceMode(AudioGuidanceSettings.GuidanceMode.VOICE_ALERTS_AND_GUIDANCE)
                .setVolumeLevel(AudioGuidanceSettings.VolumeLevel.NORMAL)
                .setVibrationEnabled(true)
                .setBluetoothAudioEnabled(true)
                .build(),
        )
        activeNavigator.addArrivalListener(arrivalListener)
        activeNavigator.addRemainingTimeOrDistanceChangedListener(5, 25, remainingListener)
        activeNavigator.addReroutingListener(reroutingListener)
        activeNavigator.addRouteChangedListener(routeChangedListener)
    }

    private suspend fun loadBatch(activeNavigator: Navigator, legIndexes: IntRange) {
        val destinationCount = legIndexes.count()
        val reservation = backendApi.reserveNavigation(
            origin = plan.legs[legIndexes.first].from,
            destinations = legIndexes.map { plan.legs[it].to },
            expectedProvider = MapProvider.GOOGLE,
        )
        check(reservation.reservedDestinations == destinationCount) {
            "Navigation reservation did not cover the requested batch"
        }
        val pointsById = plan.selectedPoints.associateBy { it.id }
        val waypoints = legIndexes.map { legIndex ->
            val leg = plan.legs[legIndex]
            val title = leg.destinationPointId?.let(pointsById::get)?.name
                ?: if (legIndex == plan.legs.lastIndex) "路线终点" else "下一站"
            Waypoint.builder()
                .setLatLng(leg.to.latitude, leg.to.longitude)
                .setTitle(title)
                .setVehicleStopover(true)
                .build()
        }
        activeNavigator.stopGuidance()
        activeNavigator.clearDestinations()
        val status = activeNavigator.setDestinations(waypoints, routingOptions()).awaitNavigationResult()
        check(status == Navigator.RouteStatus.OK) { status.toUserMessage() }
        coordinator.markLoaded(legIndexes)
        activeNavigator.startGuidance()
    }

    private suspend fun acquireNavigator(): Navigator = suspendCancellableCoroutine { continuation ->
        NavigationApi.getNavigator(
            application,
            object : NavigationApi.NavigatorListener {
                override fun onNavigatorReady(readyNavigator: Navigator) {
                    continuation.resumeIfActive(readyNavigator)
                }

                override fun onError(errorCode: Int) {
                    continuation.resumeWithExceptionIfActive(
                        IllegalStateException(initializationErrorMessage(errorCode)),
                    )
                }
            },
        )
    }

    private fun routingOptions(): RoutingOptions = RoutingOptions()
        .travelMode(
            when (plan.mode) {
                TravelMode.DRIVE -> RoutingOptions.TravelMode.DRIVING
                TravelMode.BIKE -> RoutingOptions.TravelMode.CYCLING
                TravelMode.WALK -> RoutingOptions.TravelMode.WALKING
                TravelMode.TRANSIT -> error("Transit does not use native road navigation")
            },
        )
        .routingStrategy(
            when (plan.objective) {
                RouteObjective.FASTEST -> RoutingOptions.RoutingStrategy.DEFAULT_BEST
                RouteObjective.SHORTEST -> RoutingOptions.RoutingStrategy.SHORTER
            },
        )
}

internal suspend fun <T> ListenableResultFuture<T>.awaitNavigationResult(): T = try {
    // SDK 7.8.0 throws cancelled listener failures on the UI thread, including cleanup-triggered cancellation.
    runInterruptible(Dispatchers.IO) { completedNavigationResult() }
} catch (exception: FutureCancellationException) {
    currentCoroutineContext().ensureActive()
    throw NavigationRouteRequestCancelledException(exception)
}

private fun <T> ListenableResultFuture<T>.completedNavigationResult(): T = try {
    get()
} catch (exception: ExecutionException) {
    throw exception.cause ?: exception
}

internal class NavigationRouteRequestCancelledException(
    cause: FutureCancellationException,
) : IllegalStateException("Google 导航路线请求已取消", cause)

private fun <T> CancellableContinuation<T>.resumeIfActive(value: T) {
    if (isActive) resume(value)
}

private fun <T> CancellableContinuation<T>.resumeWithExceptionIfActive(exception: Throwable) {
    if (isActive) resumeWithException(exception)
}

internal fun initializationErrorMessage(errorCode: Int): String = when (errorCode) {
    NavigationApi.ErrorCode.NOT_AUTHORIZED -> "Google 导航授权失败，请检查应用密钥限制"
    NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED -> "需要先接受 Google 导航条款"
    NavigationApi.ErrorCode.NETWORK_ERROR -> "Google 导航暂时无法连接网络"
    NavigationApi.ErrorCode.LOCATION_PERMISSION_MISSING -> "Google 导航需要定位权限"
    else -> "Google 导航初始化失败"
}

private fun Navigator.RouteStatus.toUserMessage(): String = when (this) {
    Navigator.RouteStatus.NO_ROUTE_FOUND -> "Google 导航未找到可用路线"
    Navigator.RouteStatus.NETWORK_ERROR -> "Google 导航暂时无法连接网络"
    Navigator.RouteStatus.QUOTA_CHECK_FAILED -> "Google 导航额度检查失败"
    Navigator.RouteStatus.ROUTE_CANCELED -> "Google 导航路线请求已取消"
    Navigator.RouteStatus.LOCATION_DISABLED -> "请开启系统定位后再开始导航"
    Navigator.RouteStatus.LOCATION_UNKNOWN -> "暂时无法取得当前位置"
    Navigator.RouteStatus.WAYPOINT_ERROR -> "巡礼点无法用于 Google 导航"
    Navigator.RouteStatus.DUPLICATE_WAYPOINTS_ERROR -> "相邻巡礼点位置重复，无法开始导航"
    Navigator.RouteStatus.OK -> "Google 导航路线已就绪"
}
