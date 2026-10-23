package art.boox.probe

import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.EditText
import java.io.File

object EraserChecks {
    fun run(context: Context) {
        val preferenceName = "eraser-tests-${System.nanoTime()}"
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = context.getSharedPreferences(preferenceName, mode)
            override fun getFilesDir() = File(context.cacheDir, preferenceName).apply { mkdirs() }
        }
        val document = DrawingDocument.create(CanvasSpec(320, 160))
        val history = EditHistory()
        val eraser = BrushPreset.ERASER.defaults().copy(size = 48f).compile()
        val samples = listOf(PenSample(96f, 64f, 0.1f, 1), PenSample(310f, 64f, 1f, 2))
        fun composite(pending: Boolean = false): Bitmap {
            val pixels = Bitmap.createBitmap(320, 160, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(pixels)
            canvas.drawColor(Color.WHITE)
            if (pending) document.layers.forEach { layer ->
                document.drawLayer(canvas, layer, pending = if (layer.id == document.layerId)
                    ({ target -> eraser.draw(target, samples, layer.alphaLocked) }) else null, isolatePending = true)
            } else document.drawComposite(canvas)
            return pixels
        }
        try {
            check(eraser.isEraser && eraser.engineId == "round-eraser" && !eraser.hasDynamicWidth)
            check(!NativePreviewPolicy.supports(eraser))
            check(!NativePreviewPolicy.supports(BrushPreset.ERASER.defaults().copy(size = 1f).compile()))
            val store = BrushSettingsStore(isolated)
            val ink = BrushPreset.INK.defaults().copy(shade = InkShade.LIGHT, size = 8f)
            store.save(ink)
            store.save(store.load(BrushPreset.ERASER).copy(size = 85f))
            check(store.selected() == BrushPreset.ERASER && store.load(BrushPreset.ERASER).size == 85f)
            check(store.load(BrushPreset.INK) == ink) { "Eraser changed ink shade/size" }

            document.layer.eraseColor(InkShade.LIGHT.argb)
            val lowerId = document.layerId
            document.addLayer()
            document.layer.eraseColor(Color.BLACK)
            for (opacity in listOf(100, 50)) {
                document.layer.eraseColor(Color.BLACK)
                document.setLayerOpacity(document.layerId, opacity)
                val preview = composite(true)
                try {
                    check(document.layer.getPixel(160, 64) == Color.BLACK && history.undoCount == 0)
                    check(preview.getPixel(160, 64) == InkShade.LIGHT.argb) { "Preview cleared lower ink or paper" }
                    val before = document.layer.copy(Bitmap.Config.ARGB_8888, false)
                    try {
                        check(history.execute(document, StrokeEdit(samples, eraser)))
                        check(document.layer.getPixel(160, 64) == Color.TRANSPARENT)
                        check(document.layer.getPixel(160, 100) == Color.BLACK)
                        check(document.findLayer(lowerId)!!.bitmap.getPixel(160, 64) == InkShade.LIGHT.argb)
                        val committed = composite()
                        try { check(preview.sameAs(committed)) { "Live erasing differs from final layer composite at opacity $opacity" } }
                        finally { committed.recycle() }
                        check(history.undo(document) == "Erase" && document.layer.sameAs(before))
                        check(history.redo(document) == "Erase" && document.layer.getPixel(160, 64) == 0)
                    } finally { before.recycle() }
                } finally { preview.recycle(); history.clear() }
            }
            document.layer.eraseColor(Color.BLACK)
            document.setLayerProperties(document.layerId, LayerProperties(100, true))
            check(!history.execute(document, StrokeEdit(samples, eraser))) { "Eraser bypassed alpha lock" }
            check(document.layer.getPixel(160, 64) == Color.BLACK)
            document.setLayerProperties(document.layerId, LayerProperties(100, false))
            val dot = listOf(PenSample(20f, 120f, 0f, 1))
            check(history.execute(document, StrokeEdit(dot, eraser)))
            check(document.layer.getPixel(20, 120) == 0 && document.layer.getPixel(50, 120) == Color.BLACK)
            check(!history.execute(document, StrokeEdit(listOf(PenSample(-100f, -100f, 1f, 1)), eraser)))

            document.deleteLayer(document.layerId)
            document.layer.eraseColor(Color.BLACK)
            check(history.execute(document, StrokeEdit(samples, eraser)))
            val file = File(isolated.filesDir, "erased.png")
            check(document.exportPng(file))
            BitmapFactory.decodeFile(file.path).also {
                check(it.getPixel(160, 64) == Color.WHITE && it.getPixel(160, 100) == Color.BLACK)
                it.recycle()
            }
            val surface = RasterSurface(isolated, CanvasSpec(320, 160)) {}
            try {
                surface.createDocument(CanvasSpec(320, 160))
                surface.selectBrush(BrushPreset.ERASER.defaults())
                surface.document!!.layer.eraseColor(Color.BLACK)
                surface.document!!.activeLayer.locked = true
                check(surface.commit(samples, "android") == null)
                surface.document!!.activeLayer.locked = false
                surface.document!!.activeLayer.visible = false
                check(surface.commit(samples, "android") == null)
                check(surface.history.undoCount == 0 && surface.document!!.layer.getPixel(160, 64) == Color.BLACK)
            } finally { surface.dispose() }
        } finally {
            document.recycle(); context.deleteSharedPreferences(preferenceName)
            File(context.cacheDir, preferenceName).deleteRecursively()
        }
    }

    fun dialogs(instrumentation: Instrumentation) {
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext,
            MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val prefs = instrumentation.targetContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val original = prefs.all
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            var dialog: AlertDialog? = null
            try {
                val initial = BrushPreset.ERASER.defaults().copy(shade = InkShade.LIGHT)
                var applied: BrushSettings? = null
                dialog = BrushSettingsDialog.show(activity, initial) { applied = it; true }
                val root = dialog.findViewById<View>(android.R.id.content)
                check(root.findViewWithTag<View>("Brush pressure") == null && root.findViewWithTag<View>("Brush tilt") == null)
                root.findViewWithTag<EditText>("Brush size input").setText("128")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                check(applied == initial.copy(size = 128f))

                val tools = activity.findViewById<View>(android.R.id.content)
                check(listOf("brush.size", "brush.settings", "brush.shade").all { tools.findViewWithTag<View>(it) == null })
                val surface = tools.findViewWithTag<RasterSurface>("Drawing surface")
                // First tap changes tools; the second opens settings for the selected tool.
                val next = if (surface.brush.isEraser) BrushPreset.INK else BrushPreset.ERASER
                val button = tools.findViewWithTag<EinkIconButton>("brush.${next.id}")
                button.performClick()
                check(button.isChecked && surface.brush.settings!!.preset == next)
                val selected = surface.brush.settings
                fun settingsWindow() = WindowInspector.getGlobalWindowViews()
                    .firstOrNull { it.findViewWithTag<View>("brush.dialog") != null }
                check(settingsWindow() == null) { "First tap opened settings instead of selecting" }
                button.performClick()
                val settingsRoot = checkNotNull(settingsWindow()) { "Selected tool did not open settings" }
                settingsRoot.findViewWithTag<EditText>("Brush size input").setText("4")
                settingsRoot.findViewById<Button>(android.R.id.button2).performClick()
                check(surface.brush.settings == selected) { "Cancel applied brush changes" }
            } catch (error: Throwable) { failure = error }
            finally { dialog?.dismiss(); activity.finish() }
        }
        instrumentation.waitForIdleSync()
        // Restore preferences touched through real toolbar selection.
        prefs.edit().clear().also { edit -> original.forEach { (key, value) ->
            when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Float -> edit.putFloat(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
            }
        } }.commit()
        failure?.let { throw it }
    }
}
