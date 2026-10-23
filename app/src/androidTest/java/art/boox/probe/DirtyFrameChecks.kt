package art.boox.probe

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File

object DirtyFrameChecks {
    /** Measure real SurfaceHolder locks without touching saved artwork. */
    fun surfaceBenchmark(instrumentation: android.app.Instrumentation) {
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext,
            MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val directory = File(activity.cacheDir, "surface-performance-${System.nanoTime()}")
        var testSurface: RasterSurface? = null
        var dialog: android.app.AlertDialog? = null
        try {
            instrumentation.runOnMainSync {
                val isolated = object : ContextWrapper(activity) {
                    override fun getFilesDir() = directory.apply { mkdirs() }
                }
                val surface = RasterSurface(isolated, CanvasSpec(1860, 2480)) { }
                testSurface = surface
                surface.inputEnabled = false
                dialog = android.app.AlertDialog.Builder(activity).setView(surface).create().also {
                    it.show(); it.window!!.setLayout(-1, (400 * activity.resources.displayMetrics.density).toInt())
                }
            }
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (true) {
                var ready = false
                instrumentation.runOnMainSync { ready = testSurface!!.document != null && testSurface!!.holder.surface.isValid }
                if (ready) break
                check(android.os.SystemClock.uptimeMillis() < deadline) { "Benchmark surface unavailable" }
                Thread.sleep(20)
            }
            var failure: Throwable? = null
            instrumentation.runOnMainSync {
                try {
                    val surface = testSurface!!
                    surface.zoom(ZoomAction.ACTUAL)
                    val center = surface.viewport.transform.toDocument(surface.width / 2f, surface.height / 2f)
                    val samples = listOf(PenSample(center.x - 30, center.y - 10, 0.5f, 0),
                        PenSample(center.x + 30, center.y + 10, 1f, 1))
                    surface.commit(samples, "surface-benchmark")
                    val dirty = StrokeDirtyBounds.document(samples, surface.brush.maxRenderedWidth)!!
                    val fullTimes = mutableListOf<Double>(); val dirtyTimes = mutableListOf<Double>()
                    repeat(14) { i ->
                        var start = StrokePerformance.now()
                        surface.render()
                        val full = StrokePerformance.ms(start)
                        start = StrokePerformance.now()
                        surface.render(dirty)
                        val partial = StrokePerformance.ms(start)
                        if (i >= 4) { fullTimes += full; dirtyTimes += partial }
                    }
                    android.util.Log.i("BooxPerf", "surfaceBenchmark runs=10 fullMedianMs=${fullTimes.sorted()[5]} dirtyMedianMs=${dirtyTimes.sorted()[5]}")
                    // Compare actual per-stroke SurfaceHolder work with a combined frame.
                    // Excludes native pause/resume and physical pen/panel latency.
                    for (deferred in listOf(false, true)) {
                        surface.createDocument(CanvasSpec(1860, 2480))
                        surface.zoom(ZoomAction.ACTUAL)
                        val middle = surface.viewport.transform.toDocument(surface.width / 2f, surface.height / 2f)
                        val commits = mutableListOf<Double>()
                        var combined: android.graphics.RectF? = null
                        val batchStarted = StrokePerformance.now()
                        repeat(6) { i ->
                            val input = listOf(PenSample(middle.x - 50 + i * 12, middle.y, 0.4f, 0),
                                PenSample(middle.x - 45 + i * 12, middle.y + 25, 1f, 1))
                            val started = StrokePerformance.now()
                            val bounds = checkNotNull(surface.commit(input, "batch-benchmark", deferPresentation = deferred))
                            commits += StrokePerformance.ms(started)
                            combined?.union(bounds) ?: run { combined = android.graphics.RectF(bounds) }
                        }
                        if (deferred) surface.render(combined)
                        check(surface.history.undoCount == 6)
                        android.util.Log.i("BooxPerf", "batchBenchmark deferred=$deferred strokes=6 presentations=${if (deferred) 1 else 6} commitMedianMs=${commits.sorted()[3]} totalMs=${StrokePerformance.ms(batchStarted)}")
                    }
                } catch (error: Throwable) { failure = error }
            }
            failure?.let { throw it }
        } finally {
            instrumentation.runOnMainSync { dialog?.dismiss(); testSurface?.dispose(); activity.finish() }
            instrumentation.waitForIdleSync()
            directory.deleteRecursively()
        }
    }

    fun run(context: Context) {
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(context.cacheDir, "dirty-frame-tests").apply { mkdirs() }
        }
        val surface = RasterSurface(isolated, CanvasSpec(260, 260)) { }
        surface.surfaceChanged(surface.holder, 0, 400, 350)
        val partial = Bitmap.createBitmap(400, 350, Bitmap.Config.ARGB_8888)
        val complete = Bitmap.createBitmap(400, 350, Bitmap.Config.ARGB_8888)
        try {
            for (angle in listOf(0f, 37f, 90f)) for (scale in listOf(0.25f, 1f, 2.5f)) {
                surface.createDocument(CanvasSpec(260, 260))
                surface.rotateView(angle)
                surface.viewport.zoomTo(scale, 200f, 175f)
                for (layered in listOf(false, true)) {
                    if (layered) {
                        surface.applyLayerCommand(LayerCommand.Add)
                        surface.document!!.setLayerOpacity(surface.document!!.layerId, 43)
                    }
                    surface.drawFrame(Canvas(partial))
                    for (offset in listOf(0f, 35f, 100f)) {
                        val samples = listOf(PenSample(-3f + offset, 15f + offset, 0.2f, 0,
                            tiltRadians = 1.2f), PenSample(110f + offset, 120f + offset, 1f, 1,
                            tiltRadians = 1.2f), PenSample(261f, 259f, 0.7f, 2))
                        val dirty = StrokeDirtyBounds.view(StrokeDirtyBounds.document(samples,
                            surface.brush.maxRenderedWidth)!!, surface.viewport.transform, 400, 350)
                        surface.commit(samples, "dirty-frame-check")
                        if (!dirty.isEmpty) {
                            val target = Canvas(partial)
                            val saved = target.save()
                            target.clipRect(dirty)
                            surface.drawFrame(target)
                            target.restoreToCount(saved)
                        }
                        surface.drawFrame(Canvas(complete))
                        check(partial.sameAs(complete)) {
                            "Partial frame diverged at angle=$angle scale=$scale layered=$layered"
                        }
                    }
                }
            }
        } finally { partial.recycle(); complete.recycle(); surface.dispose() }
    }
}
