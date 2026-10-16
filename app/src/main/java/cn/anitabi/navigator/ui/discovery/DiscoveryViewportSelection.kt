package cn.anitabi.navigator.ui.discovery

import cn.anitabi.navigator.core.model.MapProvider
import java.util.Collections

data class DiscoveryViewportToken(
    val provider: MapProvider = MapProvider.GOOGLE,
    val dataVersion: String = "",
    val filterRevision: Long = 0,
    val cameraRevision: Long = 0,
    val layoutRevision: Long = 0,
    val generation: Long = 0,
)

enum class DiscoveryViewportInvalidation { CAMERA, LAYOUT, MAP_LIFECYCLE, DATA, FILTER }

/** Copies the calculation result; a mutable worker set cannot alter a published selection. */
class DiscoveryViewportSnapshot(token: DiscoveryViewportToken, visibleIds: Set<String>) {
    val token: DiscoveryViewportToken = token
    val visibleIds: Set<String> = Collections.unmodifiableSet(LinkedHashSet(visibleIds))
}

internal fun DiscoveryUiState.viewportIsCurrent(token: DiscoveryViewportToken): Boolean =
    token == viewportToken && token.provider == provider && token.dataVersion == mapDataVersion &&
        !listMode && !locating && !dataPreparing && cameraCommand == null && data.indexAvailable

internal fun DiscoveryUiState.invalidatedViewport(
    reason: DiscoveryViewportInvalidation,
    filtersChanged: Boolean = false,
): DiscoveryUiState = copy(
    viewportToken = DiscoveryViewportToken(
        provider = provider,
        dataVersion = mapDataVersion,
        filterRevision = viewportToken.filterRevision + if (filtersChanged || reason == DiscoveryViewportInvalidation.FILTER) 1 else 0,
        cameraRevision = viewportToken.cameraRevision + if (reason == DiscoveryViewportInvalidation.CAMERA) 1 else 0,
        layoutRevision = viewportToken.layoutRevision + if (reason == DiscoveryViewportInvalidation.LAYOUT) 1 else 0,
        generation = viewportToken.generation + 1,
    ),
    viewportSnapshot = null,
)
