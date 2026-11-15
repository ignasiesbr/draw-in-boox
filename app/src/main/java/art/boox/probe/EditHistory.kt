package art.boox.probe

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Process
import android.util.Log
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** Editing actions declare all pixels they can touch, independently of input/tool choice.
 * Future fill/cut tools use this same transaction boundary; view navigation is not an edit. */
interface DocumentEdit {
    val label: String
    fun affectedRegions(spec: CanvasSpec): List<Rect>
    fun apply(document: DrawingDocument)
}

class StrokeEdit(private val samples: List<PenSample>, private val brush: PressureBrush) : DocumentEdit {
    override val label = if (brush.isEraser) "Erase" else "Stroke"
    override fun affectedRegions(spec: CanvasSpec): List<Rect> {
        if (brush.requiresPreparedGeometry) {
            val bounds = brush.renderedBounds(samples) ?: return emptyList()
            return if (bounds.intersect(0f, 0f, spec.width.toFloat(), spec.height.toFloat()))
                listOf(Rect().also { bounds.roundOut(it) }) else emptyList()
        }
        val padding = brush.maxRenderedWidth / 2f + 2f
        var previous = samples.firstOrNull() ?: return emptyList()
        return samples.mapNotNull { point ->
            val rect = RectF(min(previous.x, point.x) - padding, min(previous.y, point.y) - padding,
                max(previous.x, point.x) + padding, max(previous.y, point.y) + padding)
            previous = point
            if (rect.intersect(0f, 0f, spec.width.toFloat(), spec.height.toFloat())) Rect().also { rect.roundOut(it) }
            else null
        }
    }
    override fun apply(document: DrawingDocument) = brush.draw(Canvas(document.layer), samples, document.activeLayer.alphaLocked)
}

object ClearLayerEdit : DocumentEdit {
    override val label = "Clear"
    override fun affectedRegions(spec: CanvasSpec) = listOf(Rect(0, 0, spec.width, spec.height))
    override fun apply(document: DrawingDocument) = document.clear()
}

/** UI owns pixels/history order. Admitted edits retain bounded raw tile copies for
 * immediate Undo; the worker compresses those immutable copies, never a live bitmap. */
class EditHistory(
    private val capacity: Int = 10,
    private val maxBytes: Long = minOf(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)
) {
    init { require(capacity > 0 && maxBytes > 0) }
    private var compression = sharedCompression
    internal constructor(compression: HistoryCompression, capacity: Int = 10,
        maxBytes: Long = minOf(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)) : this(capacity, maxBytes) {
        this.compression = compression
    }
    private data class Patch(val rect: Rect, val tile: HistoryTile) {
        val byteSize: Int get() = tile.byteSize
        fun restore(bitmap: Bitmap) {
            bitmap.setPixels(tile.pixels(), 0, rect.width(), rect.left, rect.top, rect.width(), rect.height())
        }
        /** Only used before publishing this patch to history/the compression worker. */
        fun captureRaw(bitmap: Bitmap) {
            bitmap.getPixels(tile.pixels(), 0, rect.width(), rect.left, rect.top, rect.width(), rect.height())
        }
    }
    private data class Change(val before: Patch, val after: Patch)
    private interface Entry {
        val label: String
        val bytes: Long
        fun restore(document: DrawingDocument, redo: Boolean)
        fun cancelCompression() {}
    }
    private data class PixelEntry(val documentId: String, val layerId: String, override val label: String,
        val changes: List<Change>, val compression: HistoryCompression.Reservation?) : Entry {
        override val bytes: Long get() = changes.sumOf { it.before.byteSize.toLong() + it.after.byteSize + 128 }
        override fun cancelCompression() { compression?.cancel() }
        override fun restore(document: DrawingDocument, redo: Boolean) {
            check(document.id == documentId) { "History belongs to another document" }
            val layer = checkNotNull(document.findLayer(layerId)) { "History layer is missing" }
            changes.forEach { if (redo) it.after.restore(layer.bitmap) else it.before.restore(layer.bitmap) }
            document.selectLayer(layerId)
            document.invalidateComposite(changes.map { it.before.rect })
        }
    }
    private data class StackEntry(val documentId: String, override val label: String,
        val before: LayerStackState, val after: LayerStackState,
        val oldPixels: Map<String, List<Patch>>, val newPixels: Map<String, List<Patch>>) : Entry {
        override val bytes: Long get() = (oldPixels.values + newPixels.values).sumOf { patches ->
            patches.sumOf { it.byteSize.toLong() + 64 }
        } + (before.layers.size + after.layers.size) * 256L
        override fun restore(document: DrawingDocument, redo: Boolean) {
            check(document.id == documentId)
            document.restoreStack(if (redo) after else before)
            (if (redo) newPixels else oldPixels).forEach { (id, patches) ->
                val bitmap = checkNotNull(document.findLayer(id)).bitmap
                patches.forEach { it.restore(bitmap) }
            }
            document.invalidateComposite()
        }
    }
    private val entries = mutableListOf<Entry>()
    private var cursor = 0
    // Worker replacements can only shrink tiles. Read current retained sizes instead
    // of posting accounting callbacks that might outlive Clear/New/redo branching.
    val bytesUsed: Long get() = entries.sumOf { it.bytes }
    val undoCount: Int get() = cursor
    val redoCount: Int get() = entries.size - cursor
    val canUndo: Boolean get() = undoCount > 0
    val canRedo: Boolean get() = redoCount > 0

    fun clear() { entries.forEach { it.cancelCompression() }; entries.clear(); cursor = 0 }

    fun execute(document: DrawingDocument, edit: DocumentEdit): Boolean {
        val started = StrokePerformance.now()
        check(document.canPaint) { "Selected layer is locked or hidden" }
        val bounds = Rect(0, 0, document.spec.width, document.spec.height)
        val tiles = linkedSetOf<Pair<Int, Int>>()
        edit.affectedRegions(document.spec).forEach { region ->
            val clipped = Rect(region)
            if (clipped.intersect(bounds)) {
                for (y in clipped.top / TILE until (clipped.bottom + TILE - 1) / TILE)
                    for (x in clipped.left / TILE until (clipped.right + TILE - 1) / TILE) tiles += x to y
            }
        }
        val layerId = document.layerId
        val bounded = StrokePerformance.now()
        val rects = tiles.map { (x, y) -> Rect(x * TILE, y * TILE,
            min((x + 1) * TILE, bounds.right), min((y + 1) * TILE, bounds.bottom)) }
        val rawBytes = rects.sumOf { it.width().toLong() * it.height() * 8 }
        // Count raw copies against the normal history budget, before allocating.
        // Do not evict extra actions just because the newest one is not compressed yet.
        val keptBytes = entries.take(cursor).takeLast(capacity - 1).sumOf { it.bytes }
        val available = Runtime.getRuntime().let { it.maxMemory() - it.totalMemory() + it.freeMemory() }
        var fallback = when {
            edit === ClearLayerEdit -> "clear"
            rawBytes + rects.size * 128L > maxBytes - keptBytes -> "history-budget"
            available < rawBytes + 8L * 1024 * 1024 -> "memory"
            else -> "backlog"
        }
        var reservation = if (edit !== ClearLayerEdit && rawBytes + rects.size * 128L <= maxBytes - keptBytes &&
            available >= rawBytes + 8L * 1024 * 1024) compression.reserve(rawBytes) else null
        // Allocate BOTH sides before mutation, so allocation failure can fall back
        // without repeating an edit or losing rollback pixels after a partial draw.
        val raw = if (reservation == null) null else try { captureRaw(document.layer, rects) }
            catch (_: OutOfMemoryError) { fallback = "allocation"; reservation.cancel(); reservation = null; null }
            catch (error: Throwable) { reservation.cancel(); throw error }
        val captureSession = if (raw == null) CaptureSession() else null
        var submitted = false
        try {
            val before = raw?.first ?: rects.map { checkNotNull(captureSession).capture(document.layer, it) }
            val beforeCaptured = StrokePerformance.now()
            var applied = beforeCaptured
            val changes = try {
                edit.apply(document)
                applied = StrokePerformance.now()
                before.mapIndexedNotNull { index, old ->
                    val after = raw?.second?.get(index)?.also { it.captureRaw(document.layer) }
                        ?: checkNotNull(captureSession).capture(document.layer, old.rect)
                    if (old.tile.samePixels(after.tile)) null else Change(old, after)
                }
            } catch (error: Throwable) {
                before.forEach { it.restore(document.layer) }
                document.invalidateComposite(before.map { it.rect })
                throw error
            }
            val afterCaptured = StrokePerformance.now()
            StrokePerformance.log("history label=${edit.label} capture=${if (raw != null) "raw" else "compressed"} fallback=${if (raw != null) "none" else fallback} rawBytes=$rawBytes tiles=${tiles.size} changed=${changes.size} boundsMs=${StrokePerformance.ms(started, bounded)} beforeMs=${StrokePerformance.ms(bounded, beforeCaptured)} brushMs=${StrokePerformance.ms(beforeCaptured, applied)} afterMs=${StrokePerformance.ms(applied, afterCaptured)} totalMs=${StrokePerformance.ms(started, afterCaptured)}")
            if (changes.isEmpty()) return false
            document.invalidateComposite(changes.map { it.before.rect })
            append(PixelEntry(document.id, layerId, edit.label, changes, reservation))
            val queued = StrokePerformance.now()
            submitted = reservation?.submit { cancelled ->
                val compressStarted = StrokePerformance.now()
                val beforeBytes = changes.sumOf { it.before.byteSize.toLong() + it.after.byteSize }
                try {
                    HistoryTileCodec().use { codec ->
                        for (change in changes) {
                            if (cancelled()) break
                            change.before.tile.compress(codec)
                            if (cancelled()) break
                            change.after.tile.compress(codec)
                        }
                    }
                } finally {
                    val afterBytes = changes.sumOf { it.before.byteSize.toLong() + it.after.byteSize }
                    StrokePerformance.log("historyCompression queueMs=${StrokePerformance.ms(queued, compressStarted)} workMs=${StrokePerformance.ms(compressStarted)} beforeBytes=$beforeBytes afterBytes=$afterBytes cancelled=${cancelled()}")
                }
            } ?: false
            return true
        } finally {
            captureSession?.close()
            if (!submitted) reservation?.cancel()
        }
    }

    private fun captureRaw(bitmap: Bitmap, rects: List<Rect>): Pair<List<Patch>, List<Patch>> {
        fun allocate(rect: Rect) = Patch(Rect(rect), HistoryTile.raw(IntArray(rect.width() * rect.height())))
        val before = rects.map { allocate(it).also { patch -> patch.captureRaw(bitmap) } }
        return before to rects.map(::allocate)
    }

    fun executeLayer(document: DrawingDocument, command: LayerCommand): Boolean {
        val before = document.stackState()
        val affected = command.affectedPixels(document)
        val oldPixels = captureLayers(document, affected, maxBytes)
        val after: LayerStackState
        val newPixels: Map<String, List<Patch>>?
        try {
            command.apply(document)
            after = document.stackState()
            if (before == after) return false
            // Only Add guarantees a transparent layer. Imported pixels must survive redo.
            val inserted = if (command == LayerCommand.Add) emptySet()
                else after.layers.map { it.id }.toSet() - before.layers.map { it.id }.toSet()
            val oldBytes = oldPixels?.values?.sumOf { patches -> patches.sumOf { it.byteSize.toLong() + 64 } } ?: maxBytes
            newPixels = captureLayers(document, (affected + inserted).filter { document.findLayer(it) != null }.toSet(), maxBytes - oldBytes)
        } catch (error: Throwable) {
            // Commands validate/allocate before mutation. Restore retained pixels if needed.
            if (oldPixels != null && document.stackState() != before) {
                document.restoreStack(before)
                oldPixels.forEach { (id, patches) -> patches.forEach { it.restore(checkNotNull(document.findLayer(id)).bitmap) } }
            }
            document.invalidateComposite()
            throw error
        }
        if (oldPixels == null || newPixels == null) clear()
        else append(StackEntry(document.id, command.label, before, after, oldPixels, newPixels))
        return true
    }

    private fun append(entry: Entry) {
        while (entries.size > cursor) entries.removeAt(entries.lastIndex).cancelCompression()
        entries += entry; cursor++
        while (entries.size > capacity || bytesUsed > maxBytes) {
            entries.removeAt(0).cancelCompression(); cursor--
        }
    }

    private fun captureLayers(document: DrawingDocument, ids: Set<String>, budget: Long): Map<String, List<Patch>>? = CaptureSession().use { captureSession ->
        var used = 0L
        val result = mutableMapOf<String, List<Patch>>()
        for (id in ids) {
            val bitmap = checkNotNull(document.findLayer(id)).bitmap
            val patches = mutableListOf<Patch>()
            for (y in 0 until bitmap.height step TILE) for (x in 0 until bitmap.width step TILE) {
                val patch = captureSession.capture(bitmap, Rect(x, y, min(x + TILE, bitmap.width), min(y + TILE, bitmap.height)))
                used += patch.byteSize + 64
                if (used > budget) return null
                patches += patch
            }
            result[id] = patches
        }
        return result
    }

    fun undo(document: DrawingDocument): String? {
        if (!canUndo) return null
        val entry = entries[cursor - 1]
        entry.restore(document, false); cursor--
        return entry.label
    }

    fun redo(document: DrawingDocument): String? {
        if (!canRedo) return null
        val entry = entries[cursor]
        entry.restore(document, true); cursor++
        return entry.label
    }

    /** Reuse native compressor and scratch space across all before/after tiles in a transaction.
     * Patches still own independent lossless bytes; native resources are released on failure too. */
    private class CaptureSession : java.io.Closeable {
        private val pixels = IntArray(TILE * TILE)
        private val codec = HistoryTileCodec()
        fun capture(bitmap: Bitmap, rect: Rect): Patch {
            val count = rect.width() * rect.height()
            bitmap.getPixels(pixels, 0, rect.width(), rect.left, rect.top, rect.width(), rect.height())
            return Patch(Rect(rect), HistoryTile.packed(codec.encode(pixels, count), count))
        }
        override fun close() { codec.close() }
    }

    private companion object {
        const val TILE = 128
        val compressionExecutor by lazy {
            Executors.newSingleThreadExecutor { task ->
                Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "undo-compression")
                    .apply { isDaemon = true }
            }
        }
        val sharedCompression by lazy {
            HistoryCompression(minOf(8L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 32),
                launch = { compressionExecutor.execute(it) },
                failed = { Log.w("BooxPerf", "History compression failed; raw Undo tiles retained", it) })
        }
    }
}
