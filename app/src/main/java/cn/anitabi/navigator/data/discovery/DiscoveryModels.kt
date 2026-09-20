package cn.anitabi.navigator.data.discovery

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.PilgrimagePoint
import kotlinx.serialization.Serializable

@Serializable
data class DiscoverySubject(
    val anime: Anime,
    val city: String? = null,
    val color: String? = null,
    val pointIds: List<String> = emptyList(),
    val englishName: String? = null,
) {
    val id: Long get() = anime.subjectId
    val name: String get() = anime.nameCn ?: anime.name
}

@Serializable
data class DiscoveryPoint(
    val subjectId: Long,
    val rawId: String,
    val coordinate: GeoPoint,
    val priority: Int = 999,
    val name: String? = null,
    val nameCn: String? = null,
    val imageUrl: String? = null,
    val episode: String? = null,
    val timecodeSeconds: Double? = null,
    val groupId: String? = null,
    val groupName: String? = null,
    val description: String? = null,
    val source: String? = null,
    val sourceUrl: String? = null,
    val detailsVersion: String? = null,
) {
    val id: String get() = "$subjectId::$rawId"
    val displayName: String get() = nameCn ?: name ?: "\u672a\u547d\u540d\u5730\u70b9"
    val detailsLoaded: Boolean get() = detailsVersion != null

    fun toPilgrimagePoint(): PilgrimagePoint = PilgrimagePoint(
        id = id,
        name = displayName,
        coordinate = coordinate,
        imageUrl = imageUrl,
        origin = source,
        originUrl = sourceUrl,
    )
}

@Serializable
data class DiscoverySnapshot(
    val version: String,
    val modified: Long,
    val pageSize: Int,
    val subjects: List<DiscoverySubject>,
    val points: List<DiscoveryPoint>,
    val loadedPages: Set<Int> = emptySet(),
    val currentSubjectIds: Set<Long> = emptySet(),
    val checkedAtMillis: Long = 0,
    val endVersionVerified: Boolean = false,
) {
    val pageCount: Int get() = (subjects.size + pageSize - 1) / pageSize
    val detailsLoaded: Boolean get() = points.any(DiscoveryPoint::detailsLoaded)
    val detailsComplete: Boolean get() = points.all(DiscoveryPoint::detailsLoaded)
    val detailsCurrent: Boolean get() = endVersionVerified &&
        loadedPages.size == pageCount &&
        points.all { it.detailsVersion == version }
}

enum class DiscoveryError { NETWORK, INVALID_DATA, CACHE, VERSION_CHANGED }

data class DiscoveryState(
    val snapshot: DiscoverySnapshot? = null,
    val initialized: Boolean = false,
    val refreshing: Boolean = false,
    val paused: Boolean = false,
    val error: DiscoveryError? = null,
    val loadingSubjectIds: Set<Long> = emptySet(),
) {
    val indexAvailable: Boolean get() = snapshot != null
    val detailsComplete: Boolean get() = snapshot?.detailsComplete == true
    val detailsCurrent: Boolean get() = snapshot?.detailsCurrent == true
}

internal data class DiscoveryPointDetail(
    val rawId: String,
    val name: String? = null,
    val nameCn: String? = null,
    val imageUrl: String? = null,
    val episode: String? = null,
    val timecodeSeconds: Double? = null,
    val groupId: String? = null,
    val groupName: String? = null,
    val description: String? = null,
    val source: String? = null,
    val sourceUrl: String? = null,
    val isFolder: Boolean = false,
)

internal data class DiscoverySubjectDetails(
    val subjectId: Long,
    val points: List<DiscoveryPointDetail>,
)
