package io.agentspace.quest.terminal
import io.agentspace.quest.connection.SshConnection
import io.agentspace.quest.sessions.SessionKey
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
class TerminalInputTest {
    private val key = SessionKey("herdr", "c", "a".repeat(64), "terminal:1")
    @Test fun unicodeMultilineAndShellCharactersAreEncodedAsData() {
        val text = "hello\n\"café\"; `echo example`"
        val parts = SshConnection.inputCommand(key, text).split(' ')
        assertEquals(4, parts.size)
        assertEquals("agentspace-send-v1", parts[0])
        assertEquals(text, String(Base64.getDecoder().decode(parts[3]), Charsets.UTF_8))
    }
    @Test fun boundsControlsAndIdentityAreCheckedBeforeTransport() {
        for (text in listOf("", "  ", "a\u001bb", "a\rb", "a\u0000b", "é".repeat(8193)))
            assertThrows(IllegalArgumentException::class.java) { SshConnection.inputCommand(key, text) }
        assertThrows(IllegalArgumentException::class.java) { SshConnection.inputCommand(key.copy(terminalId="x;id"), "hello") }
    }
}
