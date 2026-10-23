package art.boox.probe

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.InflaterInputStream
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
        if (brush.isBooxFountain) {
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

/** Lossless tile patches preserve overlap/alpha without replaying tool code on redo.
 * Compressed storage has both an action limit and a byte budget; no full-canvas snapshots. */
class EditHistory(
    private val capacity: Int = 10,
    private val maxBytes: Long = minOf(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)
) {
    init { require(capacity > 0 && maxBytes > 0) }
    private data class Patch(val rect: Rect, val bytes: ByteArray) {
        fun restore(bitmap: Bitmap) {
            val data = ByteArray(rect.width() * rect.height() * 4)
            DataInputStream(InflaterInputStream(ByteArrayInputStream(bytes))).use { it.readFully(data) }
            val pixels = IntArray(data.size / 4)
            ByteBuffer.wrap(data).asIntBuffer().get(pixels)
            bitmap.setPixels(pixels, 0, rect.width(), rect.left, rect.top, rect.width(), rect.height())
        }
    }
    private data class Change(val before: Patch, val after: Patch)
    private interface Entry {
        val label: String
        val bytes: Long
        fun restore(document: DrawingDocument, redo: Boolean)
    }
    private data class PixelEntry(val documentId: String, val layerId: String, override val label: String, val changes: List<Change>) : Entry {
        override val bytes: Long get() = changes.sumOf { it.before.bytes.size.toLong() + it.after.bytes.size + 128 }
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
            patches.sumOf { it.bytes.size.toLong() + 64 }
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
    var bytesUsed = 0L
        private set
    val undoCount: Int get() = cursor
    val redoCount: Int get() = entries.size - cursor
    val canUndo: Boolean get() = undoCount > 0
    val canRedo: Boolean get() = redoCount > 0

    fun clear() { entries.clear(); cursor = 0; bytesUsed = 0 }

    fun execute(document: DrawingDocument, edit: DocumentEdit): Boolean = CaptureSession().use { captureSession ->
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
        val before = tiles.map { (x, y) -> captureSession.capture(document.layer, Rect(x * TILE, y * TILE,
            min((x + 1) * TILE, bounds.right), min((y + 1) * TILE, bounds.bottom))) }
        val beforeCaptured = StrokePerformance.now()
        var applied = beforeCaptured
        val changes = try {
            edit.apply(document)
            applied = StrokePerformance.now()
            before.mapNotNull { old ->
                val after = captureSession.capture(document.layer, old.rect)
                if (old.bytes.contentEquals(after.bytes)) null else Change(old, after)
            }
        } catch (error: Exception) {
            before.forEach { it.restore(document.layer) }
            document.invalidateComposite(before.map { it.rect })
            throw error
        }
        val afterCaptured = StrokePerformance.now()
        StrokePerformance.log("history label=${edit.label} tiles=${tiles.size} changed=${changes.size} boundsMs=${StrokePerformance.ms(started, bounded)} beforeMs=${StrokePerformance.ms(bounded, beforeCaptured)} brushMs=${StrokePerformance.ms(beforeCaptured, applied)} afterMs=${StrokePerformance.ms(applied, afterCaptured)} totalMs=${StrokePerformance.ms(started, afterCaptured)}")
        if (changes.isEmpty()) return false
        document.invalidateComposite(changes.map { it.before.rect })
        append(PixelEntry(document.id, layerId, edit.label, changes))
        return true
    }

    fun executeLayer(document: DrawingDocument, command: LayerCommand): Boolean {
        val before = document.stackState()
        val affected = command.affectedPixels(document)
        val oldPixels = captureLayers(document, affected, maxBytes)
        try { command.apply(document) }
        catch (error: Exception) {
            // Commands validate/allocate before mutation. Restore retained pixels if needed.
            if (oldPixels != null && document.stackState() != before) {
                document.restoreStack(before)
                oldPixels.forEach { (id, patches) -> patches.forEach { it.restore(checkNotNull(document.findLayer(id)).bitmap) } }
            }
            document.invalidateComposite()
            throw error
        }
        val after = document.stackState()
        if (before == after) return false
        // Added layers are known transparent; redo can allocate them without pixel backups.
        val inserted = if (command == LayerCommand.Add) emptySet()
            else after.layers.map { it.id }.toSet() - before.layers.map { it.id }.toSet()
        val oldBytes = oldPixels?.values?.sumOf { patches -> patches.sumOf { it.bytes.size.toLong() + 64 } } ?: maxBytes
        val newPixels = captureLayers(document, (affected + inserted).filter { document.findLayer(it) != null }.toSet(), maxBytes - oldBytes)
        if (oldPixels == null || newPixels == null) clear()
        else append(StackEntry(document.id, command.label, before, after, oldPixels, newPixels))
        return true
    }

    private fun append(entry: Entry) {
        while (entries.size > cursor) bytesUsed -= entries.removeAt(entries.lastIndex).bytes
        entries += entry; cursor++; bytesUsed += entry.bytes
        while (entries.size > capacity || bytesUsed > maxBytes) {
            bytesUsed -= entries.removeAt(0).bytes; cursor--
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
                used += patch.bytes.size + 64
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
        private val raw = ByteArray(TILE * TILE * 4)
        private val ints = ByteBuffer.wrap(raw).asIntBuffer()
        private val output = ByteArrayOutputStream(1024)
        private val compressed = ByteArray(8192)
        private val deflater = Deflater(Deflater.BEST_SPEED)
        fun capture(bitmap: Bitmap, rect: Rect): Patch {
            val count = rect.width() * rect.height()
            bitmap.getPixels(pixels, 0, rect.width(), rect.left, rect.top, rect.width(), rect.height())
            ints.rewind(); ints.put(pixels, 0, count)
            output.reset(); deflater.reset(); deflater.setInput(raw, 0, count * 4); deflater.finish()
            while (!deflater.finished()) {
                val size = deflater.deflate(compressed)
                check(size > 0) { "History compression made no progress" }
                output.write(compressed, 0, size)
            }
            return Patch(Rect(rect), output.toByteArray())
        }
        override fun close() { deflater.end() }
    }

    private companion object { const val TILE = 128 }
}
