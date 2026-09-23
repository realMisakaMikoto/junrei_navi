package cn.anitabi.navigator.core.model

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.Serializable

/** User inputs only. Route responses, navigation progress and public catalogs stay elsewhere. */
@Serializable
data class PlannerDraft(
    val draftId: String,
    val selectedAnimes: List<Anime>,
    val displayAnime: Anime,
    val selectedPoints: List<PilgrimagePoint>,
    val manualOrderPointIds: List<String> = selectedPoints.map(PilgrimagePoint::id),
    val manualOrderRequested: Boolean = false,
    val mode: TravelMode = TravelMode.WALK,
    val objective: RouteObjective = RouteObjective.FASTEST,
    val endPolicy: EndPolicy = EndPolicy.OPEN,
    val startPointId: String? = selectedPoints.firstOrNull()?.id,
    val useCurrentLocation: Boolean = false,
    /** Fixed user-owned start of a saved tour; a live-location rule does not capture a new fix. */
    val savedStart: GeoPoint? = null,
    val fixedEndPointId: String? = selectedPoints.lastOrNull()?.id,
    val dwellMinutesInput: String = "15",
    val transitTimeMode: TransitTimeMode = TransitTimeMode.NOW,
    val transitDate: String,
    val transitTime: String,
    val transitZoneId: String,
    val transitRoutingPreference: TransitRoutingPreference = TransitRoutingPreference.RECOMMENDED,
    val transitTravelModes: Set<TransitTravelMode> = emptySet(),
    val sourceTourId: String? = null,
) {
    fun withValidEndpointOrder(): PlannerDraft {
        val start = startPointId?.takeUnless { useCurrentLocation }
        val end = fixedEndPointId?.takeIf { endPolicy == EndPolicy.FIXED && it != start }
        val middle = manualOrderPointIds.filterNot { it == start || it == end }
        return copy(manualOrderPointIds = listOfNotNull(start) + middle + listOfNotNull(end))
    }

    fun validate() {
        require(draftId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        require(selectedAnimes.isNotEmpty())
        require(selectedAnimes.map(Anime::subjectId).distinct().size == selectedAnimes.size)
        val ids = selectedPoints.mapTo(hashSetOf(), PilgrimagePoint::id)
        require(ids.size == selectedPoints.size && ids.none(String::isBlank))
        val subjects = selectedAnimes.mapTo(hashSetOf(), Anime::subjectId)
        require(ids.all { id ->
            if ("::" in id) id.substringBefore("::").toLongOrNull() in subjects
            else selectedAnimes.size == 1
        })
        require(manualOrderPointIds.size == ids.size && manualOrderPointIds.toSet() == ids)
        require(startPointId == null || startPointId in ids)
        require(fixedEndPointId == null || fixedEndPointId in ids)
        require(!useCurrentLocation || startPointId == null)
        require(dwellMinutesInput.length <= 3 && dwellMinutesInput.all(Char::isDigit))
        LocalDate.parse(transitDate)
        LocalTime.parse(transitTime)
        ZoneId.of(transitZoneId)
    }
}
