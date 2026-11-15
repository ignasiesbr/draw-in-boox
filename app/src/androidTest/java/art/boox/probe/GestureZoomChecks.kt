package art.boox.probe

import android.content.Context
import android.content.ContextWrapper
import android.view.InputDevice
import android.view.MotionEvent
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

object GestureZoomChecks {
    internal fun send(view: RasterSurface, action: Int, ratio: Float = 1f, degrees: Float = 0f,
        x: Float = 180f, y: Float = 160f, reversed: Boolean = false, third: Boolean = false) {
        val radius = 100f * ratio
        val radians = Math.toRadians(degrees.toDouble())
        val dx = cos(radians).toFloat() * radius
        val dy = sin(radians).toFloat() * radius
        val order = if (third) listOf(0, 1, 2) else if (reversed) listOf(1, 0) else listOf(0, 1)
        val props = order.map { i -> MotionEvent.PointerProperties().apply {
            id = listOf(3, 9, 12)[i]; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coords = order.map { i -> MotionEvent.PointerCoords().apply {
            this.x = x + if (i == 0) -dx else dx
            this.y = y + if (i == 0) -dy else dy
            pressure = 1f
        } }.toTypedArray()
        val count = if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) 1 else order.size
        val event = MotionEvent.obtain(1, 2, action, count, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    fun run(context: Context) {
        val testContext = object : ContextWrapper(context) {
            override fun getFilesDir() = File(context.cacheDir, "zoom-gesture-tests").apply { mkdirs() }
        }
        val strokes = mutableListOf<PenStroke>()
        val view = RasterSurface(testContext, CanvasSpec(512, 512)) { strokes += it }
        view.configureGestures(true)
        view.configureRotationGesture(true)
        view.surfaceChanged(view.holder, 0, 400, 400)
        view.createDocument(CanvasSpec(512, 512))
        view.zoom(ZoomAction.ACTUAL)
        view.rotateView(37.25f)
        view.commit(listOf(PenSample(256f, 256f, 1f, 1)), "test")
        val pixels = view.document!!.layer.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        var began = 0
        var ended = 0
        view.navigationStarted = { began++; view.rawInput = false }
        view.ready = { ended++ }
        val secondDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        fun start() {
            send(view, MotionEvent.ACTION_DOWN)
            send(view, secondDown)
        }
        fun release(ratio: Float, degrees: Float = 0f) {
            send(view, MotionEvent.ACTION_POINTER_UP, ratio, degrees)
            send(view, MotionEvent.ACTION_UP, ratio, degrees)
        }

        check(ZoomLevels.all.map { it.number } == (1..14).toList())
        check(ZoomLevels.all.map { it.percent } == (75..400 step 25).toList())
        for (level in ZoomLevels.all) check(ZoomLevels.nearest(level.scale) == level)
        check(ZoomLevels.nearest(1.13f).scale == 1.25f)
        val original = view.viewport.transform
        val anchor = original.toDocument(180f, 160f)
        view.rawInput = true
        start()
        send(view, MotionEvent.ACTION_MOVE, 1.03f)
        check(began == 0 && view.zoomPreviewLevel == null) { "Small span jitter began zoom" }
        send(view, MotionEvent.ACTION_MOVE, 1.6f, reversed = true)
        check(began == 1 && view.isNavigating && !view.rawInput)
        check(view.zoomPreviewLevel?.percent == 150 && view.viewport.transform.scale == 1.5f)
        send(view, MotionEvent.ACTION_MOVE, 2.5f, 30f)
        check(view.zoomPreviewLevel?.percent == 250 && view.rotationPreviewAngle == null)
        check(view.viewport.transform.scale == 2.5f) { "Pinch did not update the canvas before release" }
        release(4f, 30f)
        check(ended == 1 && !view.isNavigating && view.zoomPreviewLevel == null)
        check(view.viewport.transform.scale == 4f && view.viewport.rotationDegrees == 37.25f)
        val afterAnchor = view.viewport.transform.toDocument(180f, 160f)
        check(abs(anchor.x - afterAnchor.x) < 0.002f && abs(anchor.y - afterAnchor.y) < 0.002f) {
            "Rotated zoom lost its pinch anchor"
        }

        // Pinch clamps at 75% and 400%; there is no zero/reset snap.
        view.zoom(ZoomAction.ACTUAL)
        start()
        send(view, MotionEvent.ACTION_MOVE, 0.05f)
        check(view.zoomPreviewLevel?.number == 1)
        release(0.05f)
        check(view.viewport.transform.scale == 0.75f)
        start()
        send(view, MotionEvent.ACTION_MOVE, 100f)
        check(view.zoomPreviewLevel?.percent == 400)
        release(100f)
        check(view.viewport.transform.scale == 4f)

        // Cancel, focus loss and additional fingers discard the selected zoom.
        for (reason in listOf("cancel", "focus", "third")) {
            val before = view.viewport.transform
            start()
            send(view, MotionEvent.ACTION_MOVE, 0.5f)
            check(view.zoomPreviewLevel != null)
            when (reason) {
                "cancel" -> send(view, MotionEvent.ACTION_CANCEL, 0.5f)
                "focus" -> { view.inputEnabled = false; view.inputEnabled = true }
                else -> send(view, MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0.5f, third = true)
            }
            release(0.5f)
            val after = view.viewport.transform
            check(after.scale == before.scale && abs(after.x - before.x) < 0.002f &&
                abs(after.y - before.y) < 0.002f && view.zoomPreviewLevel == null && !view.isNavigating)
        }

        // Rotation owns the stream once selected, even if finger spacing then changes.
        view.zoom(ZoomAction.ACTUAL)
        start()
        send(view, MotionEvent.ACTION_MOVE, 1f, 30f)
        val selectedAngle = view.rotationPreviewAngle
        send(view, MotionEvent.ACTION_MOVE, 2f, 30f)
        check(view.zoomPreviewLevel == null && view.rotationPreviewAngle == selectedAngle)
        send(view, MotionEvent.ACTION_CANCEL, 2f, 30f)

        // Pan owns the pair: later pinch/twist must not show either badge or change scale.
        start()
        send(view, MotionEvent.ACTION_MOVE, x = 220f)
        val panned = view.viewport.transform
        send(view, MotionEvent.ACTION_MOVE, 1.6f, 30f, x = 240f, reversed = true)
        check(view.zoomPreviewLevel == null && view.rotationPreviewAngle == null)
        check(view.viewport.transform.scale == panned.scale && view.viewport.rotationDegrees == 37.25f)
        check(abs(view.viewport.transform.x - panned.x - 20f) < 0.002f) { "Pinch interrupted an active pan" }
        // Even a large final spacing change cannot turn the release into zoom.
        send(view, MotionEvent.ACTION_POINTER_UP, 4f, 60f, x = 240f)
        send(view, MotionEvent.ACTION_UP, 4f, 60f, x = 240f)
        check(!view.isNavigating && view.zoomPreviewLevel == null && view.viewport.transform.scale == panned.scale)

        // Lifting the pair permits zoom in the next gesture; cancellation clears pan lock too.
        start()
        send(view, MotionEvent.ACTION_MOVE, 1.6f)
        check(view.zoomPreviewLevel?.percent == 150 && view.viewport.transform.scale == 1.5f)
        send(view, MotionEvent.ACTION_CANCEL, 1.6f)
        start()
        send(view, MotionEvent.ACTION_MOVE, x = 220f)
        send(view, MotionEvent.ACTION_CANCEL, x = 220f)
        start()
        send(view, MotionEvent.ACTION_MOVE, 2f)
        check(view.zoomPreviewLevel?.percent == 200)
        send(view, MotionEvent.ACTION_CANCEL, 2f)

        view.beginRawStroke()
        start()
        send(view, MotionEvent.ACTION_MOVE, 2f)
        check(view.zoomPreviewLevel == null && !view.isNavigating)
        view.endRawStroke()
        send(view, MotionEvent.ACTION_CANCEL, 2f)
        check(strokes.size == 1 && view.document!!.layer.sameAs(pixels))

        // A fresh pen stroke after zoom still lands in the right document coordinates.
        view.viewport.change(ZoomAction.FIT)
        view.viewport.zoomTo(1.5f, 200f, 200f)
        val point = view.viewport.transform.toView(256f, 256f)
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val prop = MotionEvent.PointerProperties().apply { id = 7; toolType = MotionEvent.TOOL_TYPE_STYLUS }
            val coord = MotionEvent.PointerCoords().apply { x = point.x; y = point.y; pressure = 0.5f }
            val event = MotionEvent.obtain(1, 2, action, 1, arrayOf(prop), arrayOf(coord),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            try { view.onTouchEvent(event) } finally { event.recycle() }
        }
        check(strokes.size == 2)
        val sample = strokes.last().samples.last()
        check(abs(sample.x - 256f) < 0.002f && abs(sample.y - 256f) < 0.002f)
        view.checkpoint()
        val restored = RasterSurface(testContext, CanvasSpec(1, 1)) { }
        restored.surfaceChanged(restored.holder, 0, 400, 400)
        check(restored.viewport.transform.scale == 1.5f && restored.viewport.rotationDegrees == 37.25f)
        pixels.recycle(); restored.document?.recycle(); view.document?.recycle()
    }
}
