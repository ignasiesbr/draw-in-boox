package art.boox.probe

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Path
import kotlin.math.hypot

data class PenSample(
    val x: Float, val y: Float, val pressure: Float, val time: Long,
    val rawPressure: Float = pressure,
    val tiltRadians: Float? = null, val viewOrientationRadians: Float? = null,
    val rawTiltX: Int? = null, val rawTiltY: Int? = null,
    val documentAzimuthRadians: Float? = null
)

data class PressureRange(val min: Float, val max: Float, val origin: String) {
    init { require(min.isFinite() && max.isFinite() && max > min) }
    fun normalize(raw: Float): Float =
        if (raw.isFinite()) ((raw - min) / (max - min)).coerceIn(0f, 1f) else 0f

    companion object {
        val ANDROID_DEFAULT = PressureRange(0f, 1f, "android-default")
    }
}

data class PenStroke(val samples: List<PenSample>, val source: String, val pressureRange: PressureRange,
    val brush: PressureBrush = PressureBrush())

/** Shared pressure curve and geometry for Android preview, bitmap commit, and replay. */
class PressureBrush(val minWidth: Float = 1f, val maxWidth: Float = 12f,
    val widthModifier: StrokeWidthModifier = TiltWidthModifier(),
    val settings: BrushSettings? = null) {
    val isEraser = settings?.preset == BrushPreset.ERASER
    val isBooxFountain = settings?.preset == BrushPreset.LAB_FOUNTAIN
    val booxFamily = settings?.preset?.booxFamily
    val usesBooxEngine = booxFamily != null
    private val roundMarker = isEraser || (settings?.preset == BrushPreset.MONOLINE &&
        !settings.pressure && !settings.tilt)
    val engineId = if (usesBooxEngine) "boox-${booxFamily!!.label.lowercase()}-${BuildConfig.RENDERER_BACKEND}" else if (isEraser) "round-eraser" else if (roundMarker) "round-marker" else "continuous-round"
    val engineVersion = 1
    val inkColor: Int = settings?.shade?.argb ?: Color.BLACK
    init { require(minWidth.isFinite() && maxWidth.isFinite() && minWidth > 0 && maxWidth >= minWidth)
        require(widthModifier.maxMultiplier.isFinite() && widthModifier.maxMultiplier >= 1f) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = inkColor; style = Paint.Style.FILL; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val alphaLockMode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    private val eraseMode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    private val path = Path()
    private val booxGeometry by lazy { BooxBrushGeometry(maxWidth, inkColor, checkNotNull(booxFamily),
        settings?.pressure ?: true, settings?.tilt ?: false) }

    fun renderedBounds(samples: List<PenSample>) = if (usesBooxEngine) booxGeometry.bounds(samples)
        else StrokeDirtyBounds.document(samples, maxRenderedWidth)

    fun width(pressure: Float): Float = minWidth + (maxWidth - minWidth) *
        (if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else 0f)

    val maxRenderedWidth: Float get() = if (booxFamily?.dry == true) maxWidth * widthModifier.maxMultiplier
        else booxFamily?.maximumWidth(maxWidth) ?: (maxWidth * widthModifier.maxMultiplier)
    val hasDynamicWidth: Boolean get() = (!usesBooxEngine || booxFamily?.appPreviewWidth == true) &&
        (minWidth != maxWidth || widthModifier.maxMultiplier != 1f)
    fun width(sample: PenSample): Float = (if (booxFamily?.dry == true)
        maxWidth * (0.22f + 0.78f * kotlin.math.sqrt(if (settings?.pressure == false) 1f else
            sample.pressure.let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f })) else width(sample.pressure)) *
        widthModifier.multiplier(sample).let { if (it.isFinite()) it.coerceIn(1f, widthModifier.maxMultiplier) else 1f }

    fun draw(canvas: Canvas, samples: List<PenSample>, alphaLocked: Boolean = false) {
        if (samples.isEmpty() || (isEraser && alphaLocked)) return
        paint.xfermode = if (isEraser) eraseMode else if (alphaLocked) alphaLockMode else null
        try {
            if (usesBooxEngine) { booxGeometry.draw(canvas, paint, samples); return }
            if (roundMarker) {
                val first = samples.first()
                path.rewind(); path.moveTo(first.x, first.y)
                var moved = false
                for (i in 1 until samples.size) {
                    val current = samples[i]
                    if (current.x != first.x || current.y != first.y) moved = true
                    path.lineTo(current.x, current.y)
                }
                if (moved) {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = maxWidth
                    canvas.drawPath(path, paint)
                } else canvas.drawCircle(first.x, first.y, maxWidth / 2f, paint)
                return
            }
            var previous = samples.first()
            var previousRadius = width(previous) / 2
            canvas.drawCircle(previous.x, previous.y, previousRadius, paint)
            for (i in 1 until samples.size) {
                val current = samples[i]
                val dx = current.x - previous.x
                val dy = current.y - previous.y
                val length = hypot(dx, dy)
                val a = previousRadius
                val b = width(current) / 2
                if (length > 0f) {
                    val nx = -dy / length
                    val ny = dx / length
                    path.rewind()
                    path.moveTo(previous.x + nx * a, previous.y + ny * a)
                    path.lineTo(current.x + nx * b, current.y + ny * b)
                    path.lineTo(current.x - nx * b, current.y - ny * b)
                    path.lineTo(previous.x - nx * a, previous.y - ny * a)
                    path.close()
                    canvas.drawPath(path, paint)
                }
                canvas.drawCircle(current.x, current.y, b, paint)
                previous = current
                previousRadius = b
            }
        } finally { paint.xfermode = null; paint.style = Paint.Style.FILL }
    }
}
