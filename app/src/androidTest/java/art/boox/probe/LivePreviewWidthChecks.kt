package art.boox.probe

object LivePreviewWidthChecks {
    fun run() {
        val queued = mutableListOf<Pair<Runnable, Long>>()
        val applied = mutableListOf<Float>()
        fun sample(degrees: Float) = PenSample(0f, 0f, 1f, 1,
            tiltRadians = Math.toRadians(degrees.toDouble()).toFloat())
        val brush = PressureBrush()
        val controller = LivePreviewWidth(widthForSample = brush::width,
            post = { task, delay -> queued += task to delay },
            remove = { task -> queued.removeAll { it.first === task } }, apply = { applied += it })
        fun drain() { val tasks = queued.toList(); queued.clear(); tasks.forEach { it.first.run() } }
        controller.begin(sample(65f))
        check(queued.single().second == 0L)
        drain(); check(applied == listOf(30f))
        repeat(100) { controller.update(sample(it % 60f)) }
        check(queued.size == 1 && queued.single().second == 2L)
        drain()
        check(applied.last() == brush.width(sample(39f)))
        val count = applied.size
        controller.update(sample(39.01f)); drain()
        check(applied.size == count) { "Tiny tilt jitter flooded firmware" }
        controller.update(sample(0f))
        val stale = queued.single().first
        controller.finish(); check(queued.isEmpty())
        stale.run(); check(applied.size == count)
        controller.update(sample(65f)); check(queued.isEmpty())
        controller.begin(sample(0f)); drain(); check(applied.last() == 12f)
        controller.update(sample(65f)); val oldStroke = queued.single().first
        controller.begin(sample(0f))
        oldStroke.run(); check(queued.size == 1)
        drain(); check(applied.last() == 12f)
        // New strokes force their initial width even when the previous value matched:
        // pen-up handoff resets the SDK's width independently of this controller.
        val before = applied.size
        controller.begin(sample(0f)); drain(); check(applied.size == before + 1)
        check(controller.appliedCount == 1)
        controller.update(sample(0f).copy(pressure = 0.2f)); drain()
        check(applied.last() == brush.width(0.2f)) { "Live pressure diverged from bitmap pressure curve" }
        controller.update(sample(65f).copy(pressure = 0.7f)); drain()
        check(applied.last() == brush.width(sample(65f).copy(pressure = 0.7f)))
        check(controller.appliedRange!!.first <= controller.appliedRange!!.second)
        controller.finish()
        val zoomed = LivePreviewWidth(widthForSample = { brush.width(it) * 2f },
            post = { task, delay -> queued += task to delay }, remove = {}, apply = { applied += it })
        zoomed.begin(sample(65f)); drain(); check(applied.last() == 60f)
        zoomed.finish()
        // SDK callbacks already on UI: an inline begin must send width before end
        // can cancel queued work, even for a one-point stroke.
        val immediate = LivePreviewWidth(widthForSample = brush::width,
            post = { task, _ -> task.run() }, remove = {}, apply = { applied += it })
        val beforeImmediate = applied.size
        immediate.begin(sample(65f)); immediate.finish()
        check(applied.size == beforeImmediate + 1 && applied.last() == 30f)
    }
}
