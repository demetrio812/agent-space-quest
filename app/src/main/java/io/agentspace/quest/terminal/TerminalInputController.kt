package io.agentspace.quest.terminal

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import io.agentspace.quest.connection.*
import io.agentspace.quest.sessions.SessionKey
import kotlinx.coroutines.*

data class TerminalInputState(val sending: Boolean = false, val message: String = "")

/** One explicit send per session at a time; input is never automatically replayed. */
class TerminalInputController(context: Context) {
    private val profiles = ConnectionProfileStore(context)
    private val keys = DeviceKeyStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val states = mutableStateMapOf<String, TerminalInputState>()
    private val clients = mutableMapOf<String, SshConnection>()
    private var generation = 0
    fun state(id: String) = states[id] ?: TerminalInputState()
    fun send(id: String, text: String, onSent: () -> Unit) {
        if (state(id).sending) return
        val key = SessionKey.decode(id) ?: return
        val profile = profiles.load() ?: return
        if (profile.id != key.connectionId) return
        try { SshConnection.inputCommand(key, text) } catch (error: IllegalArgumentException) {
            states[id] = TerminalInputState(message = error.message.orEmpty()); return
        }
        val token = generation
        val client = SshConnection(profile, keys)
        clients[id] = client
        states[id] = TerminalInputState(true, "Sending…")
        scope.launch {
            try {
                withContext(Dispatchers.IO) { client.sendText(key, text) }
                if (token == generation) { states[id] = TerminalInputState(message = "Sent"); onSent() }
            } catch (error: Exception) {
                if (token == generation) states[id] = TerminalInputState(message = "Send not confirmed · check the terminal before trying again. Draft kept.")
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { client.close() }
                if (clients[id] === client) clients.remove(id)
            }
        }
    }
    fun clear() {
        generation++
        clients.values.toList().forEach { client -> scope.launch(NonCancellable + Dispatchers.IO) { client.close() } }
        clients.clear(); states.clear()
    }
    fun destroy() { clear(); scope.cancel() }
}
