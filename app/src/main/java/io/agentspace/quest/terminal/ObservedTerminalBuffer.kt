package io.agentspace.quest.terminal

import com.termux.terminal.*

/** One read-only terminal model. Parsing is performed by the stream worker; drawing holds a short lock. */
class ObservedTerminalBuffer {
    private var emulator: TerminalEmulator? = null
    private var lastSequence = -1L
    @Volatile var revision: Long = 0
        private set

    @Synchronized fun accept(frame: ObserveEvent.Frame) {
        require(frame.width in 1..512 && frame.height in 1..256 && frame.width * frame.height <= 65536)
        require(frame.seq > lastSequence) { "Out-of-order terminal frame" }
        val current = emulator
        require(current != null || frame.full) { "Initial terminal frame must be a full screen" }
        require(frame.full || (current!!.mColumns == frame.width && current.mRows == frame.height)) { "New dimensions require a full screen" }
        val next = if (frame.full) TerminalEmulator(ReadOnlyOutput, frame.width, frame.height, 8, 16, 2000, QuietClient) else current!!
        next.append(frame.bytes, frame.bytes.size)
        emulator = next
        lastSequence = frame.seq
        revision++
    }

    @Synchronized fun <T> withEmulator(block: (TerminalEmulator) -> T): T = block(checkNotNull(emulator))
    fun line(row: Int): String = withEmulator { it.screen.getSelectedText(0, row, it.mColumns - 1, row).trimEnd() }

    /** Bounded, rendered screen excerpt for cards; ANSI bytes never reach the UI. */
    @Synchronized fun previewLines(): List<String> {
        val current = emulator ?: return emptyList()
        return (0 until current.mRows).map { row ->
            current.screen.getSelectedText(0, row, current.mColumns - 1, row).trimEnd()
        }.filter { it.isNotBlank() }.takeLast(3).map { it.take(120) }
    }

    private object ReadOnlyOutput : TerminalOutput() {
        // Terminal replies, bell and clipboard escape sequences have no side effects.
        override fun write(data: ByteArray?, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }
    private object QuietClient : TerminalSessionClient {
        override fun onTextChanged(session: TerminalSession?) = Unit
        override fun onTitleChanged(session: TerminalSession?) = Unit
        override fun onSessionFinished(session: TerminalSession?) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
        override fun onBell(session: TerminalSession?) = Unit
        override fun onColorsChanged(session: TerminalSession?) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int = 0
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, error: Exception?) = Unit
        override fun logStackTrace(tag: String?, error: Exception?) = Unit
    }
}
