package io.agentspace.quest

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

internal val ChromeSurface = Color(0xFF223747)
internal val ChromeInk = Color(0xFFE7F0F5)
internal val ChromeMuted = Color(0xFFADBFCD)
internal val ChromeAccent = Color(0xFFB3DDFA)
private val SelectedSurface = Color(0xFF304F66)

/** Navigation remains distinct from session cards, even in the compact picker. */
@Composable
internal fun WorkspaceNavigation(workspace: WorkspaceState, modifier: Modifier = Modifier, onOpenWindow: ((String) -> Unit)? = null, onSelected: () -> Unit = {}) {
    Column(modifier.background(Color(0xFF142330), RoundedCornerShape(12.dp)).padding(8.dp)) {
        Text("Workspaces", color = ChromeMuted, fontSize = 14.sp, modifier = Modifier.padding(10.dp, 10.dp))
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WorkspaceRow("All workspaces", workspace.sessions.size, workspace.dashboardWorkspace == null) {
                workspace.selectDashboardWorkspace(null); onSelected()
            }
            WorkspaceRow("★ Favourites", workspace.favoriteSessions.size, workspace.dashboardWorkspace == FAVORITES_WORKSPACE,
                onOpen = onOpenWindow?.let { { it(FAVORITES_WORKSPACE) } }, open = workspace.workspaceWindow(FAVORITES_WORKSPACE) != null) {
                workspace.selectDashboardWorkspace(FAVORITES_WORKSPACE); onSelected()
            }
            workspace.sessions.groupBy { it.workspaceId }.forEach { (id, sessions) ->
                WorkspaceRow(sessions.first().workspaceLabel, sessions.size, workspace.dashboardWorkspace == id,
                    onOpen = onOpenWindow?.let { { it(id) } }, open = workspace.workspaceWindow(id) != null) {
                    workspace.selectDashboardWorkspace(id); onSelected()
                }
            }
        }
    }
}

@Composable
private fun WorkspaceRow(label: String, count: Int, selected: Boolean, onOpen: (() -> Unit)? = null, open: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(Modifier.fillMaxWidth().zIndex(if (hovered) 2f else 0f)
        .background(if (selected) SelectedSurface else if (hovered) ChromeSurface else Color.Transparent, RoundedCornerShape(10.dp))
        .hoverable(interaction).selectable(selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
        .heightIn(min = 56.dp).padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(3.dp).height(22.dp).background(if (selected) ChromeAccent else Color.Transparent, RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = if (selected) ChromeInk else ChromeMuted, fontSize = 15.sp, lineHeight = 20.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$count ${if (count == 1) "session" else "sessions"}", color = ChromeMuted, fontSize = 12.sp)
        }
        if (onOpen != null) DashboardIcon(OpenWorkspaceIcon, if (open) "Show workspace window" else "Open workspace window", onOpen)
    }
}

/** Drawn within the existing panel, keeping depth, input and lifecycle predictable. */
@Composable
internal fun DashboardSheet(title: String, subtitle: String, onClose: () -> Unit,
    footer: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC09131C))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(18.dp).widthIn(max = 740.dp).fillMaxWidth().fillMaxHeight()
            .clip(RoundedCornerShape(24.dp)).background(Color(0xFF1D3040))
            .border(1.dp, Color(0xFF476074), RoundedCornerShape(24.dp)).padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = ChromeInk, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, color = ChromeMuted, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 5.dp, end = 12.dp))
                }
                DashboardIcon(Icons.Default.Close, "Close", onClose)
            }
            Spacer(Modifier.height(24.dp))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
            if (footer != null) {
                Spacer(Modifier.height(18.dp))
                footer()
            }
        }
    }
}

@Composable
internal fun DashboardSettings(workspace: WorkspaceState, saveStatus: String, onClose: () -> Unit,
    onConnection: (() -> Unit)?, onReset: () -> Unit, onTips: () -> Unit) {
    val preferences = androidx.compose.ui.platform.LocalContext.current.applicationContext.getSharedPreferences("agentspace_ui", android.content.Context.MODE_PRIVATE)
    val home = LocalHomeMode.current
    val switchMode = LocalSwitchMode.current
    var tipsReset by remember { mutableStateOf(false) }
    var passthrough by remember { mutableStateOf(preferences.getBoolean("passthrough_enabled", true)) }
    DashboardSheet("Settings", "Layout, connection and presentation.", onClose,
        footer = { Text(saveStatus, color = ChromeMuted, fontSize = 13.sp) }) {
        if (switchMode != null) SettingsAction(Icons.Default.Home,
            if (home) "Open immersive workspace" else "Open in Quest Home",
            if (home) "Freely arrange and group panels" else "Windows in your chosen Home environment", switchMode)
        if (home) Text("Move and resize windows with Quest's window controls. Your Home environment stays unchanged.", color = ChromeMuted, fontSize = 14.sp)
        if (!home) {
            Text("Background", color = ChromeInk, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RestoreChoice("Passthrough", "See your room", Icons.Default.Home, passthrough, Modifier.weight(1f)) {
                    passthrough = true; preferences.edit().putBoolean("passthrough_enabled", true).apply()
                }
                RestoreChoice("Dark background", "Hide your room", Icons.Default.Star, !passthrough, Modifier.weight(1f)) {
                    passthrough = false; preferences.edit().putBoolean("passthrough_enabled", false).apply()
                }
            }
            Text("When you reopen", color = ChromeInk, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RestoreChoice("Around me", "Follow my opening position", Icons.Default.Home,
                    workspace.restoreLocation == RestoreLocation.AROUND_ME, Modifier.weight(1f)) { workspace.restoreLocation = RestoreLocation.AROUND_ME }
                RestoreChoice("Room position", "Keep my room arrangement", Icons.Default.Place,
                    workspace.restoreLocation == RestoreLocation.ROOM_POSITION, Modifier.weight(1f)) { workspace.restoreLocation = RestoreLocation.ROOM_POSITION }
            }
            Text(if (workspace.restoreLocation == RestoreLocation.ROOM_POSITION) "Uses the same play area. Changes to your boundary can shift panels." else "Your saved arrangement opens in front of you.",
                color = ChromeMuted, fontSize = 14.sp, lineHeight = 20.sp)
        }
        Column(Modifier.clip(RoundedCornerShape(14.dp)).background(ChromeSurface)) {
            if (onConnection != null) {
                SettingsAction(Icons.Default.Settings, "Connection & demo mode", if (workspace.isDemo) "Demo sessions are showing" else "Your Mac and live Herdr sessions", onConnection)
                SettingsDivider()
            }
            SettingsAction(Icons.Default.Info, "Gesture guide", if (home) "Use sessions and Quest window controls" else "Open, arrange and resize your sessions", onTips)
            SettingsDivider()
            SettingsAction(Icons.Default.Refresh, "Reset dismissed tips", if (tipsReset) "Tips will show when you next open each view" else "Show the dashboard and terminal guides again") {
                preferences.edit().putBoolean("dashboard_tip_seen", false).putBoolean("detail_tip_seen", false).putBoolean("home_dashboard_tip_seen", false).putBoolean("home_detail_tip_seen", false).apply()
                tipsReset = true
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CompactSettingsAction(Icons.Default.Refresh, if (home) "Reset view" else "Reset layout", Modifier.weight(1f), onReset)
            if (workspace.isDemo) CompactSettingsAction(Icons.Default.PlayArrow,
                if (workspace.paused) "Resume demo" else "Pause demo", Modifier.weight(1f)) { workspace.paused = !workspace.paused }
        }
    }
}

@Composable
private fun RestoreChoice(title: String, description: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(if (selected) SelectedSurface else ChromeSurface)
        .border(if (selected) 2.dp else 1.dp, if (selected) ChromeAccent else Color(0xFF41586B), RoundedCornerShape(14.dp))
        .selectable(selected, role = Role.RadioButton, onClick = onClick).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (selected) ChromeAccent else ChromeMuted, modifier = Modifier.size(22.dp))
            Text(title, color = ChromeInk, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (selected) Icon(Icons.Default.Check, null, tint = ChromeAccent, modifier = Modifier.size(18.dp))
        }
        Text(description, color = ChromeMuted, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun SettingsAction(icon: ImageVector, title: String, description: String, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = action).heightIn(min = 76.dp).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = ChromeAccent, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = ChromeInk, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text(description, color = ChromeMuted, fontSize = 14.sp, lineHeight = 20.sp)
        }
        Icon(Icons.Default.KeyboardArrowRight, null, tint = ChromeMuted, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun CompactSettingsAction(icon: ImageVector, title: String, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(ChromeSurface).clickable(onClick = onClick)
        .heightIn(min = 54.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = ChromeMuted, modifier = Modifier.size(22.dp))
        Text(title, color = ChromeInk, fontSize = 16.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SettingsDivider() { Box(Modifier.padding(start = 54.dp, end = 16.dp).fillMaxWidth().height(1.dp).background(Color(0xFF3A5061))) }

@Composable
internal fun DashboardGestureGuide(onClose: () -> Unit) {
    val home = LocalHomeMode.current
    DashboardSheet(if (home) "Sessions in Quest Home" else "Arrange your sessions", if (home) "Your workspace, in your own environment." else "Three gestures to make it yours.", onClose,
        footer = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Find this guide again in Settings.", color = ChromeMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(ChromeAccent).clickable(onClick = onClose)
                    .heightIn(min = 48.dp).padding(horizontal = 24.dp, vertical = 13.dp), contentAlignment = Alignment.Center) {
                    Text("Got it", color = Color(0xFF152D40), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }) {
        GestureLesson(0, "Open & switch", "Select a session tile. Use the tabs above it to switch sessions.")
        GestureLesson(1, if (home) "Open another window" else "Pull into your space", if (home) "Use the open-window icon on a tile, beside the terminal title, or beside a workspace. Star sessions to collect them in Favourites. Quest places new windows in Home." else "Hold a tile, then drag it out. Drop it onto another panel to group sessions.")
        GestureLesson(2, "Shape your dashboard", if (home) "Use Quest’s window controls to move, resize or close a panel. Other visible sessions keep updating." else "Drag a corner to resize. Make it shallow for a scrolling row; swipe to browse.")
    }
}

@Composable
internal fun GestureLesson(kind: Int, title: String, description: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 122.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        GestureDrawing(kind, Modifier.width(112.dp).height(104.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF142633)).padding(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, color = ChromeInk, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium)
            Text(description, color = ChromeMuted, fontSize = 16.sp, lineHeight = 23.sp)
        }
    }
}

/** Small, scalable gesture diagrams rather than decorative artwork. */
@Composable
private fun GestureDrawing(kind: Int, modifier: Modifier) {
    Canvas(modifier) {
        val sx = size.width / 100f; val sy = size.height / 100f
        fun panel(x: Float, y: Float, w: Float, h: Float, active: Boolean = false) {
            drawRoundRect(if (active) Color(0xFF416785) else Color(0xFF263F53), Offset(x * sx, y * sy), Size(w * sx, h * sy), CornerRadius(5f * sx))
            drawRoundRect(if (active) ChromeAccent else Color(0xFF7895AB), Offset(x * sx, y * sy), Size(w * sx, h * sy), CornerRadius(5f * sx), style = Stroke(1.5f * sx))
        }
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(ChromeAccent, Offset(x * sx, y * sy), Offset(x2 * sx, y2 * sy), 2f * sx)
        when (kind) {
            0 -> {
                panel(5f, 18f, 90f, 67f)
                panel(11f, 25f, 23f, 15f, true); panel(39f, 25f, 23f, 15f); panel(67f, 25f, 22f, 15f)
                line(17f, 54f, 74f, 54f); line(17f, 65f, 59f, 65f)
                drawCircle(ChromeAccent, 5f * sx, Offset(26f * sx, 35f * sy))
            }
            1 -> {
                panel(3f, 33f, 64f, 56f)
                panel(9f, 41f, 20f, 17f); panel(36f, 41f, 20f, 17f)
                panel(48f, 8f, 48f, 43f, true)
                line(23f, 69f, 69f, 31f); line(69f, 31f, 57f, 33f); line(69f, 31f, 65f, 44f)
            }
            else -> {
                panel(11f, 13f, 60f, 43f)
                panel(5f, 70f, 90f, 22f, true)
                line(19f, 76f, 19f, 86f); line(40f, 76f, 40f, 86f); line(61f, 76f, 61f, 86f); line(82f, 76f, 82f, 86f)
                line(75f, 54f, 88f, 65f); line(88f, 65f, 78f, 65f); line(88f, 65f, 88f, 55f)
            }
        }
    }
}

internal val OpenWorkspaceIcon: ImageVector by lazy {
    ImageVector.Builder("Open workspace window", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(13f, 4f); lineTo(20f, 4f); lineTo(20f, 11f)
            moveTo(20f, 4f); lineTo(10f, 14f)
            moveTo(10f, 4f); lineTo(4f, 4f); lineTo(4f, 20f); lineTo(20f, 20f); lineTo(20f, 14f)
        }
    }.build()
}
