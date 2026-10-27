package art.boox.probe

import android.view.MotionEvent
import kotlin.math.hypot
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ln

/** Owns finger navigation only. Pen input and viewport math remain separate.
 * Translation and stepped zoom are live; twist previews a rotation applied on release. */
class CanvasGestures(
    private val touchSlop: Float,
    private val minRotationSpan: Float,
    private val begin: () -> Boolean,
    private val pan: (Float, Float) -> Unit,
    private val rotation: (Float) -> Unit,
    private val zoom: (Float, Float, Float) -> Unit,
    private val end: (Boolean) -> Unit,
    private val rotationEnabled: () -> Boolean = { false }
) {
    var active = false
        private set
    private var first = -1
    private var second = -1
    private var blocked = false
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastBearing = 0f
    private var turn = 0f
    private enum class Selection { ROTATION, ZOOM }
    private var selection: Selection? = null
    private var startSpan = 0f

    fun cancel() = finish(false)

    private fun finish(commit: Boolean) {
        val wasActive = active
        active = false
        first = -1; second = -1; blocked = true
        selection = null
        if (wasActive) end(commit)
    }

    private fun bearing(event: MotionEvent, a: Int, b: Int) = Math.toDegrees(atan2(
        (event.getY(b) - event.getY(a)).toDouble(), (event.getX(b) - event.getX(a)).toDouble())).toFloat()

    private fun move(event: MotionEvent, allowBegin: Boolean) {
        if (first == -1 || second == -1) return
        val a = event.findPointerIndex(first)
        val b = event.findPointerIndex(second)
        if (a < 0 || b < 0 || event.pointerCount != 2) { cancel(); return }
        val x = (event.getX(a) + event.getX(b)) / 2f
        val y = (event.getY(a) + event.getY(b)) / 2f
        val angle = bearing(event, a, b)
        val span = hypot(event.getX(b) - event.getX(a), event.getY(b) - event.getY(a))
        if (span < minRotationSpan && selection == Selection.ROTATION) { cancel(); return }
        if (span <= 0f || startSpan <= 0f) { cancel(); return }
        if (span >= minRotationSpan) turn += DocumentViewport.normalizeAngle(angle - lastBearing)
        lastBearing = angle
        val twist = rotationEnabled() && span >= minRotationSpan && abs(turn) >= 5f
        val ratio = span / startSpan
        val pinchStrength = abs(ln(ratio)) / ln(1.15f)
        val pinch = startSpan >= minRotationSpan && pinchStrength >= 1f && abs(span - startSpan) > touchSlop
        if (!active) {
            if (!allowBegin || (!twist && !pinch && hypot(x - startX, y - startY) <= touchSlop)) return
            if (!begin()) { cancel(); return }
            active = true
        }
        if (selection == null) {
            selection = when {
                pinch && (!twist || pinchStrength > abs(turn) / 5f) -> Selection.ZOOM
                twist -> Selection.ROTATION
                else -> null
            }
        }
        when (selection) {
            Selection.ROTATION -> rotation(turn)
            Selection.ZOOM -> zoom(ratio, x, y)
            null -> pan(x - lastX, y - lastY)
        }
        lastX = x; lastY = y
    }

    /** Returns true for finger-only streams, never stealing an existing pen stroke. */
    fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancel()
            blocked = false
        }
        val fingersOnly = (0 until event.pointerCount).all {
            event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER
        }
        if (!fingersOnly) {
            val wasActive = active
            cancel()
            return wasActive
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL || event.flags and MotionEvent.FLAG_CANCELED != 0) {
            cancel(); return true
        }
        if (blocked) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount != 2) { cancel(); return true }
                first = event.getPointerId(0); second = event.getPointerId(1)
                startX = (event.getX(0) + event.getX(1)) / 2f
                startY = (event.getY(0) + event.getY(1)) / 2f
                lastX = startX; lastY = startY
                lastBearing = bearing(event, 0, 1)
                turn = 0f; selection = null
                startSpan = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
            }
            MotionEvent.ACTION_MOVE -> move(event, true)
            MotionEvent.ACTION_POINTER_UP -> {
                if (active) move(event, false)
                finish(true)
            }
            MotionEvent.ACTION_UP -> finish(true)
        }
        return true
    }
}
