package cn.anitabi.navigator.data.repository

import cn.anitabi.navigator.data.discovery.DiscoveryPoint
import cn.anitabi.navigator.data.discovery.DiscoveryRepository
import cn.anitabi.navigator.data.discovery.DISCOVERY_IMAGE_METADATA_VERSION
import cn.anitabi.navigator.data.images.AnitabiImageReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Supplements display metadata only; a current source NoImage never triggers forced requests. */
internal fun observePlannerDraftImages(
    drafts: PlannerDraftRepository,
    discovery: DiscoveryRepository,
    scope: CoroutineScope,
): Job = scope.launch {
    var round: Pair<String, String>? = null
    val attempted = mutableMapOf<Long, Set<String>>()
    var indexedPoints: List<DiscoveryPoint>? = null
    var byId = emptyMap<String, DiscoveryPoint>()
    combine(drafts.state, discovery.state) { draft, data -> draft to data }.collect { (stored, data) ->
        val draft = stored.draft
        val snapshot = data.snapshot
        if (!stored.initialized || draft == null || snapshot == null) return@collect
        val currentRound = draft.draftId to snapshot.version
        if (round != currentRound) { round = currentRound; attempted.clear() }
        val missing = draft.selectedPoints.filter { it.imageUrl == null }
        if (missing.isEmpty()) return@collect
        if (indexedPoints !== snapshot.points) {
            indexedPoints = snapshot.points
            byId = snapshot.points.associateBy(DiscoveryPoint::id)
        }
        val subjects = linkedMapOf<Long, MutableSet<String>>()
        val images = buildMap {
            missing.forEach { point ->
                val scopedId = if ("::" in point.id) point.id else
                    draft.selectedAnimes.singleOrNull()?.let { "${it.subjectId}::${point.id}" } ?: return@forEach
                val source = byId[scopedId] ?: return@forEach
                val image = AnitabiImageReference.normalize(source.imageUrl)
                if (image != null) put(point.id, image)
                else if (source.imageMetadataVersion < DISCOVERY_IMAGE_METADATA_VERSION) {
                    subjects.getOrPut(source.subjectId) { linkedSetOf() }.add(scopedId)
                }
            }
        }
        drafts.supplementPointImages(draft.draftId, images)
        attempted.keys.retainAll(subjects.keys)
        if (data.paused) return@collect
        for ((subject, targets) in subjects) {
            if (attempted[subject] == targets) continue
            if (drafts.state.value.draft?.draftId != draft.draftId ||
                discovery.state.value.snapshot?.version != snapshot.version || discovery.state.value.paused) break
            attempted[subject] = targets.toSet()
            try {
                discovery.ensureSubjectDetails(subject)
                if (discovery.state.value.paused) { attempted.remove(subject); break }
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                // A background pause cancels the source request, not this app-scoped observer.
                attempted.remove(subject)
                break
            }
        }
    }
}
