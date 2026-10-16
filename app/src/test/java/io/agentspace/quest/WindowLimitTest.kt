package io.agentspace.quest

import org.junit.Assert.*
import org.junit.Test

class WindowLimitTest {
    @Test fun fullPoolRejectsExtraWindowWithoutLosingSessionsAndReusesClosedSlot() {
        val workspace = WorkspaceState(false)
        workspace.applyCatalog((0..40).map { n -> SessionSnapshot("session-$n", "Session $n", "agent", "project", "working", emptyList()) })
        repeat(WorkspaceState.MAX_WINDOWS) { assertEquals(it, workspace.detach("session-$it")) }
        val groups = workspace.groups
        assertNull(workspace.detach("session-32"))
        assertEquals(groups, workspace.groups)
        assertEquals(41, workspace.sessions.size)
        assertTrue(workspace.windowNotice!!.contains("32"))
        workspace.closeGroup(7)
        assertEquals(7, workspace.detach("session-32"))
        assertNull(workspace.windowNotice)
        assertEquals(WorkspaceState.MAX_WINDOWS, workspace.groups.size)
    }
}
