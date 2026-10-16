package io.agentspace.quest.sessions

import org.json.JSONArray
import java.util.Base64

data class SessionKey(
    val backendId: String,
    val connectionId: String,
    val serverIdentity: String,
    val terminalId: String,
) {
    fun encode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        JSONArray(listOf(backendId, connectionId, serverIdentity, terminalId)).toString().toByteArray(Charsets.UTF_8),
    )

    companion object {
        fun decode(encoded: String): SessionKey? = try {
            require(encoded.length in 1..8192)
            val fields = JSONArray(String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8))
            require(fields.length() == 4)
            val values = (0..3).map { index ->
                (fields.get(index) as? String)?.also { require(validIdentity(it)) }
                    ?: error("Invalid identity")
            }
            SessionKey(values[0], values[1], values[2], values[3])
        } catch (_: Exception) { null }
    }
}

internal fun validIdentity(value: String): Boolean = value.isNotBlank() && value.length <= 512 &&
    value.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }

data class SessionInfo(
    val key: SessionKey,
    val paneId: String,
    val title: String,
    val project: String,
    val agent: String,
    val reportedStatus: String,
    val workspaceId: String = "",
    val workspaceLabel: String = "",
    val terminalTitle: String = "",
    val inputEnabled: Boolean = false,
)
