package io.agentspace.quest.sessions

import java.io.Closeable

/** Synchronous bounded discovery. Call from a background dispatcher. */
interface SessionBackend : Closeable {
    fun listSessions(): List<SessionInfo>
}
