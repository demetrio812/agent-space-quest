package io.agentspace.quest

import org.junit.Assert.*
import org.junit.Test

class HomeWindowStateTest {
    @Test fun identitySurvivesUnicodeAndDelimiters() {
        val target = HomeWindowTarget("live:profile/1", "session", "workspace/a?b#héllo 中文")
        assertEquals(target, HomeWindowTarget.decode(target.encode()))
        assertNotEquals(target.encode(), target.copy(kind = "workspace").encode())
        assertNull(HomeWindowTarget.decode(target.encode())?.takeIf { it.source == "demo" })
    }
    @Test fun rejectsMalformedAndForeignTargets() {
        listOf("", "https://window/session/a/b", "agentspace://window/other/YQ/Yg", "agentspace://window/session/!/!", "agentspace://window/session/YQ/").forEach {
            assertNull(it, HomeWindowTarget.decode(it))
        }
    }
    @Test fun onlyFirstAndLastVisibleOwnerChangeTransport() {
        val owners = HomeVisibility()
        assertTrue(owners.start(1))
        assertFalse(owners.start(1))
        assertFalse(owners.start(2))
        assertFalse(owners.stop(1))
        assertTrue(owners.active)
        assertFalse(owners.stop(1))
        assertTrue(owners.stop(2))
        assertFalse(owners.active)
        assertFalse(owners.stop(2))
    }
    @Test fun staleSourceCannotRestorePrivateSessionInDemo() {
        val workspace = WorkspaceState(true)
        val before = workspace.layoutState()
        assertNull(workspace.resolveHomeTarget(HomeWindowTarget("live:old", "session", "private-session"), "demo"))
        assertEquals(before, workspace.layoutState())
        assertNull(workspace.session("private-session"))
    }
    @Test fun retainedTaskRestoresPlaceholderAndUsesStableIdentity() {
        val workspace = WorkspaceState(false)
        val old = HomeWindowTarget("live:p", "session", "old")
        val slot = workspace.resolveHomeTarget(old, "live:p")!!
        assertFalse(workspace.session("old")!!.available)
        workspace.closeGroup(slot)
        val replacement = HomeWindowTarget("live:p", "session", "new")
        assertEquals(slot, workspace.resolveHomeTarget(replacement, "live:p"))
        val restored = workspace.resolveHomeTarget(old, "live:p")!!
        assertNotEquals(slot, restored)
        assertEquals(listOf("old"), workspace.group(restored)!!.sessionIds)
        assertEquals(restored, workspace.resolveHomeTarget(old, "live:p"))
    }
    @Test fun workspaceSelectionSurvivesHomeLayoutRoundTrip() {
        val workspace = WorkspaceState(false)
        workspace.applyCatalog(listOf(
            SessionSnapshot("a", "A", "agent", "project", "idle", emptyList(), workspaceId = "ws"),
            SessionSnapshot("b", "B", "agent", "project", "idle", emptyList(), workspaceId = "ws")))
        val target = HomeWindowTarget("live:p", "workspace", "ws")
        val group = workspace.resolveHomeTarget(target, "live:p")!!
        workspace.selectTab(group, "b")
        val saved = LayoutCodec.decode(LayoutCodec.encode(SavedLayout(workspace.layoutState(), emptyMap())))!!
        val reopened = WorkspaceState(false)
        reopened.restoreLayoutState(saved.workspace)
        val restored = reopened.resolveHomeTarget(target, "live:p")!!
        assertEquals("b", reopened.group(restored)!!.selectedId)
        assertFalse(reopened.group(restored)!!.overview)
    }
    @Test fun lateOldActivityDestructionKeepsReplacementWindow() {
        val owners = HomeWindowOwners()
        val target = HomeWindowTarget("live:p", "session", "a")
        owners.attach(1, target)
        owners.attach(2, target)
        assertNull(owners.detach(1))
        assertEquals(target, owners.detach(2))
        assertNull(owners.detach(2))
    }
}
