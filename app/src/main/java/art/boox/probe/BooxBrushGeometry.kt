package art.boox.probe

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.PenUtils
import com.onyx.android.sdk.pennative.NeoPenNative
import com.onyx.android.sdk.pennative.PenConfig
import com.onyx.android.sdk.pennative.PenInkResult

/** Lab-only adapter to the pinned Java/JNI contract. All geometry lives in document
 * coordinates; native handles are short-lived and never reach the surface worker. */
internal class BooxBrushGeometry(private val size: Float, private val color: Int,
    private val family: BooxBrushFamily, pressure: Boolean, tilt: Boolean) {
    private val dryGeometry = if (family.dry) DryBrushGeometry(size, color, family, pressure, tilt) else null
    private var cachedSamples: List<PenSample>? = null
    private var cachedCount = -1
    private var cachedLast: PenSample? = null
    private var points = emptyList<TouchPoint>()
    private var bounds: RectF? = null

    fun bounds(samples: List<PenSample>): RectF? {
        dryGeometry?.let { return it.bounds(samples) }
        prepare(samples); return bounds?.let(::RectF)
    }

    fun draw(canvas: Canvas, paint: Paint, samples: List<PenSample>) {
        dryGeometry?.let { it.draw(canvas, paint, samples); return }
        prepare(samples)
        if (points.size == 1) canvas.drawCircle(points[0].x, points[0].y, points[0].size / 2f, paint)
        else PenUtils.drawStrokeByPointSize(canvas, paint, points, false)
    }

    private fun prepare(samples: List<PenSample>) {
        if (samples === cachedSamples && samples.size == cachedCount && samples.lastOrNull() == cachedLast) return
        if (samples.isEmpty()) { points = emptyList(); bounds = null; return }
        val config = PenConfig().apply {
            type = family.rendererType
            width = size; minWidth = minOf(0.5f, size); this.color = this@BooxBrushGeometry.color
            maxTouchPressure = 1f // PenSample is already normalized in both capture paths.
            pressureSensitivity = 1f; velocitySensitivity = 0f; smoothLevel = 0.5f
            tiltEnabled = false; directionEnabled = false
            displayScaleX = 1f; displayScaleY = 1f
        }
        val native = NeoPenNative
        val handle = native.createPen(config.type, config)
        check(handle != 0L) { "BOOX brush renderer could not create a pen" }
        val rendered = ArrayList<TouchPoint>()
        var renderedBounds: RectF? = null
        val originTime = samples.first().time
        fun encode(sample: PenSample) = doubleArrayOf(sample.x.toDouble(), sample.y.toDouble(),
            sample.pressure.toDouble(), 0.0, 0.0, 0.0, (sample.time - originTime).coerceAtLeast(0).toDouble())
        fun append(result: PenInkResult?) {
            val ink = result?.realInk ?: return
            check(ink.bitmaps.isEmpty() && ink.pointSizeArray.all { it == 3 }) { "Unexpected brush geometry encoding" }
            val values = ink.points
            check(values.size % 3 == 0 && rendered.size + values.size / 3 <= 100_000) { "Brush geometry exceeds stroke budget" }
            for (i in values.indices step 3) {
                val x = values[i]; val y = values[i + 1]; val width = values[i + 2]
                check(x.isFinite() && y.isFinite() && width.isFinite() && width >= 0f && width <= family.maximumWidth(size)) {
                    "Invalid brush geometry"
                }
                if (width == 0f) continue
                val previous = rendered.lastOrNull()
                rendered += TouchPoint(x, y, 1f, width, 0L)
                val padding = width / 2f + 2f
                // PenUtils uses the current point's size for the whole segment,
                // including the previous endpoint's round cap.
                val region = RectF(minOf(previous?.x ?: x, x) - padding,
                    minOf(previous?.y ?: y, y) - padding, maxOf(previous?.x ?: x, x) + padding,
                    maxOf(previous?.y ?: y, y) + padding)
                renderedBounds?.union(region) ?: run { renderedBounds = region }
            }
        }
        try {
            append(native.onPenDown(handle, encode(samples.first()), false))
            for (i in 1 until samples.lastIndex) append(native.onPenMove(handle, encode(samples[i]), null, false))
            append(native.onPenUp(handle, encode(samples.last()), false))
        } finally { native.destroyPen(handle) }
        points = rendered; bounds = renderedBounds
        cachedSamples = samples; cachedCount = samples.size; cachedLast = samples.last()
    }
}
