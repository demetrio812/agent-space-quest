package io.agentspace.quest.connection

import org.junit.Assert.*
import org.junit.Test

class ConnectionProfileTest {
    private val id = "8c85cd73-144f-4a48-9f26-9a8f000cc686"
    private fun profile(host: String = "mac.local", port: Int = 22, user: String = "example") =
        ConnectionProfile(id, host, port, user, "${if (port == 22) host else "[$host]:$port"} ssh-ed25519 AAAA")

    @Test fun hostAndPortAreTransportValuesWithNoShellSyntax() {
        profile().validate()
        profile("192.0.2.24", 2222).validate()
        profile("fe80::1%en0").validate()
        profile("::1").validate()
        listOf("", "mac.local;id", "mac.local\nother", "-oProxyCommand=x", " user@mac").forEach {
            assertThrows(IllegalArgumentException::class.java) { profile(it).validate() }
        }
        assertThrows(IllegalArgumentException::class.java) { profile(port = 0).validate() }
        assertThrows(IllegalArgumentException::class.java) { profile(user = "bad\nname").validate() }
    }
}
