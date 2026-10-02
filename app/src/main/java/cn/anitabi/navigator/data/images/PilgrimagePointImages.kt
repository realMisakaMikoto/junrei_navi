package cn.anitabi.navigator.data.images

import cn.anitabi.navigator.core.model.PilgrimagePoint

/** Public image metadata may fill an absent reference without replacing a user-owned point. */
fun PilgrimagePoint.withMissingAnitabiImage(reference: String?): PilgrimagePoint {
    if (imageUrl != null) return this
    val normalized = AnitabiImageReference.normalize(reference) ?: return this
    return copy(imageUrl = normalized)
}

internal fun List<PilgrimagePoint>.samePlanningPoints(other: List<PilgrimagePoint>): Boolean =
    size == other.size && indices.all { index ->
        this[index] == other[index] || this[index].copy(imageUrl = null) == other[index].copy(imageUrl = null)
    }
