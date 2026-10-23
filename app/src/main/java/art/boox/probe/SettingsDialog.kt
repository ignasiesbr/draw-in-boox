package art.boox.probe

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/** Settings editor is independent of activity/layout and commits only after Apply. */
object SettingsDialog {
    fun show(context: Context, settings: AppSettings, apply: (AppSettings) -> Boolean): AlertDialog {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setBackgroundColor(Color.WHITE)
        }
        content.addView(TextView(context).apply {
            text = "Layers panel position"; textSize = 18f; setTextColor(Color.BLACK)
        })
        val sides = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        val choices = LayerPanelSide.entries.associateWith { side ->
            RadioButton(context).apply {
                id = android.view.View.generateViewId()
                tag = "Layers panel ${side.storedValue}"
                text = side.label; textSize = 18f; minHeight = dp(56)
                setTextColor(Color.BLACK)
                sides.addView(this, RadioGroup.LayoutParams(0, dp(56), 1f))
            }
        }
        sides.check(checkNotNull(choices[settings.layerPanelSide]).id)
        content.addView(sides)
        val dialog = AlertDialog.Builder(context).setTitle("Settings")
            .setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton("Cancel", null).setPositiveButton("Apply", null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val side = choices.entries.first { it.value.id == sides.checkedRadioButtonId }.key
            if (apply(settings.copy(layerPanelSide = side))) dialog.dismiss()
        }
        return dialog
    }
}
