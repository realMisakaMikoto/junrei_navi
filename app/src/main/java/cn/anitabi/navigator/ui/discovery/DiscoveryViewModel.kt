package cn.anitabi.navigator.ui.discovery

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.annotation.MainThread
import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.TerritoryRegion
import cn.anitabi.navigator.core.model.mapProvider
import cn.anitabi.navigator.data.discovery.DiscoveryPoint
import cn.anitabi.navigator.data.discovery.DiscoveryRepository
import cn.anitabi.navigator.data.discovery.DiscoverySnapshot
import cn.anitabi.navigator.data.discovery.DiscoveryState
import cn.anitabi.navigator.navigation.CurrentLocationProvider
import cn.anitabi.navigator.navigation.MissingLocationPermissionException
import cn.anitabi.navigator.ui.discovery.map.DiscoveryCameraCommand
import cn.anitabi.navigator.ui.discovery.map.DiscoveryCameraPosition
import cn.anitabi.navigator.ui.discovery.map.DiscoveryMapPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class DiscoveryUiState(
    val data: DiscoveryState = DiscoveryState(),
    val dataPreparing: Boolean = false,
    val mapPoints: List<DiscoveryMapPoint> = emptyList(),
    val mapDataVersion: String = "",
    val pointsById: Map<String, DiscoveryPoint> = emptyMap(),
    val provider: MapProvider = MapProvider.GOOGLE,
    val providerChoices: Set<MapProvider> = emptySet(),
    val unresolvedCount: Int = 0,
    val filters: Set<Long> = emptySet(),
    val viewportToken: DiscoveryViewportToken = DiscoveryViewportToken(),
    val viewportSnapshot: DiscoveryViewportSnapshot? = null,
    val panel: DiscoveryPanelState = DiscoveryPanelState(),
    val overlapIds: List<String> = emptyList(),
    val batchMode: Boolean = false,
    val listMode: Boolean = false,
    val nearby: Boolean = false,
    val location: GeoPoint? = null,
    val locationProvider: MapProvider? = null,
    val establishedLocationProvider: MapProvider? = null,
    val locating: Boolean = false,
    val cameraCommand: DiscoveryCameraCommand? = null,
    val message: String? = null,
    val groupByEpisode: Boolean = false,
) {
    val visibleIds: Set<String> get() = viewportSnapshot?.takeIf { viewportIsCurrent(it.token) }?.visibleIds.orEmpty()
    val canSelectViewport: Boolean get() = visibleIds.isNotEmpty()
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class DiscoveryViewModel(
    private val repository: DiscoveryRepository,
    private val preferences: DiscoveryCameraStore,
    private val locationProvider: CurrentLocationProvider,
    private val classifyTerritory: (GeoPoint) -> TerritoryRegion?,
    private val savedState: SavedStateHandle,
    private val freshLocation: suspend () -> GeoPoint = locationProvider::currentLocation,
    private val preparationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DiscoveryUiState(
        panel = restorePanels(savedState),
        filters = savedState.get<LongArray>("filters")?.toSet().orEmpty(),
    ))
    val state = mutableState.asStateFlow()
    private val query = MutableStateFlow("")
    private val searchIndex = MutableStateFlow<Pair<DiscoverySearchIndex?, Boolean>>(null to false)
    private val mutableSearch = MutableStateFlow(DiscoverySearchResults())
    val searchResults = mutableSearch.asStateFlow()
    private var preparedData: PreparedDiscoveryData? = null
    private var sequence = 0L
    private var userMovedCamera = false
    private var initialLocationAttempted = false
    private var lastCamera: DiscoveryCameraPosition? = null
    private var cameraIntent = 0L
    private var focusPointIds: Set<String>? = null
    private var foreground = true
    private var mapLocationActive = false
    private var locationTrackingEnabled = false
    private var locationGeneration = 0L
    private var locateJob: Job? = null
    private var locationTrackingJob: Job? = null

    init {
        val last = preferences.lastCamera().also { lastCamera = it }
        if (last != null) mutableState.update {
            it.copy(provider = last.provider, cameraCommand = restore(last)).invalidatedViewport(DiscoveryViewportInvalidation.CAMERA)
        }
        viewModelScope.launch { repository.initialize() }
        viewModelScope.launch {
            repository.state.collectLatest { data ->
                val snapshot = data.snapshot
                val previous = preparedData
                val affectsViewport = snapshot?.version != previous?.snapshot?.version ||
                    snapshot?.points !== previous?.snapshot?.points || snapshot?.subjects !== previous?.snapshot?.subjects
                if (affectsViewport) mutableState.update {
                    it.copy(dataPreparing = true).invalidatedViewport(DiscoveryViewportInvalidation.DATA)
                }
                val prepared = if (snapshot == null) null else if (previous?.snapshot === snapshot) previous else
                    withContext(preparationDispatcher) {
                        prepareDiscoveryData(snapshot, previous, classifyTerritory) { coroutineContext.ensureActive() }
                    }
                // Commit only after withContext's cancellation check; workers never mutate shared caches.
                preparedData = prepared
                mutableState.update { current -> val next = current.copy(
                    data = data, dataPreparing = false,
                    mapPoints = prepared?.mapPoints.orEmpty(), pointsById = prepared?.pointsById.orEmpty(),
                    mapDataVersion = prepared?.mapDataVersion.orEmpty(),
                    providerChoices = prepared?.providerChoices.orEmpty(),
                    unresolvedCount = prepared?.unresolvedCount ?: 0,
                )
                    if (affectsViewport || next.mapDataVersion != current.mapDataVersion)
                        next.invalidatedViewport(DiscoveryViewportInvalidation.DATA) else next
                }
                searchIndex.value = prepared?.searchIndex to (snapshot?.detailsCurrent == true)
            }
        }
        viewModelScope.launch {
            combine(query.debounce(250), searchIndex) { text, index -> text to index }
                .collectLatest { (text, search) ->
                    mutableSearch.value = withContext(Dispatchers.Default) {
                        search.first?.search(text, search.second) { coroutineContext.ensureActive() } ?: DiscoverySearchResults()
                    }
                }
        }
    }

    fun setForeground(foreground: Boolean) {
        this.foreground = foreground
        if (!foreground) {
            invalidateViewport(DiscoveryViewportInvalidation.MAP_LIFECYCLE)
            stopLocationWork()
        } else startLocationTracking()
        repository.setForeground(foreground)
    }
    fun refresh() = repository.refresh(force = true)
    fun retrySubjectDetails(subjectId: Long) { viewModelScope.launch { repository.ensureSubjectDetails(subjectId) } }
    fun updateQuery(text: String) { query.value = text }

    fun initializeLocation(hasPermission: Boolean) {
        if (!hasPermission) {
            initialLocationAttempted = true
            locationTrackingEnabled = false
            stopLocationWork()
            mutableState.update { it.copy(location = null) }
            return
        }
        if (initialLocationAttempted) return
        initialLocationAttempted = true
        locate(initial = true)
    }

    fun locate(initial: Boolean = false) {
        if (state.value.locating || !foreground) return
        locationTrackingEnabled = true
        locationTrackingJob?.cancel()
        locationTrackingJob = null
        val generation = ++locationGeneration
        if (!initial) userMovedCamera = true
        val requestedIntent = ++cameraIntent
        mutableState.update { it.copy(locating = true, message = null).invalidatedViewport(DiscoveryViewportInvalidation.CAMERA) }
        locateJob = viewModelScope.launch {
            try {
                val result = readFreshLocation()
                currentCoroutineContext().ensureActive()
                if (generation != locationGeneration || !foreground) return@launch
                if (result.permissionDenied) locationTrackingEnabled = false
                val location = result.coordinate
                val classifiedProvider = location?.let(classifyTerritory)?.mapProvider
                mutableState.update { current ->
                    val mayMove = requestedIntent == cameraIntent && (!initial || !userMovedCamera)
                    current.copy(
                        locating = false, location = location, locationProvider = classifiedProvider,
                        establishedLocationProvider = if (classifiedProvider != null && (mayMove || classifiedProvider == current.provider))
                            classifiedProvider else current.establishedLocationProvider,
                        provider = if (mayMove && classifiedProvider != null) classifiedProvider else current.provider,
                        listMode = if (!initial && mayMove && classifiedProvider != null) false else current.listMode,
                        cameraCommand = if (result.permissionDenied && current.cameraCommand is DiscoveryCameraCommand.Locate) null
                        else if (mayMove && location != null && classifiedProvider != null)
                            DiscoveryCameraCommand.Locate(++sequence, location) else current.cameraCommand,
                        message = if (!initial && location == null) "暂时无法取得位置，请检查定位权限与系统定位开关" else current.message,
                    ).invalidatedViewport(DiscoveryViewportInvalidation.CAMERA)
                }
            } finally {
                if (generation == locationGeneration) {
                    locateJob = null
                    startLocationTracking(delayFirst = true)
                }
            }
        }
    }

    /** The host supplies map-mode visibility and lifecycle, including loading/empty maps. */
    fun setMapLocationActive(active: Boolean) {
        mapLocationActive = active
        if (active) startLocationTracking() else stopLocationWork()
    }

    private fun stopLocationWork() {
        locationGeneration++
        locateJob?.cancel()
        locateJob = null
        locationTrackingJob?.cancel()
        locationTrackingJob = null
        // Keep the last local coordinate for nearby lists; it is no longer eligible for SDK display.
        mutableState.update { it.copy(
            locating = false, locationProvider = null,
            cameraCommand = it.cameraCommand.takeUnless { command -> command is DiscoveryCameraCommand.Locate },
        ) }
    }

    private fun startLocationTracking(delayFirst: Boolean = false) {
        if (!foreground || !mapLocationActive || !locationTrackingEnabled ||
            locateJob?.isActive == true || locationTrackingJob?.isActive == true) return
        val generation = locationGeneration
        locationTrackingJob = viewModelScope.launch {
            if (delayFirst) delay(LOCATION_REFRESH_MILLIS)
            while (isActive) {
                val result = readFreshLocation()
                currentCoroutineContext().ensureActive()
                if (generation != locationGeneration || !foreground || !mapLocationActive) return@launch
                if (result.permissionDenied) locationTrackingEnabled = false
                val location = result.coordinate
                mutableState.update { it.copy(
                    location = location, locationProvider = location?.let(classifyTerritory)?.mapProvider,
                    cameraCommand = if (result.permissionDenied && it.cameraCommand is DiscoveryCameraCommand.Locate)
                        null else it.cameraCommand,
                ) }
                if (result.permissionDenied) return@launch
                delay(LOCATION_REFRESH_MILLIS)
            }
        }
    }

    private suspend fun readFreshLocation(): DiscoveryLocationResult = try {
        DiscoveryLocationResult(withTimeoutOrNull(8_000) { freshLocation() })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: MissingLocationPermissionException) {
        DiscoveryLocationResult(null, permissionDenied = true)
    } catch (_: SecurityException) {
        DiscoveryLocationResult(null, permissionDenied = true)
    } catch (_: Exception) {
        DiscoveryLocationResult(null)
    }

    private data class DiscoveryLocationResult(val coordinate: GeoPoint?, val permissionDenied: Boolean = false)

    fun toggleFilter(subjectId: Long) {
        focusPointIds = null
        mutableState.update { current -> current.copy(filters =
            if (subjectId in current.filters) current.filters - subjectId else current.filters + subjectId,
        ).invalidatedViewport(DiscoveryViewportInvalidation.FILTER) }
        savedState["filters"] = state.value.filters.toLongArray()
    }

    fun clearFilters() {
        focusPointIds = null
        mutableState.update { it.copy(filters = emptySet()).invalidatedViewport(DiscoveryViewportInvalidation.FILTER) }
        savedState["filters"] = longArrayOf()
    }

    @MainThread
    fun invalidateViewport(reason: DiscoveryViewportInvalidation): DiscoveryViewportToken {
        mutableState.update { it.invalidatedViewport(reason) }
        return state.value.viewportToken
    }

    @MainThread
    fun viewportCalculated(token: DiscoveryViewportToken, ids: Set<String>): Boolean {
        val current = state.value
        if (!current.viewportIsCurrent(token) || !validViewportMembers(current, ids)) return false
        return mutableState.compareAndSet(current, current.copy(viewportSnapshot = DiscoveryViewportSnapshot(token, ids)))
    }

    /** No suspension: validation and the one selection update share this latest UI snapshot. */
    @MainThread
    fun selectViewport(addSelections: (List<Pair<Anime, PilgrimagePoint>>) -> Unit): Boolean {
        val current = state.value
        if (!current.canSelectViewport || !validViewportMembers(current, current.visibleIds)) return false
        val subjects = current.data.snapshot?.subjects?.associateBy { it.id } ?: return false
        val batch = current.visibleIds.map { id ->
            val point = current.pointsById[id] ?: return false
            val anime = subjects[point.subjectId]?.anime ?: return false
            anime to point.toPilgrimagePoint().copy(id = point.rawId)
        }
        if (state.value !== current) return false
        addSelections(java.util.Collections.unmodifiableList(batch))
        return true
    }

    private fun validViewportMembers(current: DiscoveryUiState, ids: Set<String>): Boolean = ids.all { id ->
        val point = current.pointsById[id]
        point != null && preparedData?.providersByPoint?.get(id) == current.provider &&
            (current.filters.isEmpty() || point.subjectId in current.filters)
    }

    fun setBatchMode(enabled: Boolean) { mutableState.update { it.copy(batchMode = enabled) } }
    fun setListMode(enabled: Boolean) { mutableState.update {
        it.copy(listMode = enabled, message = null).invalidatedViewport(DiscoveryViewportInvalidation.MAP_LIFECYCLE)
    } }
    fun setNearby(enabled: Boolean) { mutableState.update { it.copy(nearby = enabled && it.location != null) } }
    fun setGroupByEpisode(enabled: Boolean) { mutableState.update { it.copy(groupByEpisode = enabled) } }
    fun selectProvider(provider: MapProvider) {
        userMovedCamera = true
        cameraIntent++
        val ids = focusPointIds
        mutableState.update { it.copy(provider = provider, listMode = false,
            cameraCommand = DiscoveryCameraCommand.FitAll(++sequence, ids), message = null)
            .invalidatedViewport(DiscoveryViewportInvalidation.CAMERA) }
    }
    fun mapUnavailable() { mutableState.update {
        it.copy(listMode = true, message = "地图暂时无法加载，已切换到地点列表")
            .invalidatedViewport(DiscoveryViewportInvalidation.MAP_LIFECYCLE)
    } }
    fun cameraChanged(camera: DiscoveryCameraPosition) {
        if (camera.provider != state.value.provider) return
        if (camera != lastCamera) invalidateViewport(DiscoveryViewportInvalidation.CAMERA)
        lastCamera = camera
        preferences.saveCamera(camera)
    }
    fun cameraCommandApplied(commandSequence: Long, camera: DiscoveryCameraPosition) {
        if (state.value.cameraCommand?.sequence != commandSequence || camera.provider != state.value.provider) return
        cameraChanged(camera)
        mutableState.update { it.copy(cameraCommand = null) }
    }
    fun mapDetached() {
        invalidateViewport(DiscoveryViewportInvalidation.MAP_LIFECYCLE)
        val camera = lastCamera ?: return
        if (camera.provider != state.value.provider || state.value.cameraCommand != null) return
        mutableState.update { it.copy(cameraCommand = restore(camera)) }
    }
    fun manualMove() {
        userMovedCamera = true
        cameraIntent++
        mutableState.update { it.copy(cameraCommand = null).invalidatedViewport(DiscoveryViewportInvalidation.CAMERA) }
    }
    fun resetBearing() {
        userMovedCamera = true
        cameraIntent++
        mutableState.update { it.copy(cameraCommand = DiscoveryCameraCommand.ResetBearing(++sequence))
            .invalidatedViewport(DiscoveryViewportInvalidation.CAMERA) }
    }
    fun fitAll(pointIds: Set<String>? = null) {
        userMovedCamera = true
        cameraIntent++
        focusPointIds = pointIds
        val current = state.value
        val focus = discoveryFocus(current, pointIds)
        mutableState.update { it.copy(
            provider = focus.provider, filters = focus.filters,
            listMode = if (focus.pointIds.isEmpty()) true else it.listMode,
            cameraCommand = if (focus.pointIds.isEmpty()) null else DiscoveryCameraCommand.FitAll(++sequence, focus.pointIds),
        ).invalidatedViewport(DiscoveryViewportInvalidation.CAMERA, filtersChanged = it.filters != focus.filters) }
        savedState["filters"] = state.value.filters.toLongArray()
    }
    fun openSubject(subjectId: Long, focus: Boolean = true) {
        open(DiscoveryPanel.Subject(subjectId))
        viewModelScope.launch { repository.ensureSubjectDetails(subjectId) }
        if (focus) state.value.data.snapshot?.subjects?.find { it.id == subjectId }?.let { fitAll(it.pointIds.toSet()) }
    }
    fun openPoint(id: String, fromMap: Boolean = false) {
        val point = state.value.pointsById[id] ?: return
        open(DiscoveryPanel.Point(id))
        viewModelScope.launch { repository.ensureSubjectDetails(point.subjectId) }
        userMovedCamera = true
        cameraIntent++
        focusPointIds = setOf(id)
        val provider = preparedData?.providersByPoint?.get(id)
        mutableState.update { current -> current.copy(
            provider = provider ?: current.provider,
            filters = if (current.filters.isEmpty()) emptySet() else current.filters + point.subjectId,
            listMode = if (provider == null) true else current.listMode,
            cameraCommand = provider?.let { DiscoveryCameraCommand.Focus(++sequence, id, minimallyPan = fromMap) },
        ).invalidatedViewport(DiscoveryViewportInvalidation.CAMERA,
            filtersChanged = current.filters.isNotEmpty() && point.subjectId !in current.filters) }
        savedState["filters"] = state.value.filters.toLongArray()
    }
    fun openOverlap(ids: List<String>) {
        mutableState.update { it.copy(overlapIds = ids) }
        open(DiscoveryPanel.Overlap)
    }
    fun openCity(city: DiscoveryCity) {
        open(DiscoveryPanel.Overview)
        fitAll(city.pointIds)
    }
    fun backPanel() { mutableState.update {
        it.copy(panel = it.panel.back()).invalidatedViewport(DiscoveryViewportInvalidation.LAYOUT)
    }; savePanels() }
    fun rememberPanel(presentation: PanelPresentation) {
        rememberPanel(state.value.panel.current.key, presentation)
    }
    fun rememberPanel(key: String, presentation: PanelPresentation) {
        mutableState.update { current ->
            val next = current.copy(panel = current.panel.remember(key, presentation))
            if (key == current.panel.current.key && current.panel.presentation.detent != presentation.detent)
                next.invalidatedViewport(DiscoveryViewportInvalidation.LAYOUT) else next
        }
        savePanels()
    }
    private fun open(panel: DiscoveryPanel) { mutableState.update {
        it.copy(panel = it.panel.open(panel)).invalidatedViewport(DiscoveryViewportInvalidation.LAYOUT)
    }; savePanels() }
    private fun restore(camera: DiscoveryCameraPosition) = DiscoveryCameraCommand.Restore(++sequence, camera)
    private fun savePanels() {
        val panel = state.value.panel
        savedState["panels"] = ArrayList(panel.stack.map { it.key })
        savedState["presentationKeys"] = ArrayList(panel.presentations.keys)
        savedState["presentations"] = panel.presentations.values.flatMap { listOf(it.detent.ordinal, it.firstVisibleItem, it.scrollOffset) }.toIntArray()
    }

    private companion object {
        const val LOCATION_REFRESH_MILLIS = 15_000L
    }
}

internal data class DiscoveryFocus(val provider: MapProvider, val filters: Set<Long>, val pointIds: Set<String>)

internal fun discoveryFocus(state: DiscoveryUiState, requestedIds: Set<String>?): DiscoveryFocus {
    val candidates = state.mapPoints.filter { point ->
        if (requestedIds != null) point.id in requestedIds else state.filters.isEmpty() || point.subjectId in state.filters
    }
    val providers = candidates.mapTo(linkedSetOf()) { it.provider }
    // A single-provider result has an unambiguous destination; mixed results stay in the current group.
    val provider = providers.singleOrNull() ?: state.provider
    val filters = if (requestedIds != null && state.filters.isNotEmpty()) {
        state.filters + candidates.map { it.subjectId }
    } else state.filters
    return DiscoveryFocus(provider, filters, candidates.filter { it.provider == provider }.mapTo(linkedSetOf()) { it.id })
}

internal data class PreparedDiscoveryData(
    val snapshot: DiscoverySnapshot,
    val providersByPoint: Map<String, MapProvider?>,
    val mapPoints: List<DiscoveryMapPoint>,
    val pointsById: Map<String, DiscoveryPoint>,
    val providerChoices: Set<MapProvider>,
    val searchIndex: DiscoverySearchIndex,
    val membershipRevision: Long,
) {
    val mapDataVersion: String get() = "${snapshot.version}:$membershipRevision"
    val unresolvedCount: Int get() = pointsById.size - mapPoints.size
}

/** Immutable worker result; a cancelled generation never marks the shared cache current. */
internal fun prepareDiscoveryData(
    snapshot: DiscoverySnapshot,
    previous: PreparedDiscoveryData?,
    classifyTerritory: (GeoPoint) -> TerritoryRegion?,
    checkCancellation: () -> Unit = {},
): PreparedDiscoveryData {
    checkCancellation()
    if (previous != null && previous.snapshot.version == snapshot.version &&
        previous.snapshot.points === snapshot.points && previous.snapshot.subjects === snapshot.subjects
    ) return previous.copy(snapshot = snapshot)
    val sameGeneration = previous?.snapshot?.version == snapshot.version
    val providers = snapshot.points.associate { point ->
        checkCancellation()
        val canReuse = sameGeneration && previous!!.pointsById[point.id]?.coordinate == point.coordinate &&
            point.id in previous.providersByPoint
        point.id to if (canReuse) previous!!.providersByPoint[point.id] else classifyTerritory(point.coordinate)?.mapProvider
    }
    val subjects = snapshot.subjects.associateBy { it.id }
    val pointsById = LinkedHashMap<String, DiscoveryPoint>(snapshot.points.size)
    val mapPoints = snapshot.points.mapNotNull { point ->
        checkCancellation()
        pointsById[point.id] = point
        val provider = providers[point.id] ?: return@mapNotNull null
        val subject = subjects[point.subjectId]
        DiscoveryMapPoint(
            id = point.id, subjectId = point.subjectId, coordinate = point.coordinate,
            provider = provider, title = point.displayName,
            colorArgb = subjectColor(subject?.color, point.subjectId),
            imageUrl = point.imageUrl, subjectImageUrl = subject?.anime?.imageUrl,
        )
    }
    // Cache repair can restore members or correct coordinates within the same source generation.
    val membershipChanged = previous == null || !sameGeneration || previous.pointsById.keys != pointsById.keys ||
        pointsById.any { (id, point) -> previous.pointsById[id]?.coordinate != point.coordinate } ||
        previous.providersByPoint != providers
    val revision = (previous?.membershipRevision ?: 0) + if (membershipChanged) 1 else 0
    return PreparedDiscoveryData(
        snapshot, providers, mapPoints, pointsById, mapPoints.mapTo(linkedSetOf()) { it.provider },
        DiscoverySearchIndex(snapshot, checkCancellation), revision,
    )
}

private fun restorePanels(saved: SavedStateHandle): DiscoveryPanelState {
    val stack = saved.get<ArrayList<String>>("panels")?.mapNotNull(DiscoveryPanel::fromKey).orEmpty()
    val keys = saved.get<ArrayList<String>>("presentationKeys").orEmpty()
    val values = saved.get<IntArray>("presentations") ?: intArrayOf()
    val presentations = keys.mapIndexedNotNull { index, key ->
        val offset = index * 3
        if (offset + 2 >= values.size) null else key to PanelPresentation(
            PanelDetent.entries.getOrElse(values[offset]) { PanelDetent.COLLAPSED }, values[offset + 1], values[offset + 2],
        )
    }.toMap()
    return DiscoveryPanelState(stack.ifEmpty { listOf(DiscoveryPanel.Overview) }, presentations)
}

internal fun subjectColor(color: String?, id: Long): Int {
    val hex = color?.removePrefix("#")
    if (hex?.length == 6) hex.toLongOrNull(16)?.let { return (0xff000000 or it).toInt() }
    val palette = intArrayOf(0xffa94236.toInt(), 0xff426b62.toInt(), 0xff4d6686.toInt(), 0xff86623a.toInt(), 0xff825675.toInt())
    return palette[Math.floorMod(id, palette.size.toLong()).toInt()]
}
