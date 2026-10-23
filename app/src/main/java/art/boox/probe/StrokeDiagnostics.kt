package art.boox.probe

import android.os.Process
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Diagnostic work must never delay input. Bounded queue drops diagnostics, never artwork. */
class StrokeDiagnostics : java.io.Closeable {
    val dropped = AtomicInteger()
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(4), { task ->
            Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "stroke-diagnostics")
        }, { _, _ ->
            val count = dropped.incrementAndGet()
            if (count == 1 || count % 20 == 0) Log.w("BooxPerf", "diagnostics dropped=$count queue full or closed; artwork unaffected")
        })

    fun submit(work: () -> Unit) {
        val queued = StrokePerformance.now()
        executor.execute {
            val started = StrokePerformance.now()
            try { work() }
            catch (error: Exception) { Log.e("BooxProbe", "Stroke diagnostics failed", error) }
            finally { StrokePerformance.log("diagnostics queueMs=${StrokePerformance.ms(queued, started)} workMs=${StrokePerformance.ms(started)} backlog=${executor.queue.size} dropped=${dropped.get()}") }
        }
    }
    override fun close() { executor.shutdown() } // Drain bounded queue; never wait on the UI thread.
}
