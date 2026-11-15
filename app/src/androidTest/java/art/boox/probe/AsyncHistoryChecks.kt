package art.boox.probe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.util.ArrayDeque

/** Hold worker jobs to exercise history while tiles are raw, then drain the same
 * production compression path and verify exact pixels/order again. Isolated pixels. */
object AsyncHistoryChecks {
    fun run() {
        val tasks = ArrayDeque<Runnable>()
        val worker = HistoryCompression(4L * 1024 * 1024, jobLimit = 8, launch = { tasks.add(it) })
        val document = DrawingDocument.create(CanvasSpec(260, 260))
        val history = EditHistory(compression = worker)
        val snapshots = mutableListOf<Bitmap>()
        fun snapshot() = document.layer.copy(Bitmap.Config.ARGB_8888, false).also { snapshots += it }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        val brush = PressureBrush()
        fun stroke(x: Float) = StrokeEdit(listOf(PenSample(x, 10f, 0.3f, 1), PenSample(x + 80f, 250f, 1f, 2)), brush)
        try {
            val empty = snapshot()
            check(history.execute(document, stroke(20f)) && history.canUndo && worker.pendingJobs == 1)
            val first = snapshot()
            check(history.execute(document, stroke(100f)) && worker.pendingJobs == 2)
            val second = snapshot()
            check(history.undo(document) == "Stroke" && document.layer.sameAs(first))
            check(history.undo(document) == "Stroke" && document.layer.sameAs(empty))
            check(history.redo(document) == "Stroke" && document.layer.sameAs(first))
            check(history.redo(document) == "Stroke" && document.layer.sameAs(second))

            // Unchanged raw pixels preserve redo and release their capture reservation.
            history.undo(document)
            val noOp = object : DocumentEdit {
                override val label = "No-op"
                override fun affectedRegions(spec: CanvasSpec) = listOf(Rect(0, 0, 260, 260))
                override fun apply(document: DrawingDocument) {}
            }
            check(!history.execute(document, noOp) && history.canRedo && worker.pendingJobs == 2)
            check(history.redo(document) != null && document.layer.sameAs(second))

            val failure = object : DocumentEdit {
                override val label = "Failure"
                override fun affectedRegions(spec: CanvasSpec) = listOf(Rect(0, 0, 260, 260))
                override fun apply(document: DrawingDocument) { document.clear(); error("rollback fixture") }
            }
            var caught = false
            try { history.execute(document, failure) } catch (_: IllegalStateException) { caught = true }
            check(caught && history.undoCount == 2 && document.layer.sameAs(second) && worker.pendingJobs == 2)
            val rawBytes = history.bytesUsed
            drain()
            check(worker.pendingBytes == 0L && worker.pendingJobs == 0 && history.bytesUsed < rawBytes)
            check(history.undo(document) == "Stroke" && document.layer.sameAs(first))
            check(history.redo(document) == "Stroke" && document.layer.sameAs(second))

            // New edits cancel abandoned redo work without resurrecting its pixels.
            check(history.execute(document, stroke(40f)) && worker.pendingJobs == 1)
            history.undo(document)
            check(history.execute(document, stroke(60f)) && !history.canRedo && worker.pendingJobs == 1)
            val branch = snapshot()
            drain()
            history.undo(document)
            check(document.layer.sameAs(second))
            history.redo(document)
            check(document.layer.sameAs(branch))

            // Pending pixels belong to a stable layer even if structural edits interleave.
            history.execute(document, stroke(80f))
            val oldLayer = document.layerId
            val oldPixels = snapshot()
            history.executeLayer(document, LayerCommand.Add)
            val newLayer = document.layerId
            history.execute(document, stroke(30f))
            val newPixels = snapshot()
            drain()
            history.undo(document)
            check(document.layerId == newLayer && document.layer.sameAs(empty))
            history.undo(document)
            check(document.layerId == oldLayer && document.layer.sameAs(oldPixels))
            history.redo(document); history.redo(document)
            check(document.layerId == newLayer && document.layer.sameAs(newPixels))

            history.clear()
            val one = EditHistory(compression = worker, capacity = 1)
            try {
                one.execute(document, stroke(15f))
                one.execute(document, stroke(55f))
                check(one.undoCount == 1 && worker.pendingJobs == 1) { "Evicted raw action retained queued work" }
                one.clear()
                check(worker.pendingJobs == 0 && worker.pendingBytes == 0L)
                drain()
                check(!one.canUndo && !one.canRedo && one.bytesUsed == 0L)
            } finally { one.clear() }

            // Full queues and tight history budgets retain the compressed fallback.
            for ((limit, budget) in listOf(1L to 32L * 1024 * 1024, 4L * 1024 * 1024 to 4096L)) {
                val rejected = HistoryCompression(limit, launch = { error("Fallback unexpectedly submitted work") })
                val synchronous = EditHistory(compression = rejected, maxBytes = budget)
                document.clear() // Each fallback case must paint a fresh dot, not a no-op.
                val before = snapshot()
                try {
                    val dot = object : DocumentEdit {
                        override val label = "Dot"
                        override fun affectedRegions(spec: CanvasSpec) = listOf(Rect(2, 2, 3, 3))
                        override fun apply(document: DrawingDocument) {
                            Canvas(document.layer).drawRect(2f, 2f, 3f, 3f, Paint().apply { color = Color.BLACK })
                        }
                    }
                    check(synchronous.execute(document, dot) && synchronous.canUndo && rejected.pendingJobs == 0)
                    check(synchronous.bytesUsed <= budget)
                    synchronous.undo(document)
                    check(document.layer.sameAs(before))
                } finally { synchronous.clear() }
            }
        } finally {
            history.clear(); drain()
            snapshots.forEach { it.recycle() }
            document.recycle()
        }
    }
}
