package art.boox.probe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Checkable
import android.widget.ImageButton
import kotlin.math.cos
import kotlin.math.sin

enum class UiIcon { LAYERS, HIDE, ADD, DUPLICATE, MERGE_DOWN, DELETE, VISIBLE, HIDDEN, LOCK, UNLOCK, UP, DOWN, SETTINGS, INK, TILT, MONOLINE, MARKER, ERASER, SIZE, SHADE, UNDO, REDO }

/** Shared monochrome action/toggle. State is model-driven; clicking never silently toggles it.
 * No elevation, ripple or animated state; checked toggles have a 2dp black border. */
// Native widgets match the Activity framework theme; no AppCompat tinting is needed.
@android.annotation.SuppressLint("AppCompatCustomView")
class EinkIconButton(context: Context, icon: UiIcon, label: String, val toggleable: Boolean = false) : ImageButton(context), Checkable {
    private var checked = false
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE }

    init {
        contentDescription = label; tooltipText = label
        setImageDrawable(UiIconDrawable(icon))
        scaleType = ScaleType.FIT_CENTER
        minimumWidth = dp(48); minimumHeight = dp(48)
        setPadding(dp(11), dp(11), dp(11), dp(11))
        setBackgroundColor(Color.WHITE)
        elevation = 0f; stateListAnimator = null
        isFocusable = true
    }

    override fun isChecked() = checked
    override fun setChecked(value: Boolean) {
        if (!toggleable || checked == value) return
        checked = value
        if (Build.VERSION.SDK_INT >= 30) stateDescription = if (value) "On" else "Off"
        refreshDrawableState(); invalidate()
    }
    override fun toggle() = setChecked(!checked)
    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        val states = super.onCreateDrawableState(extraSpace + 1)
        if (checked) mergeDrawableStates(states, intArrayOf(android.R.attr.state_checked))
        return states
    }
    override fun drawableStateChanged() {
        super.drawableStateChanged()
        imageAlpha = if (isEnabled) 255 else 85
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (checked || isPressed || isFocused) {
            border.strokeWidth = dp(if (checked) 2 else 1).toFloat()
            val inset = border.strokeWidth / 2f + dp(1)
            canvas.drawRect(inset, inset, width - inset, height - inset, border)
        }
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isCheckable = toggleable
        info.isChecked = checked
        info.className = if (toggleable) "android.widget.ToggleButton" else "android.widget.ImageButton"
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

object EinkUi {
    fun selectionBackground(context: Context, selected: Boolean) = GradientDrawable().apply {
        setColor(Color.WHITE)
        setStroke(((if (selected) 2f else 1f) * context.resources.displayMetrics.density).toInt(),
            if (selected) Color.BLACK else 0xffbbbbbb.toInt())
    }
}

/** Repo-native vector glyphs: tiny, dependency-free, and drawn with consistent ink weight. */
class UiIconDrawable(private val icon: UiIcon) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    override fun draw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        fun line(vararg points: Float) {
            path.rewind(); path.moveTo(points[0], points[1])
            for (i in 2 until points.size step 2) path.lineTo(points[i], points[i + 1])
            canvas.drawPath(path, paint)
        }
        fun box(left: Float, top: Float, right: Float, bottom: Float, clear: Boolean = false) {
            if (clear) {
                val color = paint.color; paint.color = Color.WHITE; paint.style = Paint.Style.FILL
                canvas.drawRect(left, top, right, bottom, paint)
                paint.color = color; paint.style = Paint.Style.STROKE
            }
            canvas.drawRect(left, top, right, bottom, paint)
        }
        when (icon) {
            UiIcon.UNDO, UiIcon.REDO -> {
                val arrow = canvas.save()
                if (icon == UiIcon.REDO) canvas.scale(-1f, 1f, 12f, 12f)
                line(8f, 4f, 3f, 9f, 8f, 14f)
                path.rewind(); path.moveTo(3f, 9f); path.lineTo(13f, 9f)
                path.cubicTo(23f, 9f, 23f, 21f, 13f, 21f); canvas.drawPath(path, paint)
                canvas.restoreToCount(arrow)
            }
            UiIcon.ERASER -> {
                line(3f, 14f, 14f, 3f, 22f, 11f, 12f, 21f, 10f, 21f, 3f, 14f)
                line(7f, 10f, 15f, 18f); line(10f, 21f, 22f, 21f)
            }
            UiIcon.MARKER -> {
                line(4f, 17f, 15f, 3f, 22f, 9f, 11f, 22f, 4f, 17f)
                line(6f, 14f, 14f, 20f); line(3f, 21f, 8f, 21f, 10f, 19f)
            }
            UiIcon.SHADE -> {
                box(3f, 3f, 21f, 21f)
                line(3f, 21f, 21f, 3f); line(8f, 20f, 20f, 8f); line(14f, 20f, 20f, 14f)
            }
            UiIcon.INK -> {
                line(5f, 20f, 9f, 10f, 17f, 2f, 22f, 7f, 14f, 15f, 5f, 20f)
                line(9f, 10f, 14f, 15f); canvas.drawCircle(11f, 13f, 1f, paint)
            }
            UiIcon.TILT -> {
                line(3f, 18f, 16f, 3f, 21f, 8f, 7f, 21f, 3f, 18f)
                line(5f, 15f, 10f, 18f); line(12f, 21f, 22f, 21f)
            }
            UiIcon.MONOLINE -> {
                path.rewind(); path.moveTo(3f, 18f); path.cubicTo(8f, 2f, 16f, 22f, 21f, 6f)
                canvas.drawPath(path, paint)
                line(3f, 4f, 9f, 4f); line(15f, 20f, 21f, 20f)
            }
            UiIcon.SIZE -> {
                canvas.drawCircle(12f, 12f, 8f, paint)
                line(5f, 12f, 19f, 12f); line(8f, 9f, 5f, 12f, 8f, 15f)
                line(16f, 9f, 19f, 12f, 16f, 15f)
            }
            UiIcon.LAYERS -> {
                line(3f, 7f, 12f, 2f, 21f, 7f, 12f, 12f, 3f, 7f)
                line(3f, 12f, 12f, 17f, 21f, 12f); line(3f, 17f, 12f, 22f, 21f, 17f)
            }
            UiIcon.HIDE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            UiIcon.ADD -> { box(3f, 3f, 21f, 21f); line(12f, 7f, 12f, 17f); line(7f, 12f, 17f, 12f) }
            UiIcon.DUPLICATE -> { box(3f, 3f, 16f, 16f); box(8f, 8f, 21f, 21f, true) }
            UiIcon.MERGE_DOWN -> {
                box(4f, 2f, 20f, 6f); line(12f, 8f, 12f, 15f); line(8f, 11f, 12f, 15f, 16f, 11f)
                box(3f, 18f, 21f, 22f)
            }
            UiIcon.DELETE -> {
                line(6f, 7f, 6f, 21f, 18f, 21f, 18f, 7f); line(3f, 6f, 21f, 6f)
                line(8f, 6f, 8f, 3f, 16f, 3f, 16f, 6f); line(10f, 10f, 10f, 17f); line(14f, 10f, 14f, 17f)
            }
            UiIcon.VISIBLE, UiIcon.HIDDEN -> {
                path.rewind(); path.moveTo(2f, 12f); path.cubicTo(7f, 3f, 17f, 3f, 22f, 12f)
                path.cubicTo(17f, 21f, 7f, 21f, 2f, 12f); path.close(); canvas.drawPath(path, paint)
                canvas.drawCircle(12f, 12f, 3f, paint)
                if (icon == UiIcon.HIDDEN) line(3f, 3f, 21f, 21f)
            }
            UiIcon.LOCK, UiIcon.UNLOCK -> {
                path.rewind(); path.moveTo(8f, 11f); path.lineTo(8f, 7f)
                path.cubicTo(8f, 1f, 17f, 1f, 17f, 7f)
                if (icon == UiIcon.LOCK) path.lineTo(17f, 11f)
                canvas.drawPath(path, paint); box(4f, 10f, 20f, 22f, true)
                canvas.drawCircle(12f, 15f, 1f, paint); line(12f, 16f, 12f, 18f)
            }
            UiIcon.UP -> { line(12f, 21f, 12f, 3f); line(5f, 10f, 12f, 3f, 19f, 10f) }
            UiIcon.DOWN -> { line(12f, 3f, 12f, 21f); line(5f, 14f, 12f, 21f, 19f, 14f) }
            UiIcon.SETTINGS -> {
                path.rewind()
                for (i in 0 until 32) {
                    val angle = Math.PI * 2 * i / 32
                    val radius = if (i % 4 == 1 || i % 4 == 2) 10f else 7.5f
                    val x = 12f + cos(angle).toFloat() * radius; val y = 12f + sin(angle).toFloat() * radius
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close(); canvas.drawPath(path, paint); canvas.drawCircle(12f, 12f, 3f, paint)
            }
        }
        canvas.restoreToCount(saved)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = 24
    override fun getIntrinsicHeight() = 24
}
