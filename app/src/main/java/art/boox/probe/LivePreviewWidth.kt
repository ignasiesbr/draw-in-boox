package art.boox.probe

import kotlin.math.abs

/** Raw callbacks may be off-thread; only the scheduled consumer touches the SDK.
 * Each stroke has an epoch so delayed changes cannot outlive pen-up or a session. */
class LivePreviewWidth(
    private val widthForSample: (PenSample) -> Float,
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val apply: (Float) -> Unit
) {
    companion object {
        /** A fixed brush is configured once while idle. Even an equal-value setter
         * at pen-down can rebuild a firmware shader concurrently with touchConsumer. */
        fun forBrush(brush: PressureBrush, scale: Float,
            post: (Runnable, Long) -> Unit, remove: (Runnable) -> Unit,
            apply: (Float) -> Unit): LivePreviewWidth? {
            require(scale.isFinite() && scale > 0f)
            return if (brush.hasDynamicWidth) LivePreviewWidth({ brush.width(it) * scale }, post, remove, apply) else null
        }
    }
    private var epoch = 0
    private var active = false
    private var task: Runnable? = null
    private var latest = 1f
    private var lastApplied = Float.NaN
    private var count = 0
    private var minimum = Float.POSITIVE_INFINITY
    private var maximum = 0f
    private var maxLatenessNanos = 0L
    private var maxApplyNanos = 0L
    val maxScheduleLatenessMs: Double @Synchronized get() = maxLatenessNanos / 1_000_000.0
    val maxApplyMs: Double @Synchronized get() = maxApplyNanos / 1_000_000.0
    val appliedRange: Pair<Float, Float>? @Synchronized get() = if (count == 0) null else minimum to maximum
    val appliedCount: Int @Synchronized get() = count

    @Synchronized fun begin(sample: PenSample) {
        finish(); active = true; lastApplied = Float.NaN; count = 0; minimum = Float.POSITIVE_INFINITY; maximum = 0f
        maxLatenessNanos = 0L
        maxApplyNanos = 0L
        queue(sample, 0)
    }
    @Synchronized fun update(sample: PenSample) { if (active) queue(sample, 2) }
    @Synchronized fun finish() {
        active = false; epoch++
        task?.let(remove); task = null
    }
    private fun queue(sample: PenSample, delay: Long) {
        val desired = widthForSample(sample)
        if (!desired.isFinite() || desired <= 0f) return
        latest = desired
        if (task != null) return
        val expected = epoch
        val due = StrokePerformance.now() + delay * 1_000_000
        val next = Runnable { drain(expected, due) }
        task = next; post(next, delay)
    }
    private fun drain(expected: Int, due: Long) {
        val width = synchronized(this) {
            if (!active || expected != epoch) return
            maxLatenessNanos = maxOf(maxLatenessNanos, StrokePerformance.now() - due)
            task = null
            // Suppress subpixel width jitter; do not quantize the committed brush geometry.
            if (lastApplied.isFinite() && abs(latest - lastApplied) < maxOf(0.1f, lastApplied * 0.01f)) return
            latest.also { lastApplied = it; count++; minimum = minOf(minimum, it); maximum = maxOf(maximum, it) }
        }
        val started = StrokePerformance.now()
        try { apply(width) }
        finally {
            val elapsed = StrokePerformance.now() - started
            synchronized(this) { if (expected == epoch) maxApplyNanos = maxOf(maxApplyNanos, elapsed) }
        }
    }
}
