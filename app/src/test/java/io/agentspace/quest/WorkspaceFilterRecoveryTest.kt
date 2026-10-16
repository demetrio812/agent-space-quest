package io.agentspace.quest

import org.junit.Assert.*
import org.junit.Test

class WorkspaceFilterRecoveryTest {
    @Test fun obsoleteFilterFallsBackToAllWithoutLosingSavedSession() {
        val workspace = WorkspaceState(false)
        val old = SessionSnapshot("old", "Old", "agent", "project", "idle", emptyList(), workspaceId = "old-workspace")
        workspace.applyCatalog(listOf(old))
        workspace.selectDashboardWorkspace("old-workspace")
        workspace.detach("old")
        workspace.setDraft("old", "saved draft")
        workspace.applyCatalog(listOf(old.copy(id = "new", workspaceId = "new-workspace")))
        assertNull(workspace.dashboardWorkspace)
        assertTrue(workspace.dashboardSessions.any { it.id == "new" })
        assertFalse(workspace.session("old")!!.available)
        assertEquals("saved draft", workspace.draft("old"))
        assertNotNull(workspace.groupFor("old"))
    }
    @Test fun currentFilterSurvivesCatalogRefresh() {
        val workspace = WorkspaceState(false)
        val item = SessionSnapshot("one", "One", "agent", "project", "idle", emptyList(), workspaceId = "current")
        workspace.applyCatalog(listOf(item))
        workspace.selectDashboardWorkspace("current")
        workspace.applyCatalog(listOf(item.copy(title = "Renamed")))
        assertEquals("current", workspace.dashboardWorkspace)
        assertEquals("Renamed", workspace.dashboardSessions.single().title)
    }
}
