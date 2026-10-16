package io.agentspace.quest

import org.junit.Assert.*
import org.junit.Test

class FavoritesTest {
    private fun session(id: String) = SessionSnapshot(id, id, "agent", "project", "idle", emptyList(), workspaceId = "workspace")
    @Test fun groupTracksStarsWithoutTakingSessionOwnership() {
        val w = WorkspaceState(false)
        w.applyCatalog(listOf(session("a"), session("b")))
        w.toggleFavorite("a")
        val group = w.openWorkspace(FAVORITES_WORKSPACE)!!
        val individual = w.detach("a")!!
        assertNotEquals(group, individual)
        assertEquals(listOf("a"), w.group(group)!!.sessionIds)
        w.toggleFavorite("b")
        assertEquals(listOf("a", "b"), w.group(group)!!.sessionIds)
        w.selectTab(group, "a")
        w.toggleFavorite("a")
        assertEquals("b", w.group(group)!!.selectedId)
        assertTrue(w.group(group)!!.overview)
        assertNotNull(w.group(individual))
        w.toggleFavorite("b")
        assertTrue(w.group(group)!!.sessionIds.isEmpty())
    }
    @Test fun removingSelectedFavoriteReturnsFavoriteDashboardToGrid() {
        val w = WorkspaceState(false)
        w.applyCatalog(listOf(session("a")))
        w.toggleFavorite("a")
        w.selectDashboardWorkspace(FAVORITES_WORKSPACE)
        w.selectDashboard("a")
        assertEquals(FAVORITES_WORKSPACE, w.dashboardWorkspace)
        w.toggleFavorite("a")
        assertNull(w.dashboardSelection)
        assertTrue(w.dashboardSessions.isEmpty())
    }
    @Test fun refreshKeepsMissingFavoriteAndFavoriteFilter() {
        val w = WorkspaceState(false)
        w.applyCatalog(listOf(session("a"), session("b")))
        w.toggleFavorite("a")
        w.selectDashboardWorkspace(FAVORITES_WORKSPACE)
        w.applyCatalog(listOf(session("b")))
        assertEquals(FAVORITES_WORKSPACE, w.dashboardWorkspace)
        assertEquals(listOf("a"), w.dashboardSessions.map { it.id })
        assertFalse(w.session("a")!!.available)
    }
    @Test fun restoreUsesCurrentFavoritesInsteadOfOldLayoutMembership() {
        val w = WorkspaceState(false)
        w.applyCatalog(listOf(session("a"), session("b")))
        w.toggleFavorite("a")
        val group = w.openWorkspace(FAVORITES_WORKSPACE)!!
        val saved = LayoutCodec.decode(LayoutCodec.encode(SavedLayout(w.layoutState(), emptyMap())))!!
        val restored = WorkspaceState(false)
        restored.setFavorites(setOf("b"))
        restored.restoreLayoutState(saved.workspace)
        restored.applyCatalog(listOf(session("a"), session("b")))
        assertEquals(listOf("b"), restored.group(group)!!.sessionIds)
    }
    @Test fun persistenceCallbackAndSourcesStaySeparate() {
        val w = WorkspaceState(false)
        var stored = emptySet<String>()
        w.onFavoritesChanged = { stored = it }
        w.applyCatalog(listOf(session("a")))
        w.toggleFavorite("a")
        assertEquals(setOf("a"), stored)
        w.setSource(true)
        assertTrue(w.favoriteIds.isEmpty())
        assertEquals(setOf("a"), stored)
        assertNotEquals(favoriteStorageKey("demo"), favoriteStorageKey("live:p"))
        assertNotEquals(favoriteStorageKey("live:p"), favoriteStorageKey("live:q"))
    }
    @Test fun homeFavoriteTargetReusesWindowIncludingWhenEmpty() {
        val w = WorkspaceState(false)
        val target = HomeWindowTarget("live:p", "workspace", FAVORITES_WORKSPACE)
        val id = w.resolveHomeTarget(target, "live:p")!!
        assertEquals(id, w.resolveHomeTarget(target, "live:p"))
        assertEquals("Favourites", w.group(id)!!.workspaceLabel)
        assertTrue(w.group(id)!!.sessionIds.isEmpty())
    }
    @Test fun doneAndQuestionsHaveDistinctStylesWhileIdleStaysNeutral() {
        assertEquals(TileStatus.DONE, tileStatus(" DONE "))
        assertEquals(TileStatus.QUESTION, tileStatus("question"))
        assertEquals(TileStatus.QUESTION, tileStatus("needs_input"))
        assertEquals(TileStatus.IDLE, tileStatus("idle"))
        assertEquals(TileStatus.UNKNOWN, tileStatus("unknown"))
        assertEquals(TileStatus.UNKNOWN, tileStatus("done", available = false))
        assertEquals(TileStatus.WORKING, tileStatus("running"))
    }
    @Test fun offlineReopenShowsPersistedFavoritesBeforeCatalogArrives() {
        val w = WorkspaceState(false)
        w.setFavorites(setOf("a"))
        assertEquals(listOf("a"), w.favoriteSessions.map { it.id })
        assertFalse(w.favoriteSessions.single().available)
        w.toggleFavorite("a")
        assertTrue(w.favoriteSessions.isEmpty())
    }
    @Test fun oldModeSelectionCannotRestoreAnUnstarredFavorite() {
        val w = WorkspaceState(false)
        w.applyCatalog(listOf(session("a")))
        w.toggleFavorite("a")
        w.selectDashboardWorkspace(FAVORITES_WORKSPACE)
        w.selectDashboard("a")
        val saved = w.layoutState()
        val restored = WorkspaceState(false)
        restored.setFavorites(emptySet())
        restored.restoreLayoutState(saved)
        assertEquals(FAVORITES_WORKSPACE, restored.dashboardWorkspace)
        assertNull(restored.dashboardSelection)
        assertTrue(restored.dashboardSessions.isEmpty())
    }
}
