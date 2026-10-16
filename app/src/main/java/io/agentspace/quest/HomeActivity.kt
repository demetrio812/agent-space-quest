package io.agentspace.quest

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal val LocalHomeMode = staticCompositionLocalOf { false }
internal val LocalSwitchMode = staticCompositionLocalOf<(() -> Unit)?> { null }
internal val LocalOpenSessionWindow = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** The production Home dashboard, rendered directly at the OS-provided panel size. */
open class HomeActivity : ComponentActivity() {
    private lateinit var runtime: HomeRuntime
    private val owner = System.identityHashCode(this)
    private var showConnection by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var target by mutableStateOf<HomeWindowTarget?>(null)
    private val child get() = this is HomeWindowActivity
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = HomeRuntime.get(this)
        runtime.attach(owner)
        readTarget()
        setContent {
            CompositionLocalProvider(LocalHomeMode provides true,
                LocalSwitchMode provides { safely { runtime.save(); HomeNavigation.enterImmersive(this) { runtime.leaveMode() } } },
                LocalOpenSessionWindow provides { id -> openWindow("session", id) }) {
                val workspace = runtime.workspace
                val currentSource = runtime.source
                LaunchedEffect(currentSource) {
                    if (child && target?.source != currentSource) finishAndRemoveTask()
                }
                Column(Modifier.fillMaxSize().background(Color(0xFF172736))) {
                    error?.let { message ->
                        Row(Modifier.fillMaxWidth().padding(10.dp)) {
                            Text(message, color = Color(0xFFFFC58F), modifier = Modifier.weight(1f))
                            ActionButton("Dismiss") { error = null }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        if (child) {
                            val id = target?.takeIf { it.source == runtime.source }?.let {
                                if (it.kind == "session") workspace.groupFor(it.id)?.id else workspace.workspaceWindow(it.id)?.id
                            }
                            if (id != null && workspace.group(id) != null) {
                                FloatingWindowUi(workspace, id, onClose = ::closeWindow,
                                    onCloseTab = { workspace.closeTab(id, it); runtime.save() },
                                    onResize = {}, onRecenter = {}, connectionStatus = runtime.catalog.status,
                                    terminal = runtime.terminal)
                            } else {
                                Column(Modifier.fillMaxSize().padding(24.dp)) {
                                    Text("This window is no longer available.", color = Color.White)
                                    ActionButton("Close window", ::closeWindow)
                                }
                            }
                        } else if (showConnection) {
                            ConnectionUi(runtime.catalog,
                                onConnect = { switchSource(true) }, onDemo = { switchSource(false) },
                                onClose = { showConnection = false })
                        } else DashboardUi(workspace,
                            onReset = { closeChildren(); workspace.showGrid(); workspace.selectDashboardWorkspace(null); runtime.save() },
                            onCloseAll = ::closeChildren, saveStatus = runtime.saveStatus,
                            connectionStatus = runtime.catalog.status, onConnection = { showConnection = true },
                            terminal = runtime.terminal, onOpenWorkspace = { openWindow("workspace", it) })
                    }
                }
            }
        }
        Log.i("AgentSpaceHome", "Created ${if (child) "window" else "dashboard"} task=$taskId")
    }
    private fun readTarget() {
        if (!child) return
        target = HomeWindowTarget.decode(intent.dataString)
        target?.let { runtime.bindWindow(owner, it) }
        target?.let(runtime::resolve)
        if (target == null || target?.source != runtime.source) finishAndRemoveTask()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readTarget()
    }
    override fun onStart() {
        super.onStart()
        runtime.start(owner)
        if (child && target?.source != runtime.source) finishAndRemoveTask()
    }
    override fun onStop() { runtime.stop(owner); super.onStop() }
    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runtime.detach(owner, isFinishing)
        super.onDestroy()
    }
    private fun openWindow(kind: String, id: String) = safely {
        val destination = HomeWindowTarget(runtime.source, kind, id)
        val existing = if (kind == "session") runtime.workspace.groupFor(id) else runtime.workspace.workspaceWindow(id)
        val slot = runtime.resolve(destination) ?: error("Close another window before opening this one.")
        try { HomeNavigation.openWindow(this, destination) }
        catch (e: Exception) {
            if (existing == null) runtime.workspace.closeGroup(slot)
            runtime.save()
            throw e
        }
    }
    private fun closeChildren() = safely {
        HomeNavigation.closeHomeTasks(this)
        runtime.workspace.closeAll(); runtime.save()
    }
    private fun closeWindow() {
        runtime.save(); finishAndRemoveTask()
    }
    private fun switchSource(live: Boolean) = safely {
        // Finish old tasks before changing their shared data source.
        HomeNavigation.closeHomeTasks(this)
        runtime.switchSource(live)
        showConnection = false
    }
    private fun safely(action: () -> Unit) {
        try { action() }
        catch (e: Exception) {
            Log.w("AgentSpaceHome", "Window action failed", e)
            error = "Couldn't open or update the window. Try again or close another window."
        }
    }
}

/** Each stable data URI gets its own document task in Quest Home. */
class HomeWindowActivity : HomeActivity()
