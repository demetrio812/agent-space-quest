package io.agentspace.quest

import org.junit.Assert.*
import org.junit.Test

class WindowGroupsTest {
    @Test fun dashboardSelectionNeverCreatesFloatingWindowsAndCloseRestoresGrid() {
        val w = DemoWorkspace()
        assertTrue(w.sessions.size > 6)
        w.selectDashboard("api")
        w.selectDashboard("ui")
        assertEquals("ui", w.dashboardSelection)
        assertTrue(w.groups.isEmpty())
        w.showGrid()
        assertNull(w.dashboardSelection)
        assertEquals(12, w.sessions.size)
    }

    @Test fun groupingMovesOwnershipAndRemovesEmptySourceWindow() {
        val w = DemoWorkspace()
        val a = w.detach("api")!!
        val b = w.detach("ui")!!
        w.moveToGroup("api", b)
        assertNull(w.group(a))
        assertEquals(listOf("ui", "api"), w.group(b)!!.sessionIds)
        assertEquals("api", w.group(b)!!.selectedId)
        w.moveToGroup("api", b)
        assertEquals(2, w.group(b)!!.sessionIds.size)
        assertEquals(12, w.sessions.size)
    }

    @Test fun splittingAndClosingKeepOtherTabsAndDraftsIntact() {
        val w = DemoWorkspace()
        val group = w.detach("api")!!
        w.moveToGroup("ui", group)
        w.moveToGroup("search", group)
        w.setDraft("ui", "keep this draft")
        val split = w.detach("ui")!!
        assertNotEquals(group, split)
        assertEquals(listOf("api", "search"), w.group(group)!!.sessionIds)
        assertEquals(listOf("ui"), w.group(split)!!.sessionIds)
        assertEquals(split, w.detach("ui"))
        w.closeTab(group, "search")
        assertEquals("api", w.group(group)!!.selectedId)
        w.closeGroup(split)
        assertEquals("keep this draft", w.draft("ui"))
        assertNotNull(w.session("ui"))
        w.closeTab(group, "api")
        assertTrue(w.groups.isEmpty())
    }

    @Test fun groupsSelectIndependentlyAndCatalogRemainsAvailable() {
        val w = DemoWorkspace()
        val a = w.detach("api")!!
        w.moveToGroup("ui", a)
        val b = w.detach("search")!!
        w.selectTab(a, "api")
        w.selectDashboard("ui")
        assertEquals("api", w.group(a)!!.selectedId)
        assertEquals("search", w.group(b)!!.selectedId)
        assertEquals(a, w.groupFor("ui")?.id)
        w.returnToDashboard("ui")
        assertNull(w.groupFor("ui"))
        assertEquals("ui", w.dashboardSelection)
        assertEquals(listOf("api"), w.group(a)!!.sessionIds)
    }

    @Test fun invalidMovesAndSelectionsCannotLoseOrInventSessions() {
        val w = DemoWorkspace()
        val a = w.detach("api")!!
        w.moveToGroup("api", 999)
        w.moveToGroup("missing", a)
        w.selectTab(a, "search")
        assertNull(w.detach("missing"))
        assertEquals(listOf("api"), w.group(a)!!.sessionIds)
        assertEquals("api", w.group(a)!!.selectedId)
        w.closeAll()
        assertTrue(w.groups.isEmpty())
        assertEquals(12, w.sessions.size)
    }
}
