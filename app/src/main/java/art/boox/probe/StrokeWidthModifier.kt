package art.boox.probe

/** Replaceable dynamics hook; input capture never depends on a particular brush response. */
interface StrokeWidthModifier {
    val id: String
    val maxMultiplier: Float
    fun multiplier(sample: PenSample): Float
}

object NoWidthModifier : StrokeWidthModifier {
    override val id = "none"
    override val maxMultiplier = 1f
    override fun multiplier(sample: PenSample) = 1f
}

/** First tilt response: upright dead zone, smooth widening, bounded at 2.5x by 65 degrees. */
class TiltWidthModifier(
    override val maxMultiplier: Float = 2.5f,
    private val startDegrees: Float = 15f,
    private val fullDegrees: Float = 65f
) : StrokeWidthModifier {
    init { require(maxMultiplier.isFinite() && maxMultiplier >= 1f)
        require(startDegrees >= 0f && fullDegrees > startDegrees && fullDegrees <= 90f) }
    override val id = "tilt-width-v1"
    override fun multiplier(sample: PenSample): Float {
        val radians = sample.tiltRadians ?: return 1f
        if (!radians.isFinite() || radians < 0f) return 1f
        val degrees = Math.toDegrees(radians.toDouble()).toFloat()
        val t = ((degrees - startDegrees) / (fullDegrees - startDegrees)).coerceIn(0f, 1f)
        return 1f + (maxMultiplier - 1f) * t * t * (3f - 2f * t)
    }
}
