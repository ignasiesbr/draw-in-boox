package art.boox.probe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.File

object AlphaLockChecks {
    fun run(context: Context) {
        val doc = DrawingDocument.create(CanvasSpec(32, 32))
        val edits = EditHistory()
        val brush = PressureBrush()
        val path = listOf(PenSample(0f, 16f, 1f, 1), PenSample(31f, 16f, 1f, 2))
        val directory = File(context.cacheDir, "alpha-lock-tests").apply { mkdirs() }
        try {
            // Seed opaque and partially transparent pixels, including a soft edge.
            for (y in 8..23) for (x in 8..23) doc.layer.setPixel(x, y, Color.argb((x - 7) * 15, 255, 0, 0))
            val original = checkNotNull(doc.layer.copy(Bitmap.Config.ARGB_8888, false))
            val id = doc.layerId
            check(edits.executeLayer(doc, LayerCommand.Properties(id, LayerProperties(65, true))))
            check(doc.activeLayer.alphaLocked && doc.activeLayer.opacity == 65)
            check(edits.undo(doc) == "Layer settings" && !doc.activeLayer.alphaLocked && doc.activeLayer.opacity == 100)
            check(edits.redo(doc) != null && doc.activeLayer.alphaLocked)
            check(edits.execute(doc, StrokeEdit(path, brush)))
            for (y in 0..31) for (x in 0..31) {
                check(Color.alpha(doc.layer.getPixel(x, y)) == Color.alpha(original.getPixel(x, y))) {
                    "Alpha changed at $x,$y"
                }
            }
            check(Color.red(doc.layer.getPixel(16, 16)) == 0) { "Painting inside existing ink failed" }
            check(edits.undo(doc) == "Stroke" && doc.layer.sameAs(original))
            check(edits.redo(doc) == "Stroke")
            var rejected = false
            try { doc.clear() } catch (_: IllegalStateException) { rejected = true }
            check(rejected) { "Clear bypassed alpha lock" }
            val store = DocumentStore(directory)
            store.save(doc, null)
            store.loadOrCreate(CanvasSpec(1, 1)).document.also {
                check(it.activeLayer.alphaLocked && it.activeLayer.opacity == 65); it.recycle()
            }
            doc.duplicateLayer(id)
            check(doc.activeLayer.alphaLocked && doc.activeLayer.opacity == 65)
            // Preview uses an isolated layer; opaque paper underneath is not its mask.
            doc.setLayerProperties(doc.layerId, LayerProperties(100, true))
            val preview = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            Canvas(preview).also { canvas ->
                canvas.drawColor(Color.WHITE)
                doc.drawLayer(canvas, doc.activeLayer, pending = { brush.draw(it, path, true) })
            }
            check(preview.getPixel(2, 16) == Color.WHITE) { "Preview painted outside layer alpha" }
            check(Color.red(preview.getPixel(16, 16)) < 200)
            preview.recycle(); original.recycle()
            // Blank alpha-locked layers stay blank and do not add empty history entries.
            doc.addLayer()
            doc.setLayerProperties(doc.layerId, LayerProperties(100, true))
            check(!edits.execute(doc, StrokeEdit(path, brush)))
            check(doc.layer.getPixel(16, 16) == Color.TRANSPARENT)
            doc.setLayerProperties(doc.layerId, LayerProperties(100, false))
            check(edits.execute(doc, StrokeEdit(path, brush)))
            check(doc.layer.getPixel(16, 16) == Color.BLACK)
            // Surface commit uses exactly the same constraint in both input modes.
            val isolated = object : android.content.ContextWrapper(context) {
                override fun getFilesDir(): File = File(directory, "surface").apply { mkdirs() }
            }
            val surface = RasterSurface(isolated, CanvasSpec(32, 32)) {}
            surface.createDocument(CanvasSpec(32, 32))
            surface.document!!.setLayerProperties(surface.document!!.layerId, LayerProperties(100, true))
            surface.commit(path, "boox")
            check(surface.document!!.layer.getPixel(16, 16) == Color.TRANSPARENT)
            surface.commit(path, "android")
            check(surface.document!!.layer.getPixel(16, 16) == Color.TRANSPARENT)
            surface.dispose()
        } finally { doc.recycle(); directory.deleteRecursively() }
    }
}
