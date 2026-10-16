package io.agentspace.quest

import io.agentspace.quest.connection.SshConnection
import io.agentspace.quest.sessions.SessionKey
import io.agentspace.quest.terminal.ObserveEvent
import io.agentspace.quest.terminal.ObserveProtocol
import io.agentspace.quest.terminal.ObserveProtocolException
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64

class ObserveProtocolTest {
    private fun frame(bytes: String = "G1sySg==", width: String = "120", height: String = "40", seq: String = "1", full: String = "true") =
        """{"bytes":"$bytes","encoding":"ansi","full":$full,"height":$height,"seq":$seq,"type":"terminal.frame","width":$width}"""
    private val closed = """{"type":"terminal.closed","reason":"exited"}"""

    @Test fun decodesActualHerdrAnsiFrameWithoutChangingControlBytes() {
        val result = ObserveProtocol.parseLine(frame()) as ObserveEvent.Frame
        assertEquals(120, result.width)
        assertEquals(40, result.height)
        assertEquals(1L, result.seq)
        assertTrue(result.full)
        assertArrayEquals(byteArrayOf(27, 91, 50, 74), result.bytes)
        assertFalse((ObserveProtocol.parseLine(frame(full = "false")) as ObserveEvent.Frame).full)
    }

    @Test fun validatesStrictTypesGeometryEncodingAndBase64() {
        val invalid = listOf("", "{}", "[]", frame(width="\"120\""), frame(width="120.0"), frame(width="0"), frame(width="513"), frame(height="257"), frame(width="512", height="256"), frame(seq="-1"), frame(seq="1.5"), frame(seq="9223372036854775808"), frame(full="1"), frame(bytes="!!"), frame(bytes="YQ"), frame().replace("ansi", "base64"), frame().replace("terminal.frame", "future"), frame() + "garbage")
        invalid.forEach { assertThrows(ObserveProtocolException::class.java) { ObserveProtocol.parseLine(it) } }
    }

    @Test fun appliesDecodedPayloadAndLineLimitsBeforeEmission() {
        val huge = Base64.getEncoder().encodeToString(ByteArray(ObserveProtocol.MAX_FRAME_BYTES + 1))
        assertThrows(ObserveProtocolException::class.java) { ObserveProtocol.parseLine(frame(bytes=huge)) }
        var emitted = 0
        assertThrows(ObserveProtocolException::class.java) {
            ObserveProtocol.readLines(ByteArrayInputStream("x".repeat(ObserveProtocol.MAX_LINE_BYTES + 1).toByteArray())) { emitted++ }
        }
        assertEquals(0, emitted)
    }

    @Test fun handlesEveryChunkBoundaryAndCrLfAndStopsAtClosed() {
        val bytes = (frame() + "\r\n" + closed + "\r\ninvalid\n").toByteArray()
        for (chunkSize in listOf(1, 2, 7, 1024)) {
            val events = mutableListOf<ObserveEvent>()
            val stream = object : ByteArrayInputStream(bytes) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(chunkSize, len))
            }
            ObserveProtocol.readLines(stream, events::add)
            assertEquals(2, events.size)
            assertEquals("exited", (events.last() as ObserveEvent.Closed).reason)
        }
    }

    @Test fun eofAndBlankRecordAreFailuresInsteadOfNormalSessionClosure() {
        for (text in listOf("", frame(), frame() + "\n", "\n", frame() + "\n\n")) {
            assertThrows(IOException::class.java) { ObserveProtocol.readLines(ByteArrayInputStream(text.toByteArray())) {} }
        }
    }

    @Test fun commandOnlyContainsValidatedServerAndTerminalIdentity() {
        val key = SessionKey("herdr", "connection", "a".repeat(64), "terminal:1/%0")
        assertEquals("agentspace-observe-v1 ${key.serverIdentity} terminal:1/%0", SshConnection.observeCommand(key))
        for (terminal in listOf("-option", "a\nb", "a b", "a;id", "a$(id)", "", "a".repeat(257))) {
            assertThrows(IllegalArgumentException::class.java) { SshConnection.observeCommand(key.copy(terminalId=terminal)) }
        }
        assertThrows(IllegalArgumentException::class.java) { SshConnection.observeCommand(key.copy(serverIdentity="stale")) }
        assertThrows(IllegalArgumentException::class.java) { SshConnection.observeCommand(key.copy(backendId="other")) }
    }
}
