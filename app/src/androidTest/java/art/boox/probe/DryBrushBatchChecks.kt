package art.boox.probe

import android.graphics.*

object DryBrushBatchChecks {
    fun run() {
        if (BuildConfig.RENDERER_BACKEND != "recovered") return
        val old = Bitmap.createBitmap(1200, 240, Bitmap.Config.ARGB_8888)
        val candidate = Bitmap.createBitmap(1200, 240, Bitmap.Config.ARGB_8888)
        try {
            for (family in listOf(BooxBrushFamily.PENCIL, BooxBrushFamily.CHARCOAL)) {
                val geometry = DryBrushGeometry(10f, family, pressure = true, tilt = true)
                val samples = List(1000) { i -> PenSample(30f + i, 100f + kotlin.math.sin(i * 0.04f) * 30f,
                    0.1f + (i % 100) / 111f, i * 4L, tiltRadians = Math.toRadians(50.0).toFloat(), documentAzimuthRadians = i * 0.01f) }
                for (shade in listOf(InkShade.BLACK, InkShade.MEDIUM)) for (locked in listOf(false, true)) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = shade.argb
                        if (locked) xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
                    val regularTimes = mutableListOf<Double>(); val batchTimes = mutableListOf<Double>()
                    val background = IntArray(1200 * 240) { index ->
                        if (locked) Color.argb(index % 256, 119, 119, 119) else Color.TRANSPARENT }
                    repeat(4) {
                        old.setPixels(background, 0, 1200, 0, 0, 1200, 240)
                        candidate.setPixels(background, 0, 1200, 0, 0, 1200, 240)
                        var start = StrokePerformance.now()
                        geometry.drawReference(Canvas(old), paint, samples)
                        regularTimes += StrokePerformance.ms(start)
                        start = StrokePerformance.now()
                        geometry.draw(Canvas(candidate), paint, samples)
                        batchTimes += StrokePerformance.ms(start)
                    }
                    var maxDelta = 0; var different = 0; var maxAlpha = 0; var maxVisible = 0
                    var visibleTotal = 0L; var painted = 0
                    val left = IntArray(1200 * 240); val right = IntArray(left.size)
                    old.getPixels(left, 0, 1200, 0, 0, 1200, 240); candidate.getPixels(right, 0, 1200, 0, 0, 1200, 240)
                    for (i in left.indices) {
                        if (Color.alpha(left[i]) > 0 || Color.alpha(right[i]) > 0) painted++
                        val a = Color.alpha(left[i]); val b = Color.alpha(right[i])
                        maxAlpha = maxOf(maxAlpha, kotlin.math.abs(a - b))
                        if (locked) check(a == Color.alpha(background[i]) && b == a) { "Batch changed locked alpha" }
                        if (left[i] == right[i]) continue
                        different++
                        var visible = 0
                        for (shift in intArrayOf(0, 8, 16, 24)) maxDelta = maxOf(maxDelta,
                            kotlin.math.abs(((left[i] ushr shift) and 255) - ((right[i] ushr shift) and 255)))
                        for (shift in intArrayOf(0, 8, 16)) {
                            val x = ((left[i] ushr shift) and 255) * a / 255
                            val y = ((right[i] ushr shift) and 255) * b / 255
                            visible = maxOf(visible, kotlin.math.abs(x - y), kotlin.math.abs((255 - a + x) - (255 - b + y)))
                        }
                        maxVisible = maxOf(maxVisible, visible); visibleTotal += visible
                    }
                    android.util.Log.i("BooxPerf", "dryBatchBenchmark family=$family shade=$shade locked=$locked changed=$different maxDelta=$maxDelta maxAlpha=$maxAlpha visibleMax=$maxVisible visibleMean=${visibleTotal.toDouble()/painted} regularMs=${regularTimes.drop(1).sorted()[1]} batchMs=${batchTimes.drop(1).sorted()[1]}")
                    // Compare visible premultiplied ink on both black and white;
                    // unpremultiplied RGB at near-zero alpha is not a useful error bound.
                    check(maxVisible <= 18 && maxAlpha <= if (locked) 0 else 18) { "Batch changed texture beyond the raster tolerance" }
                    check(visibleTotal.toDouble() / painted < 4.0)
                }
                for (size in listOf(1f, 4f, 32f, if (family == BooxBrushFamily.CHARCOAL) 128f else 64f)) {
                    for (shade in listOf(InkShade.BLACK, InkShade.VERY_LIGHT)) {
                        val tip = DryBrushGeometry(size, family, pressure = true, tilt = true)
                        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = shade.argb }
                        val traces = listOf(
                            listOf(PenSample(540.25f, 120.75f, 0.05f, 0)),
                            listOf(PenSample(540.25f, 120.75f, 1f, 0, tiltRadians = 1.14f, documentAzimuthRadians = 0.7f)),
                            List(30) { i -> PenSample(500f + i * 2f, 120f, i / 29f, i.toLong(),
                                tiltRadians = i / 29f * 1.2f, documentAzimuthRadians = i * 0.07f) })
                        for (trace in traces) {
                            old.eraseColor(Color.TRANSPARENT); candidate.eraseColor(Color.TRANSPARENT)
                            tip.drawReference(Canvas(old), pen, trace); tip.draw(Canvas(candidate), pen, trace)
                            val a = IntArray(1200 * 240); val b = IntArray(a.size)
                            old.getPixels(a, 0, 1200, 0, 0, 1200, 240); candidate.getPixels(b, 0, 1200, 0, 0, 1200, 240)
                            var max = 0
                            for (i in a.indices) {
                                val aa = Color.alpha(a[i]); val ba = Color.alpha(b[i])
                                max = maxOf(max, kotlin.math.abs(aa - ba))
                                for (shift in intArrayOf(0, 8, 16)) {
                                    val x = ((a[i] ushr shift) and 255) * aa / 255
                                    val y = ((b[i] ushr shift) and 255) * ba / 255
                                    max = maxOf(max, kotlin.math.abs(x - y), kotlin.math.abs((255 - aa + x) - (255 - ba + y)))
                                }
                            }
                            check(max <= 18) { "Batch tip fidelity family=$family size=$size shade=$shade max=$max" }
                        }
                    }
                }
            }
        } finally { old.recycle(); candidate.recycle() }
    }
}
