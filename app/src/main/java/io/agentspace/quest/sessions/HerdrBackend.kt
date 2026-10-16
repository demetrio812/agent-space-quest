package io.agentspace.quest.sessions

import io.agentspace.quest.connection.ConnectionProfile
import io.agentspace.quest.connection.DeviceKeyStore
import io.agentspace.quest.connection.SshConnection

class HerdrBackend(profile: ConnectionProfile, credentials: DeviceKeyStore) : SessionBackend {
    private val connectionId = profile.id
    private val connection = SshConnection(profile, credentials)
    override fun listSessions(): List<SessionInfo> = HerdrProtocol.parseCatalog(connection.readCatalog(), connectionId)
    override fun close() = connection.close()
}
