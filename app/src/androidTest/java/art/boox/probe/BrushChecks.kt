package art.boox.probe

import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import java.io.File

object BrushChecks {
    fun run(context: Context) {
        val preferenceName = "brush-tests-${System.nanoTime()}"
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences(preferenceName, mode)
            override fun getFilesDir() = File(context.cacheDir, preferenceName).apply { mkdirs() }
        }
        try {
            val store = BrushSettingsStore(isolated)
            check(store.selected() == BrushPreset.TILT)
            val original = PressureBrush()
            val compiled = store.load(BrushPreset.TILT).compile()
            val samples = (0..20).map { PenSample(10f + it * 4, 50f, it / 20f, it.toLong(),
                tiltRadians = Math.toRadians((it * 4).toDouble()).toFloat()) }
            samples.forEach { check(original.width(it) == compiled.width(it)) }
            val a = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            val b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            try {
                original.draw(Canvas(a), samples); compiled.draw(Canvas(b), samples)
                check(a.sameAs(b)) { "Default Tilt geometry changed" }
            } finally { a.recycle(); b.recycle() }
            val ink = BrushPreset.INK.defaults().compile()
            val mono = BrushPreset.MONOLINE.defaults().copy(size = 7f).compile()
            samples.forEach {
                check(ink.width(it) == original.width(it.pressure))
                check(mono.width(it) == 7f)
            }
            listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, 13f).forEach { invalid ->
                check(runCatching { BrushSettings(size = invalid) }.isFailure)
            }
            val adjusted = BrushPreset.INK.defaults().copy(size = 8.5f, tilt = true)
            store.save(adjusted)
            check(BrushSettingsStore(isolated).load(BrushPreset.INK) == adjusted)
            check(store.selected() == BrushPreset.INK)
            check(store.load(BrushPreset.MONOLINE) == BrushPreset.MONOLINE.defaults())
            isolated.getSharedPreferences("", 0).edit().putString("brush_selected", "future")
                .putFloat("brush_ink_size", Float.NaN).commit()
            check(store.selected() == BrushPreset.TILT && store.load(BrushPreset.INK).size == 12f)

            val strokes = mutableListOf<PenStroke>()
            val surface = RasterSurface(isolated, CanvasSpec(128, 128)) { strokes += it }
            try {
                surface.surfaceChanged(surface.holder, 0, 128, 128)
                surface.createDocument(CanvasSpec(128, 128))
                val captured = surface.brush
                surface.beginRawStroke()
                check(!surface.selectBrush(BrushPreset.MONOLINE.defaults()))
                surface.endRawStroke()
                check(surface.selectBrush(BrushPreset.MONOLINE.defaults()))
                surface.commit(samples, "queued", capturedBrush = captured)
                check(strokes.single().brush === captured) { "Queued stroke used a new brush" }
                val pixels = surface.document!!.layer.copy(Bitmap.Config.ARGB_8888, false)
                check(surface.undo()); check(surface.redo())
                check(surface.document!!.layer.sameAs(pixels))
                pixels.recycle()
            } finally { surface.dispose() }
        } finally {
            context.deleteSharedPreferences(preferenceName)
            File(context.cacheDir, preferenceName).deleteRecursively()
        }
    }

    fun dialogs(instrumentation: Instrumentation) {
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext,
            MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            var dialog: AlertDialog? = null
            try {
                var applied: BrushSettings? = null
                val initial = BrushPreset.TILT.defaults()
                dialog = BrushSettingsDialog.show(activity, initial) { applied = it; true }
                val root = dialog.findViewById<View>(android.R.id.content)
                val size = root.findViewWithTag<EditText>("Brush size input")
                size.setText("NaN")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                check(applied == null && dialog.isShowing)
                size.setText("6.5")
                root.findViewWithTag<CheckBox>("Brush pressure").isChecked = false
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                check(applied == initial.copy(size = 6.5f, pressure = false) && !dialog.isShowing)
                applied = null
                dialog = BrushSettingsDialog.show(activity, initial) { applied = it; true }
                dialog.findViewById<View>(android.R.id.content).findViewWithTag<EditText>("Brush size input").setText("3")
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                check(applied == null)
                val ink = activity.findViewById<View>(android.R.id.content).findViewWithTag<EinkIconButton>("brush.ink")
                check(ink != null && ink.minimumWidth >= (48 * activity.resources.displayMetrics.density).toInt())
                for (preset in BrushPreset.available.filter { it.booxFamily?.dry == true }) {
                    val settings = preset.defaults()
                    applied = null
                    dialog = BrushSettingsDialog.show(activity, settings) { applied = it; true }
                    val dryControls = dialog.findViewById<View>(android.R.id.content)
                    check(dryControls.findViewWithTag<CheckBox>("Brush tilt").isChecked)
                    dryControls.findViewWithTag<CheckBox>("Brush tilt").isChecked = false
                    dryControls.findViewWithTag<CheckBox>("Brush pressure").isChecked = false
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    check(applied == settings.copy(tilt = false, pressure = false))
                }
            } catch (error: Throwable) { failure = error }
            finally { dialog?.dismiss(); activity.finish() }
        }
        instrumentation.waitForIdleSync()
        failure?.let { throw it }
    }
}
