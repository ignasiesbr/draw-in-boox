package art.boox.probe

import android.graphics.RectF
import android.content.Context
import android.content.ContextWrapper
import java.io.File

object StrokePresentationChecks {
    fun run(context: Context) {
        val queue = mutableListOf<Runnable>()
        val frames = mutableListOf<Pair<RectF, Int>>()
        val presenter = StrokePresentation(post = { task, delay ->
            check(delay == StrokePresentation.QUIET_MS); queue += task
        }, remove = { queue.remove(it) }, present = { bounds, count -> frames += bounds to count })
        val a = RectF(1f, 2f, 8f, 9f)
        val b = RectF(20f, 10f, 30f, 40f)
        // List before end, then next contact before the quiet timer: no inter-stroke frame.
        presenter.begin(); presenter.committed(a)
        check(queue.isEmpty() && presenter.isBusy)
        presenter.end()
        val stale = queue.single()
        presenter.begin()
        stale.run()
        check(frames.isEmpty() && queue.isEmpty())
        // Also support end before the completed list; preserve both dirty regions.
        presenter.end(); presenter.committed(b)
        check(queue.size == 1)
        queue.removeAt(0).run()
        check(frames.single().second == 2)
        check(frames.single().first == RectF(1f, 2f, 30f, 40f))
        check(!presenter.isBusy)

        // Pending presentation cannot outlive a session/transform change.
        presenter.begin(); presenter.end(); presenter.committed(a)
        val cancelled = queue.single()
        check(presenter.cancel() == a && queue.isEmpty())
        cancelled.run()
        check(frames.size == 1 && !presenter.isBusy)
        presenter.begin(); presenter.end()
        check(queue.isEmpty()) // Empty/cancelled contact does not manufacture a handoff.
        presenter.begin(); presenter.committed(a); presenter.end()
        queue.removeAt(0).run()
        presenter.begin() // Contact resumes while its asynchronous frame is in flight.
        presenter.restore(a, 1)
        check(queue.isEmpty()) { "Native reset was scheduled during contact" }
        presenter.committed(b); presenter.end()
        queue.removeAt(0).run()
        check(frames.last().second == 2 && frames.last().first == RectF(1f, 2f, 30f, 40f))

        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(context.cacheDir, "deferred-stroke-tests").apply { mkdirs() }
        }
        val view = RasterSurface(isolated, CanvasSpec(128, 128)) { }
        try {
            view.surfaceChanged(view.holder, 0, 128, 128)
            view.createDocument(CanvasSpec(128, 128))
            view.zoom(ZoomAction.ACTUAL)
            var displays = 0
            val combined = StrokePresentation(post = { task, _ -> queue += task },
                remove = { queue.remove(it) }, present = { bounds, strokes ->
                    check(strokes == 6); view.render(bounds); displays++
                })
            repeat(6) { i ->
                combined.begin()
                val samples = listOf(PenSample(10f + i * 15f, 64f, 1f, i.toLong()))
                combined.committed(checkNotNull(view.commit(samples, "burst", deferPresentation = true)))
                check(view.document!!.layer.getPixel(10 + i * 15, 64) == android.graphics.Color.BLACK)
                check(view.history.undoCount == i + 1) { "Presentation delay postponed history" }
                combined.end()
            }
            check(displays == 0 && queue.size == 1)
            queue.removeAt(0).run()
            check(displays == 1 && view.history.undoCount == 6)
            repeat(6) { check(view.undo()) }
            repeat(6) { check(view.redo()) }
            repeat(6) { check(view.document!!.layer.getPixel(10 + it * 15, 64) == android.graphics.Color.BLACK) }
        } finally { view.dispose() }
    }
}
