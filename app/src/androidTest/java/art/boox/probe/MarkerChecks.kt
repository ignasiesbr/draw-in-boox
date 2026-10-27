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
import android.widget.EditText
import java.io.File

object MarkerChecks {
    fun benchmark() {
        val image = Bitmap.createBitmap(1280, 512, Bitmap.Config.ARGB_8888)
        val samples = List(1000) { i -> PenSample(140f + i * 0.96f,
            256f + kotlin.math.sin(i * 0.02f) * 40f, i / 999f, i.toLong()) }
        try {
            val original = PressureBrush(128f, 128f, NoWidthModifier)
            val marker = BrushPreset.MONOLINE.defaults().copy(size = 128f).compile()
            for (brush in listOf(original, marker)) {
                val timings = mutableListOf<Double>()
                repeat(9) { i ->
                    image.eraseColor(Color.TRANSPARENT)
                    val start = StrokePerformance.now()
                    brush.draw(Canvas(image), samples)
                    if (i >= 4) timings += StrokePerformance.ms(start)
                }
                android.util.Log.i("BooxPerf", "markerBenchmark engine=${brush.engineId} samples=1000 widthPx=128 medianDrawMs=${timings.sorted()[2]}")
            }
        } finally { image.recycle() }
    }

    fun run(context: Context) {
        val preferenceName = "marker-tests-${System.nanoTime()}"
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = context.getSharedPreferences(preferenceName, mode)
            override fun getFilesDir() = File(context.cacheDir, preferenceName).apply { mkdirs() }
        }
        try {
            val defaults = BrushPreset.MONOLINE.defaults()
            check(defaults.preset.label == "Marker" && defaults.size == 32f)
            val marker = defaults.copy(size = 128f, shade = InkShade.DEEP).compile()
            check(marker.engineId == "round-marker" && marker.minWidth == 128f && marker.maxRenderedWidth == 128f)
            for (pressure in listOf(0f, 0.5f, 1f)) check(marker.width(PenSample(0f, 0f, pressure, 0, tiltRadians = 1.4f)) == 128f)
            for (invalid in listOf(0f, 129f, Float.NaN, Float.POSITIVE_INFINITY))
                check(runCatching { defaults.copy(size = invalid) }.isFailure)
            check(runCatching { BrushSettings(size = 13f) }.isFailure)

            val prefs = isolated.getSharedPreferences("", 0)
            prefs.edit().putInt("brush_schema", 1).putString("brush_selected", "monoline")
                .putFloat("brush_monoline_size", 12f).commit()
            val store = BrushSettingsStore(isolated)
            check(store.selected() == BrushPreset.MONOLINE && store.load(BrushPreset.MONOLINE).size == 12f)
            check(store.load(BrushPreset.MONOLINE).shade == InkShade.BLACK)
            store.save(defaults.copy(size = 96f, shade = InkShade.LIGHT))
            check(prefs.getInt("brush_schema", 0) == 4)
            check(BrushSettingsStore(isolated).load(BrushPreset.MONOLINE) == defaults.copy(size = 96f, shade = InkShade.LIGHT))
            check(store.load(BrushPreset.INK).shade == InkShade.BLACK) { "Marker changed another pen's shade" }
            prefs.edit().putInt("brush_monoline_gray_level", 999).putFloat("brush_monoline_size", 129f).commit()
            check(store.load(BrushPreset.MONOLINE) == defaults)

            val image = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            try {
                for (shade in InkShade.entries) {
                    image.eraseColor(Color.TRANSPARENT)
                    val brush = defaults.copy(size = 64f, shade = shade).compile()
                    check(Color.alpha(brush.inkColor) == 255 && Color.red(brush.inkColor) == shade.level)
                    brush.draw(Canvas(image), listOf(PenSample(40f, 128f, 0f, 0), PenSample(215f, 128f, 1f, 1)))
                    brush.draw(Canvas(image), listOf(PenSample(128f, 40f, 1f, 2), PenSample(128f, 215f, 0f, 3)))
                    check(image.getPixel(128, 128) == shade.argb) { "Opaque gray changed at overlap" }
                    brush.draw(Canvas(image), listOf(PenSample(30f, 30f, 1f, 4)))
                    check(image.getPixel(30, 30) == shade.argb) { "Marker dot became an outline after path rendering" }
                }
            } finally { image.recycle() }

            val document = DrawingDocument.create(CanvasSpec(385, 385))
            try {
                val empty = document.layer.copy(Bitmap.Config.ARGB_8888, false)
                val history = EditHistory()
                val samples = listOf(PenSample(127f, 127f, 0f, 0), PenSample(257f, 257f, 1f, 1), PenSample(5f, 380f, 0f, 2))
                check(history.execute(document, StrokeEdit(samples, marker)))
                check(document.layer.getPixel(127, 127) == InkShade.DEEP.argb)
                val after = document.layer.copy(Bitmap.Config.ARGB_8888, false)
                check(history.undo(document) != null && document.layer.sameAs(empty)) { "Wide marker escaped history tiles" }
                check(history.redo(document) != null && document.layer.sameAs(after))
                val png = File(isolated.filesDir, "marker.png")
                check(document.exportPng(png))
                BitmapFactory.decodeFile(png.path).also { check(it.getPixel(127, 127) == InkShade.DEEP.argb); it.recycle() }
                empty.recycle(); after.recycle()
            } finally { document.recycle() }

            val strokes = mutableListOf<PenStroke>()
            val surface = RasterSurface(isolated, CanvasSpec(256, 256)) { strokes += it }
            try {
                surface.surfaceChanged(surface.holder, 0, 256, 256); surface.createDocument(CanvasSpec(256, 256))
                check(surface.selectBrush(defaults.copy(size = 96f, shade = InkShade.LIGHT)))
                val captured = surface.brush
                check(surface.selectBrush(defaults.copy(size = 128f, shade = InkShade.NEAR_BLACK)))
                surface.commit(listOf(PenSample(128f, 128f, 1f, 0)), "captured-marker", capturedBrush = captured)
                check(strokes.single().brush === captured)
                check(surface.document!!.layer.getPixel(128, 128) == InkShade.LIGHT.argb)
                check(Color.alpha(surface.document!!.layer.getPixel(128, 182)) == 0) { "Queued marker used the later size" }
            } finally { surface.dispose() }

            var live = 0f
            val preview = LivePreviewWidth(widthForSample = { marker.width(it) * 4f },
                post = { task, _ -> task.run() }, remove = {}, apply = { live = it })
            preview.begin(PenSample(0f, 0f, 0f, 0)); preview.finish()
            check(live == 512f) { "Wide marker preview applied zoom incorrectly" }
        } finally { context.deleteSharedPreferences(preferenceName); File(context.cacheDir, preferenceName).deleteRecursively() }
    }

    fun dialogs(instrumentation: Instrumentation) {
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext,
            MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            var dialog: AlertDialog? = null
            try {
                var selected: InkShade? = null
                var allow = false
                dialog = InkShadeDialog.show(activity, InkShade.BLACK) { selected = it; allow }
                dialog.findViewById<View>(android.R.id.content).findViewWithTag<EinkIconButton>("ink.shade.238").performClick()
                check(selected == null) { "Palette draft changed the current ink before Apply" }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                check(selected == InkShade.VERY_LIGHT && dialog.isShowing)
                allow = true
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                check(!dialog.isShowing)
                selected = null
                dialog = InkShadeDialog.show(activity, InkShade.BLACK) { selected = it; true }
                dialog.findViewById<View>(android.R.id.content).findViewWithTag<EinkIconButton>("ink.shade.68").performClick()
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                check(selected == null)

                var settings: BrushSettings? = null
                dialog = BrushSettingsDialog.show(activity, BrushPreset.MONOLINE.defaults(), sizeOnly = true) { settings = it; true }
                val input = dialog.findViewById<View>(android.R.id.content).findViewWithTag<EditText>("Brush size input")
                input.setText("129"); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                check(settings == null && dialog.isShowing)
                input.setText("128"); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                check(settings?.size == 128f && !dialog.isShowing)
                val root = activity.findViewById<View>(android.R.id.content)
                check(root.findViewWithTag<EinkIconButton>("brush.monoline").contentDescription == "Marker")
                check(root.findViewWithTag<EinkIconButton>("brush.shade") == null)
            } catch (error: Throwable) { failure = error }
            finally { dialog?.dismiss(); activity.finish() }
        }
        instrumentation.waitForIdleSync()
        failure?.let { throw it }
    }
}
