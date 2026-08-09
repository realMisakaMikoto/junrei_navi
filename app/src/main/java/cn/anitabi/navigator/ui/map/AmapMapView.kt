package cn.anitabi.navigator.ui.map

import android.util.Log
import android.view.View
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.anitabi.navigator.core.model.MapProvider
import com.amap.api.maps.AMap
import com.amap.api.maps.MapView

private const val AMAP_LOG_TAG = "AmapMapView"

@Composable
internal fun AmapMapView(
    privacyReady: Boolean,
    onMapReady: (AMap) -> Unit,
    modifier: Modifier = Modifier,
    onUnavailable: () -> Unit = {},
    onViewportSizeChanged: (width: Int, height: Int) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnMapReady = rememberUpdatedState(onMapReady)
    val currentOnUnavailable = rememberUpdatedState(onUnavailable)
    val currentOnViewportSizeChanged = rememberUpdatedState(onViewportSizeChanged)
    var attempt by remember { mutableIntStateOf(0) }
    var runtimeFailure by remember(attempt) { mutableStateOf(false) }
    var savedMapState by rememberSaveable { mutableStateOf<Bundle?>(null) }
    val creation: Result<SingleLiveMapLease<MapView>> = remember(privacyReady, attempt) {
        if (!privacyReady) {
            Result.failure(IllegalStateException("AMap privacy gate is not ready"))
        } else {
            runCatching {
                processMapCoordinator.acquire(MapProvider.AMAP) {
                    val view = MapView(context)
                    try {
                        view.onCreate(savedMapState)
                        view
                    } catch (error: Throwable) {
                        runCatching(view::onDestroy)
                            .onFailure { destroyError ->
                                logAmapFailure("ON_DESTROY_AFTER_CREATE_FAILURE", destroyError)
                            }
                        throw error
                    }
                }.also { lease ->
                    // A provider switch can retire this lease before its DisposableEffect runs.
                    // Still release the constructed SDK view in that narrow lifecycle window.
                    lease.installDestroyAction {
                        runCatching(lease.value::onDestroy)
                            .onFailure { error -> logAmapFailure("ON_DESTROY_BEFORE_ATTACH", error) }
                    }
                }
            }.onFailure { error -> logAmapFailure("CONSTRUCTOR", error) }
        }
    }
    val lease = creation.getOrNull()
    val mapView = lease?.value
    val unavailable = mapView == null || runtimeFailure

    LaunchedEffect(unavailable) {
        if (unavailable) currentOnUnavailable.value()
    }

    if (unavailable) {
        AmapUnavailablePanel(
            modifier = modifier,
            privacyReady = privacyReady,
            onRetry = {
                runtimeFailure = false
                attempt += 1
            },
        )
        return
    }
    requireNotNull(lease)
    requireNotNull(mapView)

    AndroidView(
        factory = { mapView },
        modifier = modifier.onSizeChanged { size ->
            currentOnViewportSizeChanged.value(size.width, size.height)
        },
    )

    DisposableEffect(lifecycleOwner, mapView, lease) {
        if (lease.isDestroyed) {
            return@DisposableEffect onDispose {}
        }
        var resumed = false
        var mapDelivered = false
        var disposed = false

        fun failSafely(stage: String, block: () -> Unit): Boolean = try {
            block()
            true
        } catch (error: RuntimeException) {
            logAmapFailure(stage, error)
            runtimeFailure = true
            false
        }

        fun pause() {
            if (!resumed) return
            runCatching(mapView::onPause)
                .onFailure { error -> logAmapFailure("ON_PAUSE", error) }
            resumed = false
        }

        fun activate() {
            if (!mapView.isAttachedToWindow) return
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
            if (!resumed) resumed = failSafely("ON_RESUME", mapView::onResume)
            if (!resumed || mapDelivered) return
            mapDelivered = failSafely("GET_MAP") {
                if (!disposed) currentOnMapReady.value(mapView.map)
            }
        }

        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = activate()

            override fun onViewDetachedFromWindow(view: View) = pause()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> activate()
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                -> pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.addOnAttachStateChangeListener(attachListener)
        lease.installDestroyAction {
            disposed = true
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.removeOnAttachStateChangeListener(attachListener)
            pause()
            runCatching(mapView::onDestroy)
                .onFailure { error -> logAmapFailure("ON_DESTROY", error) }
        }
        activate()

        onDispose {
            if (!lease.isDestroyed) {
                runCatching {
                    Bundle().also { state ->
                        mapView.onSaveInstanceState(state)
                        savedMapState = state
                    }
                }.onFailure { error -> logAmapFailure("ON_SAVE_INSTANCE_STATE", error) }
            }
            lease.close()
        }
    }
}

private fun logAmapFailure(stage: String, error: Throwable) {
    Log.w(AMAP_LOG_TAG, "$stage failed (${error.javaClass.name})")
}

@Composable
private fun AmapUnavailablePanel(
    modifier: Modifier,
    privacyReady: Boolean,
    onRetry: () -> Unit,
) {
    Surface(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("高德地图暂时无法加载", style = MaterialTheme.typography.titleMedium)
            Text(
                if (privacyReady) {
                    "选点、行程与导航进度已保留，请稍后重试。"
                } else {
                    "高德地图隐私授权、密钥或地区数据尚未就绪，未创建地图。"
                },
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text("重试")
            }
        }
    }
}
