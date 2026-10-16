package io.agentspace.quest.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalViewportTest {
    @Test fun opensAndResizesAtBottomAfterMeasurement() {
        val view = TerminalViewport()
        view.layout(0f, 0f)
        view.layout(800f, 700f)
        assertEquals(100f, view.offset)
        view.layout(1000f, 500f)
        assertEquals(500f, view.offset)
    }
    @Test fun readerPositionSurvivesUpdatesAndCanRejoinLatest() {
        val view = TerminalViewport()
        view.layout(800f, 600f); view.pan(-100f)
        view.layout(900f, 600f)
        assertEquals(100f, view.offset); assertFalse(view.following)
        view.pan(200f); view.layout(1000f, 600f)
        assertEquals(400f, view.offset); assertTrue(view.following)
    }
    @Test fun fitAndNewSessionResetSafely() {
        val view = TerminalViewport()
        view.layout(800f, 600f); view.pan(-100f)
        view.reset(); view.layout(500f, 600f)
        assertEquals(0f, view.offset)
        view.layout(800f, 600f)
        assertEquals(200f, view.offset)
    }
}
