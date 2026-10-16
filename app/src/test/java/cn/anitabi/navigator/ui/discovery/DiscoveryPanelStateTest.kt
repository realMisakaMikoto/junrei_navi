package cn.anitabi.navigator.ui.discovery

import org.junit.Assert.*
import org.junit.Test

class DiscoveryPanelStateTest {
    @Test fun returnsToEachContentsOwnDetentAndScroll() {
        val overview = PanelPresentation(PanelDetent.EXPANDED, 12, 33)
        val subject = PanelPresentation(PanelDetent.HALF, 4, 17)
        val state = DiscoveryPanelState().remember(overview)
            .open(DiscoveryPanel.Subject(42)).remember(subject)
            .open(DiscoveryPanel.Point("42::point"))
            .remember(PanelPresentation(PanelDetent.EXPANDED, 2, 0))
        assertEquals(DiscoveryPanel.Point("42::point"), state.current)
        assertEquals(subject, state.back().presentation)
        assertEquals(overview, state.back().back().presentation)
        assertFalse(state.back().back().canGoBack)
    }

    @Test fun reopeningAncestorPopsInsteadOfStackingDuplicateSheets() {
        val state = DiscoveryPanelState().open(DiscoveryPanel.Subject(42))
            .open(DiscoveryPanel.Point("42::point")).open(DiscoveryPanel.Subject(42))
        assertEquals(listOf(DiscoveryPanel.Overview, DiscoveryPanel.Subject(42)), state.stack)
        assertEquals(state, state.open(DiscoveryPanel.Subject(42)))
    }

    @Test fun restoredKeysRetainCompositePointIdentity() {
        val point = DiscoveryPanel.Point("42::a:b")
        assertEquals(point, DiscoveryPanel.fromKey(point.key))
        assertNull(DiscoveryPanel.fromKey("subject:bad"))
    }
}
