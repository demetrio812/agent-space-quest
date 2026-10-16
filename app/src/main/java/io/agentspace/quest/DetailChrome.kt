package io.agentspace.quest

import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.agentspace.quest.terminal.TerminalController
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PanelActionIcon(icon: ImageVector, label: String, size: Dp = 34.dp, above: Boolean = false, selected: Boolean = false, action: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    var held by remember { mutableStateOf(false) }
    var tooltip by remember { mutableStateOf(false) }
    LaunchedEffect(hovered, held) {
        if (hovered || held) { delay(if (held) 0 else 450); tooltip = true; if (held) { delay(1800); held = false } }
        else tooltip = false
    }
    Box(Modifier.size(size).zIndex(if (tooltip) 20f else 0f)) {
        Box(Modifier.fillMaxSize().background(if (selected) Color(0xFF416785) else if (hovered) Color(0xFF345268) else Color(0xFF23394B), RoundedCornerShape(8.dp))
            .hoverable(interactions).combinedClickable(role = Role.Button, onClick = action, onLongClick = { held = true }), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = ChromeInk, modifier = Modifier.size(21.dp))
        }
        if (tooltip) Text(label, color = ChromeInk, fontSize = 12.sp, maxLines = 3,
            modifier = Modifier.align(if (above) Alignment.BottomEnd else Alignment.TopEnd).offset(y = if (above) -size - 4.dp else size + 4.dp)
                .requiredWidthIn(min = 110.dp, max = 190.dp).background(Color(0xFF0D1B25), RoundedCornerShape(6.dp))
                .border(1.dp, Color(0xFF567084), RoundedCornerShape(6.dp)).padding(8.dp))
    }
}

internal fun detailStatus(workspace: WorkspaceState, id: String?, controller: TerminalController?, connection: String): String {
    if (workspace.isDemo) return "Demo · prompts stay on this device"
    val catalog = if (connection.startsWith("Connected")) "Connected" else connection
    val stream = when {
        id == null -> null
        workspace.session(id)?.available == false -> "Session unavailable"
        controller != null -> controller.state(id).status.substringBefore(" · use").replace(" · read-only", "")
        else -> "Waiting for live screen"
    }
    return listOfNotNull(catalog, stream, if (id != null && workspace.session(id)?.inputEnabled != true) "Read-only" else null, id?.let { controller?.input?.state(it)?.message?.takeIf(String::isNotBlank) }).joinToString(" · ")
}

@Composable
internal fun DetailFooter(status: String, onResize: ((Float) -> Unit)? = null, onRecenter: (() -> Unit)? = null, onClose: (() -> Unit)? = null) {
    val healthy = status.startsWith("Demo") || (status.startsWith("Connected") && !status.contains("disconnected", true) && !status.contains("unavailable", true))
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp).height(34.dp).zIndex(10f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(status, color = if (healthy) Color(0xFF97D8BC) else Color(0xFFFFC58F), fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onResize != null) {
            PanelActionIcon(MinusIcon, "Smaller window", above = true) { onResize(1f / 1.15f) }
            PanelActionIcon(Icons.Default.Add, "Larger window", above = true) { onResize(1.15f) }
        }
        if (onRecenter != null) PanelActionIcon(Icons.Default.Place, "Recenter window", above = true, action = onRecenter)
        if (onClose != null) PanelActionIcon(Icons.Default.Close, "Close window", above = true, action = onClose)
    }
}

@Composable
internal fun rememberDetailTip(enabled: Boolean): Pair<Boolean, () -> Unit> {
    val prefs = LocalContext.current.applicationContext.getSharedPreferences("agentspace_ui", Context.MODE_PRIVATE)
    val tipKey = if (LocalHomeMode.current) "home_detail_tip_seen" else "detail_tip_seen"
    var visible by remember { mutableStateOf(enabled && !prefs.getBoolean(tipKey, false)) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == tipKey && prefs.getBoolean(key, false)) visible = false
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return visible to { visible = false; prefs.edit().putBoolean(tipKey, true).apply() }
}

@Composable
internal fun DetailGestureGuide(onClose: () -> Unit) {
    val home = LocalHomeMode.current
    DashboardSheet("More room for your terminal", "Your controls stay at the edges.", onClose, footer = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Reset dismissed tips in Settings.", color = ChromeMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
            ActionButton("Got it", onClose)
        }
    }) {
        GestureLesson(0, "Read & reconnect", "Sessions update independently and reconnect after network interruptions. Use the pencil to type or dictate with the keyboard, then Send. Spoken text stays in your draft until you send it.")
        GestureLesson(2, "Fit, zoom & pan", "Fit shows the whole remote screen. Use the text-size icons to zoom, then drag to pan. The Mac’s terminal size stays unchanged.")
        GestureLesson(1, "Arrange your windows", if (home) "Use Quest’s window controls to move, resize or close this panel. Open another session using the icon beside its title." else "Use the footer icons to resize, recenter or close this window. Hold a session tile to move it between groups.")
    }
}

private fun lineIcon(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, pathBuilder = draw)
    }.build()
internal val MinusIcon = lineIcon("Smaller") { moveTo(5f, 12f); lineTo(19f, 12f) }
internal val SmallerTextIcon = lineIcon("Smaller text") {
    moveTo(3f, 19f); lineTo(8f, 5f); lineTo(13f, 19f); moveTo(5f, 14f); lineTo(11f, 14f)
    moveTo(15f, 12f); lineTo(22f, 12f)
}
internal val LargerTextIcon = lineIcon("Larger text") {
    moveTo(3f, 19f); lineTo(8f, 5f); lineTo(13f, 19f); moveTo(5f, 14f); lineTo(11f, 14f)
    moveTo(15f, 12f); lineTo(22f, 12f); moveTo(18.5f, 8.5f); lineTo(18.5f, 15.5f)
}
internal val FitTerminalIcon = lineIcon("Fit screen") {
    moveTo(9f, 4f); lineTo(4f, 4f); lineTo(4f, 9f); moveTo(15f, 4f); lineTo(20f, 4f); lineTo(20f, 9f)
    moveTo(4f, 15f); lineTo(4f, 20f); lineTo(9f, 20f); moveTo(15f, 20f); lineTo(20f, 20f); lineTo(20f, 15f)
    moveTo(8f, 9f); lineTo(16f, 9f); moveTo(8f, 13f); lineTo(16f, 13f); moveTo(8f, 17f); lineTo(13f, 17f)
}

internal val FavoriteOutlineIcon = lineIcon("Favourite") {
    moveTo(12f, 3f); lineTo(14.8f, 8.7f); lineTo(21f, 9.6f); lineTo(16.5f, 14f)
    lineTo(17.6f, 20.2f); lineTo(12f, 17.3f); lineTo(6.4f, 20.2f); lineTo(7.5f, 14f)
    lineTo(3f, 9.6f); lineTo(9.2f, 8.7f); close()
}
