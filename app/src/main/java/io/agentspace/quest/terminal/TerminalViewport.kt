package io.agentspace.quest.terminal

/** Follow the bottom after initial measurement and resizing, until the reader pans away. */
internal class TerminalViewport {
    var offset = 0f; private set
    var following = true; private set
    private var maximum = 0f
    fun reset() { following = true; offset = 0f }
    fun layout(content: Float, viewport: Float) {
        maximum = (content - viewport).coerceAtLeast(0f)
        offset = if (following) maximum else offset.coerceIn(0f, maximum)
    }
    fun pan(delta: Float) {
        offset = (offset + delta).coerceIn(0f, maximum)
        following = maximum - offset <= 2f
    }
}
