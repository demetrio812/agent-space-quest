package io.agentspace.quest

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.agentspace.quest.connection.ConnectionException
import io.agentspace.quest.connection.ConnectionFailure
import io.agentspace.quest.connection.ConnectionProfileStore
import io.agentspace.quest.connection.DeviceKeyStore
import io.agentspace.quest.sessions.HerdrBackend
import kotlinx.coroutines.*

/** Only the latest connection generation can publish into the workspace. */
class CatalogController(context: Context, private val workspace: WorkspaceState, private val onCatalog: (List<String>) -> Unit = {}) {
    val profiles = ConnectionProfileStore(context)
    val keys = DeviceKeyStore(context)
    var status by mutableStateOf("Demo · local simulated sessions")
        private set
    var connected by mutableStateOf(false)
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var task: Job? = null
    private var backend: HerdrBackend? = null
    private var generation = 0

    fun start() {
        stop()
        if (workspace.isDemo) { status = "Demo · local simulated sessions"; return }
        val profile = profiles.load()
        if (profile == null) { status = "Set up your Mac connection"; return }
        val current = generation
        task = scope.launch {
            var delayMs = 2000L
            status = "Connecting to ${profile.host}…"
            val client = HerdrBackend(profile, keys)
            backend = client
            try {
                while (isActive && current == generation) {
                    try {
                        val sessions = withContext(Dispatchers.IO) { client.listSessions() }
                        if (current != generation) break
                        workspace.applyCatalog(sessions.map { entry ->
                            SessionSnapshot(entry.key.encode(), if (entry.title == entry.key.terminalId) "${entry.agent} · ${entry.project}" else entry.title, entry.agent, entry.project, entry.reportedStatus,
                                emptyList(), workspaceId = entry.key.copy(terminalId = "workspace:" + entry.workspaceId.ifBlank { entry.project }).encode(),
                                workspaceLabel = entry.workspaceLabel.ifBlank { entry.project }, terminalTitle = entry.terminalTitle, inputEnabled = entry.inputEnabled)
                        })
                        onCatalog(sessions.map { it.key.encode() })
                        connected = true
                        status = "Connected · ${sessions.size} Herdr sessions · SSH"
                        delayMs = 2000
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        if (current != generation) break
                        connected = false
                        android.util.Log.w("AgentSpaceCatalog", "Catalog failed generation=$current", error)
                        val message = error.message.orEmpty().take(160)
                        status = "${if (workspace.sessions.isEmpty()) "Connection unavailable" else "Showing saved data"} · $message"
                        if (error is ConnectionException && error.reason in setOf(ConnectionFailure.AUTHENTICATION, ConnectionFailure.HOST_KEY)) break
                        delayMs = (delayMs * 2).coerceAtMost(30000)
                    }
                    delay(delayMs)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { client.close() }
                if (backend === client) backend = null
            }
        }
    }

    fun stop() {
        generation++
        task?.cancel(); task = null
        connected = false
        val old = backend; backend = null
        if (old != null) scope.launch(Dispatchers.IO) { old.close() }
    }
    fun destroy() { stop(); scope.cancel() }
}
