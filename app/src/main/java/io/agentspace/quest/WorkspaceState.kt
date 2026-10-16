package io.agentspace.quest

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

const val FAVORITES_WORKSPACE = "__agentspace_favourites__"

data class SessionSnapshot(
    val id: String,
    val title: String,
    val agent: String,
    val project: String,
    val status: String,
    val lines: List<String>,
    val revision: Int = 0,
    val available: Boolean = true,
    val workspaceId: String = project,
    val workspaceLabel: String = project,
    val terminalTitle: String = "",
    val inputEnabled: Boolean = false,
)

data class SessionGroup(
    val id: Int, val sessionIds: List<String>, val selectedId: String,
    val workspaceId: String? = null, val workspaceLabel: String = "", val overview: Boolean = false,
)

/** Layout and view state are independent of the catalog transport. */
class WorkspaceState(demo: Boolean = true) {
    var isDemo by mutableStateOf(demo)
        private set
    var sessions by mutableStateOf(if (demo) DemoSessionSource.initialSessions() else emptyList())
        private set
    var paused by mutableStateOf(false)
    var restoreLocation by mutableStateOf(RestoreLocation.AROUND_ME)
    var dashboardSelection by mutableStateOf<String?>(null)
        private set
    var dashboardWorkspace by mutableStateOf<String?>(null)
        private set
    var favoriteIds by mutableStateOf(emptySet<String>())
        private set
    internal var onFavoritesChanged: (Set<String>) -> Unit = {}
    val favoriteSessions get() = sessions.filter { it.id in favoriteIds }
    val dashboardSessions get() = if (dashboardWorkspace == FAVORITES_WORKSPACE) favoriteSessions
        else sessions.filter { dashboardWorkspace == null || it.workspaceId == dashboardWorkspace }
    fun setFavorites(ids: Set<String>) {
        favoriteIds = ids.filter { it.isNotBlank() }.take(256).toSet()
        if (!isDemo) sessions = sessions + favoriteIds.filter { session(it) == null }.map(::placeholder)
        if (dashboardWorkspace == FAVORITES_WORKSPACE && dashboardSelection !in favoriteIds) dashboardSelection = null
        refreshFavoriteGroups()
    }
    fun toggleFavorite(id: String) {
        if (id !in favoriteIds && session(id) == null) return
        if (id !in favoriteIds && favoriteIds.size >= 256) { windowNotice = "Favourites limit reached (256). Remove a star to add another."; return }
        windowNotice = null
        setFavorites(if (id in favoriteIds) favoriteIds - id else favoriteIds + id)
        onFavoritesChanged(favoriteIds)
    }
    private fun refreshFavoriteGroups() {
        val ids = favoriteSessions.map { it.id }
        groups = groups.map { group ->
            if (group.workspaceId != FAVORITES_WORKSPACE) group else group.copy(
                sessionIds = ids, selectedId = group.selectedId.takeIf { it in ids } ?: ids.firstOrNull().orEmpty(),
                workspaceLabel = "Favourites", overview = group.overview || group.selectedId !in ids)
        }
    }
    var groups by mutableStateOf(emptyList<SessionGroup>())
        private set
    private var drafts by mutableStateOf(emptyMap<String, String>())
    var windowNotice by mutableStateOf<String?>(null)
        private set
    private fun windowLimit(): Int? { windowNotice = "Window limit reached ($MAX_WINDOWS). Close a window to open another."; return null }
    private var ticks = 0

    fun setSource(demo: Boolean) {
        isDemo = demo
        favoriteIds = emptySet()
        windowNotice = null
        sessions = if (demo) DemoSessionSource.initialSessions() else emptyList()
        dashboardSelection = null; dashboardWorkspace = null; groups = emptyList(); drafts = emptyMap(); paused = false
    }

    fun applyCatalog(incoming: List<SessionSnapshot>) {
        if (isDemo) return
        val referenced = groups.filter { it.workspaceId == null }.flatMap { it.sessionIds }.toSet() + listOfNotNull(dashboardSelection) + drafts.keys + favoriteIds
        val incomingIds = incoming.map { it.id }.toSet()
        // Saved workspace IDs can disappear when the Herdr server is recreated.
        // Keep saved session placeholders, but don't leave the catalog behind an obsolete filter.
        if (incoming.isNotEmpty() && dashboardWorkspace != null && dashboardWorkspace != FAVORITES_WORKSPACE && incoming.none { it.workspaceId == dashboardWorkspace }) {
            dashboardWorkspace = null
        }
        val missing = referenced.filter { it !in incomingIds }.map { id ->
            (session(id) ?: placeholder(id)).copy(available = false, lines = listOf("Session unavailable on this server. Saved layout and draft are preserved."))
        }
        sessions = incoming.distinctBy { it.id } + missing
        groups = groups.map { group ->
            if (group.workspaceId == null || group.workspaceId == FAVORITES_WORKSPACE) group else {
                val members = incoming.filter { it.workspaceId == group.workspaceId }.distinctBy { it.id }
                val ids = members.map { it.id }
                group.copy(sessionIds = ids, selectedId = group.selectedId.takeIf { it in ids } ?: ids.firstOrNull().orEmpty(),
                    workspaceLabel = members.firstOrNull()?.workspaceLabel ?: group.workspaceLabel,
                    overview = group.overview || group.selectedId !in ids)
            }
        }
        refreshFavoriteGroups()
    }

    private fun placeholder(id: String) = SessionSnapshot(id, "Saved session", "Herdr", "Waiting for connection", "unknown",
        listOf("Waiting for the session catalog…"), available = false)

    fun session(id: String): SessionSnapshot? = sessions.firstOrNull { it.id == id }
    fun draft(id: String): String = drafts[id].orEmpty()
    fun setDraft(id: String, text: String) { if (session(id) != null) drafts = drafts + (id to text) }

    fun layoutState() = WorkspaceLayoutState(dashboardSelection, groups, paused, drafts, restoreLocation, dashboardWorkspace)

    fun restoreLayoutState(saved: WorkspaceLayoutState) {
        if (!isDemo) {
            val savedIds = saved.groups.flatMap { it.sessionIds } + listOfNotNull(saved.dashboardSelection) + saved.drafts.keys
            sessions = sessions + savedIds.distinct().filter { session(it) == null }.take(256).map(::placeholder)
        }
        val known = sessions.map { it.id }.toSet()
        val owned = mutableSetOf<String>()
        val windowIds = mutableSetOf<Int>()
        groups = saved.groups.mapNotNull { group ->
            if (group.id !in 0 until MAX_WINDOWS || !windowIds.add(group.id)) return@mapNotNull null
            val ids = group.sessionIds.filter { it in known && (group.workspaceId != null || owned.add(it)) }.distinct()
            if (ids.isEmpty() && group.workspaceId == null) null
            else group.copy(sessionIds = ids, selectedId = group.selectedId.takeIf { it in ids } ?: ids.firstOrNull().orEmpty())
        }
        dashboardSelection = saved.dashboardSelection?.takeIf { it in known && (saved.dashboardWorkspace != FAVORITES_WORKSPACE || it in favoriteIds) }
        drafts = saved.drafts.filterKeys { it in known }
        paused = saved.paused
        restoreLocation = saved.restoreLocation
        dashboardWorkspace = saved.dashboardWorkspace
        refreshFavoriteGroups()
    }

    fun group(id: Int) = groups.firstOrNull { it.id == id }
    fun groupFor(sessionId: String) = groups.firstOrNull { it.workspaceId == null && sessionId in it.sessionIds }
    fun workspaceWindow(workspaceId: String) = groups.firstOrNull { it.workspaceId == workspaceId }
    fun openWorkspace(workspaceId: String): Int? {
        windowNotice = null
        workspaceWindow(workspaceId)?.let { return it.id }
        val members = if (workspaceId == FAVORITES_WORKSPACE) favoriteSessions else sessions.filter { it.workspaceId == workspaceId && it.available }
        if (members.isEmpty() && workspaceId != FAVORITES_WORKSPACE) return null
        val slot = (0 until MAX_WINDOWS).firstOrNull { candidate -> groups.none { it.id == candidate } } ?: return windowLimit()
        groups = groups + SessionGroup(slot, members.map { it.id }, members.firstOrNull()?.id.orEmpty(), workspaceId, if (workspaceId == FAVORITES_WORKSPACE) "Favourites" else members.first().workspaceLabel, overview = true)
        return slot
    }
    fun showWorkspaceGrid(groupId: Int) {
        groups = groups.map { if (it.id == groupId && it.workspaceId != null) it.copy(overview = true) else it }
    }
    fun selectDashboard(id: String) {
        val target = session(id) ?: return
        if (dashboardWorkspace == FAVORITES_WORKSPACE && id !in favoriteIds) dashboardWorkspace = target.workspaceId
        else if (dashboardWorkspace != null && dashboardWorkspace != FAVORITES_WORKSPACE && dashboardWorkspace != target.workspaceId) dashboardWorkspace = target.workspaceId
        dashboardSelection = id
    }
    fun selectDashboardWorkspace(id: String?) { dashboardWorkspace = id; dashboardSelection = null }
    fun showGrid() { dashboardSelection = null }
    fun selectTab(groupId: Int, id: String) {
        groups = groups.map { if (it.id == groupId && id in it.sessionIds) it.copy(selectedId = id, overview = false) else it }
    }

    /** A session has at most one floating owner; the dashboard is always a catalog. */
    fun detach(id: String): Int? {
        windowNotice = null
        if (session(id) == null) return null
        val existing = groupFor(id)
        if (existing?.sessionIds?.size == 1) return existing.id
        val remaining = withoutSession(groups, id)
        val slot = (0 until MAX_WINDOWS).firstOrNull { candidate -> remaining.none { it.id == candidate } } ?: return windowLimit()
        groups = remaining + SessionGroup(slot, listOf(id), id)
        return slot
    }

    fun moveToGroup(id: String, groupId: Int) {
        val target = group(groupId) ?: return
        if (session(id) == null) return
        if (target.workspaceId != null) {
            if (id in target.sessionIds) selectTab(groupId, id)
            return
        }
        if (id in target.sessionIds) { selectTab(groupId, id); return }
        groups = withoutSession(groups, id).map {
            if (it.id == groupId) it.copy(sessionIds = it.sessionIds + id, selectedId = id) else it
        }
    }

    fun returnToDashboard(id: String) {
        if (session(id) == null) return
        groups = withoutSession(groups, id)
        selectDashboard(id)
    }

    fun closeTab(groupId: Int, id: String) {
        val target = group(groupId) ?: return
        if (target.workspaceId != null) { showWorkspaceGrid(groupId); return }
        if (id in target.sessionIds) groups = withoutSession(groups, id)
    }
    fun closeGroup(groupId: Int) { windowNotice = null; groups = groups.filterNot { it.id == groupId } }
    fun closeAll() { windowNotice = null; groups = emptyList() }

    private fun withoutSession(from: List<SessionGroup>, id: String): List<SessionGroup> = from.mapNotNull { group ->
        if (group.workspaceId != null || id !in group.sessionIds) group else {
            val remaining = group.sessionIds - id
            if (remaining.isEmpty()) null else group.copy(
                sessionIds = remaining,
                selectedId = if (group.selectedId == id) remaining[minOf(group.sessionIds.indexOf(id), remaining.lastIndex)] else group.selectedId,
            )
        }
    }

    fun submit(id: String, text: String) {
        if (!isDemo) return
        val prompt = text.trim()
        if (prompt.isEmpty() || session(id) == null) return
        update(id, "› $prompt", "Demo reply: I’ll inspect the local example and report what I find.")
        setDraft(id, "")
    }

    fun tick() {
        if (!isDemo || paused || sessions.isEmpty()) return
        ticks++
        val index = (ticks - 1) % sessions.size
        val current = sessions[index]
        val messages = listOf(
            "Scanning changed files and related tests…",
            "Checking the implementation against the task notes…",
            "One more local validation pass is in progress…",
            "Summary updated; waiting for the next step."
        )
        val status = if (ticks % 11 == 0) "Ready" else if (ticks % 7 == 0) "Review" else "Working"
        update(current.id, messages[(ticks / sessions.size) % messages.size], status = status)
    }

    private fun update(id: String, vararg additions: String, status: String? = null) {
        sessions = sessions.map { old ->
            if (old.id != id) old else old.copy(
                status = status ?: old.status,
                lines = (old.lines + additions).takeLast(80),
                revision = old.revision + 1,
            )
        }
    }

    companion object { const val MAX_WINDOWS = 32 }
}

/** Compatibility name for existing demo previews and tests. */
typealias DemoWorkspace = WorkspaceState

object DemoSessionSource {
    fun initialSessions() = listOf(
            SessionSnapshot("api", "API migration", "Aster", "Northstar / services", "Working", listOf("$ ./gradlew test", "38 checks passed", "Reviewing auth route changes…")),
            SessionSnapshot("ui", "UI polish", "Mira", "Atlas / web", "Review", listOf("$ npm run build", "Bundle compiled successfully", "Comparing spacing at tablet width…")),
            SessionSnapshot("search", "Search index", "Orion", "Beacon / platform", "Working", listOf("$ pytest tests/search", "12 tests passed", "Inspecting incremental updates…")),
            SessionSnapshot("billing", "Billing tests", "Pax", "Mercury / backend", "Ready", listOf("$ go test ./billing/...", "All packages passed", "Coverage summary ready.")),
            SessionSnapshot("docs", "Docs refresh", "Luna", "Atlas / docs", "Working", listOf("$ make docs", "Generated 24 pages", "Checking navigation links…")),
            SessionSnapshot("release", "Release prep", "Nova", "Northstar / mobile", "Review", listOf("$ ./gradlew assembleDebug", "BUILD SUCCESSFUL", "Reviewing release notes…")),
            SessionSnapshot("auth", "Sign-in flow", "Iris", "Atlas / identity", "Working", listOf("$ npm test -- auth", "14 checks passed", "Checking session expiry…")),
            SessionSnapshot("metrics", "Usage metrics", "Sol", "Beacon / analytics", "Review", listOf("$ python validate.py", "Events reconciled", "Reviewing aggregation windows…")),
            SessionSnapshot("sync", "Offline sync", "Echo", "Northstar / mobile", "Working", listOf("$ ./gradlew test", "Sync queue recovered", "Testing reconnect behavior…")),
            SessionSnapshot("a11y", "Accessibility", "Sage", "Atlas / web", "Review", listOf("$ npm run audit", "Focus order checked", "Reviewing keyboard navigation…")),
            SessionSnapshot("cache", "Cache tuning", "Rune", "Beacon / platform", "Ready", listOf("$ go test ./cache/...", "All cases passed", "Benchmarks ready to review.")),
            SessionSnapshot("deploy", "Deploy checks", "Vega", "Mercury / infra", "Working", listOf("$ make validate", "Configuration verified", "Preparing rollout notes…")),
        )
}
