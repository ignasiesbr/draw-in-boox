package art.boox.probe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color

object NativeEraserChecks {
    fun run() {
        val document = DrawingDocument.create(CanvasSpec(128, 128))
        val settings = BrushPreset.ERASER.defaults().copy(size = 24f, shade = InkShade.LIGHT)
        val brush = settings.compile()
        try {
            check(NativePreviewPolicy.color(brush, document) == Color.WHITE) { "Eraser requested ink shade instead of white" }
            check(NativePreviewPolicy.color(brush) == null) { "Eraser opened without layer context" }
            check(NativePreviewPolicy.color(settings.copy(size = 30f).compile(), document) == Color.WHITE)
            check(NativePreviewPolicy.color(settings.copy(size = 30.1f).compile(), document) == null)
            check(NativePreviewPolicy.color(settings.copy(size = 48f).compile(), document, 0.5f) == null)
            check(NativePreviewPolicy.color(brush, document, 2f) == null)
            check(NativePreviewPolicy.color(brush, document, 1.25f) == Color.WHITE)
            for (scale in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY))
                check(NativePreviewPolicy.color(brush, document, scale) == null)
            document.activeLayer.alphaLocked = true
            check(!NativePreviewPolicy.supports(brush, document))
            document.activeLayer.alphaLocked = false
            document.activeLayer.opacity = 50
            check(!NativePreviewPolicy.supports(brush, document))
            document.activeLayer.opacity = 100
            document.activeLayer.locked = true
            check(!NativePreviewPolicy.supports(brush, document))
            document.activeLayer.locked = false
            document.activeLayer.visible = false
            check(!NativePreviewPolicy.supports(brush, document))
            document.activeLayer.visible = true

            // White is preview only: canonical pixels must still become transparent,
            // and undo/redo must recover exact pixels rather than replaying white.
            document.layer.eraseColor(Color.BLACK)
            val history = EditHistory()
            val path = listOf(PenSample(30f, 64f, 0f, 1), PenSample(100f, 64f, 1f, 2))
            check(history.execute(document, StrokeEdit(path, brush)))
            check(document.layer.getPixel(64, 64) == Color.TRANSPARENT)
            val screen = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            try {
                Canvas(screen).also { it.drawColor(Color.WHITE); document.drawComposite(it) }
                check(screen.getPixel(64, 64) == Color.WHITE && screen.getPixel(64, 20) == Color.BLACK)
            } finally { screen.recycle() }
            check(history.undo(document) == "Erase" && document.layer.getPixel(64, 64) == Color.BLACK)
            check(history.redo(document) == "Erase" && document.layer.getPixel(64, 64) == Color.TRANSPARENT)
            check(LivePreviewWidth.forBrush(brush, 1f, post = { _, _ -> error("Fixed eraser queued setter") },
                remove = {}, apply = { error("Fixed eraser mutated native shader at contact") }) == null)

            document.addLayer()
            check(!NativePreviewPolicy.supports(brush, document)) { "Layered artwork used white eraser preview" }
            document.layers.first().visible = false
            check(!NativePreviewPolicy.supports(brush, document)) { "Hidden layers bypassed conservative single-layer policy" }
            check(NativePreviewPolicy.supports(BrushPreset.INK.defaults().compile(), document))
            check(!NativePreviewPolicy.supports(BrushPreset.MONOLINE.defaults().copy(size = 24f, shade = InkShade.LIGHT).compile(), document))
        } finally { document.recycle() }
    }
}
