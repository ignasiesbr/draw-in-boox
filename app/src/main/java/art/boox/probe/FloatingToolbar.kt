package art.boox.probe

import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.hypot

/** Opaque overlay with dedicated grips: ordinary button/pen scrolling never moves it. */
class FloatingToolbar(context: Context, private val preferredWidth: Int,
    vertical: Boolean = false, private val label: String = "Floating toolbar") : LinearLayout(context) {
    val controls = LinearLayout(context).apply {
        orientation = if (vertical) VERTICAL else HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    var beginMove: () -> Boolean = { false }
    var endMove: (Float, Float) -> Unit = { _, _ -> }
    var isDragging = false
        private set
    private var position: Pair<Float, Float>? = null
    private val grip = Grip(vertical)

    init {
        tag = label
        orientation = if (vertical) VERTICAL else HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(if (vertical) 2 else 6), dp(6), dp(6))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(dp(1), Color.BLACK)
            cornerRadius = dp(12).toFloat()
        }
        isClickable = true
        elevation = 0f
        if (vertical) {
            addView(grip, LayoutParams(dp(40), dp(20)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(controls, LayoutParams(-1, -2))
        } else {
            addView(grip, LayoutParams(dp(20), dp(52)))
            addView(controls, LayoutParams(0, -2, 1f))
        }
    }

    /** Fractions of the available workspace survive recreation and screen size changes. */
    fun restorePosition(x: Float, y: Float) {
        if (x.isFinite() && y.isFinite()) position = x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)
        requestLayout()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val area = parent as? View ?: return
        position?.let { (x, y) ->
            translationX = (area.width - width).coerceAtLeast(0) * x - left
            translationY = (area.height - height).coerceAtLeast(0) * y - top
        }
    }

    fun cancelDrag() {
        grip.reset()
        if (!isDragging) return
        isDragging = false
        val area = parent as? View ?: return
        val x = if (area.width > width) (left + translationX) / (area.width - width) else 0f
        val y = if (area.height > height) (top + translationY) / (area.height - height) else 0f
        position = x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)
        endMove(position!!.first, position!!.second)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus) cancelDrag()
        super.onWindowFocusChanged(hasWindowFocus)
    }

    override fun onDetachedFromWindow() {
        cancelDrag()
        super.onDetachedFromWindow()
    }

    private inner class Grip(private val horizontal: Boolean) : View(context) {
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private var pending = false
        private var touching = false
        private var startX = 0f
        private var startY = 0f
        private var startLeft = 0f
        private var startTop = 0f
        private var hoverArmedUntil = 0L
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val activate = Runnable {
            pending = false
            if (isShown && hasWindowFocus() && !isDragging && beginMove()) {
                isDragging = true
                isPressed = true
                parent.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
        }

        init {
            tag = "$label ${if (horizontal) "top" else "left"} drag handle"
            contentDescription = "Move $label: hold or hover, then drag"
            isClickable = true
        }

        private fun arm(event: MotionEvent, immediate: Boolean = false) {
            removeCallbacks(activate)
            startX = event.rawX; startY = event.rawY
            startLeft = this@FloatingToolbar.left + this@FloatingToolbar.translationX
            startTop = this@FloatingToolbar.top + this@FloatingToolbar.translationY
            pending = true
            if (immediate) activate.run()
            else postDelayed(activate, ViewConfiguration.getLongPressTimeout().toLong())
        }

        private fun move(event: MotionEvent) {
            val dx = event.rawX - startX
            val dy = event.rawY - startY
            if (!isDragging) {
                if (pending && hypot(dx, dy) > slop) { removeCallbacks(activate); pending = false }
                return
            }
            val area = this@FloatingToolbar.parent as? View ?: return
            this@FloatingToolbar.translationX = (startLeft + dx).coerceIn(0f,
                (area.width - this@FloatingToolbar.width).coerceAtLeast(0).toFloat()) - this@FloatingToolbar.left
            this@FloatingToolbar.translationY = (startTop + dy).coerceIn(0f,
                (area.height - this@FloatingToolbar.height).coerceAtLeast(0).toFloat()) - this@FloatingToolbar.top
        }

        fun reset() {
            removeCallbacks(activate); pending = false; touching = false
            isPressed = false; invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touching = true
                    if (!isDragging) arm(event, SystemClock.uptimeMillis() < hoverArmedUntil)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount != 1) cancelDrag() else move(event)
                }
                MotionEvent.ACTION_UP -> { cancelDrag(); performClick() }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelDrag()
            }
            return true
        }

        override fun onHoverEvent(event: MotionEvent): Boolean {
            if (touching) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> arm(event)
                MotionEvent.ACTION_HOVER_MOVE -> move(event)
                MotionEvent.ACTION_HOVER_EXIT -> {
                    if (isDragging) hoverArmedUntil = SystemClock.uptimeMillis() + 300L
                    cancelDrag()
                }
            }
            return true
        }

        override fun performClick(): Boolean { super.performClick(); return true }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height / 2f
            for (a in -1..1) for (b in -1..0) {
                val x = if (horizontal) a.toFloat() * dp(5) else (b + 0.5f) * dp(5)
                val y = if (horizontal) (b + 0.5f) * dp(5) else a.toFloat() * dp(5)
                canvas.drawCircle(cx + x, cy + y, dp(1).coerceAtLeast(1).toFloat(), ink)
            }
            if (isPressed) {
                ink.style = Paint.Style.STROKE; ink.strokeWidth = dp(1).toFloat()
                canvas.drawRect(1f, 1f, width - 1f, height - 1f, ink)
                ink.style = Paint.Style.FILL
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = if (View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.UNSPECIFIED)
            preferredWidth else View.MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(minOf(preferredWidth, available),
            View.MeasureSpec.EXACTLY), heightMeasureSpec)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
