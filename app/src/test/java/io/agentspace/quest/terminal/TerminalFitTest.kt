package io.agentspace.quest.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalFitTest {
    @Test fun fullScreenFitTouchesOneViewportEdgeWithoutClippingTheOther() {
        for ((width, height) in listOf(1500f to 1100f, 1100f to 400f, 600f to 1400f)) {
            val scale = terminalFitScale(width, height, 19.27f * 120, 38f * 40)
            val drawnWidth = 19.27f * 120 * scale
            val drawnHeight = 38f * 40 * scale
            assertTrue(drawnWidth <= width + .001f)
            assertTrue(drawnHeight <= height + .001f)
            assertTrue(kotlin.math.abs(drawnWidth - width) < .001f || kotlin.math.abs(drawnHeight - height) < .001f)
        }
    }
    @Test fun fitCanEnlargeASmallTerminalWithoutTheOldFontSizeCap() {
        assertEquals(10f, terminalFitScale(2000f, 1000f, 200f, 100f), .001f)
        assertEquals(1f, terminalFitScale(0f, 1000f, 200f, 100f), .001f)
    }
}
