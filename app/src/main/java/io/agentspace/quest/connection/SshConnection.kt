package io.agentspace.quest.connection

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import io.agentspace.quest.sessions.HerdrProtocol
import io.agentspace.quest.sessions.SessionKey
import io.agentspace.quest.terminal.ObserveEvent
import io.agentspace.quest.terminal.ObserveProtocol
import io.agentspace.quest.terminal.ObserveProtocolException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

enum class ConnectionFailure { AUTHENTICATION, HOST_KEY, NETWORK, CATALOG, CLOSED }
class ConnectionException(val reason: ConnectionFailure, message: String) : IOException(message)
data class HostIdentity(val knownHost: String, val fingerprint: String)

/** Fixed catalog, observe and explicit input commands. Each observer uses its own connection. */
class SshConnection(private val profile: ConnectionProfile, private val credentials: DeviceKeyStore) : Closeable {
    private val stateLock = Any()
    @Volatile private var closed = false
    @Volatile private var session: Session? = null
    @Volatile private var client: JSch? = null
    @Volatile private var channel: ChannelExec? = null

    fun readCatalog(): String = executeBounded(CATALOG_COMMAND)

    fun sendText(key: SessionKey, text: String) {
        require(key.connectionId == profile.id)
        val response = executeBounded(inputCommand(key, text), 12000)
        if (org.json.JSONObject(response).opt("sent") != true) throw IOException("Input was not acknowledged.")
    }

    @Synchronized private fun executeBounded(request: String, timeoutMs: Int = COMMAND_TIMEOUT_MS): String {
        if (closed) throw ConnectionException(ConnectionFailure.CLOSED, "Connection closed.")
        var command: ChannelExec? = null
        try {
            val connected = connect()
            command = connected.openChannel("exec") as ChannelExec
            synchronized(stateLock) {
                if (closed) throw ConnectionException(ConnectionFailure.CLOSED, "Connection closed.")
                channel = command
            }
            command.setCommand(request)
            command.setInputStream(null)
            command.setPty(false)
            command.setErrStream(BoundedDiscardStream(), true)
            val input = command.inputStream
            val output = ByteArrayOutputStream()
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            command.connect(COMMAND_TIMEOUT_MS)
            val buffer = ByteArray(8192)
            while (true) {
                if (closed || Thread.currentThread().isInterrupted)
                    throw ConnectionException(ConnectionFailure.CLOSED, "Connection closed.")
                if (System.nanoTime() >= deadline)
                    throw ConnectionException(ConnectionFailure.NETWORK, "The Mac request timed out.")
                val available = input.available()
                if (available > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size, available))
                    if (count > 0) {
                        if (output.size() + count > HerdrProtocol.MAX_RESPONSE_BYTES)
                            throw ConnectionException(ConnectionFailure.CATALOG, "Catalog exceeds the 2 MiB response limit.")
                        output.write(buffer, 0, count)
                    }
                    continue
                }
                if (command.isClosed) break
                if (!connected.isConnected) throw ConnectionException(ConnectionFailure.NETWORK, "Connection to the Mac was lost.")
                Thread.sleep(10)
            }
            if (command.exitStatus != 0)
                throw ConnectionException(ConnectionFailure.CATALOG, "The Mac rejected the request (exit ${command.exitStatus}). Check the headset permissions.")
            if (output.size() == 0)
                throw ConnectionException(ConnectionFailure.CATALOG, "The Mac returned no acknowledgement.")
            return output.toString("UTF-8")
        } catch (error: Exception) {
            disconnectTransport()
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw classify(error)
        } finally {
            command?.disconnect()
            synchronized(stateLock) { if (channel === command) channel = null }
        }
    }

    /** Blocking IO; close() disconnects the channel and interrupts a waiting read. Never exposes terminal input. */
    @Synchronized fun observe(key: SessionKey, onEvent: (ObserveEvent) -> Unit) {
        require(key.connectionId == profile.id) { "Terminal belongs to another connection." }
        val request = observeCommand(key)
        if (closed) throw ConnectionException(ConnectionFailure.CLOSED, "Connection closed.")
        var command: ChannelExec? = null
        try {
            val connected = connect()
            // SSH keepalives detect dead peers while allowing indefinitely quiet terminals.
            connected.serverAliveInterval = 15000
            connected.serverAliveCountMax = 3
            command = connected.openChannel("exec") as ChannelExec
            synchronized(stateLock) {
                if (closed) throw ConnectionException(ConnectionFailure.CLOSED, "Connection closed.")
                channel = command
            }
            command.setCommand(request)
            command.setInputStream(null)
            command.setPty(false)
            command.setErrStream(BoundedDiscardStream(), true)
            val input = command.inputStream
            command.connect(COMMAND_TIMEOUT_MS)
            ObserveProtocol.readLines(input) { event ->
                if (closed || Thread.currentThread().isInterrupted)
                    throw ConnectionException(ConnectionFailure.CLOSED, "Connection closed.")
                onEvent(event)
            }
        } catch (error: Exception) {
            if (closed || Thread.currentThread().isInterrupted)
                throw ConnectionException(ConnectionFailure.CLOSED, "Connection closed.")
            if (error is ObserveProtocolException) throw error
            throw classify(error)
        } finally {
            command?.disconnect()
            disconnectTransport()
        }
    }

    private fun connect(): Session {
        session?.takeIf { it.isConnected }?.let { return it }
        disconnectTransport()
        profile.validate()
        val jsch = JSch()
        jsch.setKnownHosts(ByteArrayInputStream((profile.knownHost.trim() + "\n").toByteArray(Charsets.UTF_8)))
        credentials.configureIdentity(jsch)
        val connecting = jsch.getSession(profile.user, profile.host, profile.port)
        configure(connecting)
        synchronized(stateLock) {
            if (closed) {
                jsch.removeAllIdentity()
                throw ConnectionException(ConnectionFailure.CLOSED, "Connection closed.")
            }
            client = jsch
            session = connecting
        }
        try {
            connecting.connect(CONNECT_TIMEOUT_MS)
        } finally {
            // close() may run before JSch has installed its connecting socket.
            if (closed) connecting.disconnect()
        }
        if (closed) throw ConnectionException(ConnectionFailure.CLOSED, "Connection closed.")
        return connecting
    }

    override fun close() {
        synchronized(stateLock) { closed = true }
        disconnectTransport()
    }

    private fun disconnectTransport() {
        synchronized(stateLock) {
            channel?.disconnect()
            channel = null
            session?.disconnect()
            session = null
            client?.removeAllIdentity()
            client = null
        }
    }

    private class BoundedDiscardStream : OutputStream() {
        private var count = 0L
        override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            count += length
            if (count > 64 * 1024) throw IOException("SSH error output limit exceeded")
        }
    }

    companion object {
        const val CATALOG_COMMAND = "agentspace-catalog-v1"
        private val serverIdentityPattern = Regex("[0-9a-f]{64}")
        private val terminalIdPattern = Regex("[A-Za-z0-9_.:@%+/][A-Za-z0-9_.:@%+/-]{0,255}")

        fun observeCommand(key: SessionKey): String {
            require(key.backendId == "herdr" && serverIdentityPattern.matches(key.serverIdentity)) { "Invalid Herdr server identity." }
            require(terminalIdPattern.matches(key.terminalId)) { "Invalid Herdr terminal identity." }
            return "agentspace-observe-v1 ${key.serverIdentity} ${key.terminalId}"
        }

        fun inputCommand(key: SessionKey, text: String): String {
            observeCommand(key) // Same strict server and terminal identity checks.
            require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= 16384) { "Use 1–16,384 bytes of text." }
            require(text.none { (it.code < 32 && it != '\n' && it != '\t') || it.code in 127..159 }) { "Terminal control characters are not allowed." }
            return "agentspace-send-v1 ${key.serverIdentity} ${key.terminalId} ${Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))}"
        }

        private const val CONNECT_TIMEOUT_MS = 8000
        private const val COMMAND_TIMEOUT_MS = 5000

        private fun configure(session: Session) {
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("PreferredAuthentications", "publickey")
            session.setConfig("server_host_key", "rsa-sha2-512,rsa-sha2-256")
            session.setConfig("PubkeyAcceptedAlgorithms", "rsa-sha2-512,rsa-sha2-256")
            session.timeout = CONNECT_TIMEOUT_MS
            session.serverAliveInterval = 15000
            session.serverAliveCountMax = 1
        }

        /** Captures a cryptographically presented key and rejects it before authentication. The caller must compare its fingerprint before saving. */
        fun probeHostKey(host: String, port: Int): HostIdentity {
            ConnectionProfile.validateEndpoint(host, port)
            val jsch = JSch()
            var captured: ByteArray? = null
            jsch.hostKeyRepository = object : HostKeyRepository {
                override fun check(hostname: String?, key: ByteArray): Int {
                    captured = key.copyOf()
                    return HostKeyRepository.NOT_INCLUDED
                }
                override fun add(hostkey: HostKey?, userInfo: UserInfo?) = Unit
                override fun remove(host: String?, type: String?) = Unit
                override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
                override fun getKnownHostsRepositoryID(): String = "untrusted-probe"
                override fun getHostKey(): Array<HostKey> = emptyArray()
                override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
            }
            val session = jsch.getSession("agentspace-host-probe", host, port)
            configure(session)
            try {
                session.connect(CONNECT_TIMEOUT_MS)
            } catch (error: Exception) {
                if (captured == null) throw classify(error)
            } finally { session.disconnect() }
            val bytes = captured ?: throw ConnectionException(ConnectionFailure.HOST_KEY, "The Mac did not provide a usable SSH host key.")
            val key = HostKey(ConnectionProfile.hostToken(host, port), bytes)
            val fingerprint = Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
            return HostIdentity("${key.host} ${key.type} ${key.key}", "SHA256:$fingerprint")
        }

        fun classify(error: Exception): ConnectionException {
            android.util.Log.w("AgentSpaceSsh", "Transport failure: ${error.javaClass.simpleName}", error)
            if (error is ConnectionException) return error
            val message = error.message.orEmpty().lowercase()
            return when {
                "hostkey" in message || "host key" in message || "knownhosts" in message ->
                    ConnectionException(ConnectionFailure.HOST_KEY, "The Mac SSH host key is unrecognized or changed. Verify its fingerprint and pair again.")
                "auth fail" in message || "auth cancel" in message || "authentication" in message ->
                    ConnectionException(ConnectionFailure.AUTHENTICATION, "SSH authentication was rejected. Install this device's public key for the selected Mac user.")
                else -> ConnectionException(ConnectionFailure.NETWORK, "Connection to the Mac was lost or unavailable. Check its address, Wi-Fi and Remote Login, then retry.")
            }
        }
    }
}
