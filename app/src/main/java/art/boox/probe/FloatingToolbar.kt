package art.boox.probe

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout

/** Opaque canvas overlay. Pens scroll while history, settings and rotation stay in reach. */
class FloatingToolbar(context: Context, private val preferredWidth: Int) : LinearLayout(context) {
    init {
        tag = "Floating toolbar"
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(dp(1), Color.BLACK)
            cornerRadius = dp(12).toFloat()
        }
        // Consume taps on the frame/padding as well as the individual controls.
        isClickable = true
        elevation = 0f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = if (View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.UNSPECIFIED)
            preferredWidth else View.MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(minOf(preferredWidth, available),
            View.MeasureSpec.EXACTLY), heightMeasureSpec)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
