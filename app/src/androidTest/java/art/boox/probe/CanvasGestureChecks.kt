package art.boox.probe

import android.content.Context
import android.content.ContextWrapper
import android.view.InputDevice
import android.view.MotionEvent
import java.io.File
import kotlin.math.abs

object CanvasGestureChecks {
    private data class Finger(val id: Int, val x: Float, val y: Float, val tool: Int = MotionEvent.TOOL_TYPE_FINGER)

    private fun send(view: RasterSurface, action: Int, vararg fingers: Finger) {
        val properties = fingers.map { p -> MotionEvent.PointerProperties().apply { id = p.id; toolType = p.tool } }.toTypedArray()
        val coords = fingers.map { p -> MotionEvent.PointerCoords().apply { x = p.x; y = p.y; pressure = 0.5f } }.toTypedArray()
        val event = MotionEvent.obtain(1, 2, action, fingers.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    fun run(context: Context) {
        val testContext = object : ContextWrapper(context) {
            override fun getFilesDir() = File(context.cacheDir, "pan-tests").apply { mkdirs() }
        }
        val strokes = mutableListOf<PenStroke>()
        val view = RasterSurface(testContext, CanvasSpec(512, 512)) { strokes += it }
        view.surfaceChanged(view.holder, 0, 400, 400)
        view.createDocument(CanvasSpec(512, 512))
        view.zoom(ZoomAction.ACTUAL)
        view.rotateView(37.25f)
        var began = 0
        var ended = 0
        view.navigationStarted = { began++; view.rawInput = false }
        view.ready = { ended++ }
        val a = Finger(3, 100f, 100f)
        val b = Finger(9, 200f, 200f)
        val secondDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val initial = view.viewport.transform

        send(view, MotionEvent.ACTION_DOWN, a)
        send(view, MotionEvent.ACTION_MOVE, a.copy(x = 170f))
        check(view.viewport.transform == initial) { "One finger panned the paper" }
        send(view, MotionEvent.ACTION_UP, a)

        // Raw BOOX mode must still receive finger navigation, then close its old session.
        view.rawInput = true
        send(view, MotionEvent.ACTION_DOWN, a)
        send(view, secondDown, a, b)
        send(view, MotionEvent.ACTION_MOVE, a.copy(x = 101f), b.copy(x = 201f))
        check(began == 0 && view.viewport.transform == initial) { "Tiny touch jitter began navigation" }
        // Reordered indices must not lose the pointer pair or jump the midpoint.
        send(view, MotionEvent.ACTION_MOVE, b.copy(x = 240f, y = 230f), a.copy(x = 140f, y = 130f))
        check(began == 1 && view.isNavigating && !view.rawInput)
        check(abs(view.viewport.transform.x - initial.x - 40f) < 0.001f)
        check(abs(view.viewport.transform.y - initial.y - 30f) < 0.001f)
        check(view.viewport.transform.scale == initial.scale && view.viewport.rotationDegrees == 37.25f)
        check(!view.zoom(ZoomAction.IN) && !view.rotateView(0f)) { "Toolbar changed an active gesture" }
        send(view, MotionEvent.ACTION_POINTER_UP, a.copy(x = 140f), b.copy(x = 240f))
        val finished = view.viewport.transform
        check(ended == 1 && !view.isNavigating)
        send(view, MotionEvent.ACTION_MOVE, b.copy(x = 300f))
        check(view.viewport.transform == finished) { "Remaining finger continued panning" }
        send(view, MotionEvent.ACTION_UP, b)

        // Focus loss ends once, freezes the viewport, and prevents a stale gesture resuming.
        send(view, MotionEvent.ACTION_DOWN, a)
        send(view, secondDown, a, b)
        send(view, MotionEvent.ACTION_MOVE, a.copy(x = 150f), b.copy(x = 250f))
        view.inputEnabled = false
        val paused = view.viewport.transform
        check(ended == 2 && !view.isNavigating)
        view.inputEnabled = true
        send(view, MotionEvent.ACTION_MOVE, a.copy(x = 250f), b.copy(x = 350f))
        check(view.viewport.transform == paused)
        send(view, MotionEvent.ACTION_CANCEL, a, b)

        // A third contact stops navigation; the surviving pair cannot restart this stream.
        send(view, MotionEvent.ACTION_DOWN, a)
        send(view, secondDown, a, b)
        send(view, MotionEvent.ACTION_MOVE, a.copy(y = 150f), b.copy(y = 250f))
        send(view, MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), a, b, Finger(12, 300f, 300f))
        val third = view.viewport.transform
        send(view, MotionEvent.ACTION_MOVE, a.copy(x = 200f), b.copy(x = 300f))
        check(!view.isNavigating && view.viewport.transform == third)
        send(view, MotionEvent.ACTION_CANCEL, a, b)

        // Active raw strokes take priority over fingers.
        view.beginRawStroke()
        send(view, MotionEvent.ACTION_DOWN, a)
        send(view, secondDown, a, b)
        send(view, MotionEvent.ACTION_MOVE, a.copy(x = 200f), b.copy(x = 300f))
        check(view.viewport.transform == third && !view.isNavigating)
        view.endRawStroke()
        send(view, MotionEvent.ACTION_CANCEL, a, b)
        check(strokes.isEmpty()) { "Finger navigation painted a stroke" }

        // Pen coordinates after a translated and rotated view still map to document pixels.
        view.viewport.change(ZoomAction.FIT)
        view.viewport.panBy(30f, 20f)
        val point = view.viewport.transform.toView(256f, 256f)
        val pen = Finger(7, point.x, point.y, MotionEvent.TOOL_TYPE_STYLUS)
        send(view, MotionEvent.ACTION_DOWN, pen)
        send(view, MotionEvent.ACTION_UP, pen)
        check(strokes.size == 1 && abs(strokes.single().samples.last().x - 256f) < 0.002f)
        check(abs(strokes.single().samples.last().y - 256f) < 0.002f)
        val before = view.viewport.state()
        view.checkpoint()
        val restored = RasterSurface(testContext, CanvasSpec(1, 1)) { }
        restored.surfaceChanged(restored.holder, 0, 400, 400)
        check(abs(restored.viewport.transform.x - view.viewport.transform.x) < 0.002f)
        check(abs(restored.viewport.transform.y - view.viewport.transform.y) < 0.002f)
        check(!before.fit)
        repeat(10) { view.viewport.panBy(10000f, -10000f) }
        check(!view.viewport.drawingRect().isEmpty) { "Paper was lost off screen" }
        check(android.graphics.Region().setPath(view.viewport.paperPath(), android.graphics.Region(0, 0, 400, 400))) {
            "Only the rotated bounding box remained visible"
        }
        view.zoom(ZoomAction.FIT)
        val bounds = view.viewport.documentRect()
        check(abs(bounds.centerX() - 200f) < 0.002f && abs(bounds.centerY() - 200f) < 0.002f)
        restored.document?.recycle()
        view.document?.recycle()
    }
}
