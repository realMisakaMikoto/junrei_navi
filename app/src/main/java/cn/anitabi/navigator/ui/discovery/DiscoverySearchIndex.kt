package cn.anitabi.navigator.ui.discovery

import cn.anitabi.navigator.data.discovery.DiscoveryPoint
import cn.anitabi.navigator.data.discovery.DiscoverySnapshot
import cn.anitabi.navigator.data.discovery.DiscoverySubject
import cn.anitabi.navigator.data.repository.PilgrimageData
import java.util.Locale

data class DiscoveryCity(val name: String, val pointIds: Set<String>)
data class DiscoverySearchResults(
    val subjects: List<DiscoverySubject> = emptyList(),
    val points: List<DiscoveryPoint> = emptyList(),
    val cities: List<DiscoveryCity> = emptyList(),
    val complete: Boolean = false,
) {
    val isEmpty: Boolean get() = subjects.isEmpty() && points.isEmpty() && cities.isEmpty()
}

/** Built for each published detail snapshot, never for a camera movement. */
class DiscoverySearchIndex(snapshot: DiscoverySnapshot, checkCancellation: () -> Unit = {}) {
    private val subjects = snapshot.subjects.map { subject ->
        checkCancellation()
        subject to listOfNotNull(subject.name, subject.anime.name, subject.englishName).joinToString(" ").normalized()
    }
    private val points = snapshot.points.filter(DiscoveryPoint::detailsLoaded).map { point ->
        checkCancellation()
        point to listOfNotNull(point.name, point.nameCn).joinToString(" ").normalized()
    }
    private val cities = snapshot.subjects.filter { !it.city.isNullOrBlank() }.groupBy { it.city!! }
        .map { (city, subjects) ->
            checkCancellation()
            DiscoveryCity(city, subjects.flatMap { it.pointIds }.toSet()) to city.normalized()
        }
    private val complete = snapshot.detailsCurrent

    fun search(query: String, complete: Boolean = this.complete, checkCancellation: () -> Unit = {}): DiscoverySearchResults {
        val normalized = query.trim().normalized()
        if (normalized.isBlank()) return DiscoverySearchResults(complete = complete)
        return DiscoverySearchResults(
            subjects = subjects.filter { checkCancellation(); normalized in it.second }.map { it.first },
            points = points.filter { checkCancellation(); normalized in it.second }.map { it.first },
            cities = cities.filter { checkCancellation(); normalized in it.second }.map { it.first },
            complete = complete,
        )
    }
}

private fun String.normalized(): String = lowercase(Locale.ROOT)

/** Bangumi selection can reuse known index coordinates when its detail API is metadata-only. */
internal fun DiscoverySnapshot.subjectSelection(subjectId: Long): PilgrimageData? {
    val subject = subjects.find { it.id == subjectId } ?: return null
    return PilgrimageData(
        anime = subject.anime,
        points = points.filter { it.subjectId == subjectId }.map { it.toPilgrimagePoint().copy(id = it.rawId) },
        expectedPointCount = subject.pointIds.size,
    )
}
