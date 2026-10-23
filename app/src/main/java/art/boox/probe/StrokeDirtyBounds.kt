package art.boox.probe

import android.graphics.Rect
import android.graphics.RectF

/** Conservative stroke footprint; viewport changes still require a full frame. */
object StrokeDirtyBounds {
    fun document(samples: List<PenSample>, maximumWidth: Float): RectF? {
        if (samples.isEmpty()) return null
        var left = Float.POSITIVE_INFINITY; var top = left
        var right = Float.NEGATIVE_INFINITY; var bottom = right
        for (point in samples) {
            left = minOf(left, point.x); top = minOf(top, point.y)
            right = maxOf(right, point.x); bottom = maxOf(bottom, point.y)
        }
        val margin = maximumWidth / 2f + 2f
        return RectF(left - margin, top - margin, right + margin, bottom + margin)
    }

    fun view(document: RectF, transform: ViewTransform, width: Int, height: Int): Rect {
        // Skia's rotated bitmap/edge rasterization can vary with an outer dirty clip.
        // Keep exact existing pixels at arbitrary angles; optimize upright writing first.
        if (transform.rotationDegrees != 0f) return Rect(0, 0, width, height)
        val mapped = RectF(document)
        transform.matrix().mapRect(mapped)
        mapped.inset(-2f, -2f) // Filter/rounding safety in screen pixels, even when zoomed out.
        val result = Rect().also { mapped.roundOut(it) }
        if (!result.intersect(0, 0, width, height)) result.setEmpty()
        return result
    }
}
