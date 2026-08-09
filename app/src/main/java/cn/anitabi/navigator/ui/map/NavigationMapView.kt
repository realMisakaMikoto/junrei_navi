package cn.anitabi.navigator.ui.map

import android.util.Log
import android.view.View
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
import com.google.android.gms.maps.GoogleMap
import com.google.android.libraries.navigation.NavigationView

private const val MAP_LOG_TAG = "NavigationMapView"

private fun logMapFailure(stage: String, error: Throwable) {
    Log.w(MAP_LOG_TAG, "$stage failed (${error.javaClass.name})")
}

@Composable
fun NavigationMapView(
    onMapReady: (GoogleMap) -> Unit,
    modifier: Modifier = Modifier,
    navigationUiEnabled: Boolean = false,
    onUnavailable: () -> Unit = {},
    onViewportSizeChanged: (width: Int, height: Int) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnMapReady = rememberUpdatedState(onMapReady)
    val currentOnUnavailable = rememberUpdatedState(onUnavailable)
    val currentOnViewportSizeChanged = rememberUpdatedState(onViewportSizeChanged)
    var attempt by remember(navigationUiEnabled) { mutableIntStateOf(0) }
    var runtimeFailure by remember(navigationUiEnabled, attempt) { mutableStateOf(false) }
    val creation = remember(navigationUiEnabled, attempt) {
        runCatching {
            processMapCoordinator.acquire(MapProvider.GOOGLE) { NavigationView(context) }.also { lease ->
                lease.installDestroyAction {
                    runCatching(lease.value::onDestroy)
                        .onFailure { error -> logMapFailure("ON_DESTROY_BEFORE_ATTACH", error) }
                }
            }
        }
            .onFailure { error -> logMapFailure("CONSTRUCTOR", error) }
    }
    val lease = creation.getOrNull()
    val navigationView = lease?.value
    val unavailable = navigationView == null || runtimeFailure

    LaunchedEffect(unavailable) {
        if (unavailable) currentOnUnavailable.value()
    }

    if (unavailable) {
        MapUnavailablePanel(
            modifier = modifier,
            onRetry = {
                runtimeFailure = false
                attempt += 1
            },
        )
        return
    }
    requireNotNull(lease)
    requireNotNull(navigationView)

    AndroidView(
        factory = { navigationView },
        modifier = modifier.onSizeChanged { size ->
            currentOnViewportSizeChanged.value(size.width, size.height)
        },
    )

    DisposableEffect(lifecycleOwner, navigationView, lease) {
        if (lease.isDestroyed) {
            return@DisposableEffect onDispose {}
        }
        var created = false
        var configured = false
        var started = false
        var resumed = false
        var mapRequested = false
        var disposed = false

        fun failSafely(stage: String, block: () -> Unit): Boolean = try {
            block()
            true
        } catch (error: RuntimeException) {
            logMapFailure(stage, error)
            runtimeFailure = true
            false
        }

        fun start() {
            if (navigationView.isAttachedToWindow && created && configured && !started) {
                started = failSafely("ON_START", navigationView::onStart)
            }
        }

        fun resume() {
            start()
            if (started && !resumed) {
                resumed = failSafely("ON_RESUME", navigationView::onResume)
            }
        }

        fun pause() {
            if (resumed) {
                runCatching(navigationView::onPause)
                    .onFailure { error -> logMapFailure("ON_PAUSE", error) }
                resumed = false
            }
        }

        fun stop() {
            pause()
            if (started) {
                runCatching(navigationView::onStop)
                    .onFailure { error -> logMapFailure("ON_STOP", error) }
                started = false
            }
        }

        fun requestMap() {
            if (!navigationView.isAttachedToWindow || !created || !configured || mapRequested) return
            mapRequested = failSafely("GET_MAP") {
                navigationView.getMapAsync { map ->
                    if (disposed) return@getMapAsync
                    try {
                        currentOnMapReady.value(map)
                    } catch (error: RuntimeException) {
                        logMapFailure("MAP_READY_CALLBACK", error)
                        runtimeFailure = true
                    }
                }
            }
        }

        fun activateAttachedView() {
            if (!navigationView.isAttachedToWindow) return
            if (!created) {
                created = failSafely("ON_CREATE") { navigationView.onCreate(null) }
            }
            if (!created) return
            if (!configured) {
                configured = failSafely("UI_CONFIGURATION") {
                    navigationView.setNavigationUiEnabled(navigationUiEnabled)
                    navigationView.setHeaderEnabled(navigationUiEnabled)
                    navigationView.setEtaCardEnabled(navigationUiEnabled)
                    navigationView.setTripProgressBarEnabled(navigationUiEnabled)
                }
            }
            if (!configured) return
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                resume()
                if (!resumed) return
            } else if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                start()
                if (!started) return
            } else {
                return
            }
            requestMap()
        }

        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                activateAttachedView()
            }

            override fun onViewDetachedFromWindow(view: View) {
                stop()
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME -> activateAttachedView()
                Lifecycle.Event.ON_PAUSE -> pause()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        navigationView.addOnAttachStateChangeListener(attachListener)
        activateAttachedView()

        lease.installDestroyAction {
            disposed = true
            lifecycleOwner.lifecycle.removeObserver(observer)
            navigationView.removeOnAttachStateChangeListener(attachListener)
            stop()
            if (created) {
                runCatching(navigationView::onDestroy)
                    .onFailure { error -> logMapFailure("ON_DESTROY", error) }
            }
        }
        onDispose { lease.close() }
    }
}

@Composable
private fun MapUnavailablePanel(modifier: Modifier, onRetry: () -> Unit) {
    Surface(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Google 地图暂时无法加载", style = MaterialTheme.typography.titleMedium)
            Text(
                "选点、行程与导航进度已保留，请稍后重试。",
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text("重试")
            }
        }
    }
}
