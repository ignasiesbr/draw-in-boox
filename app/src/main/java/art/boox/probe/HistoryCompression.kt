package art.boox.probe

import java.util.ArrayDeque

/** One process-wide drain, bounded by reserved raw bytes AND jobs (including capture).
 * Work owns immutable tile copies only. Cancelling queued work drops its references;
 * cancelling active work retains its reservation until the worker actually returns. */
internal class HistoryCompression(
    private val byteLimit: Long,
    private val jobLimit: Int = 4,
    private val launch: (Runnable) -> Unit,
    private val failed: (Throwable) -> Unit = {}
) {
    init { require(byteLimit > 0 && jobLimit > 0) }
    private val lock = Any()
    private val queue = ArrayDeque<Reservation>()
    private var draining = false
    private var unavailable = false
    private var bytes = 0L
    private var jobs = 0
    val pendingBytes: Long get() = synchronized(lock) { bytes }
    val pendingJobs: Int get() = synchronized(lock) { jobs }

    inner class Reservation internal constructor(private val size: Long) {
        @Volatile var cancelled = false
            private set
        private var active = false
        private var released = false
        private var work: ((() -> Boolean) -> Unit)? = null

        fun submit(action: (() -> Boolean) -> Unit): Boolean {
            synchronized(lock) {
                if (cancelled || released) return false
                check(work == null && !active) { "Compression reservation already submitted" }
                work = action
                queue.addLast(this)
                if (!draining) {
                    draining = true
                    try { launch(Runnable { drain() }) }
                    catch (error: Exception) { return disable(error) }
                    catch (error: OutOfMemoryError) { return disable(error) }
                }
                return true
            }
        }

        fun cancel() = synchronized(lock) {
            cancelled = true
            if (!active) {
                queue.remove(this)
                work = null
                release()
            }
        }

        private fun release() {
            if (released) return
            released = true
            bytes -= size; jobs--
        }

        internal fun take(): ((() -> Boolean) -> Unit)? {
            active = true
            return work.also { work = null }
        }

        internal fun finish() = synchronized(lock) { active = false; release() }
    }

    fun reserve(size: Long): Reservation? = synchronized(lock) {
        if (size <= 0 || unavailable || jobs >= jobLimit || size > byteLimit - bytes) null
        else Reservation(size).also { bytes += size; jobs++ }
    }

    /** Called under lock after a worker cannot be scheduled; retained raw history
     * remains valid and future edits take the synchronous path. */
    private fun disable(error: Throwable): Boolean {
        unavailable = true; draining = false
        while (queue.isNotEmpty()) queue.removeFirst().cancel()
        failed(error)
        return false
    }

    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                if (queue.isEmpty()) { draining = false; return }
                queue.removeFirst().let { it to it.take() }
            }
            try { next.second?.invoke { next.first.cancelled } }
            catch (error: Exception) { failed(error) }
            catch (error: OutOfMemoryError) { failed(error) }
            finally { next.first.finish() }
        }
    }

}
