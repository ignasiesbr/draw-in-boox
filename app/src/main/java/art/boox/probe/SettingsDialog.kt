package art.boox.probe

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.CheckBox
import android.widget.Button

data class SettingsAction(val label: String, val enabled: Boolean = true, val run: () -> Unit)

/** Settings editor is independent of activity/layout and commits only after Apply. */
object SettingsDialog {
    fun show(context: Context, settings: AppSettings, actions: List<SettingsAction> = emptyList(),
        info: String? = null, apply: (AppSettings) -> Boolean): AlertDialog {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        lateinit var dialog: AlertDialog
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "Settings content"
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setBackgroundColor(Color.WHITE)
        }
        if (info != null) content.addView(TextView(context).apply {
            text = info; textSize = 14f; setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, dp(12))
        })
        if (actions.isNotEmpty()) {
            content.addView(TextView(context).apply {
                text = "Canvas"; textSize = 18f; setTextColor(Color.BLACK)
            })
            actions.chunked(2).forEach { pair ->
                val row = LinearLayout(context)
                pair.forEach { action ->
                    row.addView(Button(context).apply {
                        text = action.label; tag = action.label; textSize = 16f
                        isAllCaps = false; isEnabled = action.enabled
                        setOnClickListener { dialog.dismiss(); action.run() }
                    }, LinearLayout.LayoutParams(0, dp(56), 1f))
                }
                content.addView(row)
            }
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
        content.addView(TextView(context).apply {
            text = "History gestures"; textSize = 18f; setTextColor(Color.BLACK)
            setPadding(0, dp(16), 0, 0)
        })
        fun gestureChoice(label: String, tagName: String, enabled: Boolean) = CheckBox(context).apply {
            text = label; tag = tagName; isChecked = enabled
            textSize = 18f; minHeight = dp(56); setTextColor(Color.BLACK)
            content.addView(this)
        }
        val undoGesture = gestureChoice("Undo: two-finger tap", "Undo gesture", settings.undoGestureEnabled)
        val redoGesture = gestureChoice("Redo: two-finger double tap", "Redo gesture", settings.redoGestureEnabled)
        content.addView(TextView(context).apply {
            text = "Advanced"; tag = "Advanced settings"; textSize = 18f; setTextColor(Color.BLACK)
            setPadding(0, dp(24), 0, 0)
        })
        val crashGuard = gestureChoice("Firmware crash guard", "Firmware crash guard", settings.firmwareCrashGuardEnabled)
        content.addView(TextView(context).apply {
            text = "Limits BOOX preview for gray and wide strokes. Turn off to test unrestricted native preview; the display service may crash."
            textSize = 14f; setTextColor(Color.DKGRAY)
        })
        dialog = AlertDialog.Builder(context).setTitle("Settings")
            .setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton("Cancel", null).setPositiveButton("Apply", null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val side = choices.entries.first { it.value.id == sides.checkedRadioButtonId }.key
            if (apply(settings.copy(layerPanelSide = side, undoGestureEnabled = undoGesture.isChecked,
                    redoGestureEnabled = redoGesture.isChecked,
                    firmwareCrashGuardEnabled = crashGuard.isChecked))) dialog.dismiss()
        }
        return dialog
    }
}
