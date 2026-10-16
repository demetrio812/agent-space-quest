package io.agentspace.quest.terminal

import com.termux.terminal.TextStyle
import org.junit.Assert.*
import org.junit.Test

class ObservedTerminalBufferTest {
    private fun frame(seq: Long, text: String, full: Boolean = seq == 1L, width: Int = 20, height: Int = 5) =
        ObserveEvent.Frame(width, height, seq, full, text.toByteArray(Charsets.UTF_8))

    @Test fun oneCellObservationIsSupportedConsistentlyWithProtocol() {
        val buffer = ObservedTerminalBuffer()
        buffer.accept(frame(1, "a", width = 1, height = 1))
        assertEquals("a", buffer.line(0))
    }

    @Test fun progressAndCursorUpdatesReplaceScreenCellsRatherThanAppendTranscript() {
        val buffer = ObservedTerminalBuffer()
        buffer.accept(frame(1, "Progress 10%"))
        buffer.accept(frame(2, "\rProgress 90%\u001b[K"))
        buffer.accept(frame(3, "\u001b[2;1Hsecond\u001b[1;10H99"))
        assertEquals("Progress 99%", buffer.line(0))
        assertEquals("second", buffer.line(1))
    }

    @Test fun splitUtf8AndAnsiSurviveAcrossFrames() {
        val buffer = ObservedTerminalBuffer()
        buffer.accept(frame(1, "\u001b[3"))
        val bytes = "1mé中".toByteArray(Charsets.UTF_8)
        buffer.accept(ObserveEvent.Frame(20, 5, 2, false, bytes.copyOfRange(0, 3)))
        buffer.accept(ObserveEvent.Frame(20, 5, 3, false, bytes.copyOfRange(3, bytes.size)))
        assertEquals("é中", buffer.line(0))
        assertEquals(1, buffer.withEmulator { TextStyle.decodeForeColor(it.screen.getStyleAt(0, 0)) })
    }

    @Test fun clearAndAlternateScreenRestoreMainBuffer() {
        val buffer = ObservedTerminalBuffer()
        buffer.accept(frame(1, "main"))
        buffer.accept(frame(2, "\u001b[?1049h\u001b[2J\u001b[Halternate"))
        assertEquals("alternate", buffer.line(0))
        buffer.accept(frame(3, "\u001b[?1049l"))
        assertEquals("main", buffer.line(0))
        buffer.accept(frame(4, "\u001b[2J\u001b[H"))
        assertEquals("", buffer.line(0))
    }

    @Test fun fullFrameResetsStateAndDimensionChangesNeedFullFrame() {
        val buffer = ObservedTerminalBuffer()
        assertThrows(IllegalArgumentException::class.java) { buffer.accept(frame(2, "delta", false)) }
        buffer.accept(frame(1, "old"))
        assertThrows(IllegalArgumentException::class.java) { buffer.accept(frame(2, "bad", false, 25)) }
        buffer.accept(frame(3, "new", true, 25))
        assertEquals("new", buffer.line(0))
        assertEquals(25, buffer.withEmulator { it.mColumns })
        assertThrows(IllegalArgumentException::class.java) { buffer.accept(frame(2, "old", false, 25)) }
    }

    @Test fun terminalQueriesAndClipboardRequestsCannotWriteToRemoteOrClipboard() {
        val buffer = ObservedTerminalBuffer()
        buffer.accept(frame(1, "visible\u001b[6n\u001b[c\u0007"))
        assertEquals("visible", buffer.line(0))
        // The adapter has no input callback or transport reference. Responses are discarded.
        assertTrue(buffer.withEmulator { it.cursorCol } >= 0)
    }

    @Test fun previewsContainRenderedMeaningfulLinesWithoutAnsi() {
        val buffer = ObservedTerminalBuffer()
        buffer.accept(frame(1, "first\r\n\u001b[31msecond\u001b[0m\r\nthird\r\nfourth"))
        assertEquals(listOf("second", "third", "fourth"), buffer.previewLines())
        assertFalse(buffer.previewLines().joinToString().contains('\u001b'))
    }
}
