package art.boox.probe

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object StrokeDiagnosticsChecks {
    /** A blocked filesystem worker must not block the producer or create an unbounded backlog. */
    fun run() {
        val writer = StrokeDiagnostics()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val caller = Thread.currentThread()
        val order = java.util.Collections.synchronizedList(mutableListOf<Int>())
        try {
            writer.submit {
                check(Thread.currentThread() !== caller)
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                order += 0
            }
            check(started.await(5, TimeUnit.SECONDS))
            repeat(4) { i -> writer.submit { order += i + 1; if (i == 3) drained.countDown() } }
            writer.submit { error("Rejected diagnostics ran on the caller") }
            check(writer.dropped.get() == 1)
            release.countDown()
            check(drained.await(5, TimeUnit.SECONDS))
            check(order.toList() == listOf(0, 1, 2, 3, 4))
            writer.close()
            writer.submit { error("Closed writer accepted diagnostics") }
            check(writer.dropped.get() == 2)
        } finally { release.countDown(); writer.close() }
    }
}
