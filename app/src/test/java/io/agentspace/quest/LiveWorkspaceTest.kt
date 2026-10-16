package io.agentspace.quest

import org.junit.Assert.*
import org.junit.Test

class LiveWorkspaceTest {
    private fun session(id: String, title: String = id) = SessionSnapshot(id, title, "codex", "project", "unknown", emptyList())

    @Test fun liveRestoreBeforeDiscoveryKeepsGroupsDraftsAndSelection() {
        val workspace = WorkspaceState(demo = false)
        val saved = WorkspaceLayoutState("remote-1", listOf(SessionGroup(7, listOf("remote-1"), "remote-1")), false, mapOf("remote-1" to "draft"))
        workspace.restoreLayoutState(saved)
        assertEquals(saved, workspace.layoutState())
        assertNotNull(workspace.session("remote-1"))
        workspace.applyCatalog(listOf(session("remote-1", "Renamed")))
        assertEquals("Renamed", workspace.session("remote-1")!!.title)
        assertEquals("draft", workspace.draft("remote-1"))
        assertEquals(7, workspace.groupFor("remote-1")!!.id)
    }

    @Test fun absentRemoteSessionRemainsPlaceholderOnlyWhenReferenced() {
        val workspace = WorkspaceState(demo = false)
        workspace.applyCatalog(listOf(session("one"), session("two")))
        workspace.detach("one")
        workspace.applyCatalog(emptyList())
        assertFalse(workspace.session("one")!!.available)
        assertNull(workspace.session("two"))
        assertEquals(listOf("one"), workspace.group(0)!!.sessionIds)
    }

    @Test fun sourceSwitchCannotLeakDemoGroupsOrSendSyntheticReplyToLiveSession() {
        val workspace = WorkspaceState()
        workspace.detach("api")
        workspace.setSource(false)
        assertTrue(workspace.groups.isEmpty())
        workspace.applyCatalog(listOf(session("real")))
        workspace.setDraft("real", "keep")
        workspace.submit("real", "keep")
        workspace.tick()
        assertTrue(workspace.session("real")!!.lines.isEmpty())
        assertEquals("keep", workspace.draft("real"))
    }

    @Test fun catalogCanGrowAfterStartAndAllocateWindowSlot() {
        val workspace = WorkspaceState(demo = false)
        workspace.applyCatalog((1..20).map { session("remote-$it") })
        repeat(20) { assertNotNull(workspace.detach("remote-${it+1}")) }
        assertEquals(20, workspace.groups.size)
    }
}
