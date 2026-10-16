package io.agentspace.quest

internal enum class TileStatus { DONE, QUESTION, IDLE, WORKING, UNKNOWN }

/** Only reported status determines attention; preview text is never interpreted as state. */
internal fun tileStatus(status: String, available: Boolean = true): TileStatus {
    if (!available) return TileStatus.UNKNOWN
    return when (status.trim().lowercase().replace('-', '_').replace(' ', '_')) {
        "done", "completed", "complete", "ready" -> TileStatus.DONE
        "question", "needs_input", "waiting_for_input", "awaiting_input", "blocked", "review" -> TileStatus.QUESTION
        "idle" -> TileStatus.IDLE
        "working", "running", "busy", "thinking" -> TileStatus.WORKING
        else -> TileStatus.UNKNOWN
    }
}
