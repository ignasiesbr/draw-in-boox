package art.boox.probe

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import java.io.File

object HistoryChecks {
    private fun snapshot(document: DrawingDocument) = document.layer.copy(Bitmap.Config.ARGB_8888, false)
    private fun paintEdit(label: String, rect: Rect, color: Int, cut: Boolean = false) = object : DocumentEdit {
        override val label = label
        override fun affectedRegions(spec: CanvasSpec) = listOf(rect)
        override fun apply(document: DrawingDocument) {
            val paint = Paint().apply {
                this.color = color
                if (cut) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            Canvas(document.layer).drawRect(rect, paint)
        }
    }

    fun run(context: Context) {
        AsyncHistoryChecks.run()
        val document = DrawingDocument.create(CanvasSpec(260, 260))
        val history = EditHistory()
        val brush = PressureBrush()
        val empty = snapshot(document)
        val first = listOf(PenSample(0f, 0f, 0.2f, 1), PenSample(130f, 130f, 0.8f, 2), PenSample(259f, 259f, 1f, 3))
        history.execute(document, StrokeEdit(first, brush))
        val one = snapshot(document)
        history.execute(document, StrokeEdit(listOf(PenSample(0f, 259f, 1f, 4), PenSample(259f, 0f, 0.1f, 5)), brush))
        val two = snapshot(document)
        check(history.undoCount == 2 && history.redoCount == 0)
        check(history.undo(document) == "Stroke" && document.layer.sameAs(one)) { "Undo lost overlapping/edge pixels" }
        check(history.undo(document) == "Stroke" && document.layer.sameAs(empty))
        check(history.undo(document) == null)
        check(history.redo(document) == "Stroke" && document.layer.sameAs(one))
        check(history.redo(document) == "Stroke" && document.layer.sameAs(two))
        check(history.redo(document) == null)

        // Clear is an ordinary action; restore exact alpha/antialiasing and then re-clear.
        history.execute(document, ClearLayerEdit)
        check(document.layer.sameAs(empty))
        history.undo(document)
        check(document.layer.sameAs(two))
        history.redo(document)
        check(document.layer.sameAs(empty))
        check(!history.execute(document, ClearLayerEdit)) { "Empty clear consumed history" }
        history.undo(document)
        check(!history.execute(document, paintEdit("No-op", Rect(0, 0, 5, 5), Color.TRANSPARENT)))
        check(history.canRedo) { "No-op destroyed redo branch" }
        history.execute(document, paintEdit("Fill", Rect(127, 127, 260, 260), Color.argb(117, 31, 173, 209)))
        check(!history.canRedo) { "New edit did not discard redo" }
        val filled = snapshot(document)
        history.undo(document)
        check(document.layer.sameAs(two))
        history.redo(document)
        check(document.layer.sameAs(filled)) { "Color/alpha were not restored exactly" }
        history.execute(document, paintEdit("Cut", Rect(120, 120, 260, 260), Color.TRANSPARENT, true))
        val cut = snapshot(document)
        history.undo(document)
        check(document.layer.sameAs(filled))
        history.redo(document)
        check(document.layer.sameAs(cut))

        // Failed future actions roll back declared pixels and preserve both history branches.
        val count = history.undoCount
        val failure = object : DocumentEdit {
            override val label = "Fail"
            override fun affectedRegions(spec: CanvasSpec) = listOf(Rect(0, 0, 260, 260))
            override fun apply(document: DrawingDocument) { document.clear(); error("intentional test failure") }
        }
        var caught = false
        try { history.execute(document, failure) } catch (expected: IllegalStateException) { caught = true }
        check(caught && document.layer.sameAs(cut) && history.undoCount == count)
        history.clear(); document.clear()
        for (i in 0..10) history.execute(document, StrokeEdit(listOf(PenSample(10f + i * 20, 30f, 1f, i.toLong())), brush))
        check(history.undoCount == 10)
        repeat(10) { check(history.undo(document) != null) }
        check(document.layer.getPixel(10, 30) == Color.BLACK && document.layer.getPixel(30, 30) == Color.TRANSPARENT)
        check(!history.canUndo && history.redoCount == 10)
        repeat(10) { check(history.redo(document) != null) }
        val constrained = EditHistory(maxBytes = 1)
        constrained.execute(document, ClearLayerEdit)
        check(!constrained.canUndo && constrained.bytesUsed == 0L) { "History exceeded its byte budget" }
        for (bitmap in listOf(empty, one, two, filled, cut)) bitmap.recycle()
        document.recycle()

        // Surface lifecycle: navigation is not an edit, busy pen queues undo, new document resets.
        val testContext = object : ContextWrapper(context) {
            override fun getFilesDir() = File(context.cacheDir, "history-tests").apply { mkdirs() }
        }
        val view = RasterSurface(testContext, CanvasSpec(128, 128)) { }
        view.surfaceChanged(view.holder, 0, 400, 400)
        view.createDocument(CanvasSpec(128, 128))
        view.commit(listOf(PenSample(64f, 64f, 1f, 1)), "test")
        view.zoom(ZoomAction.ACTUAL); view.rotateView(37.25f); view.viewport.panBy(10f, 15f)
        val transform = view.viewport.transform
        view.beginRawStroke()
        check(view.undo() && view.history.undoCount == 1)
        view.endRawStroke()
        view.drainHistoryRequests()
        check(view.viewport.transform == transform && view.history.redoCount == 1)
        check(view.redo() && view.document!!.layer.getPixel(64, 64) == Color.BLACK)
        view.clear()
        check(view.undo() && view.document!!.layer.getPixel(64, 64) == Color.BLACK)
        view.surfaceChanged(view.holder, 0, 300, 500)
        check(view.history.canUndo && view.history.canRedo)
        view.checkpoint()
        val restored = RasterSurface(testContext, CanvasSpec(1, 1)) { }
        restored.surfaceChanged(restored.holder, 0, 400, 400)
        check(!restored.history.canUndo && restored.document!!.layer.getPixel(64, 64) == Color.BLACK)
        view.createDocument(CanvasSpec(64, 64))
        check(!view.history.canUndo && !view.history.canRedo)
        queuedRequests(view)
        restored.document?.recycle(); view.document?.recycle()
    }

    private fun queuedRequests(view: RasterSurface) {
        fun dot(x: Float) = view.commit(listOf(PenSample(x, 20f, 1f, 1)), "test")
        dot(10f); dot(20f)
        var pendingList = true
        view.historyCommitPending = { pendingList }
        // Contact has ended, but the final SDK list has not arrived: retain both taps.
        check(view.undo() && view.undo())
        check(view.history.undoCount == 2)
        dot(30f)
        view.drainHistoryRequests()
        check(view.history.undoCount == 3) { "Undo ran before the SDK commit" }
        pendingList = false
        view.viewportWillChange = { view.inputEnabled = false }
        view.drainHistoryRequests()
        check(view.history.undoCount == 1 && view.history.redoCount == 2)
        check(view.document!!.layer.getPixel(10, 20) == Color.BLACK)
        check(view.document!!.layer.getPixel(30, 20) == Color.TRANSPARENT)
        // A further toolbar tap during deferred native reopen is still actionable.
        check(!view.inputEnabled && view.undo() && view.history.undoCount == 0)
        check(view.redo() && view.redo() && view.history.undoCount == 2)
        view.inputEnabled = true
        pendingList = true
        check(view.undo())
        view.inputEnabled = false // Focus/file/lifecycle freeze cancels queued work.
        pendingList = false
        view.inputEnabled = true
        view.drainHistoryRequests()
        check(view.history.undoCount == 2) { "Queued Undo escaped a lifecycle freeze" }
        pendingList = true
        check(view.undo())
        view.createDocument(CanvasSpec(64, 64))
        dot(40f)
        pendingList = false
        view.drainHistoryRequests()
        check(view.history.undoCount == 1) { "Old Undo changed a replacement document" }
        view.viewportWillChange = null
    }
}
