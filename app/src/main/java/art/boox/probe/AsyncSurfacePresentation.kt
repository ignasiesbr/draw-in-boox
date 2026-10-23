package art.boox.probe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.SurfaceHolder

/** Worker owns its screen buffer. Jobs contain only immutable pixel patches, never live
 * document/layer bitmaps. Mailbox holds at most one waiting job plus the active job.
 * Replacing a queued job is safe because the UI unions all unacknowledged dirty bounds. */
internal class AsyncSurfacePresentation(
    private val holder: SurfaceHolder,
    private val surfaceLock: Any,
    private val valid: (Long) -> Boolean,
    private val uiPost: (Runnable) -> Unit
) : java.io.Closeable {
    data class Frame(val pixels: Bitmap, val rect: Rect, val width: Int, val height: Int,
        val epoch: Long, val complete: (Boolean) -> Unit,
        val documentTransform: ViewTransform? = null, val queued: Long = StrokePerformance.now())
    private val mailbox = Any()
    private val thread = HandlerThread("canvas-presentation").apply { start() }
    private val handler = Handler(thread.looper)
    private var pending: Frame? = null
    private var running = false
    @Volatile private var closed = false
    private var screen: Bitmap? = null
    private var screenEpoch = -1L
    private val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val paperPaint = Paint().apply { color = android.graphics.Color.WHITE }

    fun submit(frame: Frame) {
        synchronized(mailbox) {
            if (closed) { frame.pixels.recycle(); uiPost(Runnable { frame.complete(false) }); return }
            pending?.let { old -> old.pixels.recycle(); uiPost(Runnable { old.complete(false) }) }
            pending = frame
            if (!running) { running = true; handler.post { drain() } }
        }
    }

    private fun drain() {
        while (true) {
            val frame = synchronized(mailbox) {
                val next = pending
                pending = null
                if (next == null) running = false
                next
            } ?: return
            var success = false
            val started = StrokePerformance.now()
            try {
                if (!closed && valid(frame.epoch)) {
                    var buffer = screen
                    if (buffer == null || buffer.width != frame.width || buffer.height != frame.height) {
                        buffer?.recycle()
                        buffer = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
                        screen = buffer
                        screenEpoch = -1L
                    }
                    // New epochs must arrive as a full snapshot before partial updates.
                    check(screenEpoch == frame.epoch || frame.rect == Rect(0, 0, frame.width, frame.height))
                    val target = Canvas(buffer)
                    val transform = frame.documentTransform
                    if (transform == null) target.drawBitmap(frame.pixels, frame.rect.left.toFloat(), frame.rect.top.toFloat(), copyPaint)
                    else {
                        // Full/view-change/rotated frames transform an immutable document
                        // composite on the worker, instead of rasterizing rotation on UI.
                        target.drawColor(android.graphics.Color.LTGRAY)
                        val saved = target.save()
                        target.concat(transform.matrix())
                        target.drawRect(0f, 0f, frame.pixels.width.toFloat(), frame.pixels.height.toFloat(), paperPaint)
                        target.clipRect(0, 0, frame.pixels.width, frame.pixels.height)
                        target.drawBitmap(frame.pixels, 0f, 0f, bitmapPaint)
                        target.restoreToCount(saved)
                    }
                    screenEpoch = frame.epoch
                    frame.pixels.recycle() // Release patch before potentially waiting on SurfaceFlinger.
                    synchronized(surfaceLock) {
                        if (!closed && valid(frame.epoch) && holder.surface.isValid) {
                            val dirty = Rect(frame.rect)
                            val lockStarted = StrokePerformance.now()
                            val canvas = holder.lockCanvas(dirty)
                            val locked = StrokePerformance.now()
                            if (canvas != null) {
                                var drawn = locked
                                try {
                                    canvas.clipRect(dirty) // Android may expand the requested area.
                                    canvas.drawBitmap(buffer, 0f, 0f, copyPaint)
                                    drawn = StrokePerformance.now()
                                } finally { holder.unlockCanvasAndPost(canvas) }
                                success = true
                                StrokePerformance.log("asyncSurface queueMs=${StrokePerformance.ms(frame.queued, started)} lockMs=${StrokePerformance.ms(lockStarted, locked)} drawMs=${StrokePerformance.ms(locked, drawn)} postMs=${StrokePerformance.ms(drawn)} pixels=${dirty.width().toLong() * dirty.height()}")
                            }
                        }
                    }
                }
            } catch (error: Exception) { Log.w("BooxPerf", "Async surface presentation failed", error) }
            catch (error: OutOfMemoryError) { screen?.recycle(); screen = null; Log.w("BooxPerf", "Async screen buffer unavailable", error) }
            finally {
                if (!frame.pixels.isRecycled) frame.pixels.recycle()
                val finished = StrokePerformance.now()
                uiPost(Runnable {
                    StrokePerformance.log("asyncCompletion queueMs=${StrokePerformance.ms(finished)} success=$success")
                    frame.complete(success)
                })
            }
        }
    }

    override fun close() {
        synchronized(mailbox) {
            if (closed) return
            closed = true
            pending?.let { it.pixels.recycle(); uiPost(Runnable { it.complete(false) }) }
            pending = null
        }
        handler.post { screen?.recycle(); screen = null; thread.quitSafely() }
    }
}
