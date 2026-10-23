package art.boox.probe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.File

object BooxBrushChecks {
    fun run(context: Context) {
        if (!BuildConfig.BOOX_LAB) return
        checkFamily(context, BrushPreset.LAB_BRUSH)
    }

    fun checkFamily(context: Context, preset: BrushPreset) {
        val settings = preset.defaults()
        val brush = settings.compile()
        check(brush.usesBooxEngine && !brush.hasDynamicWidth)
        check(NativePreviewPolicy.supports(brush))
        check(LivePreviewWidth.forBrush(brush, 1f, post = { _, _ -> error("Double dynamics") },
            remove = {}, apply = { error("Double dynamics") }) == null)
        val samples = List(180) { i -> PenSample(35f + i * 1.4f,
            80f + kotlin.math.sin(i * 0.08f) * 24f, 0.15f + 0.85f * i / 179f, i * 4L) }
        val document = DrawingDocument.create(CanvasSpec(340, 180))
        val rendered = Bitmap.createBitmap(340, 180, Bitmap.Config.ARGB_8888)
        val replay = Bitmap.createBitmap(340, 180, Bitmap.Config.ARGB_8888)
        try {
            val started = StrokePerformance.now()
            val bounds = checkNotNull(brush.renderedBounds(samples))
            val prepared = StrokePerformance.now()
            brush.draw(Canvas(rendered), samples)
            val drawn = StrokePerformance.now()
            settings.compile().draw(Canvas(replay), samples)
            check(rendered.sameAs(replay)) { "${preset.label} replay changed" }
            var painted = 0
            for (y in 0 until rendered.height) for (x in 0 until rendered.width) {
                if (Color.alpha(rendered.getPixel(x, y)) > 0) {
                    painted++
                    check(bounds.contains(x.toFloat(), y.toFloat())) { "${preset.label} bounds missed $x,$y" }
                }
            }
            check(painted > 250)
            val history = EditHistory()
            check(history.execute(document, StrokeEdit(samples, brush)))
            check(document.layer.sameAs(rendered))
            check(history.undo(document) == "Stroke")
            for (y in 0 until rendered.height) for (x in 0 until rendered.width)
                check(document.layer.getPixel(x, y) == 0) { "Incomplete ${preset.label} undo" }
            check(history.redo(document) == "Stroke" && document.layer.sameAs(rendered))
            replay.eraseColor(Color.TRANSPARENT)
            brush.draw(Canvas(replay), listOf(PenSample(64f, 64f, 1f, 0)))
            check(Color.alpha(replay.getPixel(64, 64)) > 0)
            // SRC_ATOP keeps transparent pixels transparent, including native engine output.
            replay.eraseColor(Color.TRANSPARENT)
            Canvas(replay).drawRect(50f, 0f, 150f, 180f, android.graphics.Paint().apply { color = Color.BLACK })
            val gray = settings.copy(shade = InkShade.LIGHT).compile()
            gray.draw(Canvas(replay), samples, alphaLocked = true)
            var changed = false
            for (y in 0 until replay.height) for (x in 0 until replay.width) {
                val color = replay.getPixel(x, y)
                check(Color.alpha(color) == if (x in 50 until 150) 255 else 0)
                if (x in 50 until 150 && Color.red(color) > 0) changed = true
            }
            check(changed && !NativePreviewPolicy.supports(gray))
            File(context.cacheDir, "${preset.id}-${BuildConfig.RENDERER_BACKEND}.png").outputStream().use {
                rendered.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            android.util.Log.i("BooxPerf", "brushEngine preset=${preset.id} samples=${samples.size} painted=$painted geometryMs=${StrokePerformance.ms(started, prepared)} drawMs=${StrokePerformance.ms(prepared, drawn)}")
        } finally { document.recycle(); rendered.recycle(); replay.recycle() }
    }
}
