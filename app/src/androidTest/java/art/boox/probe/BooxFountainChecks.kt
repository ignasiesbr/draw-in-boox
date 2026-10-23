package art.boox.probe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import java.io.File

object BooxFountainChecks {
    fun run(context: Context) {
        if (!BuildConfig.BOOX_LAB) return
        val settings = BrushPreset.LAB_FOUNTAIN.defaults()
        val brush = settings.compile()
        check(brush.isBooxFountain && !brush.hasDynamicWidth && NativePreviewPolicy.supports(brush))
        check(LivePreviewWidth.forBrush(brush, 1f, post = { _, _ -> error("Vendor pressure received app width updates") },
            remove = {}, apply = { error("Vendor pressure was applied twice") }) == null)
        val samples = List(100) { i -> PenSample(20f + i * 2.7f, 70f + kotlin.math.sin(i * 0.1f) * 20,
            0.1f + i / 110f, i.toLong() * 4) }
        val document = DrawingDocument.create(CanvasSpec(320, 160))
        val expected = Bitmap.createBitmap(320, 160, Bitmap.Config.ARGB_8888)
        val replay = Bitmap.createBitmap(320, 160, Bitmap.Config.ARGB_8888)
        val dot = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            val started = StrokePerformance.now()
            val bounds = checkNotNull(brush.renderedBounds(samples))
            val prepared = StrokePerformance.now()
            brush.draw(Canvas(expected), samples)
            val drawn = StrokePerformance.now()
            File(context.cacheDir, "fountain-${BuildConfig.RENDERER_BACKEND}.png").outputStream().use {
                expected.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            settings.compile().draw(Canvas(replay), samples)
            check(expected.sameAs(replay)) { "Native fountain replay changed pixels" }
            var painted = 0
            var solidX = -1; var solidY = -1
            for (y in 0 until expected.height) for (x in 0 until expected.width) {
                if (Color.alpha(expected.getPixel(x, y)) != 0) {
                    painted++
                    if (Color.alpha(expected.getPixel(x, y)) == 255) { solidX = x; solidY = y }
                    check(bounds.contains(x.toFloat(), y.toFloat())) { "Vendor footprint missed $x,$y" }
                }
            }
            check(painted > 100) { "Vendor renderer returned no usable stroke" }
            val history = EditHistory()
            check(history.execute(document, StrokeEdit(samples, brush)))
            check(document.layer.sameAs(expected))
            check(history.undo(document) == "Stroke" && document.layer.getPixel(100, 70) == 0)
            for (y in 0 until expected.height) for (x in 0 until expected.width)
                check(document.layer.getPixel(x, y) == 0) { "Undo left fountain pixels outside captured tiles" }
            check(history.redo(document) == "Stroke" && document.layer.sameAs(expected))
            brush.draw(Canvas(dot), listOf(PenSample(32f, 32f, 1f, 0)))
            check(Color.alpha(dot.getPixel(32, 32)) > 0) { "One-point fountain stroke disappeared" }
            // Shared app preview/commit must preserve gray and alpha lock too.
            document.layer.eraseColor(Color.BLACK)
            document.activeLayer.alphaLocked = true
            val gray = settings.copy(shade = InkShade.LIGHT).compile()
            gray.draw(Canvas(document.layer), samples, alphaLocked = true)
            check(Color.alpha(document.layer.getPixel(100, 70)) == 255)
            check(solidX >= 0 && Color.red(document.layer.getPixel(solidX, solidY)) == InkShade.LIGHT.level)
            val grayBounds: RectF = checkNotNull(gray.renderedBounds(samples))
            check(grayBounds == bounds)
            val abrupt = listOf(PenSample(16f, 32f, 0f, 0), PenSample(48f, 32f, 1f, 1))
            dot.eraseColor(Color.TRANSPARENT)
            brush.draw(Canvas(dot), abrupt)
            val abruptBounds = checkNotNull(brush.renderedBounds(abrupt))
            for (y in 0 until dot.height) for (x in 0 until dot.width)
                if (Color.alpha(dot.getPixel(x, y)) != 0) check(abruptBounds.contains(x.toFloat(), y.toFloat()))
            android.util.Log.i("BooxPerf", "fountainLab backend=${BuildConfig.RENDERER_BACKEND} samples=${samples.size} painted=$painted bounds=$bounds geometryMs=${StrokePerformance.ms(started, prepared)} drawMs=${StrokePerformance.ms(prepared, drawn)} totalMs=${StrokePerformance.ms(started)}")
        } finally { document.recycle(); expected.recycle(); replay.recycle(); dot.recycle() }
    }
}
