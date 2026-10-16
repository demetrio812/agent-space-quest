package io.agentspace.quest.connection

import java.util.UUID

data class ConnectionProfile(
    val id: String,
    val host: String,
    val port: Int,
    val user: String,
    val knownHost: String,
) {
    fun validate() {
        require(UUID.fromString(id).toString().equals(id, ignoreCase = true)) { "Invalid connection identity." }
        validateEndpoint(host, port)
        require(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}").matches(user)) { "Enter a valid SSH username." }
        require(knownHost.isNotBlank() && knownHost.length <= 16384 && !knownHost.contains('\n') && !knownHost.contains('\r')) {
            "Pair and verify a single SSH host key first."
        }
        val fields = knownHost.trim().split(Regex("\\s+"))
        require(fields.size >= 3 && fields[0] == hostToken(host, port)) { "The pinned SSH key does not match this host and port." }
    }

    companion object {
        fun validateEndpoint(host: String, port: Int) {
            require(host.length in 1..253 && Regex("[A-Za-z0-9:][A-Za-z0-9.:%_-]*").matches(host)) { "Enter a hostname or IP address without spaces." }
            require(port in 1..65535) { "SSH port must be between 1 and 65535." }
        }
        fun hostToken(host: String, port: Int): String = if (port == 22) host else "[$host]:$port"
    }
}
