package art.boox.probe

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.*

/** Original BOOX geometry/textures, shared by app preview and commit. Never invokes
 * firmware repaint, retains prediction, or exposes mutable results to the surface worker. */
internal class BooxNotesGeometry(private val size: Float, private val color: Int, private val family: BooxNotesPen,
    private val settings: BooxNotesConfig) {
    private var cachedSamples: List<PenSample>? = null
    private var cachedCount = -1
    private var cachedLast: PenSample? = null
    private var results = emptyList<PenResult>()
    private var region: RectF? = null

    fun bounds(samples: List<PenSample>): RectF? { prepare(samples); return region?.let(::RectF) }

    fun draw(canvas: Canvas, paint: Paint, samples: List<PenSample>) {
        prepare(samples)
        val previousStyle = paint.style
        val previousWidth = paint.strokeWidth
        try {
            paint.style = if (family == BooxNotesPen.BALLPOINT) Paint.Style.STROKE else Paint.Style.FILL
            paint.strokeWidth = size
            if (family == BooxNotesPen.BALLPOINT && samples.all { it.x == samples[0].x && it.y == samples[0].y }) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(samples[0].x, samples[0].y, size / 2f, paint)
            } else results.forEach { it.draw(canvas, paint) }
        } finally { paint.style = previousStyle; paint.strokeWidth = previousWidth }
    }

    private fun prepare(samples: List<PenSample>) {
        if (samples === cachedSamples && samples.size == cachedCount && samples.lastOrNull() == cachedLast) return
        results = emptyList(); region = null
        cachedSamples = null; cachedCount = -1; cachedLast = null
        if (samples.isEmpty()) return
        check(samples.size <= 100_000) { "BOOX stroke exceeds point budget" }
        val started = StrokePerformance.now()
        val config = (if (family == BooxNotesPen.CALLIGRAPHY) NeoSquarePen.defaultPenConfig() else NeoPenConfig()).apply {
            // Notes SquarePenShape.getPenRenderStrokeWidth uses 2 * selected width.
            width = if (family == BooxNotesPen.CALLIGRAPHY) size * 2f else size
            this.color = this@BooxNotesGeometry.color; maxTouchPressure = 1f
            displayScaleX = 1f; displayScaleY = 1f
            if (family == BooxNotesPen.CALLIGRAPHY) {
                brushRatio = settings.ratio; brushAngle = settings.angle; smoothLevel = settings.smoothing
            }
            if (family.textured) { tiltEnabled = settings.tiltEnabled; tiltScale = settings.tiltScale }
        }
        val pen = checkNotNull(when (family) {
            BooxNotesPen.CALLIGRAPHY -> NeoSquarePen.create(config)
            BooxNotesPen.BALLPOINT -> NeoBallpointPen.create(false)
            BooxNotesPen.PENCIL_FINE -> NeoCharcoalPenV2.create(config)
            BooxNotesPen.PENCIL_COARSE -> NeoCharcoalPen.create(config)
        }) { "BOOX ${family.name} renderer unavailable" }
        val output = ArrayList<PenResult>()
        var bitmapBytes = 0L
        var geometryPoints = 0L
        var outputBounds: RectF? = null
        fun append(pair: Pair<PenResult?, PenResult?>?) {
            val ink = pair?.first ?: return
            check(output.size < 2048) { "BOOX stroke exceeds result budget" }
            if (ink is PenTextureResult) {
                for (texture in ink.textures) {
                    bitmapBytes += texture.bitmap.allocationByteCount
                    check(bitmapBytes <= 16L * 1024 * 1024) { "BOOX stroke exceeds texture budget" }
                    val rect = RectF(texture.x, texture.y, texture.x + texture.bitmap.width, texture.y + texture.bitmap.height)
                    outputBounds?.union(rect) ?: run { outputBounds = rect }
                }
            } else {
                if (ink is PenPathResult) {
                    geometryPoints += ink.points.size
                    check(geometryPoints <= 1_000_000) { "BOOX stroke exceeds geometry budget" }
                }
                val rect = RectF(ink.rect)
                check(listOf(rect.left, rect.top, rect.right, rect.bottom).all { it.isFinite() })
                // Stroked paths need their cap width; filled nib paths need AA padding only.
                val padding = if (family == BooxNotesPen.BALLPOINT) size / 2f + 2f else 2f
                rect.inset(-padding, -padding)
                outputBounds?.union(rect) ?: run { outputBounds = rect }
            }
            output += ink
        }
        fun point(sample: PenSample) = TouchPoint(sample.x, sample.y,
            sample.pressure.let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }, size, sample.time).apply {
            val axes = tiltAxes(sample)
            tiltX = axes.first; tiltY = axes.second
        }
        // Android UP commonly repeats the last MOVE. The vendor's smoothing can
        // change a finished nib when given that stationary endpoint twice. Keep
        // changed pressure/tilt, and keep the original samples for diagnostics.
        fun sameTip(a: PenSample, b: PenSample) = a.x == b.x && a.y == b.y &&
            a.pressure == b.pressure && a.tiltRadians == b.tiltRadians &&
            a.viewOrientationRadians == b.viewOrientationRadians &&
            a.rawTiltX == b.rawTiltX && a.rawTiltY == b.rawTiltY &&
            a.documentAzimuthRadians == b.documentAzimuthRadians
        var lastIndex = samples.lastIndex
        while (lastIndex > 0 && sameTip(samples[lastIndex - 1], samples[lastIndex])) lastIndex--
        try {
            append(pen.onPenDown(point(samples.first()), false))
            var index = 1
            while (index < lastIndex) {
                val end = minOf(index + 128, lastIndex)
                append(pen.onPenMove((index until end).map { point(samples[it]) }, null, false))
                index = end
            }
            // CHARCOAL V1 emits nothing for coincident down/up. A subpixel segment
            // asks that same vendor renderer for its tip; raw samples stay untouched.
            val stationary = family == BooxNotesPen.PENCIL_COARSE &&
                samples.all { it.x == samples.first().x && it.y == samples.first().y }
            val last = samples[lastIndex]
            val end = if (stationary) last.copy(x = last.x + 0.01f) else last
            append(pen.onPenUp(point(end), false))
        } finally { pen.destroy() }
        if (family == BooxNotesPen.BALLPOINT && samples.size == 1)
            outputBounds = StrokeDirtyBounds.document(samples, size)
        outputBounds?.inset(-2f, -2f)
        results = output; region = outputBounds
        cachedSamples = samples; cachedCount = samples.size; cachedLast = samples.last()
        StrokePerformance.log("booxNotes family=$family samples=${samples.size} results=${output.size} textureBytes=$bitmapBytes geometryMs=${StrokePerformance.ms(started)}")
    }

    companion object {
        internal fun tiltAxes(sample: PenSample): Pair<Int, Int> {
            val rawX = sample.rawTiltX?.takeIf { it in -89..89 }
            val rawY = sample.rawTiltY?.takeIf { it in -89..89 }
            val azimuth = PenTilt.azimuth(sample)
            if (rawX != null && rawY != null && sample.documentAzimuthRadians == null) return rawX to rawY
            val length = if (rawX != null && rawY != null) kotlin.math.hypot(
                kotlin.math.tan(Math.toRadians(rawX.toDouble())), kotlin.math.tan(Math.toRadians(rawY.toDouble())))
            else sample.tiltRadians?.takeIf { it.isFinite() }?.let { kotlin.math.tan(it.toDouble().coerceIn(0.0, Math.toRadians(89.0))) } ?: 0.0
            val angle = azimuth?.toDouble() ?: 0.0
            fun axis(component: Double) = Math.toDegrees(kotlin.math.atan(component)).toInt().coerceIn(-89, 89)
            return axis(length * kotlin.math.cos(angle)) to axis(length * kotlin.math.sin(angle))
        }
    }
}
