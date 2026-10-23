package art.boox.probe

import android.os.SystemClock
import android.util.Log

/** One summary per stage/stroke, never per sample. Times measure CPU/queue work, not panel latency. */
object StrokePerformance {
    @Volatile var enabled = true
    fun now() = SystemClock.elapsedRealtimeNanos()
    fun ms(start: Long, end: Long = now()) = (end - start) / 1_000_000.0
    fun log(message: String) { if (enabled) Log.d("BooxPerf", message) }
}
