package art.boox.probe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LayerOpacityChecks {
    private fun rendered(doc: DrawingDocument): Bitmap = Bitmap.createBitmap(doc.spec.width, doc.spec.height,
        Bitmap.Config.ARGB_8888).also { doc.drawComposite(Canvas(it)) }

    fun run(context: Context) {
        val doc = DrawingDocument.create(CanvasSpec(16, 16))
        val history = EditHistory()
        val directory = File(context.cacheDir, "layer-opacity-tests").apply { mkdirs() }
        val store = DocumentStore(directory)
        try {
            val id = doc.layerId
            doc.layer.eraseColor(Color.BLACK)
            check(history.executeLayer(doc, LayerCommand.Opacity(id, 50)))
            val alpha = rendered(doc).let { val a = Color.alpha(it.getPixel(4, 4)); it.recycle(); a }
            check(alpha in 127..128)
            check(doc.layer.getPixel(4, 4) == Color.BLACK) { "Opacity changed source pixels" }
            check(history.bytesUsed < 1024)
            check(!history.executeLayer(doc, LayerCommand.Opacity(id, 50)))
            check(history.undo(doc) == "Layer opacity" && doc.activeLayer.opacity == 100)
            check(history.redo(doc) == "Layer opacity" && doc.activeLayer.opacity == 50)
            doc.setLayerLocked(id, true); doc.setLayerVisible(id, false)
            check(history.executeLayer(doc, LayerCommand.Opacity(id, 25)))
            doc.setLayerLocked(id, false); doc.setLayerVisible(id, true)
            for (invalid in listOf(-1, 101)) {
                var rejected = false
                try { history.executeLayer(doc, LayerCommand.Opacity(id, invalid)) }
                catch (_: IllegalArgumentException) { rejected = true }
                check(rejected && doc.activeLayer.opacity == 25)
            }
            doc.setLayerOpacity(id, 0)
            rendered(doc).also { check(Color.alpha(it.getPixel(4, 4)) == 0); it.recycle() }
            check(doc.canPaint) { "Zero opacity must not lock the pixels" }
            doc.setLayerOpacity(id, 50)
            // Pending ink overlapping opaque existing ink must fade once as a group.
            val preview = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            doc.drawLayer(Canvas(preview), doc.activeLayer, pending = { it.drawColor(Color.BLACK) })
            check(Color.alpha(preview.getPixel(4, 4)) in 127..128)
            preview.recycle()
            doc.duplicateLayer(id)
            check(doc.activeLayer.opacity == 50 && doc.layer !== doc.findLayer(id)!!.bitmap)
            rendered(doc).also { check(Color.alpha(it.getPixel(4, 4)) in 190..192); it.recycle() }
            doc.setLayerOpacity(doc.layerId, 0)
            rendered(doc).also { check(Color.alpha(it.getPixel(4, 4)) in 127..128); it.recycle() }
            store.save(doc, null)
            val loaded = store.loadOrCreate(CanvasSpec(1, 1)).document
            check(loaded.stackState() == doc.stackState()); loaded.recycle()
            val png = File(directory, "opacity.png")
            check(doc.exportPng(png))
            BitmapFactory.decodeFile(png.path).also {
                check(Color.red(it.getPixel(4, 4)) in 126..128 && Color.alpha(it.getPixel(4, 4)) == 255)
                it.recycle()
            }
            // Old v2 files without the new optional field still mean fully opaque.
            fun writeArchive(opacity: Any?) {
                val item = JSONObject().put("id", id).put("name", "Old layer")
                if (opacity != null) item.put("opacity", opacity)
                val metadata = JSONObject().put("version", 2).put("id", doc.id)
                    .put("width", 16).put("height", 16).put("selectedLayerId", id)
                    .put("layers", org.json.JSONArray().put(item))
                ZipOutputStream(store.file.outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("document.json")); zip.write(metadata.toString().toByteArray()); zip.closeEntry()
                    zip.putNextEntry(ZipEntry("layers/$id.png")); doc.layer.compress(Bitmap.CompressFormat.PNG, 100, zip); zip.closeEntry()
                }
            }
            writeArchive(null)
            store.loadOrCreate(CanvasSpec(1, 1)).document.also { check(it.activeLayer.opacity == 100); it.recycle() }
            for (invalid in listOf<Any>(-1, 101, 50.5, "NaN")) {
                writeArchive(invalid)
                var rejected = false
                try { store.loadOrCreate(CanvasSpec(1, 1)) } catch (_: IllegalArgumentException) { rejected = true }
                check(rejected) { "Invalid stored opacity accepted: $invalid" }
            }
        } finally { doc.recycle(); directory.deleteRecursively() }
        // Merge must preserve alpha and overlap; undo restores both metadata and pixels.
        for ((lower, upper) in listOf(50 to 25, 0 to 50, 50 to 0, 0 to 0, 100 to 100)) {
            val layers = DrawingDocument.create(CanvasSpec(16, 16))
            try {
                layers.layer.eraseColor(Color.RED); val lowerId = layers.layerId
                layers.setLayerOpacity(lowerId, lower); layers.addLayer()
                Canvas(layers.layer).drawRect(0f, 0f, 8f, 16f, Paint().apply { color = Color.BLUE })
                layers.setLayerOpacity(layers.layerId, upper)
                val before = rendered(layers); val state = layers.stackState(); val edits = EditHistory()
                check(edits.executeLayer(layers, LayerCommand.MergeDown(layers.layerId)))
                check(layers.layerId == lowerId && layers.activeLayer.opacity == 100)
                rendered(layers).also { check(before.sameAs(it)) { "Merge changed appearance: $lower/$upper" }; it.recycle() }
                check(edits.undo(layers) != null && layers.stackState() == state)
                rendered(layers).also { check(before.sameAs(it)); it.recycle() }
                check(edits.redo(layers) != null)
                rendered(layers).also { check(before.sameAs(it)); it.recycle() }
                before.recycle()
            } finally { layers.recycle() }
        }
    }
}
