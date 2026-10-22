package art.boox.probe

import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.widget.EditText
import android.widget.Spinner

object CanvasDialogChecks {
    fun run(instrumentation: Instrumentation) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.waitForIdleSync()
            var failure: Throwable? = null
            var dialog: AlertDialog? = null
            var chosen: CanvasSpec? = null
            instrumentation.runOnMainSync {
                try { dialog = CanvasSizeDialog.show(activity) { chosen = it } }
                catch (error: Throwable) { failure = error }
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                try {
                    val shown = checkNotNull(dialog)
                    val root = shown.window!!.decorView
                    val preset = root.findViewWithTag<Spinner>("canvas-preset")
                    val width = root.findViewWithTag<EditText>("canvas-width")
                    val height = root.findViewWithTag<EditText>("canvas-height")
                    val create = shown.getButton(AlertDialog.BUTTON_POSITIVE)
                    check(create.isEnabled) { "Valid default preset cannot be created" }
                    // Select Custom before editing; the result must carry the chosen dimensions.
                    preset.setSelection(CanvasSizes.presets.size)
                    width.setText("0")
                    check(!create.isEnabled) { "Zero-width canvas was accepted" }
                    width.setText("99999")
                    check(!create.isEnabled) { "Oversized canvas was accepted" }
                    width.setText("640"); height.setText("480")
                    check(create.isEnabled) { "Valid custom canvas is disabled" }
                    create.performClick()
                    check(chosen == CanvasSpec(640, 480)) { "Custom selection dimensions were not returned" }
                } catch (error: Throwable) { failure = error }
                finally { dialog?.dismiss() }
            }
            failure?.let { throw it }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }
}
