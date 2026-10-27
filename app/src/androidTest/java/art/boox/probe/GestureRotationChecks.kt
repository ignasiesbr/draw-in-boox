package art.boox.probe

import android.content.Context
import android.content.ContextWrapper
import android.view.InputDevice
import android.view.MotionEvent
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

object GestureRotationChecks {
    private fun send(view: RasterSurface, action: Int, degrees: Float, reversed: Boolean = false, radius: Float = 100f) {
        val radians = Math.toRadians(degrees.toDouble())
        val dx = cos(radians).toFloat() * radius
        val dy = sin(radians).toFloat() * radius
        val order = if (reversed) listOf(1, 0) else listOf(0, 1)
        val props = order.map { i -> MotionEvent.PointerProperties().apply {
            id = if (i == 0) 3 else 9; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coords = order.map { i -> MotionEvent.PointerCoords().apply {
            x = 200f + if (i == 0) -dx else dx
            y = 200f + if (i == 0) -dy else dy
            pressure = 1f
        } }.toTypedArray()
        val count = if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) 1 else 2
        val event = MotionEvent.obtain(1, 2, action, count, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    fun run(context: Context) {
        val testContext = object : ContextWrapper(context) {
            override fun getFilesDir() = File(context.cacheDir, "rotation-gesture-tests").apply { mkdirs() }
        }
        var strokes = 0
        val view = RasterSurface(testContext, CanvasSpec(512, 512)) { strokes++ }
        view.surfaceChanged(view.holder, 0, 400, 400)
        view.createDocument(CanvasSpec(512, 512))
        view.rotateView(37.25f)
        view.commit(listOf(PenSample(256f, 256f, 1f, 1)), "test")
        val pixels = view.document!!.layer.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        val secondDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        var began = 0
        var ended = 0
        val previews = mutableListOf<Float?>()
        view.navigationStarted = { began++; view.rawInput = false }
        view.ready = { ended++ }
        view.rotationPreviewChanged = { previews += it }
        fun start(angle: Float = 0f, radius: Float = 100f) {
            send(view, MotionEvent.ACTION_DOWN, angle, radius = radius)
            send(view, secondDown, angle, radius = radius)
        }
        fun release(angle: Float) {
            send(view, MotionEvent.ACTION_POINTER_UP, angle)
            send(view, MotionEvent.ACTION_UP, angle)
        }

        // Fresh surfaces ignore twist, even when it accompanies a pinch or pan.
        val initial = view.viewport.transform
        check(!view.rotationGestureEnabled)
        start()
        send(view, MotionEvent.ACTION_MOVE, 30f)
        check(!view.isNavigating && view.rotationPreviewAngle == null && view.viewport.transform == initial)
        release(30f)
        start()
        GestureZoomChecks.send(view, MotionEvent.ACTION_MOVE, 1.6f, 30f, x = 200f, y = 200f)
        check(view.zoomPreviewLevel != null && view.rotationPreviewAngle == null)
        check(view.viewport.rotationDegrees == 37.25f)
        send(view, MotionEvent.ACTION_CANCEL, 30f)
        val beforePan = view.viewport.transform
        start()
        GestureZoomChecks.send(view, MotionEvent.ACTION_MOVE, degrees = 30f, x = 240f, y = 220f)
        check(view.isNavigating && view.zoomPreviewLevel == null && view.rotationPreviewAngle == null)
        check(abs(view.viewport.transform.x - beforePan.x - 40f) < 0.002f)
        check(abs(view.viewport.transform.y - beforePan.y - 20f) < 0.002f)
        send(view, MotionEvent.ACTION_CANCEL, 30f)
        // Disabling an active, opted-in rotation discards its selection.
        view.configureRotationGesture(true)
        start()
        send(view, MotionEvent.ACTION_MOVE, 30f)
        check(view.rotationPreviewAngle != null)
        view.configureRotationGesture(false)
        check(!view.isNavigating && view.rotationPreviewAngle == null && view.viewport.rotationDegrees == 37.25f)
        view.configureRotationGesture(true)
        // Restore the original position after testing live pan above.
        view.viewport.panBy(-40f, -20f)
        began = 0; ended = 0
        val enabledInitial = view.viewport.transform
        view.rawInput = true
        start()
        send(view, MotionEvent.ACTION_MOVE, 3f)
        check(began == 0 && view.rotationPreviewAngle == null) { "Small twist started rotation" }
        send(view, MotionEvent.ACTION_MOVE, 30f, reversed = true)
        check(began == 1 && view.isNavigating && !view.rawInput)
        check(view.rotationPreviewAngle == 67f)
        check(view.viewport.transform == enabledInitial) { "Twist redrew/rotated the document before release" }
        send(view, MotionEvent.ACTION_MOVE, 45f)
        check(view.rotationPreviewAngle == 82f && view.viewport.transform == enabledInitial)
        // The release sample, not just the last MOVE, determines the final selected number.
        release(60f)
        check(ended == 1 && !view.isNavigating && view.rotationPreviewAngle == null)
        check(view.viewport.rotationDegrees == 97f && previews.last() == null)
        check(previews.contains(97f))

        // Unwrap crossing +/-180, preserving pointer IDs despite reordered indices.
        start(170f)
        send(view, MotionEvent.ACTION_MOVE, -170f, reversed = true)
        check(view.rotationPreviewAngle == 117f)
        check(view.viewport.rotationDegrees == 97f)
        release(-170f)
        check(view.viewport.rotationDegrees == 117f)

        // Cancellation, focus loss and an extra contact discard deferred rotation.
        start()
        send(view, MotionEvent.ACTION_MOVE, 40f)
        send(view, MotionEvent.ACTION_CANCEL, 40f)
        check(view.viewport.rotationDegrees == 117f && view.rotationPreviewAngle == null)
        start()
        send(view, MotionEvent.ACTION_MOVE, -50f)
        view.inputEnabled = false
        check(view.viewport.rotationDegrees == 117f && !view.isNavigating && view.rotationPreviewAngle == null)
        view.inputEnabled = true
        release(-50f)
        check(view.viewport.rotationDegrees == 117f)

        // Very close contacts cannot produce large rotations from noisy bearings.
        start(radius = 10f)
        send(view, MotionEvent.ACTION_MOVE, 90f, radius = 10f)
        check(!view.isNavigating && view.rotationPreviewAngle == null)
        send(view, MotionEvent.ACTION_CANCEL, 90f, radius = 10f)

        // Pen priority also blocks pure twists (whose midpoint does not move).
        view.beginRawStroke()
        start()
        send(view, MotionEvent.ACTION_MOVE, 90f)
        check(!view.isNavigating && view.rotationPreviewAngle == null)
        view.endRawStroke()
        send(view, MotionEvent.ACTION_CANCEL, 90f)

        // Upright snapping enters from either side, resists jitter, then releases.
        for (sign in listOf(1f, -1f)) {
            view.rotateView(sign * 30f)
            start()
            send(view, MotionEvent.ACTION_MOVE, sign * -27f) // target +/-3
            check(view.rotationPreviewAngle == 0f && view.viewport.rotationDegrees == sign * 30f)
            send(view, MotionEvent.ACTION_MOVE, sign * -24f) // target +/-6, still caught
            check(view.rotationPreviewAngle == 0f)
            send(view, MotionEvent.ACTION_MOVE, sign * -22f) // target +/-8, breaks free
            check(view.rotationPreviewAngle == sign * 8f)
            send(view, MotionEvent.ACTION_MOVE, sign * -24f) // +/-6 does not re-enter
            check(view.rotationPreviewAngle == sign * 6f)
            send(view, MotionEvent.ACTION_MOVE, sign * -27f)
            release(sign * -27f)
            check(view.viewport.rotationDegrees == 0f) { "Released snap was not exactly upright" }
        }
        // Cancelling a snapped selection neither rotates nor leaves a sticky next session.
        view.rotateView(30f)
        start()
        send(view, MotionEvent.ACTION_MOVE, -27f)
        check(view.rotationPreviewAngle == 0f)
        send(view, MotionEvent.ACTION_CANCEL, -27f)
        check(view.viewport.rotationDegrees == 30f)
        start()
        send(view, MotionEvent.ACTION_MOVE, -24f)
        check(view.rotationPreviewAngle == 6f) { "Snap leaked between gestures" }
        send(view, MotionEvent.ACTION_CANCEL, -24f)
        // A bearing crossing +/-180 still snaps to the same document upright angle.
        view.rotateView(-30f)
        start(170f)
        send(view, MotionEvent.ACTION_MOVE, -160f)
        check(view.rotationPreviewAngle == 0f)
        release(-160f)
        check(view.viewport.rotationDegrees == 0f)
        view.rotateView(117f)
        check(strokes == 1 && view.document!!.layer.sameAs(pixels)) { "Navigation altered document pixels" }
        check(view.checkpoint())
        val restored = RasterSurface(testContext, CanvasSpec(1, 1)) { }
        restored.surfaceChanged(restored.holder, 0, 400, 400)
        check(abs(restored.viewport.rotationDegrees - 117f) < 0.001f)
        check(restored.document!!.layer.sameAs(pixels))
        pixels.recycle(); restored.document?.recycle(); view.document?.recycle()
    }
}
