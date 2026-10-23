package art.boox.probe

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.view.InputDevice
import android.view.MotionEvent
import java.io.File
import kotlin.math.abs

object TiltChecks {
    private fun radians(degrees: Float) = Math.toRadians(degrees.toDouble()).toFloat()
    fun run(context: Context) {
        check(PenTilt.android(0f, false) == null)
        check(PenTilt.android(Float.NaN, true) == null && PenTilt.android(-1f, true) == null)
        check(PenTilt.android(0f, true) == 0f)
        check(PenTilt.boox(100, 0) == null)
        check(abs(checkNotNull(PenTilt.boox(45, 0)) - radians(45f)) < 0.001f)
        check(abs(checkNotNull(PenTilt.boox(-45, 0)) - radians(45f)) < 0.001f)
        // Compare the inverse conversion against the SDK's own Android-to-axis converter.
        for (angle in listOf(0f, 20f, 45f, 60f)) for (orientation in listOf(0f, 0.8f, -1.2f, 2.5f)) {
            val axes = com.onyx.android.sdk.base.data.TouchPoint.Companion.computeAndroidTiltXY(radians(angle), orientation)
            check(abs(checkNotNull(PenTilt.boox(axes.first, axes.second)) - radians(angle)) < radians(2f))
        }
        val brush = PressureBrush()
        val upright = PenSample(64f, 115f, 1f, 1, tiltRadians = 0f)
        val tilted = upright.copy(tiltRadians = radians(65f))
        check(brush.width(upright) == 12f && brush.width(tilted) == 30f)
        check(brush.width(upright.copy(tiltRadians = null)) == 12f)
        check(PressureBrush(widthModifier = NoWidthModifier).width(tilted) == 12f)
        check(brush.width(tilted.copy(pressure = 0.2f)) < brush.width(tilted))
        var previous = 0f
        for (angle in 0..90) {
            val width = brush.width(upright.copy(tiltRadians = radians(angle.toFloat())))
            check(width >= previous && width <= brush.maxRenderedWidth); previous = width
        }
        val mapped = ViewTransform(2f, 7f, 9f, 37f).map(tilted)
        check(mapped.tiltRadians == tilted.tiltRadians)
        val doc = DrawingDocument.create(CanvasSpec(256, 256))
        try {
            val history = EditHistory()
            check(history.execute(doc, StrokeEdit(listOf(tilted), brush)))
            check(doc.layer.getPixel(64, 129) == Color.BLACK) { "Tilt did not widen across tile boundary" }
            history.undo(doc)
            check(doc.layer.getPixel(64, 129) == Color.TRANSPARENT) { "Tilt width exceeded history capture" }
            history.redo(doc)
            check(doc.layer.getPixel(64, 129) == Color.BLACK)
        } finally { doc.recycle() }
        val directory = File(context.cacheDir, "tilt-input-tests").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = directory }
        val view = RasterSurface(isolated, CanvasSpec(256, 256)) {}
        try {
            view.surfaceChanged(view.holder, 0, 256, 256)
            view.createDocument(CanvasSpec(256, 256)); view.zoom(ZoomAction.ACTUAL)
            val device = InputDevice.getDeviceIds().asSequence().mapNotNull { InputDevice.getDevice(it) }
                .firstOrNull { it.getMotionRange(MotionEvent.AXIS_TILT, InputDevice.SOURCE_STYLUS) != null }
            if (device != null) {
                val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 7; toolType = MotionEvent.TOOL_TYPE_STYLUS })
                fun coordinates(angle: Float) = arrayOf(MotionEvent.PointerCoords().apply {
                    x = 64f; y = 115f; pressure = 1f
                    setAxisValue(MotionEvent.AXIS_TILT, radians(angle)); setAxisValue(MotionEvent.AXIS_ORIENTATION, 0.5f)
                })
                fun event(action: Int, time: Long, angle: Float) = MotionEvent.obtain(1, time, action, 1, properties,
                    coordinates(angle), 0, 0, 1f, 1f, device.id, 0, InputDevice.SOURCE_STYLUS, 0)
                val down = event(MotionEvent.ACTION_DOWN, 1, 0f)
                view.onTouchEvent(down); down.recycle()
                val move = event(MotionEvent.ACTION_MOVE, 2, 20f)
                move.addBatch(3, coordinates(65f), 0)
                check(PenTilt.sample(move, 0, 0).tiltRadians == radians(20f))
                check(PenTilt.sample(move, 0).viewOrientationRadians == 0.5f)
                view.onTouchEvent(move); move.recycle()
                val up = event(MotionEvent.ACTION_UP, 4, 65f)
                view.onTouchEvent(up); up.recycle()
                check(view.document!!.layer.getPixel(64, 129) == Color.BLACK)
            }
        } finally { view.dispose(); directory.deleteRecursively() }
    }
}
