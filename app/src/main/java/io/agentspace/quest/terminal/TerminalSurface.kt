package io.agentspace.quest.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.view.TerminalRenderer
import com.termux.terminal.TextStyle
import kotlin.math.roundToInt

@Composable
fun TerminalSurface(controller: TerminalController, id: String, zoom: Float, modifier: Modifier, fit: Boolean = false) {
    val screen = controller.state(id)
    DisposableEffect(id, controller) { controller.retain(id); onDispose { controller.release(id) } }
    AndroidView(factory = { TerminalCanvas(it) }, modifier = modifier.fillMaxWidth(), update = {
        it.update(screen.buffer, screen.revision, zoom, fit)
    })
}

/** Uniform scaling fills one axis exactly while preserving the whole remote screen. */
internal fun terminalFitScale(width: Float, height: Float, contentWidth: Float, contentHeight: Float): Float =
    if (width <= 0f || height <= 0f || contentWidth <= 0f || contentHeight <= 0f) 1f
    else minOf(width / contentWidth, height / contentHeight)

/** Canvas-only view: no keyboard, input connection, links, clipboard or remote resize. */
internal class TerminalCanvas(context: Context) : View(context) {
    private var model: ObservedTerminalBuffer? = null
    private var revision = -1L
    private var zoom = 1f
    private var fit = false
    private var xOffset = 0f
    private val vertical = TerminalViewport()
    private var lastX = 0f
    private var lastY = 0f
    private var contentWidth = 0f
    private var contentHeight = 0f
    private var renderer: TerminalRenderer? = null
    private var rendererSize = 0
    private var topInset = 0f
    internal fun viewportMetrics() = "view=${width}x${height}; content=${contentWidth}x$contentHeight; fit=$fit"
    init { isFocusable = false; setBackgroundColor(Color.rgb(15, 32, 45)); contentDescription = "Read-only live terminal screen" }

    fun update(next: ObservedTerminalBuffer?, nextRevision: Long, nextZoom: Float, nextFit: Boolean) {
        if (model !== next || nextFit != fit || nextZoom != zoom) { xOffset = 0f; if (model !== next) vertical.reset() }
        if (model !== next || revision != nextRevision || zoom != nextZoom || fit != nextFit) {
            model = next; revision = nextRevision; zoom = nextZoom; fit = nextFit
            postInvalidateOnAnimation()
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val buffer = model ?: return
        if (buffer.revision == 0L || width == 0) return
        buffer.withEmulator { emulator ->
            // Use actual glyph/line metrics. Scale the canvas fractionally in fit mode,
            // avoiding guessed character ratios, an extra row and integer font-size gaps.
            val fontSize = if (fit) 32 else (12f * resources.displayMetrics.scaledDensity * zoom).roundToInt().coerceIn(6, 96)
            if (renderer == null || rendererSize != fontSize) {
                rendererSize = fontSize; renderer = TerminalRenderer(fontSize, Typeface.MONOSPACE)
                val paint = android.graphics.Paint().apply { typeface = Typeface.MONOSPACE; textSize = fontSize.toFloat() }
                topInset = renderer!!.fontLineSpacing + kotlin.math.ceil(paint.ascent())
            }
            val painter = renderer!!
            val rawWidth = painter.fontWidth * emulator.mColumns
            val rawHeight = (painter.fontLineSpacing * emulator.mRows).toFloat()
            val renderScale = if (fit) terminalFitScale(width.toFloat(), height.toFloat(), rawWidth, rawHeight) * zoom else 1f
            contentWidth = rawWidth * renderScale
            contentHeight = rawHeight * renderScale
            if (fit && zoom <= 1f) xOffset = 0f
            vertical.layout(contentHeight, height.toFloat())
            clampOffsets()
            canvas.save()
            canvas.clipRect(0, 0, width, height)
            canvas.drawColor(emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])
            canvas.translate(-xOffset, -vertical.offset)
            canvas.scale(renderScale, renderScale)
            canvas.translate(0f, -topInset)
            painter.render(emulator, canvas, 0, -1, -1, -1, -1)
            canvas.restore()
        }
    }
    private fun clampOffsets() {
        xOffset = xOffset.coerceIn(0f, (contentWidth - width).coerceAtLeast(0f))

    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                xOffset += lastX - event.x; vertical.pan(lastY - event.y)
                lastX = event.x; lastY = event.y; clampOffsets(); postInvalidateOnAnimation()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false); if (event.actionMasked == MotionEvent.ACTION_UP) performClick() }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
