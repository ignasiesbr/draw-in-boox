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
    val rawPressure: Float = pressure
)

data class PressureRange(val min: Float, val max: Float, val origin: String) {
    init { require(min.isFinite() && max.isFinite() && max > min) }
    fun normalize(raw: Float): Float =
        if (raw.isFinite()) ((raw - min) / (max - min)).coerceIn(0f, 1f) else 0f

    companion object {
        val ANDROID_DEFAULT = PressureRange(0f, 1f, "android-default")
    }
}

data class PenStroke(val samples: List<PenSample>, val source: String, val pressureRange: PressureRange)

/** Shared pressure curve and geometry for Android preview, bitmap commit, and replay. */
class PressureBrush(val minWidth: Float = 1f, val maxWidth: Float = 12f) {
    init { require(minWidth > 0 && maxWidth >= minWidth) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }
    private val alphaLockMode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    private val path = Path()

    fun width(pressure: Float): Float = minWidth + (maxWidth - minWidth) *
        (if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else 0f)

    fun draw(canvas: Canvas, samples: List<PenSample>, alphaLocked: Boolean = false) {
        if (samples.isEmpty()) return
        paint.xfermode = if (alphaLocked) alphaLockMode else null
        try {
            var previous = samples.first()
            canvas.drawCircle(previous.x, previous.y, width(previous.pressure) / 2, paint)
            for (i in 1 until samples.size) {
                val current = samples[i]
                val dx = current.x - previous.x
                val dy = current.y - previous.y
                val length = hypot(dx, dy)
                val a = width(previous.pressure) / 2
                val b = width(current.pressure) / 2
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
            }
        } finally { paint.xfermode = null }
    }
}
