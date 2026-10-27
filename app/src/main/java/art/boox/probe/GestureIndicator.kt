package art.boox.probe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.roundToInt

/** Small opaque sibling above the drawing surface: updating digits never redraws its bitmap. */
class GestureIndicator(context: Context, private val fastRefresh: (View, Boolean) -> Unit) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private data class Content(val angle: Float, val value: String, val hint: String)
    private var content: Content? = null
    private var scheduled = false
    private val update = Runnable { scheduled = false; invalidate() }

    init {
        visibility = INVISIBLE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun showAngle(value: Float?) {
        show(value?.let { Content(it, "${it.roundToInt()}°", "Lift to rotate") })
    }

    fun showZoom(level: ZoomLevel?) {
        show(level?.let { Content(0f, "${it.percent}%", "Pinch to zoom") })
    }

    private fun show(next: Content?) {
        if (next == null) {
            content = null
            removeCallbacks(update); scheduled = false
            if (visibility == VISIBLE) {
                visibility = INVISIBLE
                fastRefresh(this, false)
            }
            return
        }
        if (visibility != VISIBLE) {
            content = next
            fastRefresh(this, true)
            visibility = VISIBLE
            invalidate()
        } else if (content != next) {
            content = next
            // Coalesce touch samples; the last selected value is always drawn/committed.
            if (!scheduled) { scheduled = true; postDelayed(update, 33) }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = content ?: return
        // Keep a fixed square refresh area so both old and new corners are repainted.
        // Rotate the entire badge (including its white background), not the document.
        val saved = canvas.save()
        canvas.rotate(current.angle, width / 2f, height / 2f)
        val left = width / 2f - 120f * density
        val top = height / 2f - 48f * density
        val right = width / 2f + 120f * density
        val bottom = height / 2f + 48f * density
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawRect(left, top, right, bottom, paint)
        paint.color = Color.BLACK
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        canvas.drawRect(left + density, top + density, right - density, bottom - density, paint)
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.MONOSPACE
        paint.textSize = 42f * density
        canvas.drawText(current.value, width / 2f, top + 53f * density, paint)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 14f * density
        canvas.drawText(current.hint, width / 2f, top + 78f * density, paint)
        canvas.restoreToCount(saved)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(update); scheduled = false
        super.onDetachedFromWindow()
    }
}
