package art.boox.probe

import android.graphics.PointF
import android.view.MotionEvent
import kotlin.math.hypot

/** Two-finger single/double tap. Delay the single so redo never performs undo first.
 * Scheduling is injected for deterministic timing tests and cancelled on lifecycle/edit changes. */
class HistoryTapGestures(
    private val slop: Float,
    private val doubleTapTimeout: Long,
    private val eligible: () -> Boolean,
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val undo: () -> Unit,
    private val redo: () -> Unit,
    private val trace: (String) -> Unit = {}
) {
    private val origins = mutableMapOf<Int, PointF>()
    private var started = 0L
    private var valid = false
    private var reachedPair = false
    private var secondTap = false
    private var pendingSingle = false
    private var firstReleased = 0L
    private val single = Runnable {
        if (pendingSingle) {
            pendingSingle = false
            if (eligible()) { trace("History gesture: undo"); undo() }
        }
    }

    fun cancel() {
        if (valid || pendingSingle) trace("History tap cancelled")
        remove(single); pendingSingle = false
        origins.clear(); valid = false; reachedPair = false; secondTap = false
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        // Observe candidate touches without consuming them. Pan/pinch/twist must still
        // see every event and cancel this candidate when navigation begins.
        if (event.actionMasked != MotionEvent.ACTION_MOVE) {
            trace("History touch action=${event.actionMasked} fingers=${event.pointerCount}")
        }
        if (!eligible() || (0 until event.pointerCount).any { event.getToolType(it) != MotionEvent.TOOL_TYPE_FINGER }) {
            cancel(); return false
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL || event.flags and MotionEvent.FLAG_CANCELED != 0) {
            cancel(); return false
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            origins.clear(); valid = true; reachedPair = false
            started = event.eventTime
            secondTap = pendingSingle && event.eventTime - firstReleased in 0..doubleTapTimeout
            if (secondTap) remove(single)
            else if (pendingSingle) { remove(single); pendingSingle = false }
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            val index = event.actionIndex
            val id = event.getPointerId(index)
            if (origins.containsKey(id) || reachedPair) reject("extra/replaced contact")
            origins[id] = PointF(event.getX(index), event.getY(index))
            if (origins.size > 2) reject("more than two fingers")
            if (event.eventTime - started > CHORD_TIMEOUT) reject("fingers joined too slowly")
            if (origins.size == 2 && event.pointerCount == 2 && valid) reachedPair = true
        }
        for (i in 0 until event.pointerCount) {
            val origin = origins[event.getPointerId(i)]
            if (origin == null || hypot(event.getX(i) - origin.x, event.getY(i) - origin.y) > slop) reject("movement")
        }
        if (event.eventTime - started > TAP_TIMEOUT) reject("held too long")
        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && !reachedPair) reject("no simultaneous pair")
        if (!valid && pendingSingle) { remove(single); pendingSingle = false }

        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val accepted = valid && reachedPair && origins.size == 2
            valid = false
            if (accepted) {
                if (secondTap && pendingSingle) {
                    pendingSingle = false; remove(single)
                    trace("History gesture: redo")
                    redo()
                } else {
                    pendingSingle = true; firstReleased = event.eventTime
                    trace("History tap accepted: waiting ${doubleTapTimeout}ms")
                    post(single, doubleTapTimeout)
                }
            } else if (secondTap) {
                remove(single); pendingSingle = false
            }
            secondTap = false
            return accepted
        }
        return false
    }

    private fun reject(reason: String) {
        if (valid) trace("History tap rejected: $reason")
        valid = false
    }

    private companion object {
        const val CHORD_TIMEOUT = 250L
        const val TAP_TIMEOUT = 450L
    }
}
