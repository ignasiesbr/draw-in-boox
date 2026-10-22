package art.boox.probe

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

data class ZoomLevel(val number: Int, val scale: Float) {
    val percent: Int get() = (scale * 100).roundToInt()
}

/** Absolute document zoom levels; Fit and toolbar zoom remain independent. */
object ZoomLevels {
    val all = listOf(0.1f, 0.25f, 0.5f, 1f, 1.5f, 2.5f, 4f)
        .mapIndexed { index, scale -> ZoomLevel(index + 1, scale) }

    fun nearest(scale: Float): ZoomLevel {
        require(scale.isFinite() && scale > 0f)
        return all.minBy { abs(ln(scale / it.scale)) }
    }
}
