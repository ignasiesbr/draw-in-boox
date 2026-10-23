package art.boox.probe

import android.graphics.*
import com.onyx.android.sdk.pennative.NeoPenNative
import com.onyx.android.sdk.pennative.PenConfig
import com.onyx.android.sdk.pennative.PenInkResult
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Source-only seven-float dab contract. No bitmap is allocated by JNI per dab. */
internal class DryBrushGeometry(private val size: Float, color: Int, private val family: BooxBrushFamily,
    private val pressure: Boolean, private val tilt: Boolean) {
    private val tint = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
    private val destination = RectF()
    private var cachedSamples: List<PenSample>? = null
    private var cachedCount = -1
    private var cachedLast: PenSample? = null
    private var data = FloatArray(0)
    private var bounds: RectF? = null

    fun bounds(samples: List<PenSample>): RectF? { prepare(samples); return bounds?.let(::RectF) }

    fun draw(canvas: Canvas, paint: Paint, samples: List<PenSample>) {
        prepare(samples)
        val oldAlpha = paint.alpha; val oldFilter = paint.colorFilter; val oldFiltering = paint.isFilterBitmap
        paint.colorFilter = tint; paint.isFilterBitmap = true
        try {
            for (i in data.indices step 7) {
                val major = data[i + 2]; val minor = data[i + 3]
                paint.alpha = (data[i + 5] * oldAlpha).toInt().coerceIn(0, 255)
                val saved = canvas.save()
                canvas.translate(data[i], data[i + 1]); canvas.rotate(data[i + 4])
                destination.set(-major / 2f, -minor / 2f, major / 2f, minor / 2f)
                canvas.drawBitmap(DryBrushTips.pencil[data[i + 6].toInt()], null, destination, paint)
                canvas.restoreToCount(saved)
            }
        } finally { paint.alpha = oldAlpha; paint.colorFilter = oldFilter; paint.isFilterBitmap = oldFiltering }
    }

    private fun prepare(samples: List<PenSample>) {
        if (samples === cachedSamples && samples.size == cachedCount && samples.lastOrNull() == cachedLast) return
        if (samples.isEmpty()) { data = FloatArray(0); bounds = null; return }
        check(BuildConfig.RENDERER_BACKEND == "recovered") { "Dry brushes require the editable renderer" }
        val config = PenConfig().apply {
            type = family.rendererType; width = size; maxTouchPressure = 1f
            pressureSensitivity = if (pressure) 1f else 0f; tiltEnabled = tilt
            displayScaleX = 1f; displayScaleY = 1f
        }
        val native = NeoPenNative
        val handle = native.createPen(config.type, config)
        check(handle != 0L) { "Could not create ${family.label}" }
        var output = FloatArray(4096)
        var used = 0
        var resultBounds: RectF? = null
        val origin = samples.first().time
        fun encode(start: Int, end: Int) = DoubleArray((end - start) * 7).also { encoded ->
            for (index in start until end) {
                val sample = samples[index]; val offset = (index - start) * 7
                encoded[offset] = sample.x.toDouble(); encoded[offset + 1] = sample.y.toDouble()
                encoded[offset + 2] = sample.pressure.toDouble()
                encoded[offset + 4] = sample.tiltRadians?.takeIf { it.isFinite() }?.let { Math.toDegrees(it.toDouble()) } ?: 0.0
                encoded[offset + 5] = PenTilt.azimuth(sample)?.let { Math.toDegrees(it.toDouble()) } ?: 0.0
                encoded[offset + 6] = (sample.time - origin).coerceAtLeast(0).toDouble()
            }
        }
        fun append(result: PenInkResult?) {
            val ink = result?.realInk ?: return
            val values = ink.points
            check(ink.bitmaps.isEmpty() && ink.pointSizeArray.all { it == 7 } && values.size == ink.pointSizeArray.size * 7)
            val required = used + values.size
            check(required <= 700_000) { "${family.label} exceeds the stroke geometry budget" }
            if (required > output.size) output = output.copyOf(maxOf(required, output.size * 2).coerceAtMost(700_000))
            for (i in values.indices step 7) {
                check((i until i + 7).all { values[it].isFinite() }) { "Invalid dry brush geometry" }
                val major = values[i + 2]; val minor = values[i + 3]
                check(major > 0f && major <= size * 2.5f + 0.001f && minor > 0f && minor <= major &&
                    values[i + 5] in 0f..1f && values[i + 6] in 0f..3f)
                val angle = Math.toRadians(values[i + 4].toDouble())
                val dx = (abs(cos(angle)) * major + abs(sin(angle)) * minor).toFloat() / 2f + 2f
                val dy = (abs(sin(angle)) * major + abs(cos(angle)) * minor).toFloat() / 2f + 2f
                val region = RectF(values[i] - dx, values[i + 1] - dy, values[i] + dx, values[i + 1] + dy)
                resultBounds?.union(region) ?: run { resultBounds = region }
            }
            values.copyInto(output, used); used = required
        }
        try {
            append(native.onPenDown(handle, encode(0, 1), false))
            // Batching reduces JNI/Java result allocations; spacing state crosses batch boundaries.
            var index = 1
            while (index < samples.lastIndex) {
                val end = minOf(index + 128, samples.lastIndex)
                append(native.onPenMove(handle, encode(index, end), null, false)); index = end
            }
            append(native.onPenUp(handle, encode(samples.lastIndex, samples.size), false))
        } finally { native.destroyPen(handle) }
        data = output.copyOf(used); bounds = resultBounds
        cachedSamples = samples; cachedCount = samples.size; cachedLast = samples.last()
    }
}

/** Four shared, bounded masks; colors and sizes reuse these resources. Deterministic grain. */
internal object DryBrushTips {
    val pencil: Array<Bitmap> by lazy { Array(4) { variant ->
        val side = 64
        val pixels = IntArray(side * side) { index ->
            val x = (index % side + 0.5f - side / 2f) / (side / 2f)
            val y = (index / side + 0.5f - side / 2f) / (side / 2f)
            val radius = x * x + y * y
            var hash = index * 374761393 + variant * 668265263
            hash = (hash xor (hash ushr 13)) * 1274126177
            val grain = (hash ushr 24) and 255
            val alpha = if (radius >= 1f) 0 else ((1f - radius) * (60 + grain * 0.76f)).toInt()
            Color.argb(alpha, 0, 0, 0)
        }
        Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    } }
}
