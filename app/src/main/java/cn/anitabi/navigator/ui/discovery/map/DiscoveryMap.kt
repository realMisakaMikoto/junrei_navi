package cn.anitabi.navigator.ui.discovery.map

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.ui.map.AmapMapView
import cn.anitabi.navigator.ui.map.NavigationMapView
import cn.anitabi.navigator.ui.map.isAmapMapCreationReady
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext

/**
 * dataVersion identifies the coordinate/member set, including active subject filters.
 * Detail-only changes may replace points without rebuilding the spatial index.
 * The caller supplies only classified points; the adapter also enforces provider equality.
 */
@Composable
fun DiscoveryMap(
    dataVersion: String,
    points: List<DiscoveryMapPoint>,
    provider: MapProvider,
    privacyReady: Boolean,
    selectedIds: Set<String>,
    focusedPointId: String?,
    imagesEnabled: Boolean,
    darkTheme: Boolean,
    padding: DiscoveryMapPadding,
    cameraCommand: DiscoveryCameraCommand?,
    onVisibleIdsChanged: (Set<String>) -> Unit,
    onPointClick: (String) -> Unit,
    onOverlapClick: (List<String>) -> Unit,
    onCameraChanged: (DiscoveryCameraPosition) -> Unit,
    onManualMove: () -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
    onCameraCommandApplied: (Long, DiscoveryCameraPosition) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val fontDensity = LocalDensity.current
    val density = fontDensity.density
    val currentPoints = rememberUpdatedState(points)
    val currentPointClick = rememberUpdatedState(onPointClick)
    val currentOverlapClick = rememberUpdatedState(onOverlapClick)
    val currentCameraChanged = rememberUpdatedState(onCameraChanged)
    val currentCommandApplied = rememberUpdatedState(onCameraCommandApplied)
    val currentManualMove = rememberUpdatedState(onManualMove)
    val currentUnavailable = rememberUpdatedState(onUnavailable)
    val currentVisible = rememberUpdatedState(onVisibleIdsChanged)
    val currentPadding = rememberUpdatedState(padding)
    val currentCommand = rememberUpdatedState(cameraCommand)
    var adapter by remember(provider) { mutableStateOf<DiscoveryMapAdapter?>(null) }
    var width by remember(provider) { mutableIntStateOf(0) }
    var height by remember(provider) { mutableIntStateOf(0) }
    var cameraRevision by remember(provider) { mutableIntStateOf(0) }
    var index by remember(provider) { mutableStateOf<DiscoverySpatialIndex?>(null) }
    var displayPoints by remember(provider) { mutableStateOf<Map<String, GeoPoint>>(emptyMap()) }
    var clusters by remember(provider) { mutableStateOf<List<DiscoveryCluster>>(emptyList()) }
    var clusterToken by remember(provider) { mutableStateOf<DiscoveryCalculationToken?>(null) }
    var clusterInputs by remember(provider) { mutableStateOf<Any?>(null) }
    val calculationInputs = remember(adapter, dataVersion, cameraRevision, points, selectedIds,
        focusedPointId, padding, imagesEnabled, width, height, fontDensity) { Any() }
    val currentCalculationInputs = rememberUpdatedState(calculationInputs)
    val currentClusters = rememberUpdatedState(clusters)
    val currentDisplayPoints = rememberUpdatedState(displayPoints)
    val generation = remember(provider) { DiscoveryRequestGeneration() }
    val appliedMarkers = remember(adapter) { mutableMapOf<String, MarkerAppearance>() }
    val images = remember { object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    } }
    val failedImages = remember { linkedSetOf<String>() }
    var imageRevision by remember { mutableIntStateOf(0) }
    var consumedCommand by remember(provider) { mutableStateOf<Long?>(null) }
    var pendingCommand by remember(adapter) { mutableStateOf<Long?>(null) }
    var pendingClusterGeometry by remember(adapter) { mutableStateOf<Triple<DiscoveryMapPadding, Int, Int>?>(null) }
    var cameraRequestGeneration by remember(adapter) { mutableIntStateOf(0) }
    var initializedCamera by remember(adapter) { mutableStateOf(false) }

    key(provider) {
        when (provider) {
            MapProvider.GOOGLE -> NavigationMapView(
                modifier = modifier,
                onMapReady = { adapter = GoogleDiscoveryMapAdapter(it) },
                onUnavailable = { currentUnavailable.value() },
                onViewportSizeChanged = { w, h -> width = w; height = h },
            )
            MapProvider.AMAP -> AmapMapView(
                modifier = modifier,
                privacyReady = privacyReady && isAmapMapCreationReady(context),
                onMapReady = { adapter = AmapDiscoveryMapAdapter(context, it) { currentUnavailable.value() } },
                onUnavailable = { currentUnavailable.value() },
                onViewportSizeChanged = { w, h -> width = w; height = h },
            )
        }
    }

    DisposableEffect(adapter) {
        val map = adapter
        if (map != null) {
            map.listen(
                onIdle = { cameraRevision++; currentCameraChanged.value(map.camera()) },
                onMove = { generation.invalidate() },
                onGesture = {
                    generation.invalidate()
                    cameraRequestGeneration++
                    pendingCommand = null
                    pendingClusterGeometry = null
                    // A pending initial/focus command may not run after a user's gesture.
                    consumedCommand = currentCommand.value?.sequence
                    currentManualMove.value()
                },
                onMarker = { id ->
                    if (clusterInputs !== currentCalculationInputs.value || clusterToken?.let(generation::accepts) != true) return@listen
                    val cluster = currentClusters.value.firstOrNull { it.id == id } ?: return@listen
                    if (cluster.memberIds.size == 1) currentPointClick.value(cluster.memberIds.single())
                    else if (map.camera().zoom >= map.maxZoom - .1f) currentOverlapClick.value(cluster.memberIds)
                    else {
                        val anchor = currentDisplayPoints.value[cluster.anchorId] ?: return@listen
                        cameraRequestGeneration++
                        pendingCommand = null
                        val requestGeneration = cameraRequestGeneration
                        pendingClusterGeometry = Triple(currentPadding.value, width, height)
                        map.focus(anchor, (map.camera().zoom + 2f).coerceAtMost(map.maxZoom),
                            currentPadding.value.content(width, height), onSettled = {
                                if (cameraRequestGeneration == requestGeneration) pendingClusterGeometry = null
                            })
                    }
                },
            )
        }
        onDispose {
            cameraRequestGeneration++
            pendingCommand = null
            pendingClusterGeometry = null
            generation.invalidate()
            // A provider switch may have destroyed the old map lease before this effect.
            runCatching { map?.close() }
        }
    }

    LaunchedEffect(adapter, dataVersion, provider) {
        val map = adapter ?: return@LaunchedEffect
        generation.invalidate()
        index = null
        clusters = emptyList()
        clusterToken = null
        clusterInputs = null
        displayPoints = emptyMap()
        currentVisible.value(emptySet())
        try {
            val source = currentPoints.value.filter { it.provider == provider }
            val converted = LinkedHashMap<String, GeoPoint>(source.size)
            source.forEachCooperatively { point ->
                require(point.id !in converted) { "Discovery IDs must be unique" }
                converted[point.id] = map.displayCoordinate(point.id, point.coordinate)
            }
            val rebuilt = withContext(Dispatchers.Default) {
                val workerContext = coroutineContext
                DiscoverySpatialIndex(DiscoveryIndexKey(dataVersion, provider), converted.map { IndexedDiscoveryPoint(it.key, it.value) }) { workerContext.ensureActive() }
            }
            map.trimCoordinates(converted.keys)
            displayPoints = converted
            index = rebuilt
        } catch (error: CancellationException) { throw error }
        catch (_: RuntimeException) { currentUnavailable.value() }
    }

    LaunchedEffect(adapter, darkTheme, padding, width, height) {
        val map = adapter ?: return@LaunchedEffect
        if (width <= 0 || height <= 0) return@LaunchedEffect
        try {
            if (pendingClusterGeometry?.let { it != Triple(padding, width, height) } == true) {
                // Cancel a cluster tap's unfinished padding correction after a layout change.
                cameraRequestGeneration++
                pendingClusterGeometry = null
                map.stop()
            }
            map.configure(darkTheme, padding, width, height)
            if (!initializedCamera && currentCommand.value == null && provider == MapProvider.GOOGLE) {
                // Authored country overview, not a source/user location or a route target.
                map.restore(DiscoveryCameraPosition(GeoPoint(37.0, 138.0), 4.5f))
            }
            initializedCamera = true
            cameraRevision++
        } catch (_: RuntimeException) { currentUnavailable.value() }
    }

    LaunchedEffect(adapter, index, dataVersion, cameraCommand?.sequence, padding, width, height, density) {
        val map = adapter ?: return@LaunchedEffect
        val command = cameraCommand ?: run {
            if (pendingCommand != null) {
                cameraRequestGeneration++
                pendingCommand = null
                runCatching(map::stop).onFailure { currentUnavailable.value() }
            }
            return@LaunchedEffect
        }
        if (width <= 0 || height <= 0 || (consumedCommand == command.sequence && pendingCommand != command.sequence)) return@LaunchedEffect
        if ((command is DiscoveryCameraCommand.Focus || command is DiscoveryCameraCommand.FitAll) &&
            index?.key != DiscoveryIndexKey(dataVersion, provider)) return@LaunchedEffect
        if (command is DiscoveryCameraCommand.Focus && command.pointId !in displayPoints) return@LaunchedEffect
        try {
            val content = padding.content(width, height)
            val requestGeneration = ++cameraRequestGeneration
            pendingClusterGeometry = null
            consumedCommand = command.sequence
            // Geometry changes restart only an unfinished command; settled/manual views stay put.
            pendingCommand = command.sequence
            val onSettled = {
                if (cameraRequestGeneration == requestGeneration && currentCommand.value?.sequence == command.sequence) {
                    pendingCommand = null
                    currentCommandApplied.value(command.sequence, map.camera())
                    cameraRevision++
                }
            }
            when (command) {
                is DiscoveryCameraCommand.Focus -> {
                    val point = displayPoints[command.pointId] ?: return@LaunchedEffect
                    map.focus(point, maxOf(15f, map.camera().zoom).coerceAtMost(map.maxZoom),
                        if (command.minimallyPan) content.inset(28f * density) else content,
                        command.minimallyPan, onSettled)
                }
                is DiscoveryCameraCommand.FitAll -> map.fit(
                    displayPoints.filterKeys { command.pointIds == null || it in command.pointIds }.values.toList(),
                    padding, width, height, onSettled,
                )
                is DiscoveryCameraCommand.Restore -> map.restore(command.camera, onSettled)
                is DiscoveryCameraCommand.ResetBearing -> map.resetBearing(onSettled)
                is DiscoveryCameraCommand.Locate -> {
                    val point = map.displayCoordinate("__user_location", command.coordinate)
                    map.focus(point, 15f.coerceAtMost(map.maxZoom), content, onSettled = onSettled)
                }
            }
        } catch (_: RuntimeException) { pendingCommand = null; currentUnavailable.value() }
    }

    LaunchedEffect(index, calculationInputs) {
        val map = adapter ?: return@LaunchedEffect
        val spatialIndex = index ?: return@LaunchedEffect
        if (spatialIndex.key != DiscoveryIndexKey(dataVersion, provider)) return@LaunchedEffect
        if (width <= 0 || height <= 0) return@LaunchedEffect
        val token = generation.next(spatialIndex.key)
        try {
            delay(300)
            if (!generation.accepts(token)) return@LaunchedEffect
            val bounds = map.bounds()
            val camera = map.camera()
            val content = padding.content(width, height)
            val pointMetadata = withContext(Dispatchers.Default) { points.filter { it.provider == provider }.associateBy { it.id } }
            val candidates = withContext(Dispatchers.Default) {
                val workerContext = coroutineContext
                spatialIndex.query(bounds) { workerContext.ensureActive() }.filter { it.id in pointMetadata }
            }
            val project = map.projector()
            val projected = ArrayList<ProjectedDiscoveryPoint>(candidates.size)
            candidates.forEachCooperatively { point ->
                if (!generation.accepts(token)) return@LaunchedEffect
                projected += ProjectedDiscoveryPoint(point.id, project(point.coordinate))
            }
            val result = withContext(Dispatchers.Default) {
                val workerContext = coroutineContext
                clusterDiscoveryPoints(projected, pointMetadata, selectedIds, focusedPointId, content,
                    (if (camera.zoom >= 18f) 28f else 48f) * density, camera.zoom, imagesEnabled, density,
                    markerBounds = { cluster, decoration ->
                        discoveryMarkerLayout(cluster.copy(decoration = decoration), fontDensity,
                            imageAvailable = decoration == DiscoveryMarkerDecoration.IMAGE).bounds
                    }) { workerContext.ensureActive() }
            }
            if (generation.accepts(token) && calculationInputs === currentCalculationInputs.value) {
                clusters = result
                clusterToken = token
                clusterInputs = calculationInputs
                currentVisible.value(result.flatMap { it.memberIds }.toSet())
            }
        } catch (error: CancellationException) { throw error }
        catch (_: RuntimeException) { currentUnavailable.value() }
    }

    LaunchedEffect(clusters, points, imagesEnabled) {
        if (!imagesEnabled) return@LaunchedEffect
        val wanted = clusters.filter { it.decoration == DiscoveryMarkerDecoration.IMAGE }
            .mapNotNull { it.imageUrl?.takeIf(::allowedDiscoveryImage) }.toSet()
        val slots = Semaphore(3)
        coroutineScope {
            wanted.forEach { url ->
                if (images.get(url) != null || url in failedImages) return@forEach
                launch {
                    slots.withPermit {
                        val result = SingletonImageLoader.get(context).execute(
                            ImageRequest.Builder(context).data(url).size(240, 180).allowHardware(false).build(),
                        )
                        if (result is SuccessResult) {
                            images.put(url, result.image.toBitmap())
                            imageRevision++
                        } else {
                            failedImages.add(url)
                            if (failedImages.size > 256) failedImages.remove(failedImages.first())
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(adapter, clusters, clusterToken, calculationInputs, imageRevision, darkTheme, points, displayPoints) {
        val map = adapter ?: return@LaunchedEffect
        val token = clusterToken
        if (clusters.isNotEmpty() && clusterInputs !== calculationInputs) return@LaunchedEffect
        fun current(): Boolean = calculationInputs === currentCalculationInputs.value &&
            (token == null || generation.accepts(token))
        if (!current()) return@LaunchedEffect
        val metadata = withContext(Dispatchers.Default) { points.associateBy { it.id } }
        val nextIds = clusters.mapTo(hashSetOf()) { it.id }
        try {
            discoveryMarkerDelta(appliedMarkers.keys, nextIds).removed.toList().forEachCooperatively { id ->
                if (!current()) return@LaunchedEffect
                map.remove(id)
                appliedMarkers.remove(id)
            }
            clusters.forEachCooperatively { cluster ->
                if (!current()) return@LaunchedEffect
                val point = metadata[cluster.anchorId] ?: return@forEachCooperatively
                val coordinate = displayPoints[cluster.anchorId] ?: return@forEachCooperatively
                val image = cluster.imageUrl?.let(images::get).takeIf { cluster.decoration == DiscoveryMarkerDecoration.IMAGE }
                val appearance = MarkerAppearance(cluster.memberIds, cluster.anchorId, coordinate, cluster.selected,
                    cluster.decoration, point.colorArgb, point.title, image, darkTheme, density, fontDensity.fontScale)
                if (appliedMarkers[cluster.id] != appearance) {
                    val artwork = discoveryMarkerArtwork(cluster, point, image, fontDensity, darkTheme)
                    val title = if (cluster.memberIds.size > 1) "${cluster.memberIds.size} \u4e2a\u5730\u70b9\uff0c${point.title}" else point.title
                    map.upsert(cluster.id, coordinate, title, artwork, cluster.selected)
                    appliedMarkers[cluster.id] = appearance
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (_: RuntimeException) { currentUnavailable.value() }
    }
}

private data class MarkerAppearance(
    val members: List<String>, val anchorId: String, val coordinate: GeoPoint, val selected: Boolean,
    val decoration: DiscoveryMarkerDecoration, val color: Int, val title: String, val image: Bitmap?, val dark: Boolean,
    val density: Float, val fontScale: Float,
)

private fun allowedDiscoveryImage(url: String): Boolean = runCatching {
    URI(url).let { it.scheme == "https" && it.host == "image.anitabi.cn" && it.userInfo == null && (it.port == -1 || it.port == 443) }
}.getOrDefault(false)

/** Bound each stretch of SDK work; no work is dropped to meet the frame budget. */
private suspend inline fun <T> List<T>.forEachCooperatively(action: (T) -> Unit) {
    var started = SystemClock.uptimeMillis()
    for (item in this) {
        coroutineContext.ensureActive()
        action(item)
        if (SystemClock.uptimeMillis() - started >= 4) {
            yield()
            started = SystemClock.uptimeMillis()
        }
    }
}
