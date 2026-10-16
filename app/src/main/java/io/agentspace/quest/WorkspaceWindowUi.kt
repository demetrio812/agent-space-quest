package io.agentspace.quest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.agentspace.quest.terminal.TerminalController

/** A live workspace catalog; individual session windows remain independent. */
@Composable
internal fun WorkspaceWindowUi(workspace: WorkspaceState, group: SessionGroup, drag: TabDragUi?, actions: TabDragActions,
    onClose: () -> Unit, onResize: (Float) -> Unit, onRecenter: () -> Unit, connectionStatus: String, terminal: TerminalController?, showTips: Boolean = true) {
    val home = LocalHomeMode.current
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color(0xFF172736))
        .border(1.dp, Color(0xFF38566B), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().height(46.dp).zIndex(10f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(group.workspaceLabel.ifBlank { "Workspace" }, color = ChromeInk, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            PanelActionIcon(Icons.Default.Close, "Close workspace window", action = onClose)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (group.sessionIds.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (group.workspaceId == FAVORITES_WORKSPACE) "Star a session tile to add it to Favourites" else "No active sessions in this workspace", color = ChromeMuted, fontSize = 17.sp)
            } else if (group.overview) LazyVerticalGrid(GridCells.Adaptive(240.dp), Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(group.sessionIds, key = { it }) { id -> workspace.session(id)?.let { session ->
                    SessionTile(workspace, session, workspace.groupFor(id)?.id, false, false, drag?.sessionId == id, actions,
                        { if (drag == null) { workspace.selectTab(group.id, id); if (!workspace.isDemo) terminal?.watch(id) } },
                        Modifier.height(176.dp), dashboardTile = true, terminal = if (workspace.isDemo) null else terminal)
                } }
            } else Column(Modifier.fillMaxSize()) {
                if (group.sessionIds.size > 1) SessionTabs(workspace, group.sessionIds, group.selectedId, drag, actions, terminal) {
                    if (drag == null) { workspace.selectTab(group.id, it); if (!workspace.isDemo) terminal?.watch(it) }
                }
                key(group.selectedId) {
                    SessionDetail(workspace, group.selectedId, "Back to workspace grid", { workspace.showWorkspaceGrid(group.id) }, Modifier.weight(1f), terminal, showTips)
                }
            }
        }
        DetailFooter(workspace.windowNotice ?: detailStatus(workspace, if (group.overview) null else group.selectedId, terminal, connectionStatus), if (home) null else onResize, if (home) null else onRecenter)
    }
}
