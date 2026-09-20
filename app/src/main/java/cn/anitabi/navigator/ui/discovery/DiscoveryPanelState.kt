package cn.anitabi.navigator.ui.discovery

enum class PanelDetent { COLLAPSED, HALF, EXPANDED }

data class PanelPresentation(
    val detent: PanelDetent = PanelDetent.COLLAPSED,
    val firstVisibleItem: Int = 0,
    val scrollOffset: Int = 0,
)

sealed class DiscoveryPanel(val key: String) {
    data object Overview : DiscoveryPanel("overview")
    data class Subject(val subjectId: Long) : DiscoveryPanel("subject:$subjectId")
    data class Point(val pointId: String) : DiscoveryPanel("point:$pointId")
    data object Overlap : DiscoveryPanel("overlap")

    companion object {
        fun fromKey(key: String): DiscoveryPanel? = when {
            key == "overview" -> Overview
            key == "overlap" -> Overlap
            key.startsWith("subject:") -> key.removePrefix("subject:").toLongOrNull()?.let(::Subject)
            key.startsWith("point:") -> key.removePrefix("point:").takeIf(String::isNotBlank)?.let(::Point)
            else -> null
        }
    }
}

/** A single active panel with a return stack and per-content presentation. */
data class DiscoveryPanelState(
    val stack: List<DiscoveryPanel> = listOf(DiscoveryPanel.Overview),
    val presentations: Map<String, PanelPresentation> = emptyMap(),
) {
    val current: DiscoveryPanel get() = stack.last()
    val presentation: PanelPresentation get() = presentations[current.key] ?: PanelPresentation(
        detent = if (current == DiscoveryPanel.Overview) PanelDetent.COLLAPSED else PanelDetent.HALF,
    )
    val canGoBack: Boolean get() = stack.size > 1

    fun open(panel: DiscoveryPanel): DiscoveryPanelState = when {
        panel == current -> this
        panel == DiscoveryPanel.Overview -> copy(stack = listOf(panel))
        panel in stack -> copy(stack = stack.take(stack.indexOf(panel) + 1))
        else -> copy(stack = stack + panel)
    }

    fun back(): DiscoveryPanelState = if (canGoBack) copy(stack = stack.dropLast(1)) else this

    fun remember(presentation: PanelPresentation): DiscoveryPanelState = remember(current.key, presentation)

    // A departing composable can publish its final scroll after the next panel opens.
    fun remember(key: String, presentation: PanelPresentation): DiscoveryPanelState = copy(
        presentations = presentations + (key to presentation.copy(
            firstVisibleItem = presentation.firstVisibleItem.coerceAtLeast(0),
            scrollOffset = presentation.scrollOffset.coerceAtLeast(0),
        )),
    )
}
