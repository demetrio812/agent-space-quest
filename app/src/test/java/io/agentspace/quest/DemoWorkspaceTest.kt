package io.agentspace.quest

import org.junit.Assert.*
import org.junit.Test

class DemoWorkspaceTest {
    @Test fun sixIndependentSessionsOpenWithoutDuplication() {
        val workspace = DemoWorkspace()
        assertEquals(12, workspace.sessions.size)
        val first = workspace.sessions.first().id
        val second = workspace.sessions[1].id
        workspace.detach(first)
        workspace.detach(second)
        workspace.detach(first)
        assertEquals(setOf(first, second), workspace.groups.flatMap { it.sessionIds }.toSet())
        workspace.closeGroup(workspace.groupFor(first)!!.id)
        assertEquals(setOf(second), workspace.groups.flatMap { it.sessionIds }.toSet())
        assertNotNull(workspace.session(first))
    }

    @Test fun submissionIsLocalAndDraftsSurviveClosing() {
        val workspace = DemoWorkspace()
        val id = workspace.sessions.first().id
        workspace.setDraft(id, "review the parser")
        workspace.detach(id)
        workspace.closeGroup(workspace.groupFor(id)!!.id)
        workspace.detach(id)
        assertEquals("review the parser", workspace.draft(id))
        val oldRevision = workspace.session(id)!!.revision
        workspace.submit(id, workspace.draft(id))
        assertEquals("", workspace.draft(id))
        assertTrue(workspace.session(id)!!.revision > oldRevision)
        assertTrue(workspace.session(id)!!.lines.any { it.contains("review the parser") })
    }

    @Test fun pauseStopsTicksAndHistoryRemainsBounded() {
        val workspace = DemoWorkspace()
        val before = workspace.sessions.map { it.revision }
        workspace.paused = true
        repeat(100) { workspace.tick() }
        assertEquals(before, workspace.sessions.map { it.revision })
        workspace.paused = false
        repeat(500) { workspace.tick() }
        assertTrue(workspace.sessions.all { it.lines.size <= 80 })
        assertTrue(workspace.sessions.any { it.revision > 0 })
    }
}
