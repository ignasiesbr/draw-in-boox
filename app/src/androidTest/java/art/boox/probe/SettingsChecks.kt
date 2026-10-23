package art.boox.probe

import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.widget.RadioButton

object SettingsChecks {
    fun run(instrumentation: Instrumentation) {
        val target = instrumentation.targetContext
        val preferenceName = "settings-test-${System.nanoTime()}"
        val isolated = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                target.getSharedPreferences(preferenceName, mode)
        }
        try {
            val store = AppSettingsStore(isolated)
            check(store.load().layerPanelSide == LayerPanelSide.RIGHT)
            store.save(AppSettings(LayerPanelSide.LEFT))
            check(AppSettingsStore(isolated).load().layerPanelSide == LayerPanelSide.LEFT)
            isolated.getSharedPreferences("", 0).edit().putString("layer_panel_side", "future-value").commit()
            check(store.load().layerPanelSide == LayerPanelSide.RIGHT)

            val activity = instrumentation.startActivitySync(android.content.Intent(target, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            var failure: Throwable? = null
            instrumentation.runOnMainSync {
                var dialog: AlertDialog? = null
                try {
                    var selected: AppSettings? = null
                    var allowApply = false
                    dialog = SettingsDialog.show(activity, AppSettings()) {
                        selected = it; allowApply
                    }
                    check(dialog.findViewById<android.view.View>(android.R.id.content)
                        .findViewWithTag<RadioButton>("Layers panel right").isChecked)
                    dialog.findViewById<android.view.View>(android.R.id.content)
                        .findViewWithTag<RadioButton>("Layers panel left").performClick()
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    check(selected?.layerPanelSide == LayerPanelSide.LEFT && dialog.isShowing)
                    allowApply = true
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    check(!dialog.isShowing)
                    selected = null
                    dialog = SettingsDialog.show(activity, AppSettings()) { selected = it; true }
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                    check(selected == null)
                } catch (error: Throwable) { failure = error }
                finally { dialog?.dismiss(); activity.finish() }
            }
            instrumentation.waitForIdleSync()
            failure?.let { throw it }
        } finally { target.deleteSharedPreferences(preferenceName) }
    }
}
