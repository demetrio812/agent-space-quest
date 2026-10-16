package io.agentspace.quest

import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.agentspace.quest.terminal.TerminalController
import kotlinx.coroutines.delay

private val Background = Color(0xFF172736)
private val Ink = Color(0xFFE7F0F5)
private val Secondary = Color(0xFFACBFCD)
private val Connected = Color(0xFF97D8BC)
private val Attention = Color(0xFFFFC58F)

@Composable
fun DashboardUi(
    workspace: WorkspaceState,
    drag: TabDragUi? = null,
    dragActions: TabDragActions = TabDragActions(),
    onReset: () -> Unit,
    onCloseAll: () -> Unit,
    saveStatus: String = "Layout saves automatically",
    connectionStatus: String = "Demo · local simulated sessions",
    onConnection: (() -> Unit)? = null,
    terminal: TerminalController? = null,
    onExpand: () -> Unit = {},
    showLaunchTip: Boolean = true,
    initialOverlay: String? = null,
    onOpenWorkspace: ((String) -> Unit)? = null,
) {
    val preferences = LocalContext.current.applicationContext.getSharedPreferences("agentspace_ui", Context.MODE_PRIVATE)
    var workspacePicker by remember { mutableStateOf(initialOverlay == "workspaces") }
    val workspaceChoices = workspace.sessions.groupBy { it.workspaceId }
    val visibleSessions = workspace.dashboardSessions
    var settings by remember { mutableStateOf(initialOverlay == "settings") }
    val tipKey = if (LocalHomeMode.current) "home_dashboard_tip_seen" else "dashboard_tip_seen"
    var tips by remember { mutableStateOf(initialOverlay == "tips" || (showLaunchTip && !preferences.getBoolean(tipKey, false))) }
    fun dismissTips() { tips = false; preferences.edit().putBoolean(tipKey, true).apply() }
    LaunchedEffect(tips) { if (tips) onExpand() }
    val targeted = drag?.target == DASHBOARD_WINDOW
    BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Background)
        .border(if (targeted) 3.dp else 1.dp, if (targeted) Connected else Color(0xFF38566B), RoundedCornerShape(16.dp))) {
        val showRail = dashboardShowsWorkspaceRail(maxWidth.value - 28f, maxHeight.value - 82f)
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            Row(Modifier.fillMaxWidth().height(54.dp).zIndex(10f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("AgentSpace", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 10.dp))
                val selectedWorkspaceLabel = if (workspace.dashboardWorkspace == FAVORITES_WORKSPACE) "Favourites" else workspaceChoices[workspace.dashboardWorkspace]?.firstOrNull()?.workspaceLabel ?: if (workspace.dashboardWorkspace == null) "All workspaces" else "Saved workspace"
                if (!showRail) Text("$selectedWorkspaceLabel ▾", color = Secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable { onExpand(); workspacePicker = true }.padding(vertical = 12.dp, horizontal = 8.dp))
                else Spacer(Modifier.weight(1f))
                if (workspace.dashboardSelection != null) DashboardIcon(Icons.Default.Home, "Session overview") { workspace.showGrid() }
                if (workspace.groups.isNotEmpty()) DashboardIcon(Icons.Default.Close, "Close floating windows", onCloseAll)
                DashboardIcon(Icons.Default.Settings, "Settings") { onExpand(); settings = true }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val strip = dashboardUsesStrip(maxHeight.value)
                val rail = showRail
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(if (rail) 12.dp else 0.dp)) {
                    if (rail) WorkspaceNavigation(workspace, Modifier.width(184.dp).fillMaxHeight(), onOpenWindow = onOpenWorkspace)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val selected = workspace.dashboardSelection
                        if (selected == null || strip) {
                            if (visibleSessions.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(if (workspace.dashboardWorkspace == FAVORITES_WORKSPACE) "Star a session tile to add it to Favourites" else if (connectionStatus.startsWith("Connected")) "No sessions in this workspace" else "Waiting for your Mac…", color = Secondary, fontSize = 17.sp)
                            } else if (strip) {
                                LazyRow(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(visibleSessions, key = { it.id }) { session ->
                                        SessionTile(workspace, session, workspace.groupFor(session.id)?.id, false, true, drag?.sessionId == session.id,
                                            dragActions, { if (drag == null) { onExpand(); workspace.selectDashboard(session.id); if (!workspace.isDemo) terminal?.watch(session.id) } },
                                            Modifier.width(230.dp).fillMaxHeight(), dashboardTile = true, terminal = if (workspace.isDemo) null else terminal)
                                    }
                                }
                            } else {
                                LazyVerticalGrid(columns = GridCells.Adaptive(250.dp), modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(visibleSessions, key = { it.id }) { session ->
                                        SessionTile(workspace, session, workspace.groupFor(session.id)?.id, false, false, drag?.sessionId == session.id,
                                            dragActions, { if (drag == null) { workspace.selectDashboard(session.id); if (!workspace.isDemo) terminal?.watch(session.id) } }, Modifier.height(176.dp), dashboardTile = true, terminal = if (workspace.isDemo) null else terminal)
                                    }
                                }
                            }
                        } else Column(Modifier.fillMaxSize()) {
                            SessionTabs(workspace, visibleSessions.map { it.id }, selected, drag, dragActions, terminal) {
                                if (drag == null) { workspace.selectDashboard(it); if (!workspace.isDemo) terminal?.watch(it) }
                            }
                            Spacer(Modifier.height(8.dp))
                            key(selected) { SessionDetail(workspace, selected, "Close", { workspace.showGrid() }, Modifier.weight(1f), terminal, showLaunchTip) }
                        }
                    }
                }
            }
            val status = when {
                drag?.target == DASHBOARD_WINDOW -> "Release to return this session here"
                drag != null -> "Release outside to detach · drop on a window to group"
                workspace.windowNotice != null -> workspace.windowNotice!!
                saveStatus.startsWith("Couldn't") -> saveStatus
                else -> detailStatus(workspace, workspace.dashboardSelection, terminal, connectionStatus)
            }
            val healthy = !saveStatus.startsWith("Couldn't") && (workspace.isDemo || (connectionStatus.startsWith("Connected") && !status.contains("disconnected", true) && !status.contains("unavailable", true)))
            Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("●", color = if (healthy) Connected else Attention, fontSize = 9.sp)
                Spacer(Modifier.width(7.dp))
                Text(status, color = if (healthy) Connected else Attention, fontSize = 11.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${visibleSessions.size} sessions", color = Secondary, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp))
            }
        }
        if (workspacePicker) DashboardSheet("Workspaces", "Choose which sessions to show.", { workspacePicker = false }) {
            WorkspaceNavigation(workspace, Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 480.dp),
                onOpenWindow = onOpenWorkspace?.let { open -> { id -> open(id); workspacePicker = false } }) { workspacePicker = false }
        }
        if (settings) DashboardSettings(workspace, saveStatus, onClose = { settings = false },
            onConnection = onConnection?.let { { settings = false; it() } },
            onReset = { settings = false; onReset() }, onTips = { settings = false; tips = true })
        if (tips) DashboardGestureGuide(::dismissTips)
    }
}

/** Tooltips stay inside the panel texture; no platform Popup window is needed in VR. */
@Composable
internal fun DashboardIcon(icon: ImageVector, label: String, onClick: () -> Unit) =
    PanelActionIcon(icon, label, size = 40.dp, action = onClick)
