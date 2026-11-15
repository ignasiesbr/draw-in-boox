package art.boox.probe

import android.util.Log

/** Isolated on-device history/geometry benchmark. Does not measure firmware or SurfaceView. */
object StrokePerformanceChecks {
    fun run() {
        val document = DrawingDocument.create(CanvasSpec(1860, 2480))
        try {
            for (mode in listOf("synchronous", "background")) for (count in listOf(100, 500, 1500)) {
                val history = if (mode == "synchronous") EditHistory(compression = HistoryCompression(1,
                    launch = { error("Synchronous benchmark admitted a raw snapshot") })) else EditHistory()
                val brush = PressureBrush()
                val timings = mutableListOf<Double>()
                repeat(14) { iteration ->
                    // A continuous handwritten-size path, shifted to avoid testing only no-ops.
                    val samples = List(count) { i ->
                        val t = i.toFloat() / (count - 1)
                        PenSample(100f + t * 900f, 150f + iteration * 100f + kotlin.math.sin(t * 30) * 35f,
                            0.1f + t * 0.8f, i.toLong(), tiltRadians = 0.6f)
                    }
                    val started = StrokePerformance.now()
                    check(history.execute(document, StrokeEdit(samples, brush)))
                    if (iteration >= 4) timings += StrokePerformance.ms(started)
                }
                val sorted = timings.sorted()
                Log.i("BooxPerf", "benchmark historyMode=$mode samples=$count runs=${sorted.size} medianMs=${sorted[sorted.size / 2]} p95Ms=${sorted.last()} retainedHistoryBytes=${history.bytesUsed}")
                history.clear()
                document.layer.eraseColor(android.graphics.Color.TRANSPARENT)
            }
        } finally { document.recycle() }
    }
}
