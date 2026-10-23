package art.boox.probe

import android.graphics.RectF

/** UI-thread coordinator. Pixels/history commit per stroke; expensive display handoffs
 * wait for a quiet gap. Retains only one rectangle/count, never samples or bitmap copies. */
class StrokePresentation(
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val present: (RectF, Int) -> Unit,
    private val quietMs: Long = QUIET_MS
) {
    private var contact = false
    private var dirty: RectF? = null
    private var strokes = 0
    private var task: Runnable? = null
    private var epoch = 0L
    val isBusy: Boolean get() = contact || dirty != null

    fun begin() { contact = true; cancelTask() }
    fun end() { contact = false; schedule() }
    fun committed(bounds: RectF) {
        restore(bounds, 1)
    }
    /** An asynchronous paint finished after writing resumed; retry native reconciliation later. */
    fun restore(bounds: RectF, count: Int) {
        dirty?.union(bounds) ?: run { dirty = RectF(bounds) }
        strokes += count
        // Restart the quiet interval even when SDK list/end ordering differs.
        cancelTask()
        schedule()
    }

    /** Session changes must present these already-committed pixels or perform a full frame. */
    fun cancel(): RectF? {
        cancelTask(); contact = false
        val pending = dirty
        dirty = null; strokes = 0
        return pending
    }

    private fun cancelTask() { epoch++; task?.let(remove); task = null }
    private fun schedule() {
        if (contact || dirty == null || task != null) return
        val expected = epoch
        val next = Runnable {
            if (expected != epoch || contact) return@Runnable
            task = null
            val bounds = dirty ?: return@Runnable
            val count = strokes
            dirty = null; strokes = 0
            present(bounds, count)
        }
        task = next
        post(next, quietMs)
    }

    companion object { const val QUIET_MS = 450L }
}
