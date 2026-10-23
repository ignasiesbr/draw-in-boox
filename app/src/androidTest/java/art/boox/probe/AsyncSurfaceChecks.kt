package art.boox.probe

import android.app.AlertDialog
import android.app.Instrumentation
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object AsyncSurfaceChecks {
    fun run(instrumentation: Instrumentation) {
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext,
            MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val directory = File(activity.cacheDir, "async-surface-${System.nanoTime()}")
        lateinit var surface: RasterSurface
        var dialog: AlertDialog? = null
        fun onMain(work: () -> Unit) {
            var failure: Throwable? = null
            instrumentation.runOnMainSync { try { work() } catch (error: Throwable) { failure = error } }
            failure?.let { throw it }
        }
        fun paint(bounds: RectF): Double {
            val done = CountDownLatch(1)
            var success = false
            var preparationMs = 0.0
            onMain {
                val start = StrokePerformance.now()
                surface.renderAsync(bounds) { success = it; done.countDown() }
                preparationMs = StrokePerformance.ms(start)
                // A normal UI task must run while the worker owns surface presentation.
                surface.post { check(Looper.myLooper() == Looper.getMainLooper()) }
            }
            check(done.await(10, TimeUnit.SECONDS) && success) { "Async presentation failed" }
            return preparationMs
        }
        fun copySurface(): Bitmap {
            val pixels = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
            val done = CountDownLatch(1)
            var result = -1
            onMain { PixelCopy.request(surface.holder.surface, pixels,
                { result = it; done.countDown() }, Handler(Looper.getMainLooper())) }
            check(done.await(10, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS) { "Cannot read test surface: $result" }
            return pixels
        }
        fun equivalent(a: Bitmap, b: Bitmap): Boolean {
            if (a.sameAs(b)) return true
            // An additional opaque bitmap conversion rounds low channel bits on this
            // device. Bound that error; lost/moved stroke edges exceed this tolerance.
            val left = IntArray(a.width * a.height); val right = IntArray(left.size)
            a.getPixels(left, 0, a.width, 0, 0, a.width, a.height)
            b.getPixels(right, 0, b.width, 0, 0, b.width, b.height)
            val shifts = intArrayOf(0, 8, 16, 24)
            var edgePixels = 0
            for (i in left.indices) {
                var delta = 0
                for (shift in shifts) delta = maxOf(delta,
                    kotlin.math.abs(((left[i] ushr shift) and 255) - ((right[i] ushr shift) and 255)))
                // Rotated opaque edges undergo another screen-bitmap conversion.
                // Measured differences: 400/1.6M pixels above 4, maximum 17/channel.
                if (delta > 20) return false
                if (delta > 4 && ++edgePixels > left.size / 1000) return false
            }
            return true
        }
        try {
            onMain {
                val isolated = object : ContextWrapper(activity) { override fun getFilesDir() = directory.apply { mkdirs() } }
                surface = RasterSurface(isolated, CanvasSpec(260, 260)) { }
                surface.inputEnabled = false
                dialog = AlertDialog.Builder(activity).setView(surface).create().also {
                    it.show(); it.window!!.setLayout(-1, (350 * activity.resources.displayMetrics.density).toInt())
                }
            }
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (true) {
                var ready = false
                onMain { ready = surface.document != null && surface.holder.surface.isValid }
                if (ready) break
                check(android.os.SystemClock.uptimeMillis() < deadline)
                Thread.sleep(20)
            }
            val preparations = mutableListOf<Double>()
            // Real TouchHelper integration: app inputEnabled alone cannot establish
            // that the native reader was enabled after opening SDK resources.
            onMain {
                surface.selectBrush(BrushPreset.INK.defaults())
                surface.inputEnabled = true
                val native = BooxPenAdapter(surface) { }
                try {
                    repeat(2) { attempt ->
                        if (attempt > 0) surface.zoom(ZoomAction.OUT)
                        check(native.open() && surface.rawInput) { "Native reader did not enable on open/reopen" }
                        native.close()
                        check(!surface.rawInput)
                    }
                    surface.zoom(ZoomAction.ACTUAL)
                    surface.document!!.activeLayer.alphaLocked = true
                    check(native.open() && surface.rawInput) { "Alpha lock disabled compatible native pen input" }
                    native.close()
                    surface.document!!.activeLayer.alphaLocked = false
                    surface.selectBrush(BrushPreset.ERASER.defaults().copy(size = 24f))
                    check(native.open() && surface.rawInput) { "Native white eraser reader did not open" }
                    native.close()
                    surface.selectBrush(BrushPreset.INK.defaults())
                    check(native.open() && surface.rawInput) { "Native pen did not reopen after white eraser" }
                    native.close()
                } finally { native.close(); surface.inputEnabled = false }
            }
            for (angle in listOf(0f, 37f)) {
                onMain { surface.createDocument(CanvasSpec(260, 260)); surface.rotateView(angle); surface.zoom(ZoomAction.ACTUAL) }
                repeat(3) { i ->
                    // Full seed after synchronous/view invalidation, then a real partial update.
                    paint(RectF(0f, 0f, 260f, 260f))
                    lateinit var bounds: RectF
                    onMain {
                        val samples = listOf(PenSample(60f + i * 30, 70f, 0.3f, 0),
                            PenSample(70f + i * 30, 170f, 1f, 1))
                        bounds = checkNotNull(surface.commit(samples, "async-test", deferPresentation = true))
                    }
                    val preparation = paint(bounds)
                    if (angle == 0f) preparations += preparation
                    val asynchronous = copySurface()
                    try {
                        onMain { surface.render() }
                        val synchronous = copySurface()
                        try {
                            if (!equivalent(asynchronous, synchronous)) {
                                var count = 0; var maxDelta = 0
                                val region = android.graphics.Rect()
                                for (y in 0 until synchronous.height) for (x in 0 until synchronous.width) {
                                    val a = asynchronous.getPixel(x, y); val b = synchronous.getPixel(x, y)
                                    if (a != b) {
                                        count++; region.union(x, y, x + 1, y + 1)
                                        for (shift in listOf(0, 8, 16, 24)) maxDelta = maxOf(maxDelta,
                                            kotlin.math.abs(((a ushr shift) and 255) - ((b ushr shift) and 255)))
                                    }
                                }
                                File(instrumentation.targetContext.cacheDir, "async-actual.png").outputStream().use { asynchronous.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                File(instrumentation.targetContext.cacheDir, "async-reference.png").outputStream().use { synchronous.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                error("Background surface pixels differ angle=$angle count=$count maxDelta=$maxDelta region=$region")
                            }
                        }
                        finally { synchronous.recycle() }
                    } finally { asynchronous.recycle() }
                }
            }
            // Multiple queued requests must preserve all changed regions in the latest frame.
            onMain { surface.rotateView(0f) }
            paint(RectF(0f, 0f, 260f, 260f))
            val done = CountDownLatch(1)
            onMain {
                repeat(4) { i ->
                    val dirty = checkNotNull(surface.commit(listOf(PenSample(30f + i * 55, 200f, 1f, i.toLong())),
                        "async-queue", deferPresentation = true))
                    surface.renderAsync(dirty) { if (i == 3) { check(it); done.countDown() } }
                }
            }
            check(done.await(10, TimeUnit.SECONDS))
            val queued = copySurface()
            try {
                onMain { surface.render() }
                val reference = copySurface()
                try { check(equivalent(queued, reference)) { "Coalescing lost a dirty region" } }
                finally { reference.recycle() }
            } finally { queued.recycle() }
            // Gray/wide brushes must be visible before pen-up without native ink.
            onMain {
                surface.createDocument(CanvasSpec(260, 260)); surface.rotateView(-22f)
                surface.zoom(ZoomAction.ACTUAL)
                surface.selectBrush(BrushPreset.MONOLINE.defaults().copy(size = 100.8f, shade = InkShade.LIGHT))
                surface.inputEnabled = true
            }
            fun sendPen(action: Int, x: Float) {
                val point = surface.viewport.transform.toView(x, 90f)
                val properties = arrayOf(android.view.MotionEvent.PointerProperties().apply { id = 7; toolType = android.view.MotionEvent.TOOL_TYPE_STYLUS })
                val coords = arrayOf(android.view.MotionEvent.PointerCoords().apply { this.x = point.x; y = point.y; pressure = 0.5f; size = 1f })
                val event = android.view.MotionEvent.obtain(1, 2, action, 1, properties, coords,
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_STYLUS, 0)
                try { surface.onTouchEvent(event) } finally { event.recycle() }
            }
            lateinit var middle: android.graphics.PointF
            onMain {
                sendPen(android.view.MotionEvent.ACTION_DOWN, 60f)
                sendPen(android.view.MotionEvent.ACTION_MOVE, 180f)
                middle = surface.viewport.transform.toView(120f, 90f)
                check(surface.history.undoCount == 0 && surface.document!!.layer.getPixel(120, 90) == 0)
            }
            val liveDeadline = android.os.SystemClock.uptimeMillis() + 5000
            var visible = false
            while (!visible && android.os.SystemClock.uptimeMillis() < liveDeadline) {
                val pixels = copySurface()
                try {
                    val color = pixels.getPixel(middle.x.toInt(), middle.y.toInt())
                    visible = kotlin.math.abs(android.graphics.Color.red(color) - InkShade.LIGHT.level) <= 4
                } finally { pixels.recycle() }
                if (!visible) Thread.sleep(16)
            }
            check(visible) { "App gray/wide preview was invisible until pen-up" }
            onMain {
                check(surface.isDrawing && surface.history.undoCount == 0)
                sendPen(android.view.MotionEvent.ACTION_UP, 180f)
                check(surface.history.undoCount == 1 && surface.document!!.layer.getPixel(120, 90) == InkShade.LIGHT.argb)
            }
            // Eraser preview must reveal the lower layer before lift without
            // mutating either layer or its undo history until commit.
            onMain {
                surface.createDocument(CanvasSpec(260, 260)); surface.rotateView(-22f)
                surface.zoom(ZoomAction.ACTUAL)
                surface.document!!.layer.eraseColor(InkShade.LIGHT.argb)
                surface.document!!.addLayer()
                surface.document!!.layer.eraseColor(android.graphics.Color.BLACK)
                surface.document!!.invalidateComposite()
                surface.selectBrush(BrushPreset.ERASER.defaults().copy(size = 64f))
                surface.inputEnabled = true
                surface.render()
                sendPen(android.view.MotionEvent.ACTION_DOWN, 60f)
                sendPen(android.view.MotionEvent.ACTION_MOVE, 180f)
                middle = surface.viewport.transform.toView(120f, 90f)
            }
            val eraserDeadline = android.os.SystemClock.uptimeMillis() + 5000
            var erasedLive = false
            while (!erasedLive && android.os.SystemClock.uptimeMillis() < eraserDeadline) {
                val pixels = copySurface()
                try {
                    erasedLive = kotlin.math.abs(android.graphics.Color.red(pixels.getPixel(middle.x.toInt(), middle.y.toInt())) - InkShade.LIGHT.level) <= 4
                } finally { pixels.recycle() }
                if (!erasedLive) Thread.sleep(16)
            }
            check(erasedLive) { "Live eraser did not reveal lower layer before pen-up" }
            onMain {
                check(surface.isDrawing && surface.history.undoCount == 0 &&
                    surface.document!!.layer.getPixel(120, 90) == android.graphics.Color.BLACK)
                sendPen(android.view.MotionEvent.ACTION_UP, 180f)
                check(surface.history.undoCount == 1 && surface.document!!.layer.getPixel(120, 90) == 0)
                check(surface.undo() && surface.document!!.layer.getPixel(120, 90) == android.graphics.Color.BLACK)
                check(surface.redo() && surface.document!!.layer.getPixel(120, 90) == 0)
            }
            val cancelled = CountDownLatch(1)
            var staleAccepted = true
            onMain {
                surface.renderAsync(RectF(0f, 0f, 260f, 260f)) { staleAccepted = it; cancelled.countDown() }
                check(surface.cancelAsyncPresentation())
                surface.rotateView(15f) // A synchronous replacement must invalidate old worker frames.
            }
            check(cancelled.await(10, TimeUnit.SECONDS) && !staleAccepted)
            android.util.Log.i("BooxPerf", "asyncBenchmark partialUiMedianMs=${preparations.sorted()[1]} runs=${preparations.size}")
        } finally {
            onMain { dialog?.dismiss(); surface.dispose(); activity.finish() }
            instrumentation.waitForIdleSync()
            directory.deleteRecursively()
        }
    }
}
