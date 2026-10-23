package art.boox.probe

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

data class LayerProperties(val opacity: Int, val alphaLocked: Boolean)

/** Draft layer properties; one Apply produces one command, Cancel changes nothing. */
object LayerSettingsDialog {
    fun show(context: Context, layer: RasterLayer, apply: (LayerProperties) -> Unit): AlertDialog {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(16))
            setBackgroundColor(Color.WHITE)
        }
        content.addView(TextView(context).apply { text = layer.name; textSize = 18f; setTextColor(Color.BLACK) })
        val label = TextView(context).apply {
            tag = "Layer opacity value"; textSize = 18f; setTextColor(Color.BLACK)
            setPadding(0, dp(16), 0, 0)
        }
        content.addView(label)
        val slider = SeekBar(context).apply {
            tag = "Layer opacity"; contentDescription = "Layer opacity"
            max = 100; progress = layer.opacity
            progressTintList = ColorStateList.valueOf(Color.BLACK)
            thumbTintList = ColorStateList.valueOf(Color.BLACK)
            // Explicit track: the BOOX framework theme can leave the stock track invisible.
            progressDrawable = LayerDrawable(arrayOf(
                GradientDrawable().apply { setColor(Color.BLACK) },
                ClipDrawable(GradientDrawable().apply { setColor(Color.BLACK) }, Gravity.LEFT, ClipDrawable.HORIZONTAL)
            )).apply {
                setId(0, android.R.id.background); setId(1, android.R.id.progress)
                setLayerHeight(0, dp(2)); setLayerGravity(0, Gravity.CENTER_VERTICAL)
                setLayerHeight(1, dp(6)); setLayerGravity(1, Gravity.CENTER_VERTICAL)
            }
            progressBackgroundTintList = ColorStateList.valueOf(Color.BLACK)
            minimumHeight = dp(56)
        }
        fun updateLabel() { label.text = "Opacity: ${slider.progress}%" }
        updateLabel()
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = updateLabel()
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        content.addView(slider, LinearLayout.LayoutParams(-1, dp(56)))
        val alphaRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val alphaLock = EinkIconButton(context, UiIcon.LOCK, "Alpha lock", toggleable = true).apply {
            tag = "Layer alpha lock"; isChecked = layer.alphaLocked
            setOnClickListener { isChecked = !isChecked }
        }
        alphaRow.addView(alphaLock, LinearLayout.LayoutParams(dp(56), dp(56)))
        alphaRow.addView(TextView(context).apply {
            text = "Alpha lock\nPreserve existing transparency"; textSize = 16f; setTextColor(Color.BLACK)
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(alphaRow)
        val dialog = AlertDialog.Builder(context).setTitle("Layer settings")
            .setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton("Cancel", null).setPositiveButton("Apply", null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            apply(LayerProperties(slider.progress, alphaLock.isChecked)); dialog.dismiss()
        }
        return dialog
    }
}
