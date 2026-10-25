package art.boox.probe

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import java.io.File

/** Device-side bitmap and palm-filter checks using a separate temporary directory. */
class RasterInstrumentation : Instrumentation() {
    private var performanceOnly = false
    private var asyncOnly = false
    private var fountainOnly = false
    private var brushesOnly = false
    private var dryBatchOnly = false
    private var settingsOnly = false
    private var documentsOnly = false
    private var notesOnly = false
    override fun onCreate(arguments: Bundle?) {
        performanceOnly = arguments?.getString("performanceOnly") == "true"
        asyncOnly = arguments?.getString("asyncOnly") == "true"
        fountainOnly = arguments?.getString("fountainOnly") == "true"
        brushesOnly = arguments?.getString("brushesOnly") == "true"
        dryBatchOnly = arguments?.getString("dryBatchOnly") == "true"
        settingsOnly = arguments?.getString("settingsOnly") == "true"
        documentsOnly = arguments?.getString("documentsOnly") == "true"
        notesOnly = arguments?.getString("notesOnly") == "true"
        super.onCreate(arguments); start()
    }
    override fun onStart() {
        val result = Bundle()
        try {
            if (notesOnly) {
                var failure: Throwable? = null
                runOnMainSync { try { BooxNotesChecks.run(targetContext) } catch (error: Throwable) { failure = error } }
                failure?.let { throw it }
                result.putString("stream", "PASS: BOOX notes pen checks\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (settingsOnly) {
                SettingsChecks.run(this)
                result.putString("stream", "PASS: settings checks\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (documentsOnly) {
                var failure: Throwable? = null
                runOnMainSync { try { DocumentFileChecks.run(targetContext) } catch (error: Throwable) { failure = error } }
                failure?.let { throw it }
                DocumentFileChecks.actions(this)
                result.putString("stream", "PASS: portable document checks\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (dryBatchOnly) {
                var failure: Throwable? = null
                runOnMainSync { try { DryBrushBatchChecks.run() } catch (error: Throwable) { failure = error } }
                failure?.let { throw it }
                result.putString("stream", "PASS: dry brush batching checks\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (brushesOnly) {
                var failure: Throwable? = null
                runOnMainSync { try { BooxBrushChecks.run(targetContext) } catch (error: Throwable) { failure = error } }
                failure?.let { throw it }
                result.putString("stream", "PASS: brush engine checks\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (fountainOnly) {
                var failure: Throwable? = null
                runOnMainSync { try { BooxFountainChecks.run(targetContext) } catch (error: Throwable) { failure = error } }
                failure?.let { throw it }
                result.putString("stream", "PASS: fountain renderer checks\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (asyncOnly) {
                AsyncSurfaceChecks.run(this)
                result.putString("stream", "PASS: background surface checks\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (performanceOnly) {
                var failure: Throwable? = null
                runOnMainSync { try { LivePreviewWidthChecks.run(); StrokePerformanceChecks.run(); MarkerChecks.benchmark() } catch (error: Throwable) { failure = error } }
                failure?.let { throw it }
                result.putString("stream", "PASS: isolated stroke performance benchmark; see BooxPerf\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            var failure: Throwable? = null
            runOnMainSync {
              try {
                val testContext = object : ContextWrapper(targetContext) {
                    override fun getFilesDir(): File = File(targetContext.cacheDir, "raster-tests").apply { mkdirs() }
                }
                val strokes = mutableListOf<PenStroke>()
                val view = RasterSurface(testContext, CanvasSpec(128, 128)) { strokes += it }
                view.surfaceChanged(view.holder, 0, 128, 128)
                // Each run starts from a known viewport; restore is tested separately.
                view.createDocument(CanvasSpec(128, 128))
                view.zoom(ZoomAction.ACTUAL)
                view.clear()
                val file = File(testContext.filesDir, "test.png")
                try {
                    view.commit(listOf(PenSample(20f, 64f, 0.2f, 1), PenSample(100f, 64f, 0.8f, 2)), "test")
                    check(view.save(file)) { "PNG save failed" }
                    BitmapFactory.decodeFile(file.path).also {
                        check(it.getPixel(64, 64) == Color.BLACK) { "Stroke missing from bitmap" }
                        check(it.getPixel(64, 20) == Color.WHITE) { "Background is not white" }
                        it.recycle()
                    }
                    check(strokes.size == 1) { "Stroke callback count is wrong" }
                    view.clear()
                    val finger = MotionEvent.obtain(1, 1, MotionEvent.ACTION_DOWN, 50f, 50f, 0)
                    view.onTouchEvent(finger); finger.recycle()
                    val up = MotionEvent.obtain(1, 2, MotionEvent.ACTION_UP, 50f, 50f, 0)
                    view.onTouchEvent(up); up.recycle()
                    check(strokes.size == 1) { "Finger input drew a stroke" }
                    check(view.save(file))
                    BitmapFactory.decodeFile(file.path).also {
                        check(it.getPixel(64, 64) == Color.WHITE) { "Clear did not erase stroke" }
                        it.recycle()
                    }
                    checkPressure(view, file)
                    checkPointers(view, strokes)
                    checkRotatedPointers(view, strokes)
                    checkCheckpoint(testContext, view, file)
                    DocumentChecks.run(targetContext)
                    DocumentFileChecks.run(targetContext)
                    CanvasGestureChecks.run(targetContext)
                    GestureRotationChecks.run(targetContext)
                    GestureZoomChecks.run(targetContext)
                    HistoryChecks.run(targetContext)
                    HistoryTapChecks.run()
                    LayerChecks.run(targetContext)
                    LayerRenameChecks.run(targetContext)
                    LayerOpacityChecks.run(targetContext)
                    AlphaLockChecks.run(targetContext)
                    TiltChecks.run(targetContext)
                    LivePreviewWidthChecks.run()
                    BrushChecks.run(targetContext)
                    MarkerChecks.run(targetContext)
                    EraserChecks.run(targetContext)
                    NativePreviewSafetyChecks.run(targetContext)
                    NativeEraserChecks.run()
                    BooxFountainChecks.run(targetContext)
                    BooxBrushChecks.run(targetContext)
                    BooxNotesChecks.run(targetContext)
                    DryBrushBatchChecks.run()
                    DirtyFrameChecks.run(targetContext)
                    StrokePresentationChecks.run(targetContext)
                } finally { file.delete() }
              } catch (error: Throwable) { failure = error }
            }
            failure?.let { throw it }
            CanvasDialogChecks.run(this)
            DocumentFileChecks.actions(this)
            LayerPanelChecks.run(this)
            SettingsChecks.run(this)
            BrushChecks.dialogs(this)
            MarkerChecks.dialogs(this)
            EraserChecks.dialogs(this)
            DirtyFrameChecks.surfaceBenchmark(this)
            AsyncSurfaceChecks.run(this)
            StrokeDiagnosticsChecks.run()
            result.putString("stream", "PASS: bitmap stroke, PNG, pressure/input/lifecycle, presets/limits, migration, viewport/rotation/mask, two-finger pan/cancellation/persistence, fixed document/export orientation, document checkpoint, canvas selector validation\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun checkPressure(view: RasterSurface, file: File) {
        val raw = PressureRange(0f, 4095f, "test")
        check(raw.normalize(2047.5f) == 0.5f)
        check(raw.normalize(-1f) == 0f && raw.normalize(5000f) == 1f)
        check(raw.normalize(Float.NaN) == 0f)
        check(PressureRange(1f, 5f, "test-offset").normalize(3f) == 0.5f)
        view.clear()
        view.commit(listOf(PenSample(20f, 32f, 0f, 1), PenSample(100f, 32f, 0f, 2)), "test")
        view.commit(listOf(PenSample(20f, 80f, 1f, 1), PenSample(100f, 80f, 1f, 2)), "test")
        check(view.save(file))
        BitmapFactory.decodeFile(file.path).also {
            check(it.getPixel(64, 32) != Color.WHITE) { "Light-pressure stroke missing" }
            check(it.getPixel(64, 35) == Color.WHITE) { "Light-pressure stroke is too thick" }
            check(it.getPixel(64, 84) == Color.BLACK) { "Firm-pressure stroke did not widen" }
            it.recycle()
        }
        // Raw and normalized inputs must produce identical geometry.
        check(view.brush.width(raw.normalize(2047.5f)) == view.brush.width(0.5f))
        // Samples exactly at the bitmap edge must be clipped, never translated or wrapped.
        view.commit(listOf(PenSample(0f, 0f, 1f, 1)), "test")
        check(view.save(file))
        BitmapFactory.decodeFile(file.path).also {
            check(it.getPixel(0, 0) == Color.BLACK)
            check(it.getPixel(127, 0) == Color.WHITE)
            it.recycle()
        }
    }

    private data class Pointer(val id: Int, val tool: Int, val x: Float, val y: Float, val pressure: Float = 0.5f)

    private fun send(view: RasterSurface, action: Int, vararg pointers: Pointer, flags: Int = 0) {
        val properties = pointers.map { p -> MotionEvent.PointerProperties().apply { id = p.id; toolType = p.tool } }.toTypedArray()
        val coordinates = pointers.map { p -> MotionEvent.PointerCoords().apply {
            x = p.x; y = p.y; pressure = p.pressure; size = 1f
        } }.toTypedArray()
        val event = MotionEvent.obtain(1, 2, action, pointers.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_STYLUS, flags)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    private fun checkPointers(view: RasterSurface, strokes: MutableList<PenStroke>) {
        view.clear()
        val before = strokes.size
        val palm = Pointer(0, MotionEvent.TOOL_TYPE_FINGER, 100f, 100f)
        val pen = Pointer(7, MotionEvent.TOOL_TYPE_STYLUS, 20f, 20f)
        // Palm first, pen joins at index 1; palm leaves and pen becomes index 0.
        send(view, MotionEvent.ACTION_DOWN, palm)
        send(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), palm, pen)
        send(view, MotionEvent.ACTION_MOVE, palm.copy(x = 110f), pen.copy(x = 40f))
        send(view, MotionEvent.ACTION_POINTER_UP, palm, pen.copy(x = 40f))
        send(view, MotionEvent.ACTION_MOVE, pen.copy(x = 60f))
        send(view, MotionEvent.ACTION_UP, pen.copy(x = 80f))
        check(strokes.size == before + 1) { "Mixed palm+pen input lost or duplicated the stroke" }
        check(strokes.last().samples.all { it.y == 20f && it.x < 100f }) { "Palm coordinates contaminated the stroke" }
        check(strokes.last().samples.last().x == 80f) { "Pen pointer was lost after index changed" }

        for (tool in listOf(MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_MOUSE, MotionEvent.TOOL_TYPE_UNKNOWN)) {
            val other = palm.copy(tool = tool)
            send(view, MotionEvent.ACTION_DOWN, other)
            send(view, MotionEvent.ACTION_UP, other)
        }
        check(strokes.size == before + 1) { "Non-pen tool drew on the canvas" }
        send(view, MotionEvent.ACTION_DOWN, pen)
        send(view, MotionEvent.ACTION_CANCEL, pen)
        send(view, MotionEvent.ACTION_UP, pen)
        check(strokes.size == before + 1) { "Cancelled stroke committed" }
        send(view, MotionEvent.ACTION_DOWN, pen)
        send(view, MotionEvent.ACTION_UP, pen, flags = MotionEvent.FLAG_CANCELED)
        check(strokes.size == before + 1) { "Cancelled pointer-up committed" }
        send(view, MotionEvent.ACTION_DOWN, pen)
        view.inputEnabled = false
        send(view, MotionEvent.ACTION_UP, pen)
        view.inputEnabled = true
        send(view, MotionEvent.ACTION_UP, pen)
        check(strokes.size == before + 1) { "Paused input leaked a stroke after resume" }
        send(view, MotionEvent.ACTION_DOWN, pen)
        view.rawInput = true
        send(view, MotionEvent.ACTION_UP, pen)
        view.rawInput = false
        send(view, MotionEvent.ACTION_UP, pen)
        check(strokes.size == before + 1) { "Mode change leaked a pending stroke" }
        send(view, MotionEvent.ACTION_DOWN, pen)
        send(view, MotionEvent.ACTION_UP, pen)
        check(strokes.size == before + 2) { "Pen did not recover after cancellation/resume" }
    }

    private fun checkCheckpoint(testContext: ContextWrapper, view: RasterSurface, file: File) {
        view.clear()
        view.commit(listOf(PenSample(64f, 64f, 1f, 1)), "test")
        view.checkpoint()
        val restored = RasterSurface(testContext, CanvasSpec(128, 128)) { }
        restored.surfaceChanged(restored.holder, 0, 128, 128)
        check(restored.save(file))
        BitmapFactory.decodeFile(file.path).also {
            check(it.getPixel(64, 64) == Color.BLACK) { "Bitmap checkpoint did not restore" }
            it.recycle()
        }
        restored.clear()
    }

    private fun checkRotatedPointers(view: RasterSurface, strokes: MutableList<PenStroke>) {
        view.clear()
        view.rotateView(37.25f)
        val first = view.viewport.transform.toView(20f, 20f)
        val last = view.viewport.transform.toView(40f, 40f)
        val before = strokes.size
        send(view, MotionEvent.ACTION_DOWN, Pointer(7, MotionEvent.TOOL_TYPE_STYLUS, first.x, first.y))
        check(!view.rotateView(90f)) { "View rotated during a pen stroke" }
        check(view.viewport.rotationDegrees == 37.25f)
        send(view, MotionEvent.ACTION_UP, Pointer(7, MotionEvent.TOOL_TYPE_STYLUS, last.x, last.y))
        check(strokes.size == before + 1)
        val sample = strokes.last().samples.last()
        check(kotlin.math.abs(sample.x - 40f) < 0.001f && kotlin.math.abs(sample.y - 40f) < 0.001f) {
            "Rotated Android pen input did not commit in document coordinates"
        }
        check(!view.viewport.containsViewPoint(0f, 0f))
        send(view, MotionEvent.ACTION_DOWN, Pointer(7, MotionEvent.TOOL_TYPE_STYLUS, 0f, 0f))
        send(view, MotionEvent.ACTION_UP, Pointer(7, MotionEvent.TOOL_TYPE_STYLUS, 0f, 0f))
        check(strokes.size == before + 1) { "Angled gray margin accepted paint" }
        view.rotateView(0f)
    }
}
