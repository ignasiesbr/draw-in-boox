package art.boox.probe

import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.widget.RadioButton
import android.widget.CheckBox
import android.view.View
import java.io.File

object SettingsChecks {
    fun run(instrumentation: Instrumentation) {
        val target = instrumentation.targetContext
        val originalRuntimeGuard = NativePreviewPolicy.firmwareCrashGuardEnabled
        val preferenceName = "settings-test-${System.nanoTime()}"
        val isolated = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                target.getSharedPreferences(preferenceName, mode)
        }
        try {
            val store = AppSettingsStore(isolated)
            check(store.load().layerPanelSide == LayerPanelSide.RIGHT)
            check(store.load().undoGestureEnabled && store.load().redoGestureEnabled)
            check(store.load().firmwareCrashGuardEnabled)
            store.save(AppSettings(LayerPanelSide.LEFT))
            check(AppSettingsStore(isolated).load().layerPanelSide == LayerPanelSide.LEFT)
            isolated.getSharedPreferences("", 0).edit().putString("layer_panel_side", "future-value").commit()
            check(store.load().layerPanelSide == LayerPanelSide.RIGHT)
            for ((undo, redo) in listOf(false to false, true to false, false to true, true to true)) {
                val configured = AppSettings(undoGestureEnabled = undo, redoGestureEnabled = redo)
                store.save(configured)
                check(AppSettingsStore(isolated).load() == configured)
            }
            val grayMarker = BrushPreset.MONOLINE.defaults().copy(size = 100.8f, shade = InkShade.LIGHT).compile()
            for (enabled in listOf(false, true)) {
                val configured = store.load().copy(firmwareCrashGuardEnabled = enabled)
                store.save(configured)
                val restored = AppSettingsStore(isolated).load()
                check(restored == configured)
                NativePreviewPolicy.configure(restored)
                check(NativePreviewPolicy.supports(grayMarker) == !enabled)
            }
            NativePreviewPolicy.configure(AppSettings(firmwareCrashGuardEnabled = originalRuntimeGuard))
            val directory = File(target.cacheDir, preferenceName).apply { mkdirs() }
            var manualFailure: Throwable? = null
            instrumentation.runOnMainSync {
                try {
                    val surface = RasterSurface(object : ContextWrapper(target) {
                        override fun getFilesDir() = directory
                    }, CanvasSpec(64, 64)) {}
                    try {
                        surface.createDocument(CanvasSpec(64, 64))
                        surface.configureHistoryGestures(false, false)
                        check(!surface.undoGestureEnabled && !surface.redoGestureEnabled)
                        surface.commit(listOf(PenSample(32f, 32f, 1f, 1)), "settings-test")
                        check(surface.history.canUndo && surface.undo() && surface.redo()) {
                            "Disabling gestures disabled manual history actions"
                        }
                    } finally { surface.dispose() }
                } catch (error: Throwable) { manualFailure = error }
                finally { directory.deleteRecursively() }
            }
            manualFailure?.let { throw it }

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
                    val content = dialog.findViewById<View>(android.R.id.content)
                    check(content.findViewWithTag<CheckBox>("Undo gesture").isChecked)
                    check(content.findViewWithTag<CheckBox>("Redo gesture").isChecked)
                    check(content.findViewWithTag<CheckBox>("Firmware crash guard").isChecked)
                    val body = content.findViewWithTag<android.widget.LinearLayout>("Settings content")
                    check(body.indexOfChild(content.findViewWithTag<View>("Advanced settings")) >
                        body.indexOfChild(content.findViewWithTag<View>("Redo gesture")))
                    content.findViewWithTag<CheckBox>("Undo gesture").performClick()
                    content.findViewWithTag<CheckBox>("Firmware crash guard").performClick()
                    dialog.findViewById<android.view.View>(android.R.id.content)
                        .findViewWithTag<RadioButton>("Layers panel left").performClick()
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    check(selected == AppSettings(LayerPanelSide.LEFT, undoGestureEnabled = false,
                        firmwareCrashGuardEnabled = false) && dialog.isShowing)
                    check(NativePreviewPolicy.firmwareCrashGuardEnabled == originalRuntimeGuard)
                    allowApply = true
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    check(!dialog.isShowing)
                    selected = null
                    dialog = SettingsDialog.show(activity, AppSettings()) { selected = it; true }
                    dialog.findViewById<View>(android.R.id.content)
                        .findViewWithTag<CheckBox>("Redo gesture").performClick()
                    dialog.findViewById<View>(android.R.id.content)
                        .findViewWithTag<CheckBox>("Firmware crash guard").performClick()
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                    check(selected == null)
                    check(NativePreviewPolicy.firmwareCrashGuardEnabled == originalRuntimeGuard)
                    val root = activity.findViewById<View>(android.R.id.content)
                    val undo = root.findViewWithTag<EinkIconButton>("Undo")
                    val redo = root.findViewWithTag<EinkIconButton>("Redo")
                    check(undo.parent === root.findViewWithTag<View>("brush.eraser").parent && undo.parent === redo.parent)
                    check(!undo.isEnabled && !redo.isEnabled)
                    // Exercise the actual Activity Apply handler and restore before yielding
                    // UI, so this check never opens a gray/wide guard-off SDK session.
                    val realStore = AppSettingsStore(target)
                    val saved = realStore.load()
                    val realPrefs = target.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                    val hadGuard = realPrefs.contains("firmware_crash_guard_enabled")
                    val settingsButton = root.findViewWithTag<EinkIconButton>("Settings")
                    fun settingsWindow() = checkNotNull(android.view.inspector.WindowInspector.getGlobalWindowViews()
                        .lastOrNull { it.findViewWithTag<View>("Settings content") != null })
                    try {
                        for (enabled in listOf(!saved.firmwareCrashGuardEnabled, saved.firmwareCrashGuardEnabled)) {
                            settingsButton.performClick()
                            val window = settingsWindow()
                            window.findViewWithTag<CheckBox>("Firmware crash guard").isChecked = enabled
                            window.findViewById<android.widget.Button>(android.R.id.button1).performClick()
                            check(realStore.load().firmwareCrashGuardEnabled == enabled) { "Activity Apply did not persist guard=$enabled" }
                            check(NativePreviewPolicy.firmwareCrashGuardEnabled == enabled)
                            check(NativePreviewPolicy.supports(grayMarker) == !enabled)
                        }
                    } finally {
                        NativePreviewPolicy.configure(saved)
                        realPrefs.edit().also { edit ->
                            if (hadGuard) edit.putBoolean("firmware_crash_guard_enabled", saved.firmwareCrashGuardEnabled)
                            else edit.remove("firmware_crash_guard_enabled")
                        }.commit()
                    }
                } catch (error: Throwable) { failure = error }
                finally { dialog?.dismiss(); activity.finish() }
            }
            instrumentation.waitForIdleSync()
            failure?.let { throw it }
        } finally {
            NativePreviewPolicy.configure(AppSettings(firmwareCrashGuardEnabled = originalRuntimeGuard))
            target.deleteSharedPreferences(preferenceName)
        }
    }
}
