package art.boox.probe

import kotlin.math.abs
import kotlin.math.roundToInt

data class ZoomLevel(val number: Int, val scale: Float) {
    val percent: Int get() = (scale * 100).roundToInt()
}

/** Shared absolute steps for pinch and toolbar zoom. Fit can show a smaller whole page. */
object ZoomLevels {
    const val MIN_SCALE = 0.75f
    const val MAX_SCALE = 4f
    val all = (75..400 step 25).map { it / 100f }
        .mapIndexed { index, scale -> ZoomLevel(index + 1, scale) }

    fun nearest(scale: Float): ZoomLevel {
        require(scale.isFinite() && scale > 0f)
        return all.minBy { abs(scale - it.scale) }
    }

    fun next(scale: Float): Float = all.firstOrNull { it.scale > scale + 0.0001f }?.scale ?: MAX_SCALE
    fun previous(scale: Float): Float = all.lastOrNull { it.scale < scale - 0.0001f }?.scale ?: MIN_SCALE
}
