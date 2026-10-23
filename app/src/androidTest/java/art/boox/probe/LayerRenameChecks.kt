package art.boox.probe

import android.content.Context
import android.graphics.Color
import java.io.File

object LayerRenameChecks {
    fun run(context: Context) {
        val document = DrawingDocument.create(CanvasSpec(32, 32))
        val directory = File(context.cacheDir, "layer-rename-tests").apply { mkdirs() }
        val store = DocumentStore(directory)
        try {
            val id = document.layerId
            val pixels = document.layer
            pixels.setPixel(4, 5, Color.BLACK)
            document.setLayerLocked(id, true)
            document.setLayerVisible(id, false)
            val history = EditHistory()
            val rename = LayerCommand.Rename(id, "  Ink outlines  ")
            check(rename.affectedPixels(document).isEmpty()) { "Rename must not copy raster pixels" }
            check(history.executeLayer(document, rename))
            check(document.activeLayer.name == "Ink outlines")
            check(document.activeLayer.locked && !document.activeLayer.visible)
            check(document.layer === pixels && pixels.getPixel(4, 5) == Color.BLACK)
            check(history.bytesUsed <= 1024) { "Metadata rename consumed raster history space" }
            check(history.undo(document) == "Rename layer")
            check(document.activeLayer.name == "Layer 1" && document.layer === pixels)
            check(history.redo(document) == "Rename layer")
            check(document.activeLayer.name == "Ink outlines" && document.layer === pixels)
            check(!history.executeLayer(document, LayerCommand.Rename(id, "Ink outlines ")))
            check(history.undoCount == 1) { "Unchanged name added an undo action" }

            val unchanged = document.stackState()
            for (name in listOf("", "   ", "a".repeat(DrawingDocument.MAX_LAYER_NAME_LENGTH + 1), "Ink\nline", "Ink\u0000line")) {
                var rejected = false
                try { history.executeLayer(document, LayerCommand.Rename(id, name)) }
                catch (expected: IllegalArgumentException) { rejected = true }
                check(rejected && document.stackState() == unchanged && history.undoCount == 1)
            }
            document.renameLayer(id, "a".repeat(DrawingDocument.MAX_LAYER_NAME_LENGTH))
            document.renameLayer(id, "Tinta · 墨")
            store.save(document, null)
            val reopened = store.loadOrCreate(CanvasSpec(1, 1)).document
            try {
                check(reopened.stackState() == document.stackState())
                check(reopened.layer.getPixel(4, 5) == Color.BLACK)
            } finally { reopened.recycle() }
        } finally {
            document.recycle()
            store.file.delete()
        }
    }
}
