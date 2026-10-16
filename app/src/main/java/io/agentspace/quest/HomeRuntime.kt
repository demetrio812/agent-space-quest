package io.agentspace.quest

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.agentspace.quest.terminal.TerminalController
import java.io.File
import java.security.MessageDigest

/** One transport owner for every Home task; never holds an Activity context. */
internal class HomeRuntime private constructor(private val context: Context) {
    val workspace = WorkspaceState()
    private val favorites = FavoriteStore(context)
    val terminal = TerminalController(context)
    val catalog = CatalogController(context, workspace) { terminal.updateCatalog(it) }
    var source by mutableStateOf(currentSource())
        private set
    var saveStatus by mutableStateOf("Quest manages window positions")
        private set
    private val visibility = HomeVisibility()
    private val activities = mutableSetOf<Int>()
    private val windows = HomeWindowOwners()
    private var leavingMode = false
    private val main = Handler(Looper.getMainLooper())
    private var store = makeStore()
    private val ticker = object : Runnable {
        override fun run() {
            workspace.tick(); save()
            if (visibility.active) main.postDelayed(this, 1800)
        }
    }
    init { load() }
    private fun currentSource() = if (catalog.profiles.enabled) "live:${catalog.profiles.load()?.id ?: "unconfigured"}" else "demo"
    private fun makeStore(): LayoutFileStore {
        val hash = MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
        return LayoutFileStore(File(context.filesDir, "home-layout-$hash.properties"))
    }
    private fun load() {
        workspace.setSource(source == "demo")
        favorites.bind(workspace, source)
        try { store.load()?.let { workspace.restoreLayoutState(it.workspace) } }
        catch (e: Exception) { Log.w("AgentSpaceHome", "Could not restore Home selections", e) }
    }
    fun attach(owner: Int) { activities.add(owner) }
    fun start(owner: Int) {
        refreshSource()
        favorites.bind(workspace, source)
        leavingMode = false
        if (visibility.start(owner)) {
            catalog.start(); terminal.resume()
            main.removeCallbacks(ticker); main.postDelayed(ticker, 1800)
        }
    }
    fun stop(owner: Int) {
        save()
        if (visibility.stop(owner)) {
            catalog.stop(); terminal.pause(); main.removeCallbacks(ticker)
        }
    }
    fun bindWindow(owner: Int, target: HomeWindowTarget) { windows.attach(owner, target) }
    fun leaveMode() {
        save(); leavingMode = true
        catalog.stop(); terminal.pause(); main.removeCallbacks(ticker)
    }
    fun detach(owner: Int, finishing: Boolean) {
        val target = windows.detach(owner)
        if (finishing && !leavingMode && target?.source == source) {
            val group = if (target.kind == "session") workspace.groupFor(target.id) else workspace.workspaceWindow(target.id)
            group?.let { workspace.closeGroup(it.id) }
        }
        stop(owner); activities.remove(owner)
        if (activities.isEmpty()) {
            catalog.destroy(); terminal.destroy(); main.removeCallbacksAndMessages(null)
            if (instance === this) instance = null
        }
    }
    fun refreshSource() {
        val next = currentSource()
        if (source == next) return
        save(); catalog.stop(); terminal.clear()
        source = next; store = makeStore(); load()
        if (visibility.active) { catalog.start(); terminal.resume() }
    }
    fun switchSource(live: Boolean) {
        catalog.profiles.enabled = live
        refreshSource()
        // Rechecking an unchanged connection also restarts a failed catalog request.
        if (visibility.active) catalog.start()
    }
    fun save() {
        try {
            store.save(SavedLayout(workspace.layoutState(), emptyMap()))
            saveStatus = "Quest manages window positions"
        } catch (e: Exception) {
            saveStatus = "Couldn't save Home selections"
            Log.w("AgentSpaceHome", saveStatus, e)
        }
    }
    fun resolve(target: HomeWindowTarget): Int? = workspace.resolveHomeTarget(target, source).also { save() }
    companion object {
        private var instance: HomeRuntime? = null
        fun get(context: Context): HomeRuntime = instance ?: HomeRuntime(context.applicationContext).also { instance = it }
    }
}
