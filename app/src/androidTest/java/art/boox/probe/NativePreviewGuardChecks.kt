package art.boox.probe

import android.graphics.Color

/** Guard-off eligibility tests never configure the actual firmware gray/wide path. */
object NativePreviewGuardChecks {
    fun guardedColor(brush: PressureBrush, document: DrawingDocument? = null, scale: Float = 1f) =
        NativePreviewPolicy.colorWithGuard(true, brush, document, scale)
    fun guardedSupports(brush: PressureBrush, document: DrawingDocument? = null, scale: Float = 1f) =
        guardedColor(brush, document, scale) != null

    fun run() {
        for (shade in InkShade.entries) for (size in listOf(12f, 30f, 100.8f, 128f)) {
            val brush = BrushPreset.MONOLINE.defaults().copy(size = size, shade = shade).compile()
            val guarded = NativePreviewPolicy.colorWithGuard(true, brush)
            val unguarded = NativePreviewPolicy.colorWithGuard(false, brush)
            check(guarded == if (shade == InkShade.BLACK && size <= 30f) Color.BLACK else null)
            check(unguarded == shade.argb) { "Guard off did not request the selected native shade/width" }
            check(NativePreviewPolicy.color(brush) == if (NativePreviewPolicy.ENFORCE_FIRMWARE_CRASH_GUARD) guarded else unguarded)
        }
        val broadPencil = BrushPreset.LAB_PENCIL.defaults().copy(size = 64f).compile()
        check(guardedColor(broadPencil) == null)
        check(NativePreviewPolicy.colorWithGuard(false, broadPencil) == Color.BLACK)
        val document = DrawingDocument.create(CanvasSpec(64, 64))
        val erase = BrushPreset.ERASER.defaults().copy(size = 96f).compile()
        try {
            check(guardedColor(erase, document) == null)
            check(NativePreviewPolicy.colorWithGuard(false, erase, document, 2f) == Color.WHITE)
            document.activeLayer.alphaLocked = true
            check(NativePreviewPolicy.colorWithGuard(false, erase, document) == null)
            document.activeLayer.alphaLocked = false
            document.activeLayer.opacity = 50
            check(NativePreviewPolicy.colorWithGuard(false, erase, document) == null)
            document.activeLayer.opacity = 100
            document.activeLayer.locked = true
            check(NativePreviewPolicy.colorWithGuard(false, erase, document) == null)
            document.activeLayer.locked = false
            document.addLayer()
            check(NativePreviewPolicy.colorWithGuard(false, erase, document) == null)
        } finally { document.recycle() }
    }
}
