package io.agentspace.quest

import org.junit.Assert.*
import org.junit.Test

class WorkspaceWindowTest {
    private fun session(id: String, workspace: String = "w1") = SessionSnapshot(id, id, "Herdr", "repo", "idle", emptyList(), workspaceId = workspace, workspaceLabel = "Workspace $workspace")

    @Test fun workspaceWindowFollowsCatalogAndDoesNotTakeIndividualOwnership() {
        val state = WorkspaceState(false)
        state.applyCatalog(listOf(session("a"), session("b"), session("c", "w2")))
        val individual = state.detach("a")!!
        val window = state.openWorkspace("w1")!!
        assertEquals(window, state.openWorkspace("w1"))
        assertEquals(listOf("a", "b"), state.group(window)!!.sessionIds)
        assertEquals(individual, state.groupFor("a")!!.id)
        state.selectTab(window, "b")
        assertFalse(state.group(window)!!.overview)
        state.applyCatalog(listOf(session("a"), session("d")))
        assertEquals(listOf("a", "d"), state.group(window)!!.sessionIds)
        assertTrue(state.group(window)!!.overview)
        state.applyCatalog(emptyList())
        assertNotNull(state.group(window))
        assertTrue(state.group(window)!!.sessionIds.isEmpty())
        assertNotNull(state.group(individual))
        state.applyCatalog(listOf(session("e")))
        assertEquals(listOf("e"), state.group(window)!!.sessionIds)
    }

    @Test fun workspaceViewSurvivesDetachAndRejectsMovingAnotherWorkspacesSessionIntoIt() {
        val state = WorkspaceState(false)
        state.applyCatalog(listOf(session("a"), session("b"), session("c", "w2")))
        val window = state.openWorkspace("w1")!!
        val detached = state.detach("a")!!
        assertNotEquals(window, detached)
        assertEquals(listOf("a", "b"), state.group(window)!!.sessionIds)
        state.moveToGroup("c", window)
        assertEquals(listOf("a", "b"), state.group(window)!!.sessionIds)
        state.closeTab(window, "a")
        assertEquals(listOf("a", "b"), state.group(window)!!.sessionIds)
        state.closeGroup(window)
        assertNotNull(state.group(detached))
    }

    @Test fun emptyWorkspaceAndItsNameSurviveColdRestartThenReceiveNewSessions() {
        val state = WorkspaceState(false)
        state.applyCatalog(listOf(session("a")))
        val window = state.openWorkspace("w1")!!
        state.applyCatalog(emptyList())
        val saved = SavedLayout(state.layoutState(), emptyMap())
        val decoded = LayoutCodec.decode(LayoutCodec.encode(saved))!!
        assertEquals(saved, decoded)
        val reopened = WorkspaceState(false)
        reopened.restoreLayoutState(decoded.workspace)
        assertEquals("Workspace w1", reopened.group(window)!!.workspaceLabel)
        reopened.applyCatalog(listOf(session("new")))
        assertEquals(listOf("new"), reopened.group(window)!!.sessionIds)
    }

    @Test fun restoringOverlappingCatalogAndIndividualViewsPreservesSelectionAndOwnership() {
        val state = WorkspaceState(false)
        state.applyCatalog(listOf(session("a"), session("b")))
        val window = state.openWorkspace("w1")!!
        val individual = state.detach("a")!!
        state.selectTab(window, "b")
        val saved = LayoutCodec.decode(LayoutCodec.encode(SavedLayout(state.layoutState(), emptyMap())))!!
        val reopened = WorkspaceState(false)
        reopened.restoreLayoutState(saved.workspace)
        reopened.applyCatalog(listOf(session("a"), session("b")))
        assertEquals(individual, reopened.groupFor("a")!!.id)
        assertEquals(listOf("a", "b"), reopened.group(window)!!.sessionIds)
        assertEquals("b", reopened.group(window)!!.selectedId)
        assertFalse(reopened.group(window)!!.overview)
    }

    @Test fun sidebarAndPickerUseTheSameResponsiveBreakpoint() {
        assertTrue(dashboardShowsWorkspaceRail(980f, 600f))
        assertFalse(dashboardShowsWorkspaceRail(600f, 600f))
        assertFalse(dashboardShowsWorkspaceRail(1100f, 158f))
    }
}
