package art.boox.probe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File

object BooxNotesChecks {
    fun run(context: Context) {
        check(BooxNotesConfig.fromParameters(BooxNotesPen.PENCIL_FINE, floatArrayOf(1f, 3f)).tiltEnabled)
        check(!BooxNotesConfig.fromParameters(BooxNotesPen.PENCIL_FINE, floatArrayOf(Float.NaN)).tiltEnabled)
        val axis = BooxNotesGeometry.tiltAxes(PenSample(0f, 0f, 1f, 0, rawTiltX = 45, rawTiltY = 0,
            documentAzimuthRadians = (Math.PI / 2).toFloat()))
        check(kotlin.math.abs(axis.first) <= 1 && axis.second in 44..45)
        val samples = List(180) { i -> PenSample(30f + i * 1.4f,
            80f + kotlin.math.sin(i * 0.08f) * 24f, 0.15f + 0.85f * i / 179f, i * 4L,
            rawTiltX = -25, rawTiltY = 35) }
        for (preset in BrushPreset.available.filter { it.notesPen != null }) {
            val brush = preset.defaults().compile()
            check(!brush.hasDynamicWidth && NativePreviewPolicy.supports(brush))
            val rendered = Bitmap.createBitmap(340, 180, Bitmap.Config.ARGB_8888)
            val replay = Bitmap.createBitmap(340, 180, Bitmap.Config.ARGB_8888)
            val document = DrawingDocument.create(CanvasSpec(340, 180))
            try {
                val start = StrokePerformance.now()
                val bounds = checkNotNull(brush.renderedBounds(samples)) { "${preset.id} has no bounds" }
                val prepared = StrokePerformance.now()
                brush.draw(Canvas(rendered), samples)
                val drawn = StrokePerformance.now()
                brush.draw(Canvas(replay), samples)
                check(rendered.sameAs(replay)) { "${preset.id} cached preview/commit differ" }
                replay.eraseColor(Color.TRANSPARENT)
                preset.defaults().compile().draw(Canvas(replay), samples.toList())
                check(rendered.sameAs(replay)) { "${preset.id} regenerated stroke differs" }
                val segment = listOf(PenSample(60f, 90f, 0.5f, 2), PenSample(180f, 90f, 0.5f, 2))
                val beforeLift = Bitmap.createBitmap(340, 180, Bitmap.Config.ARGB_8888)
                val afterLift = Bitmap.createBitmap(340, 180, Bitmap.Config.ARGB_8888)
                try {
                    brush.draw(Canvas(beforeLift), segment)
                    brush.draw(Canvas(afterLift), segment + segment.last().copy(time = 4))
                    check(beforeLift.sameAs(afterLift)) { "${preset.id} duplicate lift sample changes geometry" }
                } finally { beforeLift.recycle(); afterLift.recycle() }
                var ink = 0
                for (y in 0 until 180) for (x in 0 until 340) {
                    if (Color.alpha(rendered.getPixel(x, y)) > 0) {
                        ink++
                        check(bounds.contains(x.toFloat(), y.toFloat())) { "${preset.id} bounds miss $x,$y: $bounds" }
                    }
                }
                check(ink > 100) { "${preset.id} returned no usable ink" }
                val history = EditHistory()
                check(history.execute(document, StrokeEdit(samples, brush)))
                check(document.layer.sameAs(rendered)) { "${preset.id} history commit differs" }
                check(history.undo(document) == "Stroke")
                val blank = IntArray(340 * 180)
                document.layer.getPixels(blank, 0, 340, 0, 0, 340, 180)
                check(blank.all { it == 0 }) { "${preset.id} undo left ink" }
                check(history.redo(document) == "Stroke" && document.layer.sameAs(rendered))
                File(context.cacheDir, "${preset.id}.png").outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }
                replay.eraseColor(Color.TRANSPARENT)
                brush.draw(Canvas(replay), listOf(PenSample(64f, 64f, 1f, 0)))
                check((40 until 90).any { y -> (40 until 90).any { x -> Color.alpha(replay.getPixel(x, y)) > 0 } }) {
                    "${preset.id} dot disappeared"
                }
                replay.eraseColor(Color.TRANSPARENT)
                Canvas(replay).drawRect(50f, 0f, 150f, 180f, Paint().apply { color = Color.BLACK })
                preset.defaults().copy(shade = InkShade.LIGHT).compile().draw(Canvas(replay), samples, alphaLocked = true)
                var grayInk = false
                for (y in 0 until 180) for (x in 0 until 340) {
                    val pixel = replay.getPixel(x, y)
                    check(Color.alpha(pixel) == if (x in 50 until 150) 255 else 0) { "${preset.id} changed locked alpha" }
                    if (Color.red(pixel) > 0) grayInk = true
                }
                check(grayInk) { "${preset.id} ignored gray" }
                android.util.Log.i("BooxPerf", "booxNotesCheck preset=${preset.id} ink=$ink geometryMs=${StrokePerformance.ms(start, prepared)} rasterMs=${StrokePerformance.ms(prepared, drawn)}")
            } finally { rendered.recycle(); replay.recycle(); document.recycle() }
        }
        // Exercise both libraries in the same process after original-library use.
        BooxFountainChecks.run(context)
        if (BuildConfig.RENDERER_BACKEND == "recovered") BooxBrushChecks.checkFamily(context, BrushPreset.LAB_PENCIL)
    }
}
