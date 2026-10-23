package art.boox.probe

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.widget.LinearLayout
import android.widget.TextView

class InkSwatchDrawable(private val shade: InkShade) : Drawable() {
    private val fill = Paint().apply { color = shade.argb }
    private val edge = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE }
    override fun draw(canvas: Canvas) {
        val inset = maxOf(1f, minOf(bounds.width(), bounds.height()) / 20f)
        edge.strokeWidth = inset
        val left = bounds.left + inset; val top = bounds.top + inset
        val right = bounds.right - inset; val bottom = bounds.bottom - inset
        canvas.drawRect(left, top, right, bottom, fill)
        canvas.drawRect(left, top, right, bottom, edge)
    }
    override fun setAlpha(alpha: Int) { fill.alpha = alpha; edge.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { fill.colorFilter = filter; invalidateSelf() }
    @Deprecated("Drawable opacity is no longer used")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Shared draft palette for the toolbar dialog and the brush editor. */
class InkShadePicker(context: Context, initial: InkShade) : LinearLayout(context) {
    var selected: InkShade = initial
        private set
    var onChanged: (() -> Unit)? = null
    private val choices = mutableMapOf<InkShade, EinkIconButton>()
    private val label = TextView(context).apply { textSize = 16f; setTextColor(Color.BLACK) }
    init {
        orientation = VERTICAL
        addView(label)
        InkShade.entries.chunked(4).forEach { shades ->
            val row = LinearLayout(context)
            shades.forEach { shade ->
                val control = EinkIconButton(context, UiIcon.SHADE, shade.label, toggleable = true).apply {
                    tag = "ink.shade.${shade.level}"
                    setImageDrawable(InkSwatchDrawable(shade))
                    setOnClickListener { selected = shade; render(); onChanged?.invoke() }
                }
                choices[shade] = control
                row.addView(control, LayoutParams(0, dp(56), 1f))
            }
            addView(row)
        }
        render()
    }
    private fun render() {
        label.text = "Ink shade: ${selected.label}"
        choices.forEach { (shade, control) -> control.isChecked = shade == selected }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

object InkShadeDialog {
    fun show(context: Context, initial: InkShade, apply: (InkShade) -> Boolean): AlertDialog {
        val picker = InkShadePicker(context, initial).apply {
            val inset = (24 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset / 2, inset, inset / 2)
        }
        val dialog = AlertDialog.Builder(context).setTitle("Ink shade").setView(picker)
            .setNegativeButton("Cancel", null).setPositiveButton("Apply", null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (apply(picker.selected)) dialog.dismiss()
        }
        return dialog
    }
}
