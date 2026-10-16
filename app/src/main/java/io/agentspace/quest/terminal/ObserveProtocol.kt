package io.agentspace.quest.terminal

import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

sealed interface ObserveEvent {
    data class Frame(val width: Int, val height: Int, val seq: Long, val full: Boolean, val bytes: ByteArray) : ObserveEvent
    data class Closed(val reason: String) : ObserveEvent
}

class ObserveProtocolException(message: String) : IOException(message)

/** Bounded Herdr NDJSON framing; sequence/snapshot continuity belongs to the screen buffer. */
object ObserveProtocol {
    const val MAX_LINE_BYTES = 2 * 1024 * 1024
    const val MAX_FRAME_BYTES = 1024 * 1024
    const val MAX_WIDTH = 512
    const val MAX_HEIGHT = 256
    const val MAX_CELLS = 65536

    fun parseLine(line: String): ObserveEvent {
        if (line.length > MAX_LINE_BYTES || line.toByteArray(Charsets.UTF_8).size > MAX_LINE_BYTES)
            throw ObserveProtocolException("Terminal record exceeds the 2 MiB limit.")
        try {
            require(line.isNotBlank())
            val tokener = JSONTokener(line)
            val record = JSONObject(tokener)
            require(tokener.nextClean() == '\u0000')
            return when (record.get("type")) {
                "terminal.frame" -> {
                    // Herdr calls this ANSI encoding; its JSON bytes field is canonical Base64.
                    require(record.get("encoding") == "ansi")
                    val width = integer(record, "width")
                    val height = integer(record, "height")
                    require(width in 1..MAX_WIDTH && height in 1..MAX_HEIGHT && width * height <= MAX_CELLS)
                    val seq = integer(record, "seq")
                    require(seq >= 0)
                    val full = record.get("full") as? Boolean ?: error("full must be boolean")
                    val encoded = record.get("bytes") as? String ?: error("bytes must be a string")
                    require(encoded.length <= ((MAX_FRAME_BYTES + 2) / 3) * 4)
                    val bytes = Base64.getDecoder().decode(encoded)
                    require(bytes.size <= MAX_FRAME_BYTES && Base64.getEncoder().encodeToString(bytes) == encoded)
                    ObserveEvent.Frame(width.toInt(), height.toInt(), seq, full, bytes)
                }
                "terminal.closed" -> {
                    val reason = if (record.has("reason")) record.get("reason") as? String
                        ?: error("reason must be a string") else "Terminal closed."
                    require(reason.length <= 1024)
                    ObserveEvent.Closed(reason)
                }
                else -> throw IllegalArgumentException("Unknown terminal record")
            }
        } catch (error: Exception) {
            if (error is ObserveProtocolException) throw error
            throw ObserveProtocolException("The Mac returned an invalid terminal record.")
        }
    }

    /** Reads arbitrary input chunks. Only an explicit terminal.closed is a normal completion. */
    fun readLines(input: InputStream, emit: (ObserveEvent) -> Unit) {
        val pending = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw IOException("Terminal observation interrupted.")
            val count = input.read(chunk)
            if (count < 0) throw IOException("Terminal observation ended without a closed record.")
            for (index in 0 until count) {
                val value = chunk[index]
                if (value == '\n'.code.toByte()) {
                    val raw = pending.toByteArray()
                    val length = if (raw.lastOrNull() == '\r'.code.toByte()) raw.size - 1 else raw.size
                    val line = try {
                        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw, 0, length)).toString()
                    } catch (_: Exception) {
                        throw ObserveProtocolException("Terminal record is not valid UTF-8.")
                    }
                    pending.reset()
                    val event = parseLine(line)
                    emit(event)
                    if (event is ObserveEvent.Closed) return
                } else {
                    if (pending.size() >= MAX_LINE_BYTES) throw ObserveProtocolException("Terminal record exceeds the 2 MiB limit.")
                    pending.write(value.toInt())
                }
            }
        }
    }

    private fun integer(record: JSONObject, name: String): Long = when (val value = record.get(name)) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw IllegalArgumentException("$name must be an integer")
    }
}
