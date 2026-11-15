package art.boox.probe

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class HistoryCompressionTest {
    @Test fun codecPreservesEveryBitAndReusesScratchAcrossEdgeTiles() {
        val random = Random(29)
        HistoryTileCodec().use { codec ->
            for (count in listOf(16384, 1, 7, 128, 256, 16384)) {
                val pixels = IntArray(count) { if (it < 256) (it shl 24) or (it shl 16) or (it shl 8) or it else random.nextInt() }
                val packed = HistoryTile.packed(codec.encode(pixels), count)
                assertArrayEquals(pixels, packed.pixels())
                // Independent packed storage must survive the codec's next use.
                codec.encode(IntArray(16384) { -1 })
                assertArrayEquals(pixels, packed.pixels())
            }
        }
    }

    @Test fun publishedTilesOnlyShrinkAndAlwaysRestoreExactPixels() {
        val random = Random(91)
        HistoryTileCodec().use { codec ->
            for (pixels in listOf(IntArray(16384), IntArray(16384) { random.nextInt() })) {
                val original = pixels.copyOf()
                val tile = HistoryTile.raw(pixels)
                val size = tile.byteSize
                tile.compress(codec)
                assertTrue(tile.byteSize <= size)
                assertArrayEquals(original, tile.pixels())
                tile.compress(codec)
                assertArrayEquals(original, tile.pixels())
            }
        }
    }

    @Test fun reservationsBoundCaptureBytesAndJobsBeforeAnythingIsQueued() {
        val tasks = ArrayDeque<Runnable>()
        val worker = HistoryCompression(128, jobLimit = 2, launch = { tasks.add(it) })
        assertNull(worker.reserve(0)); assertNull(worker.reserve(129))
        val first = worker.reserve(80)!!
        assertNull(worker.reserve(49))
        val second = worker.reserve(48)!!
        assertNull(worker.reserve(1))
        assertEquals(128L, worker.pendingBytes)
        first.cancel(); first.cancel()
        assertEquals(48L, worker.pendingBytes)
        second.cancel()
        assertEquals(0, worker.pendingJobs)
        assertEquals(0L, worker.pendingBytes)
        assertTrue(tasks.isEmpty())
    }

    @Test fun oneDrainPreservesOrderAndDropsCancelledQueueEntries() {
        val tasks = ArrayDeque<Runnable>()
        val worker = HistoryCompression(1024, jobLimit = 3, launch = { tasks.add(it) })
        val first = worker.reserve(100)!!
        val cancelled = worker.reserve(100)!!
        val last = worker.reserve(100)!!
        val seen = mutableListOf<Int>()
        first.submit { seen += 1 }
        cancelled.submit { error("Cancelled work executed") }
        last.submit { seen += 3 }
        assertEquals(1, tasks.size)
        cancelled.cancel()
        assertEquals(200L, worker.pendingBytes)
        tasks.removeFirst().run()
        assertEquals(listOf(1, 3), seen)
        assertEquals(0L, worker.pendingBytes)
        assertEquals(0, worker.pendingJobs)
    }

    @Test fun cancelAllThenSubmitBeforeDrainDoesNotLoseTheNewJob() {
        val tasks = ArrayDeque<Runnable>()
        val worker = HistoryCompression(100, jobLimit = 1, launch = { tasks.add(it) })
        val old = worker.reserve(100)!!
        old.submit { error("Obsolete document compressed") }
        old.cancel()
        var ran = false
        worker.reserve(100)!!.submit { ran = true }
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertTrue(ran)
        assertEquals(0L, worker.pendingBytes)
    }

    @Test fun activeCancellationKeepsItsBytesReservedUntilTheWorkerReturns() {
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val observedCancellation = java.util.concurrent.atomic.AtomicBoolean()
        val worker = HistoryCompression(100, jobLimit = 1, launch = { executor.execute(it) })
        try {
            val ticket = worker.reserve(100)!!
            ticket.submit { cancelled ->
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                observedCancellation.set(cancelled())
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            ticket.cancel()
            assertEquals(100L, worker.pendingBytes)
            assertNull(worker.reserve(1))
            release.countDown()
            executor.execute { finished.countDown() }
            assertTrue(finished.await(10, TimeUnit.SECONDS))
            assertTrue(observedCancellation.get())
            assertEquals(0L, worker.pendingBytes)
            assertNotNull(worker.reserve(100)?.also { it.cancel() })
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun failureLeavesRawPixelsUsableAndDoesNotBlockFollowingJobs() {
        val tasks = ArrayDeque<Runnable>()
        val failures = mutableListOf<Throwable>()
        val worker = HistoryCompression(1024, launch = { tasks.add(it) }, failed = { failures += it })
        val tile = HistoryTile.raw(IntArray(128) { it * 1234567 })
        val original = tile.pixels().copyOf()
        val compressedFirst = HistoryTile.raw(IntArray(128))
        worker.reserve(512)!!.submit {
            HistoryTileCodec().use { compressedFirst.compress(it) }
            throw IllegalStateException("codec failure between tiles")
        }
        var nextRan = false
        worker.reserve(512)!!.submit { nextRan = true }
        tasks.removeFirst().run()
        assertEquals(1, failures.size)
        assertTrue(nextRan)
        assertArrayEquals(original, tile.pixels())
        assertArrayEquals(IntArray(128), compressedFirst.pixels())
        assertTrue(compressedFirst.byteSize < 512)
        assertEquals(0L, worker.pendingBytes)
    }

    @Test fun executorRejectionReleasesTheReservationAndDisablesAsyncAdmission() {
        var failed = false
        val worker = HistoryCompression(100, launch = { throw RejectedExecutionException() }, failed = { failed = true })
        assertFalse(worker.reserve(100)!!.submit { error("Rejected work executed") })
        assertTrue(failed)
        assertEquals(0L, worker.pendingBytes)
        assertEquals(0, worker.pendingJobs)
        assertNull(worker.reserve(1))
    }

    @Test fun workerAllocationFailureAlsoReleasesRawReservations() {
        var failed = false
        val worker = HistoryCompression(100, launch = { throw OutOfMemoryError("worker fixture") }, failed = { failed = true })
        assertFalse(worker.reserve(100)!!.submit { error("Unscheduled work executed") })
        assertTrue(failed)
        assertEquals(0, worker.pendingJobs)
        assertEquals(0L, worker.pendingBytes)
        assertNull(worker.reserve(1))
    }

    @Test fun readersCanRestoreWhileTheWorkerPublishesCompressedTiles() {
        val pixels = IntArray(16384) { (it % 256) * 0x01010101 }
        val tile = HistoryTile.raw(pixels.copyOf())
        val executor = Executors.newSingleThreadExecutor()
        val done = CountDownLatch(1)
        try {
            executor.execute { try { HistoryTileCodec().use { tile.compress(it) } } finally { done.countDown() } }
            repeat(100) { assertArrayEquals(pixels, tile.pixels()) }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertArrayEquals(pixels, tile.pixels())
            assertTrue(tile.byteSize < pixels.size * 4)
        } finally { executor.shutdownNow() }
    }
}
