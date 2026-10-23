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
    private var epoch = 0
    private var active = false
    private var task: Runnable? = null
    private var latest = 1f
    private var lastApplied = Float.NaN
    private var count = 0
    private var minimum = Float.POSITIVE_INFINITY
    private var maximum = 0f
    val appliedRange: Pair<Float, Float>? @Synchronized get() = if (count == 0) null else minimum to maximum
    val appliedCount: Int @Synchronized get() = count

    @Synchronized fun begin(sample: PenSample) {
        finish(); active = true; lastApplied = Float.NaN; count = 0; minimum = Float.POSITIVE_INFINITY; maximum = 0f
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
        val next = Runnable { drain(expected) }
        task = next; post(next, delay)
    }
    private fun drain(expected: Int) {
        val width = synchronized(this) {
            if (!active || expected != epoch) return
            task = null
            // Suppress subpixel width jitter; do not quantize the committed brush geometry.
            if (lastApplied.isFinite() && abs(latest - lastApplied) < maxOf(0.1f, lastApplied * 0.01f)) return
            latest.also { lastApplied = it; count++; minimum = minOf(minimum, it); maximum = maxOf(maximum, it) }
        }
        apply(width)
    }
}
