package art.boox.probe

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
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
    override val label = "Stroke"
    override fun affectedRegions(spec: CanvasSpec): List<Rect> {
        val padding = brush.maxWidth / 2f + 2f
        var previous = samples.firstOrNull() ?: return emptyList()
        return samples.mapNotNull { point ->
            val rect = RectF(min(previous.x, point.x) - padding, min(previous.y, point.y) - padding,
                max(previous.x, point.x) + padding, max(previous.y, point.y) + padding)
            previous = point
            if (rect.intersect(0f, 0f, spec.width.toFloat(), spec.height.toFloat())) Rect().also { rect.roundOut(it) }
            else null
        }
    }
    override fun apply(document: DrawingDocument) = brush.draw(Canvas(document.layer), samples)
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
        fun restore(document: DrawingDocument) {
            val data = ByteArray(rect.width() * rect.height() * 4)
            DataInputStream(InflaterInputStream(ByteArrayInputStream(bytes))).use { it.readFully(data) }
            val pixels = IntArray(data.size / 4)
            ByteBuffer.wrap(data).asIntBuffer().get(pixels)
            document.layer.setPixels(pixels, 0, rect.width(), rect.left, rect.top, rect.width(), rect.height())
        }
    }
    private data class Change(val before: Patch, val after: Patch)
    private data class Entry(val documentId: String, val layerId: String, val label: String, val changes: List<Change>) {
        val bytes: Long get() = changes.sumOf { it.before.bytes.size.toLong() + it.after.bytes.size + 128 }
        fun restore(document: DrawingDocument, redo: Boolean) {
            check(document.id == documentId && document.layerId == layerId) { "History belongs to another document/layer" }
            changes.forEach { if (redo) it.after.restore(document) else it.before.restore(document) }
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

    fun execute(document: DrawingDocument, edit: DocumentEdit): Boolean {
        val bounds = Rect(0, 0, document.spec.width, document.spec.height)
        val tiles = linkedSetOf<Pair<Int, Int>>()
        edit.affectedRegions(document.spec).forEach { region ->
            val clipped = Rect(region)
            if (clipped.intersect(bounds)) {
                for (y in clipped.top / TILE until (clipped.bottom + TILE - 1) / TILE)
                    for (x in clipped.left / TILE until (clipped.right + TILE - 1) / TILE) tiles += x to y
            }
        }
        val before = tiles.map { (x, y) -> capture(document, Rect(x * TILE, y * TILE,
            min((x + 1) * TILE, bounds.right), min((y + 1) * TILE, bounds.bottom))) }
        val changes = try {
            edit.apply(document)
            before.mapNotNull { old ->
                val after = capture(document, old.rect)
                if (old.bytes.contentEquals(after.bytes)) null else Change(old, after)
            }
        } catch (error: Exception) {
            before.forEach { it.restore(document) }
            throw error
        }
        if (changes.isEmpty()) return false
        while (entries.size > cursor) bytesUsed -= entries.removeAt(entries.lastIndex).bytes
        val entry = Entry(document.id, document.layerId, edit.label, changes)
        entries += entry; cursor++; bytesUsed += entry.bytes
        while (entries.size > capacity || bytesUsed > maxBytes) {
            bytesUsed -= entries.removeAt(0).bytes; cursor--
        }
        return true
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

    private fun capture(document: DrawingDocument, rect: Rect): Patch {
        val pixels = IntArray(rect.width() * rect.height())
        document.layer.getPixels(pixels, 0, rect.width(), rect.left, rect.top, rect.width(), rect.height())
        val raw = ByteBuffer.allocate(pixels.size * 4).apply { asIntBuffer().put(pixels) }.array()
        val compressed = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.BEST_SPEED)
        try { DeflaterOutputStream(compressed, deflater).use { it.write(raw) } }
        finally { deflater.end() }
        return Patch(Rect(rect), compressed.toByteArray())
    }

    private companion object { const val TILE = 128 }
}
