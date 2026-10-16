package io.agentspace.quest.terminal

import android.content.Context
import android.util.Log
import androidx.compose.runtime.*
import io.agentspace.quest.connection.*
import io.agentspace.quest.sessions.SessionKey
import kotlinx.coroutines.*

/** One screen and transport per session; all decisions and view state are owned on Main. */
data class TerminalViewState(
    val buffer: ObservedTerminalBuffer? = null,
    val revision: Long = 0,
    val status: String = "Waiting for live screen…",
    val previewLines: List<String> = emptyList(),
    val closed: Boolean = false,
)

class TerminalController(context: Context) {
    val input = TerminalInputController(context)
    private val profiles = ConnectionProfileStore(context)
    private val keys = DeviceKeyStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val registry = ObservationRegistry(16)
    private val states = mutableStateMapOf<String, TerminalViewState>()
    private data class Stream(val generation: Long, val client: SshConnection, val model: ObservedTerminalBuffer, val job: Job)
    private val streams = mutableMapOf<String, Stream>()
    private val stopping = mutableSetOf<String>()
    private var serial = 0L
    private var resumed = false
    private var lastStartAt = 0L
    private var admission: Job? = null
    private var redraw: Job? = null
    private val retries = mutableMapOf<String, Job>()
    private val retryCounts = mutableMapOf<String, Int>()

    var activeId by mutableStateOf<String?>(null)
        private set
    val buffer: ObservedTerminalBuffer? get() = activeId?.let { state(it).buffer }
    val revision: Long get() = activeId?.let { state(it).revision } ?: 0
    val status: String get() = activeId?.let { state(it).status } ?: "Select a session to watch"
    val streamCount: Int get() = streams.size
    val liveCount: Int get() = states.values.count { it.status.startsWith("Live") }
    val bufferCount: Int get() = states.values.count { it.buffer != null }

    fun state(id: String): TerminalViewState = states[id] ?: when {
        registry.closed(id) -> TerminalViewState(status = "Session closed · use Reconnect to refresh", closed = true)
        registry.halted(id) -> TerminalViewState(status = "Preview unavailable · use Reconnect to refresh")
        registry.queued(id) -> TerminalViewState(status = "Queued for live preview")
        else -> TerminalViewState()
    }
    fun updateCatalog(ids: List<String>) { registry.catalog(ids); reconcile() }
    fun retain(id: String, preview: Boolean = false) { registry.retain(id, preview); reconcile() }
    fun release(id: String, preview: Boolean = false) { registry.release(id, preview); reconcile() }

    fun watch(id: String, force: Boolean = false) {
        if (SessionKey.decode(id) == null) return
        activeId = id
        registry.watch(id)
        if (!state(id).closed) registry.unhalt(id)
        if (force) {
            retries.remove(id)?.cancel(); retryCounts.remove(id)
            registry.unhalt(id)
            stop(id)
            states[id] = TerminalViewState(status = "Connecting to live screen…")
        }
        reconcile()
    }

    fun resume() {
        resumed = true
        // A lifecycle pause is not a terminal failure. Each admitted session gets a fresh snapshot.
        states.keys.filter { !state(it).closed }.forEach(registry::unhalt)
        reconcile()
        if (redraw == null) redraw = scope.launch {
            while (isActive) { publish(); delay(33) }
        }
    }
    fun pause() {
        resumed = false
        admission?.cancel(); admission = null
        retries.values.forEach { it.cancel() }; retries.clear(); retryCounts.clear()
        streams.keys.toList().forEach(::stop)
        states.keys.toList().forEach { id ->
            val old = state(id)
            if (!old.closed) states[id] = old.copy(status = "Paused · reconnects when the app resumes")
        }
        redraw?.cancel(); redraw = null
    }
    fun clear() {
        input.clear()
        val wasResumed = resumed
        pause()
        registry.clear()
        states.clear()
        oldPreviewTick.clear()
        previewRevision.clear()
        activeId = null
        if (wasResumed) resume()
    }
    fun destroy() { clear(); input.destroy(); scope.cancel() }

    private fun reconcile() {
        registry.evictions().forEach { id ->
            stop(id)
            states[id]?.let { old -> if (!old.closed) states[id] = old.copy(status = "Queued for live preview") }
        }
        prune()
        if (!resumed || admission?.isActive == true) return
        admission = scope.launch {
            while (resumed) {
                val id = registry.starts().firstOrNull() ?: break
                if (streams.size >= 16 || streams.containsKey(id)) break // wait for cleanup
                val now = android.os.SystemClock.elapsedRealtime()
                val wait = (lastStartAt + 350 - now).coerceAtLeast(0)
                if (wait > 0) delay(wait)
                if (!resumed || id !in registry.starts()) continue
                start(id)
                lastStartAt = android.os.SystemClock.elapsedRealtime()
            }
        }
    }

    private fun start(id: String) {
        val key = SessionKey.decode(id) ?: run { registry.halt(id); return }
        val profile = profiles.load()
        if (profile == null || key.backendId != "herdr" || key.connectionId != profile.id) {
            registry.halt(id)
            states[id] = state(id).copy(status = "Connection unavailable")
            return
        }
        val model = ObservedTerminalBuffer()
        val client = SshConnection(profile, keys)
        val token = ++serial
        oldPreviewTick.remove(id)
        previewRevision.remove(id)
        registry.admitted(id)
        states[id] = state(id).copy(status = "Connecting to live screen…")
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            var closed: ObserveEvent.Closed? = null
            var failure: Exception? = null
            try {
                client.observe(key) { event ->
                    when (event) {
                        is ObserveEvent.Frame -> try { model.accept(event) } catch (_: IllegalArgumentException) {
                            throw ObserveProtocolException("Terminal frame continuity was lost.")
                        }
                        is ObserveEvent.Closed -> closed = event
                    }
                }
            } catch (_: CancellationException) { }
            catch (error: Exception) { failure = error }
            finally {
                // Cancellation must finish transport cleanup before another stream can use this ID.
                withContext(NonCancellable + Dispatchers.IO) { client.close() }
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    val stream = streams[id]
                    if (stream?.generation == token) {
                        streams.remove(id)
                        registry.stopped(id)
                        if (resumed && id !in stopping) {
                            val old = state(id)
                            val retry = closed == null && retryableObservation(failure)
                            val attempt = retryCounts[id] ?: 0
                            val retryDelay = observationRetryDelay(attempt)
                            if (android.os.SystemClock.elapsedRealtime() - startedAt > 30000 && model.revision > 0) retryCounts.remove(id)
                            states[id] = when {
                                closed != null -> old.copy(revision = model.revision, previewLines = model.previewLines(), status = "Session closed · ${closed!!.reason.filterNot { it.isISOControl() }.take(120)}", closed = true)
                                failure != null -> {
                                    Log.w("AgentSpaceTerminal", "observe failed id=$id generation=$token", failure)
                                    old.copy(status = when (val error = failure) {
                                        is ObserveProtocolException -> "Invalid terminal update · use Reconnect to refresh"
                                        is ConnectionException -> if (error.reason != ConnectionFailure.NETWORK) error.message.orEmpty() else "Stream disconnected · reconnecting in ${retryDelay / 1000}s…"
                                        else -> if (retry) "Stream disconnected · reconnecting in ${retryDelay / 1000}s…" else "Stream stopped · use Reconnect to refresh"
                                    })
                                }
                                else -> old
                            }
                            registry.halt(id, closed != null)
                            if (retry) {
                                retryCounts[id] = (retryCounts[id] ?: 0) + 1
                                retries[id] = scope.launch {
                                    delay(retryDelay + kotlin.random.Random.nextLong(0, 500))
                                    retries.remove(id)
                                    if (resumed && registry.retained(id) && !registry.closed(id)) {
                                        registry.unhalt(id); reconcile()
                                    }
                                }
                            }
                        }
                        stopping.remove(id)
                        reconcile()
                    }
                }
            }
        }
        streams[id] = Stream(token, client, model, job)
    }

    private fun stop(id: String) {
        val stream = streams[id] ?: return
        if (!stopping.add(id)) return
        // Leave the stream admitted until its worker has fully closed. Its finalizer then re-admits if needed.
        stream.job.cancel()
        // The blocking SSH read needs an independent close even if destroy cancels scope now.
        scope.launch(NonCancellable + Dispatchers.IO) { stream.client.close() }
    }

    private fun publish() {
        streams.forEach { (id, stream) ->
            if (id in stopping) return@forEach
            val old = state(id)
            val model = stream.model
            if (model.revision == 0L) return@forEach
            val tick = android.os.SystemClock.elapsedRealtime() / 500
            val previewDue = model.revision > (previewRevision[id] ?: -1) && tick != oldPreviewTick[id]
            if (old.buffer !== model || old.revision != model.revision || previewDue) {
                val preview = if (previewDue) {
                    oldPreviewTick[id] = tick
                    previewRevision[id] = model.revision
                    model.previewLines()
                } else old.previewLines
                states[id] = old.copy(buffer = model, revision = model.revision, previewLines = preview, status = "Live")
            }
        }
    }
    private val oldPreviewTick = mutableMapOf<String, Long>()
    private val previewRevision = mutableMapOf<String, Long>()
    private fun prune() {
        states.keys.toList().forEach { id ->
            if (!registry.retained(id) && id !in streams) { retries.remove(id)?.cancel(); retryCounts.remove(id); states.remove(id); oldPreviewTick.remove(id); previewRevision.remove(id) }
        }
        // The catalog can be arbitrarily long. Keep live models and a small recent cache only.
        val cached = states.keys.filter { it !in streams && it != activeId }
        cached.take((cached.size - 48).coerceAtLeast(0)).forEach { id ->
            states.remove(id); oldPreviewTick.remove(id); previewRevision.remove(id)
        }
    }
}
