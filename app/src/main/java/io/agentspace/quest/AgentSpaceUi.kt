package io.agentspace.quest

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.zIndex
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest
import io.agentspace.quest.terminal.TerminalController
import io.agentspace.quest.terminal.TerminalSurface

private val Slate = Color(0xFF172736)
private val Tile = Color(0xFF23394B)
private val Pale = Color(0xFFE7F0F5)
private val Muted = Color(0xFFACBFCD)
private val Ice = Color(0xFF97CEEB)
private val Peach = Color(0xFFFFC58F)
private val Green = Color(0xFF97D8BC)
const val DASHBOARD_WINDOW = -1

data class TabDragUi(val sessionId: String, val source: Int, val target: Int? = null)
class TabDragActions(
    val start: (String) -> Unit = {},
    val move: (Float, Float) -> Unit = { _, _ -> },
    val end: () -> Unit = {},
    val cancel: () -> Unit = {},
)

@Composable
fun FloatingWindowUi(
    workspace: WorkspaceState,
    groupId: Int,
    drag: TabDragUi? = null,
    dragActions: TabDragActions = TabDragActions(),
    onClose: () -> Unit,
    onCloseTab: (String) -> Unit,
    onResize: (Float) -> Unit,
    onRecenter: () -> Unit,
    connectionStatus: String = "Demo · local simulated sessions",
    terminal: TerminalController? = null,
    showTips: Boolean = true,
) {
    val home = LocalHomeMode.current
    val group = workspace.group(groupId) ?: return
    if (group.workspaceId != null) {
        WorkspaceWindowUi(workspace, group, drag, dragActions, onClose, onResize, onRecenter, connectionStatus, terminal, showTips)
        return
    }
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Slate)
        .border(if (drag?.target == groupId) 3.dp else 1.dp, if (drag?.target == groupId) Green else Color(0xFF38566B), RoundedCornerShape(16.dp))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
            if (group.sessionIds.size > 1) SessionTabs(workspace, group.sessionIds, group.selectedId, drag, dragActions, terminal) {
                if (drag == null) { workspace.selectTab(groupId, it); if (!workspace.isDemo) terminal?.watch(it) }
            }
            key(group.selectedId) {
                SessionDetail(workspace, group.selectedId, if (group.sessionIds.size == 1) "Close window" else "Close tab",
                    { if (group.sessionIds.size == 1) onClose() else onCloseTab(group.selectedId) }, Modifier.weight(1f), terminal, showTips)
            }
            val status = when {
                drag?.target == groupId -> "Release to add this session as a tab"
                drag != null -> "Release outside to detach · drop on a window to group"
                workspace.windowNotice != null -> workspace.windowNotice!!
                else -> detailStatus(workspace, group.selectedId, terminal, connectionStatus)
            }
            DetailFooter(status, if (home) null else onResize, if (home) null else onRecenter, if (group.sessionIds.size > 1) onClose else null)
        }
    }
}

@Composable
internal fun SessionTabs(workspace: WorkspaceState, ids: List<String>, selected: String,
    drag: TabDragUi?, actions: TabDragActions, terminal: TerminalController? = null, onSelect: (String) -> Unit) {
    val scroll = rememberLazyListState()
    LaunchedEffect(selected, ids) {
        val index = ids.indexOf(selected)
        if (index >= 0 && scroll.layoutInfo.visibleItemsInfo.none { it.index == index }) scroll.animateScrollToItem(index)
    }
    LazyRow(state = scroll, modifier = Modifier.fillMaxWidth().height(94.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(ids, key = { it }) { id ->
            workspace.session(id)?.let { session ->
                SessionTile(workspace, session, workspace.groupFor(id)?.id, id == selected, true, drag?.sessionId == id,
                    actions, { onSelect(id) }, Modifier.width(190.dp).fillMaxHeight(), terminal = if (workspace.isDemo) null else terminal)
            }
        }
    }
}

@Composable
internal fun SessionTile(workspace: WorkspaceState, session: SessionSnapshot, detachedGroup: Int?, selected: Boolean, compact: Boolean,
    dragging: Boolean, actions: TabDragActions, onOpen: () -> Unit, modifier: Modifier = Modifier, dashboardTile: Boolean = false, terminal: TerminalController? = null) {
    val favorite = session.id in workspace.favoriteIds
    val openWindow = LocalOpenSessionWindow.current
    val attention = tileStatus(session.status, session.available)
    val tileBackground = when (attention) {
        TileStatus.DONE -> Color(0xFF214B3B)
        TileStatus.QUESTION -> Color(0xFF594A20)
        else -> Tile
    }
    val statusInk = when (attention) {
        TileStatus.DONE -> Color(0xFFB4F0C8)
        TileStatus.QUESTION -> Color(0xFFFFE391)
        TileStatus.IDLE, TileStatus.UNKNOWN -> Muted
        TileStatus.WORKING -> Ice
    }
    // Ignore full-detail revision ticks: tiles only redraw when their 2 Hz preview or status changes.
    val preview by remember(terminal, session.id) { derivedStateOf {
        terminal?.state(session.id)?.let { Triple(it.previewLines, it.status, it.closed) }
    } }
    DisposableEffect(terminal, session.id, session.available) {
        if (session.available) terminal?.retain(session.id, preview = true)
        onDispose { if (session.available) terminal?.release(session.id, preview = true) }
    }
    val previewLines = preview?.first?.takeIf { it.isNotEmpty() } ?: session.lines
    val density = LocalDensity.current
    val currentActions by rememberUpdatedState(actions)
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    Column(modifier.onGloballyPositioned { rootOffset = it.positionInRoot() }
        .border(if (selected || dragging) 2.dp else 1.dp, if (selected || dragging) Ice else Color(0xFF38566B), RoundedCornerShape(12.dp))
        .clip(RoundedCornerShape(12.dp)).background(tileBackground)
        .pointerInput(session.id, density) {
            detectDragGesturesAfterLongPress(
                onDragStart = { point ->
                    pointer = point
                    currentActions.start(session.id)
                    currentActions.move((rootOffset.x + pointer.x) / density.density, (rootOffset.y + pointer.y) / density.density)
                },
                onDrag = { change, amount ->
                    change.consume(); pointer += amount
                    currentActions.move((rootOffset.x + pointer.x) / density.density, (rootOffset.y + pointer.y) / density.density)
                },
                onDragEnd = { currentActions.end() },
                onDragCancel = { currentActions.cancel() },
            )
        }.clickable(onClick = onOpen).padding(if (compact || dashboardTile) 12.dp else 16.dp)) {
        Row(Modifier.fillMaxWidth().zIndex(10f), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(session.title, color = Pale, fontSize = if (compact) 15.sp else 19.sp, fontWeight = FontWeight.SemiBold,
                maxLines = if (dashboardTile && !compact) 2 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (!compact) {
                PanelActionIcon(if (favorite) Icons.Default.Star else FavoriteOutlineIcon,
                    if (favorite) "Remove from favourites" else "Add to favourites", size = 32.dp, selected = favorite) { workspace.toggleFavorite(session.id) }
                if (openWindow != null) PanelActionIcon(OpenWorkspaceIcon, "Open in a separate Home window", size = 32.dp) { openWindow(session.id) }
            } else if (favorite) androidx.compose.material3.Icon(Icons.Default.Star, "Favourite", tint = Color(0xFFFFE391), modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.height(if (compact) 4.dp else 7.dp))
        if (!compact || dashboardTile) {
            Text("${session.agent} · ${session.project}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(9.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val stateLabel = when (attention) {
                TileStatus.DONE -> "✓ Done"
                TileStatus.QUESTION -> "? Needs input"
                TileStatus.IDLE -> "Idle"
                TileStatus.WORKING -> "Working"
                TileStatus.UNKNOWN -> if (session.available) session.status else "Unavailable"
            }
            Text(stateLabel, color = statusInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            preview?.let { (lines, streamStatus, closed) ->
                val live = streamStatus.startsWith("Live")
                val label = when {
                    closed -> "Closed"
                    live -> "Live"
                    lines.isNotEmpty() -> "Stale preview"
                    streamStatus.startsWith("Connecting") -> "Connecting"
                    streamStatus.startsWith("Queued") -> "Queued"
                    else -> "Preview unavailable"
                }
                Text(label, color = if (live) Green else Muted, fontSize = 10.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            if (detachedGroup != null) Text("↗ ${detachedGroup + 1}", color = Ice, fontSize = 11.sp)
        }
        Spacer(Modifier.height(if (compact) 5.dp else 10.dp))
        previewLines.takeLast(if (compact) 1 else if (dashboardTile) 2 else 3).forEach {
            Text(it, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!compact && !dashboardTile) {
            Spacer(Modifier.weight(1f))
            Text(if (detachedGroup != null) "View here · also in window ${detachedGroup + 1}" else "Open session", color = Ice, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun SessionDetail(workspace: WorkspaceState, sessionId: String, closeLabel: String,
    onClose: () -> Unit, modifier: Modifier = Modifier, terminal: TerminalController? = null, showTips: Boolean = true) {
    val openWindow = LocalOpenSessionWindow.current
    val favorite = sessionId in workspace.favoriteIds
    val session = workspace.session(sessionId) ?: return
    var fontSize by remember(sessionId) { mutableIntStateOf(15) }
    var composer by remember(sessionId) { mutableStateOf(workspace.isDemo || workspace.draft(sessionId).isNotEmpty()) }
    var fit by remember(sessionId) { mutableStateOf(false) }
    val (showGuide, dismissGuide) = rememberDetailTip(showTips)
    val scroll = rememberScrollState()
    var followLatest by remember(sessionId) { mutableStateOf(true) }
    var autoScrolling by remember(sessionId) { mutableStateOf(false) }
    val nearBottomPx = with(LocalDensity.current) { 48.dp.toPx() }
    LaunchedEffect(scroll, nearBottomPx) {
        snapshotFlow { scroll.isScrollInProgress to (scroll.maxValue - scroll.value) }.collectLatest { (moving, distance) ->
            if (moving && !autoScrolling) followLatest = distance <= nearBottomPx
        }
    }
    LaunchedEffect(session.revision, followLatest) {
        if (followLatest) {
            withFrameNanos { }
            autoScrolling = true
            try { scroll.animateScrollTo(scroll.maxValue) } finally { autoScrolling = false }
        }
    }
    Box(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(46.dp).zIndex(10f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(session.title, color = Pale, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (session.terminalTitle.isNotBlank() && session.terminalTitle != session.title)
                        Text(session.terminalTitle, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (workspace.isDemo || (session.available && terminal != null))
                    PanelActionIcon(Icons.Default.Edit, if (composer) "Hide text input" else "Write or dictate", selected = composer) { composer = !composer }
                PanelActionIcon(if (favorite) Icons.Default.Star else FavoriteOutlineIcon,
                    if (favorite) "Remove from favourites" else "Add to favourites", selected = favorite) { workspace.toggleFavorite(sessionId) }
                if (workspace.isDemo && !followLatest) PanelActionIcon(Icons.Default.KeyboardArrowDown, "Follow latest") { followLatest = true }
                PanelActionIcon(SmallerTextIcon, "Smaller text") { fontSize = (fontSize - 1).coerceAtLeast(12) }
                PanelActionIcon(LargerTextIcon, "Larger text") { fontSize = (fontSize + 1).coerceAtMost(24) }
                if (!workspace.isDemo && session.available && terminal != null) {
                    PanelActionIcon(FitTerminalIcon, if (fit && fontSize == 15) "Use readable text size" else "Fit whole screen", selected = fit && fontSize == 15) {
                        if (fit && fontSize == 15) fit = false else { fit = true; fontSize = 15 }
                    }
                    PanelActionIcon(Icons.Default.Refresh, "Reconnect stream") { terminal.watch(sessionId, force = true) }
                }
                if (openWindow != null) PanelActionIcon(OpenWorkspaceIcon, "Open session in Quest Home window") { openWindow(sessionId) }
                PanelActionIcon(Icons.Default.Close, closeLabel, action = onClose)
            }
            if (!workspace.isDemo && session.available && terminal != null) {
                TerminalSurface(terminal, sessionId, fontSize / 15f, Modifier.weight(1f), fit)
            } else Column(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0F202D), RoundedCornerShape(12.dp))
                .verticalScroll(scroll).padding(16.dp)) {
                session.lines.forEach { line ->
                    Text(line, color = Pale, fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, lineHeight = (fontSize + 6).sp)
                }
            }
            if (composer) {
                val inputState = terminal?.input?.state(sessionId)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicTextField(value = workspace.draft(sessionId), onValueChange = { if (it.toByteArray(Charsets.UTF_8).size <= 16384) workspace.setDraft(sessionId, it) },
                        modifier = Modifier.weight(1f).background(Tile, RoundedCornerShape(10.dp)).padding(12.dp), maxLines = 3,
                        textStyle = TextStyle(color = Pale, fontSize = 16.sp),
                        decorationBox = { inner -> Box {
                            if (workspace.draft(sessionId).isEmpty()) Text(if (workspace.isDemo) "Try a demo prompt…" else "Type, or use the keyboard microphone…", color = Muted, fontSize = 14.sp)
                            inner()
                        } })
                    val canSend = workspace.draft(sessionId).isNotBlank() && inputState?.sending != true && session.available && (workspace.isDemo || session.inputEnabled)
                    Box(Modifier.background(if (canSend) Color(0xFF345E73) else Tile, RoundedCornerShape(9.dp))
                        .clickable(enabled = canSend) {
                            val draft = workspace.draft(sessionId)
                            if (workspace.isDemo) workspace.submit(sessionId, draft)
                            else terminal?.input?.send(sessionId, draft) {
                                if (workspace.draft(sessionId) == draft) workspace.setDraft(sessionId, "")
                            }
                        }.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(if (inputState?.sending == true) "Sending…" else "Send ↵", color = if (canSend) Pale else Muted, fontSize = 14.sp)
                    }
                }
                if (!workspace.isDemo) Text(inputState?.message?.ifBlank { null } ?: if (session.inputEnabled) "Send types the draft into this terminal and presses Enter." else "Read-only pairing · enable input on the Mac with pair-headset.py --allow-input.",
                    color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
        if (showGuide) DetailGestureGuide(dismissGuide)
    }
}

@Composable
internal fun ActionButton(label: String, action: () -> Unit) {
    Box(Modifier.background(Tile, RoundedCornerShape(9.dp)).clickable(onClick = action)
        .defaultMinSize(minHeight = 44.dp).padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, color = Pale, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
