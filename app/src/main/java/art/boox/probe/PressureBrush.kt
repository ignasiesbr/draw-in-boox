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
    val notesPen = settings?.preset?.notesPen
    internal val notesConfig = notesPen?.let(BooxNotesConfig::read)
    val usesBooxEngine = booxFamily != null
    val requiresPreparedGeometry = usesBooxEngine || notesPen != null
    private val roundMarker = isEraser || (settings?.preset == BrushPreset.MONOLINE &&
        !settings.pressure && !settings.tilt)
    val engineId = if (notesPen != null) "boox-notes-${notesPen.name.lowercase()}-official" else if (usesBooxEngine) "boox-${booxFamily!!.label.lowercase()}-${BuildConfig.RENDERER_BACKEND}" else if (isEraser) "round-eraser" else if (roundMarker) "round-marker" else "continuous-round"
    val engineVersion = if (booxFamily?.dry == true) 2 else 1
    val inkColor: Int = settings?.shade?.argb ?: Color.BLACK
    init { require(minWidth.isFinite() && maxWidth.isFinite() && minWidth > 0 && maxWidth >= minWidth)
        require(widthModifier.maxMultiplier.isFinite() && widthModifier.maxMultiplier >= 1f)
        if (booxFamily?.dry == true) DryBrushTips.preload(booxFamily)
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = inkColor; style = Paint.Style.FILL; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val alphaLockMode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    private val eraseMode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    private val path = Path()
    private val booxGeometry by lazy { BooxBrushGeometry(maxWidth, inkColor, checkNotNull(booxFamily),
        settings?.pressure ?: true, settings?.tilt ?: false) }
    private val notesGeometry by lazy { BooxNotesGeometry(maxWidth, inkColor, checkNotNull(notesPen), checkNotNull(notesConfig)) }

    fun renderedBounds(samples: List<PenSample>) = if (notesPen != null) notesGeometry.bounds(samples)
        else if (usesBooxEngine) booxGeometry.bounds(samples)
        else StrokeDirtyBounds.document(samples, maxRenderedWidth)

    fun width(pressure: Float): Float = minWidth + (maxWidth - minWidth) *
        (if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else 0f)

    val maxRenderedWidth: Float get() = if (notesPen != null) maxOf(notesPen.maximumWidth(maxWidth),
        if (notesPen.textured && notesConfig?.tiltEnabled == true) maxWidth * notesConfig.tiltScale else 0f)
        else if (booxFamily?.dry == true) maxWidth * widthModifier.maxMultiplier
        else booxFamily?.maximumWidth(maxWidth) ?: (maxWidth * widthModifier.maxMultiplier)
    val nativePreviewBaseWidth: Float get() = if (booxFamily?.dry == true)
        maxWidth * (settings?.nativePreviewScale ?: 0.5f) else maxWidth
    // Firmware owns dry-brush dynamics even if app pressure/tilt controls are off.
    // Bound its configured footprint independently from the final app geometry.
    val nativePreviewMaxWidth: Float get() = if (booxFamily?.dry == true) nativePreviewBaseWidth * 2.5f else maxRenderedWidth
    val nativePreviewStyle: Int get() = if (notesPen != null) notesPen.style else if (booxFamily?.dry == true)
        (settings?.nativePreviewTexture ?: NativePreviewTexture.FINE).style
        else booxFamily?.previewStyle ?: com.onyx.android.sdk.pen.TouchHelper.STROKE_STYLE_PENCIL
    val hasDynamicWidth: Boolean get() = notesPen == null && (!usesBooxEngine || booxFamily?.appPreviewWidth == true) &&
        (minWidth != maxWidth || widthModifier.maxMultiplier != 1f)
    fun width(sample: PenSample): Float = (if (booxFamily?.dry == true)
        maxWidth * ((if (booxFamily == BooxBrushFamily.CHARCOAL) 0.35f else 0.22f) +
            (if (booxFamily == BooxBrushFamily.CHARCOAL) 0.65f else 0.78f) * kotlin.math.sqrt(if (settings?.pressure == false) 1f else
            sample.pressure.let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f })) else width(sample.pressure)) *
        widthModifier.multiplier(sample).let { if (it.isFinite()) it.coerceIn(1f, widthModifier.maxMultiplier) else 1f }

    fun draw(canvas: Canvas, samples: List<PenSample>, alphaLocked: Boolean = false) {
        if (samples.isEmpty() || (isEraser && alphaLocked)) return
        paint.xfermode = if (isEraser) eraseMode else if (alphaLocked) alphaLockMode else null
        try {
            if (notesPen != null) { notesGeometry.draw(canvas, paint, samples); return }
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
