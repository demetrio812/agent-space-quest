package io.agentspace.quest

import org.junit.Assert.*
import org.junit.Test

class DashboardLayoutTest {
    @Test fun nativeResizeSavesBothAxesAndReopensWithoutApplyingScaleTwice() {
        val resized = DashboardSize().scaled(1.5f, 1f / 3f)
        assertEquals(2.1f, resized.width, .001f)
        assertEquals(.32f, resized.height, .001f)
        val saved = SavedLayout(WorkspaceState().layoutState(), emptyMap(), dashboardSize = resized)
        val restored = LayoutCodec.decode(LayoutCodec.encode(saved))!!.dashboardSize.scaled(1f, 1f)
        assertEquals(resized, restored)
        assertTrue(dashboardUsesStrip(restored.height * 720f - 82f))
    }

    @Test fun workspaceSelectionFiltersByIdentityAndDoesNotRemoveFloatingSessions() {
        val state = WorkspaceState(false)
        state.applyCatalog(listOf(
            SessionSnapshot("a", "A", "codex", "same repo", "idle", emptyList(), workspaceId = "w1", workspaceLabel = "First"),
            SessionSnapshot("b", "B", "claude", "same repo", "idle", emptyList(), workspaceId = "w2", workspaceLabel = "Second"),
        ))
        val floating = state.detach("a")!!
        state.selectDashboard("a")
        state.selectDashboardWorkspace("w2")
        assertNull(state.dashboardSelection)
        assertEquals(listOf("b"), state.dashboardSessions.map { it.id })
        assertEquals(listOf("a"), state.group(floating)!!.sessionIds)
        val saved = SavedLayout(state.layoutState(), emptyMap())
        assertEquals("w2", LayoutCodec.decode(LayoutCodec.encode(saved))!!.workspace.dashboardWorkspace)
        state.returnToDashboard("a")
        assertEquals("w1", state.dashboardWorkspace)
        assertEquals("a", state.dashboardSelection)
        state.selectDashboardWorkspace(null)
        assertEquals(2, state.dashboardSessions.size)
    }

    @Test fun aShallowDashboardUsesOneHorizontalRowWithoutShrinkingItsText() {
        assertTrue(dashboardUsesStrip(140f))
        assertTrue(dashboardUsesStrip(230f))
        assertFalse(dashboardUsesStrip(400f))
    }

    @Test fun dashboardDimensionsSurviveColdLayoutRoundTripAndLegacyFilesKeepDefaults() {
        val base = SavedLayout(WorkspaceState().layoutState(), emptyMap())
        val wide = base.copy(dashboardSize = DashboardSize(2.3f, .32f))
        assertEquals(wide, LayoutCodec.decode(LayoutCodec.encode(wide)))
        val old = LayoutCodec.encode(base).lineSequence().filterNot { it.startsWith("dashboard.width") || it.startsWith("dashboard.height") }.joinToString("\n")
        assertEquals(DashboardSize(), LayoutCodec.decode(old)!!.dashboardSize)
    }

    @Test fun invalidSavedDimensionsFallBackWithoutDiscardingTabsOrLayout() {
        val state = WorkspaceState().apply { selectDashboard("api") }
        val base = SavedLayout(state.layoutState(), emptyMap())
        val encoded = LayoutCodec.encode(base) + "\ndashboard.width=NaN\ndashboard.height=-1\n"
        val decoded = LayoutCodec.decode(encoded)!!
        assertEquals("api", decoded.workspace.dashboardSelection)
        assertEquals(DashboardSize(), decoded.dashboardSize)
    }
}
